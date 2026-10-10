// L'app d'exemple : une petite boutique qui montre la bulle Hooky, open(),
// identify() et track(). La clé du site et l'adresse de l'API se donnent
// dans local.properties (hooky.siteKey, hooky.api) ou en -P à Gradle.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val locales = Properties().apply {
    val fichier = rootProject.file("local.properties")
    if (fichier.exists()) fichier.inputStream().use { load(it) }
}
fun reglage(nom: String, defaut: String): String =
    (project.findProperty(nom) as String?) ?: locales.getProperty(nom) ?: defaut

android {
    namespace = "com.heyhooky.exemple"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.heyhooky.exemple"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "HOOKY_SITE_KEY", "\"${reglage("hooky.siteKey", "YOUR_SITE_KEY")}\"")
    }

    buildTypes {
        debug {
            // L'émulateur voit le Mac sous 10.0.2.2 : l'API Rails locale.
            buildConfigField("String", "HOOKY_API", "\"${reglage("hooky.api", "http://10.0.2.2:3032")}\"")
        }
        release {
            buildConfigField("String", "HOOKY_API", "\"${reglage("hooky.api", "https://api.heyhooky.com")}\"")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":hooky"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
}
