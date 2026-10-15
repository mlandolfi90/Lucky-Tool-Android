plugins {
    id("com.android.application")
}

android {
    namespace = "com.danielealbano.androidremotecontrolmcp.probejuez"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.danielealbano.androidremotecontrolmcp.probejuez"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    // The two NPU stacks cannot share an APK: each bundles its own QNN runtime under the same library names.
    flavorDimensions += "npu"
    productFlavors {
        create("litert") {
            dimension = "npu"
        }
        create("geniex") {
            dimension = "npu"
            applicationIdSuffix = ".geniex"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // NPU: the Hexagon skel libraries must be extracted to nativeLibraryDir (ADSP_LIBRARY_PATH points there).
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

// LiteRT NPU: only the v79 HTP (SM8750) is kept; the prepare library is only needed for on-device compilation.
androidComponents {
    onVariants(selector().withFlavor("npu" to "litert")) { variant ->
        variant.packaging.jniLibs.excludes.addAll(
            listOf(
                "**/libQnnHtpPrepare.so",
                "**/libQnnDsp*",
                "**/libQnnGpu.so",
                "**/libQnnHtpV6*",
                "**/libQnnHtpV73*",
                "**/libQnnHtpV75*",
                "**/libQnnHtpV81*",
            ),
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    "litertImplementation"("com.google.ai.edge.litert:litert-npu-runtime-qualcomm:2.3.0")
    "litertImplementation"("com.qualcomm.qti:qnn-runtime:2.50.0")
    "geniexImplementation"("com.qualcomm.qti:geniex-android:0.8.0")
}
