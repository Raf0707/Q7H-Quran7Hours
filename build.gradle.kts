
plugins {
    id("com.android.application") version "8.10.1" apply false

    id("org.jetbrains.kotlin.android") version "2.2.20" apply false

    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false

    // Kotlin Symbol Processing
    id("com.google.devtools.ksp") version "2.2.20-2.0.4" apply false

    // Room Database
    id("androidx.room") version "2.8.5" apply false
}
