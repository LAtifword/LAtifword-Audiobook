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
        versionCode = 11
        versionName = "3.3.0-integrated"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
                "**/libonnxruntime.so",
            )
        }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE-*",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE-*",
            )
        }
    }

    androidResources {
        noCompress += listOf("onnx", "bin", "json", "txt", "wav")
    }
}

val requiredSilmaAssets = mapOf(
    "F5_Preprocess.onnx" to 73_904_440L,
    "model.onnx" to 612_437_669L,
    "F5_Decode.onnx" to 62_546_929L,
    "config.json" to 156_367L,
    "default_ref.wav" to 372_680L,
    "vocab.txt" to 36_357L,
)

val verifySilmaAssets by tasks.registering {
    group = "verification"
    description = "Fails release builds when an embedded SILMA asset is absent or truncated."
    doLast {
        requiredSilmaAssets.forEach { (name, expectedBytes) ->
            val asset = file("src/main/assets/silma-f5/$name")
            check(asset.isFile) { "Required SILMA asset is missing: ${asset.path}" }
            check(asset.length() == expectedBytes) {
                "SILMA asset $name has ${asset.length()} bytes; expected $expectedBytes"
            }
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifySilmaAssets)
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.28.0")

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")
}
