plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Fortlaufende Versionsnummer aus GitHub Actions, damit jede neue APK als Update erkannt wird.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "de.eichberger.heyclaude"
    compileSdk = 34

    defaultConfig {
        applicationId = "de.eichberger.heyclaude"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.1.$buildNumber"

        // Nur echte Handy-Architekturen, hält die APK klein.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Fester Signierschlüssel im Repo: Jede neue APK lässt sich über die alte installieren.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    androidResources {
        noCompress += "onnx"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
}
