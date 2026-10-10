// La bibliothèque : com.heyhooky:hooky-android, publiée plus tard par JitPack
// ou Maven Central (maven-publish est prêt, rien n'est publié d'ici).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

// La version du SDK : publiée, et envoyée à l'API (X-Hooky-Client).
val versionSdk = "1.0.0"

group = "com.heyhooky"
version = versionSdk

android {
    namespace = "com.heyhooky.sdk"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "VERSION_SDK", "\"$versionSdk\"")
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // Les classes d'Android (Log, Uri…) appelées par mégarde dans un test
        // JVM rendent une valeur par défaut au lieu de lever.
        unitTests.isReturnDefaultValues = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // L'API publique est écrite exprès : rien ne fuit par inadvertance.
        freeCompilerArgs.add("-Xexplicit-api=strict")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose + Material 3 (versions alignées par la BoM).
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Réseau : HTTP, WebSocket (Action Cable) et multipart.
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    // Tests JVM : le vrai org.json (celui d'Android n'est qu'un bouchon hors
    // de l'appareil) et un faux serveur pour le protocole.
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    // Tests sur l'appareil : l'écran de conversation en Compose.
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.heyhooky"
            artifactId = "hooky-android"
            version = versionSdk
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Hooky for Android")
                description.set("The Hooky chat bubble for Android apps: native Jetpack Compose UI, realtime replies, attachments.")
                url.set("https://heyhooky.com")
                // La licence, les développeurs et le scm (exigés par Maven
                // Central) restent à choisir avant la première publication.
            }
        }
    }
}
