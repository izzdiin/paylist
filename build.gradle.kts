plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    // KSP version prefix MUST equal the Kotlin version (design §11, C17).
    id("com.google.devtools.ksp") version "2.0.20-1.0.24" apply false
}
