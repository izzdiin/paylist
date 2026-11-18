plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

// Stage A (design §11, C17): Room >= 2.7.0 + KSP2, WorkManager 2.9.0.
// KSP version prefix must equal Kotlin 2.0.20 (see root build.gradle.kts).
// Documented fallback, only if KSP2 fails: Room 2.6.1 + ksp.useKSP2=false.
dependencies {
    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    ksp("androidx.room:room-compiler:2.7.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.room:room-testing:2.7.0")
    testImplementation("androidx.work:work-testing:2.9.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // Real org.json for JVM unit tests: android.jar's org.json stubs throw.
    testImplementation("org.json:json:20240303")

    // androidTest: Stage B (OutboxMigrationTest + DeliveryLeaseTest,
    // real Room on-device) + Stage C end-to-end (SenderRetentionTest +
    // CutoverEndToEndTest). These COMPILE in CI/on laptopgrt2 but cannot
    // EXECUTE there (no device/emulator); execution needs a device or an emulator.
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
