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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Native libraries extracted to nativeLibraryDir, as in every run measured on the phone (2026-10-14).
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
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
}
