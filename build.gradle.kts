plugins {
    id("com.android.application")          version "8.5.2"  apply false
    id("org.jetbrains.kotlin.android")     version "2.0.0"  apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0" apply false  // NEW — required for Kotlin 2.0 + Compose
    id("org.jetbrains.kotlin.kapt")        version "2.0.0"  apply false  // was: kotlin-kapt
    id("com.google.dagger.hilt.android")   version "2.51.1" apply false
}
