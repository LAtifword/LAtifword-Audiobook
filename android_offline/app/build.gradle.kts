plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.latifbrain.offline"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.latifbrain.offline"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0-offline"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    androidResources {
        noCompress += listOf("onnx", "bin", "txt", "wav")
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
            pickFirsts += setOf("**/libonnxruntime.so")
        }
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*"
        )
    }
}

dependencies {
    implementation("com.xdcobra.sherpa:sherpa-onnx:1.13.8-1")
}
