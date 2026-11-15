// Builds the GPU (OpenCL, Adreno-optimised kernels) variant of the whisper.cpp JNI library
// for 64-bit ARM. Loaded only when the user enables "Use GPU" and the CPU supports ARMv8.2
// dotprod+fp16; it links against the vendor libOpenCL.so (e.g. Qualcomm Adreno) at runtime.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.miniichat.whispergpu"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DWHISPER_JNI_LIB_NAME=whisper_jni_gpu",
                    "-DWHISPER_JNI_OPENCL=ON",
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
