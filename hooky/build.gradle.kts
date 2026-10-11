// La bibliothèque, distribuée par JitPack depuis le dépôt public
// github.com/Defdjamel/hooky-android (ce dossier, à sa racine) :
//   repositories { maven("https://jitpack.io") }
//   implementation("com.github.Defdjamel:hooky-android:1.0.0")
// JitPack lance jitpack.yml (publishReleasePublicationToMavenLocal) et sert
// ce qui sort dans son ~/.m2 sous com.github.Defdjamel : l'AAR, ses sources,
// le POM et le module Gradle. L'app d'exemple n'est jamais publiée.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

// La version du SDK : publiée, et envoyée à l'API (X-Hooky-Client).
val versionSdk = "1.0.0"

// Les coordonnées publiées. JitPack donne la version (le tag : 1.0.0, ou un
// commit) dans $VERSION ; hors de JitPack, celle du SDK.
val groupePublie = "com.github.Defdjamel"
val versionPubliee = System.getenv("VERSION")?.takeIf { System.getenv("JITPACK") == "true" } ?: versionSdk

group = groupePublie
version = versionPubliee

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
        // Robolectric (les tests Compose sur la JVM) lit les ressources.
        unitTests.isIncludeAndroidResources = true
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
    }
    // L'API publique est écrite exprès : rien ne fuit par inadvertance.
    explicitApi()
}

dependencies {
    // En « api » : ce que l'API publique montre à l'app (HookyActivity est une
    // ComponentActivity, HookyBubble prend un Modifier, HookyBubbleView est une
    // AbstractComposeView, unreadCount un StateFlow). Le POM les déclare en
    // compile : l'app les a sans les nommer.
    implementation(libs.androidx.core.ktx)
    api(libs.androidx.activity.compose)

    // Compose + Material 3 (versions alignées par la BoM).
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Réseau : HTTP, WebSocket (Action Cable) et multipart.
    implementation(libs.okhttp)
    api(libs.kotlinx.coroutines.android)

    // Tests JVM : le vrai org.json (celui d'Android n'est qu'un bouchon hors
    // de l'appareil) et un faux serveur pour le protocole.
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // L'interface Compose testée sur la JVM, sans appareil.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // Le manifeste d'activité vide qu'il faut aux tests Compose.
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = groupePublie
            artifactId = "hooky-android"
            version = versionPubliee
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Hooky for Android")
                description.set("The Hooky chat bubble for Android apps: native Jetpack Compose UI, realtime replies, attachments.")
                url.set("https://heyhooky.com")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        name.set("Hooky")
                        url.set("https://heyhooky.com")
                    }
                }
                scm {
                    url.set("https://github.com/Defdjamel/hooky-android")
                    connection.set("scm:git:https://github.com/Defdjamel/hooky-android.git")
                }
            }
        }
    }
}
