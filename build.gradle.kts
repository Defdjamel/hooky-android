// Les plugins sont déclarés ici et appliqués dans les modules. Le Kotlin
// est celui intégré à l'Android Gradle Plugin 9.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
