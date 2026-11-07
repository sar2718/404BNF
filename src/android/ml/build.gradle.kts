plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.team404bnf.ml"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 26
        // ONNX weights are downloaded into filesDir; never bundled into APK.
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Matches the current Python reference ORT version; Android artifact contains native libraries.
    api("com.microsoft.onnxruntime:onnxruntime-android:1.31.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(libs.junit)
}
