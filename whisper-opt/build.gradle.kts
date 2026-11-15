// Builds an optimised copy of the whisper.cpp JNI library for modern 64-bit ARM CPUs
// (ARMv8.2-A + dot product + FP16 arithmetic: Snapdragon 855 and newer, Exynos 9820+, Tensor, …).
// The app loads libwhisper_jni_v82.so when /proc/cpuinfo reports those features and falls back
// to the baseline libwhisper_jni.so otherwise.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.miniichat.whisperopt"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DWHISPER_JNI_LIB_NAME=whisper_jni_v82",
                    "-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16"
                )
                cppFlags += listOf("-O3")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../app/src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
