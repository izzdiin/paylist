plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.netter.paylist"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.netter.paylist"
        minSdk = 28 // Android 9 minimum: app harus bisa dipasang di HP mana pun
        targetSdk = 34
        versionCode = 4
        versionName = "2.3"
    }

    buildTypes {
        release {
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
}

// Tanpa dependensi eksternal: hanya framework Android (org.json + HttpURLConnection bawaan).
