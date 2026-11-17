// JNI bridge between Kotlin (com.miniichat.whisper.WhisperLib) and whisper.cpp
#include <jni.h>
#include <string>
#include <atomic>
#include <cstring>
#include <cstdio>
#include <mutex>
#include <csignal>
#include <unistd.h>
#include <dlfcn.h>
#include <unwind.h>
#include <vector>
#include "whisper.h"
#include "ggml-backend.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "WhisperJNI", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "WhisperJNI", __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) do { fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); } while (0)
#define LOGW(...) LOGI(__VA_ARGS__)
#endif

namespace {

// ── engine log: every whisper/ggml log line is appended + flushed, so it survives a native crash ──
std::mutex g_log_mutex;
FILE *g_log_file = nullptr;

void engineLog(enum ggml_log_level level, const char *text, void *) {
    if (!text) return;
#ifdef __ANDROID__
    __android_log_print(level >= GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR :
                        level == GGML_LOG_LEVEL_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_INFO, "whisper", "%s", text);
#endif
    std::lock_guard<std::mutex> lock(g_log_mutex);
    if (g_log_file) { fputs(text, g_log_file); fflush(g_log_file); }
}

struct CallbackCtx {
    JNIEnv *env;
    jobject listener;      // may be null
    jmethodID onProgress;  // void onProgress(int)
    jmethodID onSegment;   // void onSegment(String)
    std::atomic<bool> *abort;
};

std::atomic<bool> g_abort{false};

jstring toJString(JNIEnv *env, const char *s) {
    // NewStringUTF needs modified UTF-8; whisper may emit partial multibyte
    // sequences at token boundaries, so go through a byte[] + String(bytes, "UTF-8").
    if (!s) s = "";
    const jsize len = (jsize) strlen(s);
    jbyteArray bytes = env->NewByteArray(len);
    env->SetByteArrayRegion(bytes, 0, len, reinterpret_cast<const jbyte *>(s));
    jclass strCls = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(strCls, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("UTF-8");
    auto out = (jstring) env->NewObject(strCls, ctor, bytes, charset);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(strCls);
    return out;
}

void progressCb(struct whisper_context *, struct whisper_state *, int progress, void *user) {
    auto *cb = static_cast<CallbackCtx *>(user);
    if (cb && cb->listener && cb->onProgress) {
        cb->env->CallVoidMethod(cb->listener, cb->onProgress, (jint) progress);
        if (cb->env->ExceptionCheck()) cb->env->ExceptionClear();
    }
}

void segmentCb(struct whisper_context *ctx, struct whisper_state *, int n_new, void *user) {
    auto *cb = static_cast<CallbackCtx *>(user);
    if (!cb || !cb->listener || !cb->onSegment) return;
    const int n = whisper_full_n_segments(ctx);
    for (int i = n - n_new; i < n; ++i) {
        jstring s = toJString(cb->env, whisper_full_get_segment_text(ctx, i));
        cb->env->CallVoidMethod(cb->listener, cb->onSegment, s);
        if (cb->env->ExceptionCheck()) cb->env->ExceptionClear();
        cb->env->DeleteLocalRef(s);
    }
}

bool abortCb(void *user) {
    auto *flag = static_cast<std::atomic<bool> *>(user);
    return flag && flag->load();
}

bool encoderBeginCb(struct whisper_context *, struct whisper_state *, void *user) {
    return !abortCb(user);
}

// ── native crash handler: write a backtrace (module + offset per frame) into the engine log,
//    then hand the signal to the previous handler (Android's debuggerd → tombstone) ──
int g_crash_fd = -1;
struct sigaction g_old_actions[NSIG];

void writeStr(const char *s) { if (g_crash_fd >= 0 && s) { ssize_t r = write(g_crash_fd, s, strlen(s)); (void) r; } }

void writeHex(uintptr_t v) {
    char buf[2 + 16 + 1]; buf[0] = '0'; buf[1] = 'x';
    for (int i = 0; i < 16; ++i) { int d = (v >> ((15 - i) * 4)) & 0xF; buf[2 + i] = (char) (d < 10 ? '0' + d : 'a' + d - 10); }
    buf[18] = 0; writeStr(buf);
}

struct BtState { int depth; };

_Unwind_Reason_Code btFrame(struct _Unwind_Context *ctx, void *arg) {
    auto *st = static_cast<BtState *>(arg);
    uintptr_t pc = _Unwind_GetIP(ctx);
    if (pc == 0) return _URC_END_OF_STACK;
    Dl_info info{};
    writeStr("  #"); char n[8]; snprintf(n, sizeof n, "%02d ", st->depth); writeStr(n);
    writeStr("pc "); writeHex(pc);
    if (dladdr(reinterpret_cast<void *>(pc), &info) && info.dli_fname) {
        writeStr("  "); writeStr(info.dli_fname);
        writeStr(" +"); writeHex(pc - reinterpret_cast<uintptr_t>(info.dli_fbase));
        if (info.dli_sname) { writeStr(" ("); writeStr(info.dli_sname); writeStr(")"); }
    }
    writeStr("\n");
    return ++st->depth >= 48 ? _URC_END_OF_STACK : _URC_NO_REASON;
}

void crashHandler(int sig, siginfo_t *info, void *uctx) {
    writeStr("\n[crash] native signal "); char n[8]; snprintf(n, sizeof n, "%d", sig); writeStr(n);
    writeStr(" fault addr "); writeHex(reinterpret_cast<uintptr_t>(info ? info->si_addr : nullptr));
    writeStr("\n[crash] backtrace:\n");
    BtState st{0};
    _Unwind_Backtrace(btFrame, &st);
    writeStr("[crash] end\n");
    // chain to the previous handler so Android still records the crash
    struct sigaction &old = g_old_actions[sig];
    if (old.sa_flags & SA_SIGINFO) { if (old.sa_sigaction) { old.sa_sigaction(sig, info, uctx); return; } }
    else if (old.sa_handler != SIG_DFL && old.sa_handler != SIG_IGN && old.sa_handler) { old.sa_handler(sig); return; }
    signal(sig, SIG_DFL);
    raise(sig);
}

void installCrashHandler() {
    static bool installed = false;
    if (installed) return;
    installed = true;
    for (int sig : {SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGABRT}) {
        struct sigaction sa{};
        sa.sa_sigaction = crashHandler;
        sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
        sigemptyset(&sa.sa_mask);
        sigaction(sig, &sa, &g_old_actions[sig]);
    }
}

// ggml-opencl (v1.9.5) creates its backend context lazily in ggml_cl_init(), but whisper.cpp
// allocates the model weights *before* any backend exists. The OpenCL buffer type's
// get_alignment()/get_max_size() then dereference a NULL backend_ctx (SIGSEGV at 0x60,
// reproduced on Adreno 740 / Galaxy S23). Initialising each GPU backend once up front
// creates that context; the backends are kept for the lifetime of the process.
std::vector<ggml_backend_t> g_gpu_warm;

void warmUpGpuBackends() {
    static bool done = false;
    if (done) return;
    done = true;
    const size_t n = ggml_backend_dev_count();
    for (size_t i = 0; i < n; ++i) {
        ggml_backend_dev_t d = ggml_backend_dev_get(i);
        const auto t = ggml_backend_dev_type(d);
        if (t != GGML_BACKEND_DEVICE_TYPE_GPU && t != GGML_BACKEND_DEVICE_TYPE_IGPU) continue;
        ggml_backend_t b = ggml_backend_dev_init(d, nullptr);
        std::lock_guard<std::mutex> lock(g_log_mutex);
        if (g_log_file) {
            fprintf(g_log_file, "[jni] gpu backend warm-up %s: %s\n", b ? "ok" : "FAILED", ggml_backend_dev_description(d));
        }
        if (b) g_gpu_warm.push_back(b);
    }
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_miniichat_whisper_WhisperLib_initContext(JNIEnv *env, jobject, jstring modelPath, jboolean useGpu, jboolean flashAttn) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = useGpu == JNI_TRUE;
    cparams.flash_attn = flashAttn == JNI_TRUE;
    if (cparams.use_gpu) warmUpGpuBackends();
    LOGI("init: model=%s use_gpu=%d flash_attn=%d", path, (int) cparams.use_gpu, (int) cparams.flash_attn);
    {
        std::lock_guard<std::mutex> lock(g_log_mutex);
        if (g_log_file) { fprintf(g_log_file, "[jni] init model=%s use_gpu=%d flash_attn=%d\n", path, (int) cparams.use_gpu, (int) cparams.flash_attn); fflush(g_log_file); }
    }
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    if (!ctx) LOGW("failed to load model %s", path);
    env->ReleaseStringUTFChars(modelPath, path);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_miniichat_whisper_WhisperLib_setLogFile(JNIEnv *env, jobject, jstring path) {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    if (g_log_file) { fclose(g_log_file); g_log_file = nullptr; }
    if (path) {
        const char *p = env->GetStringUTFChars(path, nullptr);
        g_log_file = fopen(p, "a");
        env->ReleaseStringUTFChars(path, p);
        if (g_log_file) {
            setvbuf(g_log_file, nullptr, _IONBF, 0);
            g_crash_fd = fileno(g_log_file);
            installCrashHandler();
        }
    }
    whisper_log_set(engineLog, nullptr);
    ggml_log_set(engineLog, nullptr);
}

JNIEXPORT void JNICALL
Java_com_miniichat_whisper_WhisperLib_logLine(JNIEnv *env, jobject, jstring line) {
    const char *p = env->GetStringUTFChars(line, nullptr);
    {
        std::lock_guard<std::mutex> lock(g_log_mutex);
        if (g_log_file) { fputs(p, g_log_file); fputc('\n', g_log_file); fflush(g_log_file); }
    }
    env->ReleaseStringUTFChars(line, p);
}

JNIEXPORT void JNICALL
Java_com_miniichat_whisper_WhisperLib_freeContext(JNIEnv *, jobject, jlong ptr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    if (ctx) whisper_free(ctx);
}

JNIEXPORT void JNICALL
Java_com_miniichat_whisper_WhisperLib_requestAbort(JNIEnv *, jobject) {
    g_abort.store(true);
}

JNIEXPORT jstring JNICALL
Java_com_miniichat_whisper_WhisperLib_transcribe(
        JNIEnv *env, jobject,
        jlong ptr, jfloatArray samples, jstring language, jint threads,
        jboolean translate, jstring initialPrompt, jobject listener) {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    if (!ctx) return toJString(env, "");

    g_abort.store(false);

    const char *lang = env->GetStringUTFChars(language, nullptr);
    const char *prompt = initialPrompt ? env->GetStringUTFChars(initialPrompt, nullptr) : nullptr;

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime   = false;
    params.print_progress   = false;
    params.print_timestamps = false;
    params.print_special    = false;
    params.translate        = translate == JNI_TRUE;
    params.language         = (lang && strlen(lang) > 0) ? lang : "auto";
    params.detect_language  = false;
    params.n_threads        = threads > 0 ? threads : 4;
    params.no_context       = true;
    params.single_segment   = false;
    params.suppress_blank   = true;
    if (prompt && strlen(prompt) > 0) params.initial_prompt = prompt;

    CallbackCtx cb{};
    cb.env = env;
    cb.listener = listener;
    cb.abort = &g_abort;
    if (listener) {
        jclass cls = env->GetObjectClass(listener);
        cb.onProgress = env->GetMethodID(cls, "onProgress", "(I)V");
        cb.onSegment  = env->GetMethodID(cls, "onSegment", "(Ljava/lang/String;)V");
        if (env->ExceptionCheck()) { env->ExceptionClear(); cb.onProgress = nullptr; cb.onSegment = nullptr; }
        env->DeleteLocalRef(cls);
        params.progress_callback = progressCb;
        params.progress_callback_user_data = &cb;
        params.new_segment_callback = segmentCb;
        params.new_segment_callback_user_data = &cb;
    }
    params.abort_callback = abortCb;
    params.abort_callback_user_data = &g_abort;
    params.encoder_begin_callback = encoderBeginCb;
    params.encoder_begin_callback_user_data = &g_abort;

    jsize n = env->GetArrayLength(samples);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);

    whisper_reset_timings(ctx);
    {
        std::lock_guard<std::mutex> lock(g_log_mutex);
        if (g_log_file) { fprintf(g_log_file, "[jni] whisper_full: %d samples (%.1f s), threads=%d, lang=%s\n", (int) n, n / 16000.0, params.n_threads, params.language); }
    }
    int rc = whisper_full(ctx, params, data, n);
    whisper_print_timings(ctx);  // encode / decode / fallbacks → engine log

    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);

    std::string out;
    if (rc == 0) {
        const int segs = whisper_full_n_segments(ctx);
        for (int i = 0; i < segs; ++i) {
            const char *t = whisper_full_get_segment_text(ctx, i);
            if (t) out += t;
        }
    } else {
        LOGW("whisper_full failed: %d", rc);
    }

    env->ReleaseStringUTFChars(language, lang);
    if (prompt) env->ReleaseStringUTFChars(initialPrompt, prompt);

    return toJString(env, out.c_str());
}

/**
 * Encoder benchmark (same method as whisper.cpp's bench tool): time to encode one 30-second
 * window with [threads]. Returns milliseconds, or -1 on error. The first call per context
 * includes a warm-up run.
 */
JNIEXPORT jdouble JNICALL
Java_com_miniichat_whisper_WhisperLib_benchEncode(JNIEnv *, jobject, jlong ptr, jint threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    if (!ctx) return -1;
    if (whisper_set_mel(ctx, nullptr, 0, whisper_model_n_mels(ctx)) != 0) return -1;
    static whisper_context *warmed = nullptr;
    if (warmed != ctx) { if (whisper_encode(ctx, 0, threads) != 0) return -1; warmed = ctx; }
    const int64_t t0 = ggml_time_us();
    if (whisper_encode(ctx, 0, threads) != 0) return -1;
    const double ms = (ggml_time_us() - t0) / 1000.0;
    {
        std::lock_guard<std::mutex> lock(g_log_mutex);
        if (g_log_file) { fprintf(g_log_file, "[bench] encoder 30 s window, %d threads: %.0f ms\n", (int) threads, ms); }
    }
    return ms;
}

JNIEXPORT jstring JNICALL
Java_com_miniichat_whisper_WhisperLib_systemInfo(JNIEnv *env, jobject) {
    return toJString(env, whisper_print_system_info());
}

/** One line per ggml compute device: "<type>\t<name>\t<description>" (type = CPU|GPU|IGPU|ACCEL). */
JNIEXPORT jstring JNICALL
Java_com_miniichat_whisper_WhisperLib_listDevices(JNIEnv *env, jobject) {
    std::string out;
    const size_t n = ggml_backend_dev_count();
    for (size_t i = 0; i < n; ++i) {
        ggml_backend_dev_t d = ggml_backend_dev_get(i);
        const char *type = "?";
        switch (ggml_backend_dev_type(d)) {
            case GGML_BACKEND_DEVICE_TYPE_CPU:   type = "CPU"; break;
            case GGML_BACKEND_DEVICE_TYPE_GPU:   type = "GPU"; break;
            case GGML_BACKEND_DEVICE_TYPE_IGPU:  type = "IGPU"; break;
            case GGML_BACKEND_DEVICE_TYPE_ACCEL: type = "ACCEL"; break;
            default: break;
        }
        out += type; out += '\t';
        out += ggml_backend_dev_name(d); out += '\t';
        out += ggml_backend_dev_description(d); out += '\n';
    }
    return toJString(env, out.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_miniichat_whisper_WhisperLib_hasGpuBackend(JNIEnv *, jobject) {
#ifdef WHISPER_JNI_HAS_GPU
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT jstring JNICALL
Java_com_miniichat_whisper_WhisperLib_detectedLanguage(JNIEnv *env, jobject, jlong ptr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    if (!ctx) return toJString(env, "");
    int id = whisper_full_lang_id(ctx);
    return toJString(env, id >= 0 ? whisper_lang_str(id) : "");
}

} // extern "C"
