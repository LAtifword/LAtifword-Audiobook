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
        versionCode = 12
        versionName = "3.4.0-habibi-msa"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            // Runtime-fix test builds must coexist with the already-installed
            // v3.4 candidate so physical A/B testing never requires uninstalling it.
            applicationIdSuffix = ".habibifix"
            versionNameSuffix = "-runtime-fix"
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

// The Habibi MSA ONNX export is pinned and checksummed by CI. Runtime validation
// verifies that the complete uncompressed packaged files were atomically copied
// before ONNX Runtime opens them. Minimum sizes catch Git-LFS/Xet pointer files
// and interrupted downloads without hard-coding upstream byte lengths.
val requiredHabibiAssets = mapOf(
    "F5_Preprocess.onnx" to 1_000_000L,
    "model.onnx" to 500_000_000L,
    "F5_Decode.onnx" to 1_000_000L,
    "config.json" to 1_000L,
    "default_ref.wav" to 10_000L,
    "vocab.txt" to 5_000L,
)

val verifyHabibiAssets by tasks.registering {
    group = "verification"
    description = "Fails release builds when an embedded Habibi MSA asset is absent or truncated."
    doLast {
        requiredHabibiAssets.forEach { (name, minimumBytes) ->
            val asset = file("src/main/assets/habibi-f5/$name")
            check(asset.isFile) { "Required Habibi MSA asset is missing: ${asset.path}" }
            check(asset.length() >= minimumBytes) {
                "Habibi MSA asset $name has ${asset.length()} bytes; minimum expected is $minimumBytes"
            }
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyHabibiAssets)
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.28.0")

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")
}
