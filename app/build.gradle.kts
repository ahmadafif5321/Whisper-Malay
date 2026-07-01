plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kafkasl.phonewhisper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kafkasl.phonewhisper"
        minSdk = 30
        targetSdk = 34
        versionCode = 3
        versionName = "0.4.0-v2"
        setProperty("archivesBaseName", "whisper-Malay")

        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    @Suppress("DEPRECATION")
    kotlinOptions { jvmTarget = "17" }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Let android.util.Log (and other stubbed android APIs) return defaults instead
            // of throwing "not mocked" in plain JVM unit tests.
            isReturnDefaultValues = true
        }
    }

    packaging {
        // jniLibs ships libonnxruntime.so 1.17.1 for sherpa-onnx; the onnxruntime-android
        // AAR (same 1.17.1) bundles an identical copy — keep one
        jniLibs { pickFirsts += "lib/arm64-v8a/libonnxruntime.so" }
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Must stay on 1.17.1 to match the libonnxruntime.so bundled for sherpa-onnx in jniLibs
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-extensions-android:0.10.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.apache.commons:commons-compress:1.27.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
