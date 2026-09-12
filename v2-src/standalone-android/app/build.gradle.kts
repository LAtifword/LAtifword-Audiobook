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
        versionCode = 5
        versionName = "2.1.1"
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
            // sherpa-onnx 1.13.8 ships its own libonnxruntime.so. Keep a single
            // copy in the APK so sherpa TTS and the Java ORT bridge share the
            // same process-level runtime instead of loading duplicate SONAMEs.
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
        noCompress += listOf("onnx", "bin", "json")
    }
}

dependencies {
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // v1.22.0 was incompatible with sherpa-onnx 1.13.8's bundled ONNX Runtime
    // and caused OrtEnvironment to fail before narration began. Use the closest
    // matching public Android Java/JNI bridge and keep Rawi optional at runtime.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.28.0")
}
