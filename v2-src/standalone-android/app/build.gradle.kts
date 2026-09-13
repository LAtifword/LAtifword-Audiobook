plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.latif.audiobook.offline"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.latif.audiobook.offline"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "3.0.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += setOf(
                "**/libc++_shared.so",
                "**/libonnxruntime.so"
            )
        }
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }

    androidResources {
        // The neural weights are already binary-compressed and need random access/copy-once extraction.
        noCompress += listOf("onnx", "bin", "json", "txt", "wav")
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.28.0")
}
