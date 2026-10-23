// Vendored copy of the openWakeWord Android library (Apache-2.0). See VENDORED.md.
// Upstream's publishing/docs plugins are removed; the Kotlin source is unchanged.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rementia.openwakeword.lib"
    compileSdk = 35

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // The engine exposes detections as a Kotlin Flow, so coroutines are part of its public API.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")
    implementation("org.apache.commons:commons-math3:3.6.1")
}
