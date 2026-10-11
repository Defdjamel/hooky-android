// La bibliothèque, servie par GitHub Pages depuis la branche « maven » du
// dépôt public github.com/Defdjamel/hooky-android (ce dossier, à sa racine) :
//   repositories { maven("https://defdjamel.github.io/hooky-android") }
//   implementation("com.heyhooky:hooky-android:1.0.0")
// `./gradlew :hooky:publishReleasePublicationToDepotRepository` écrit le dépôt
// Maven dans build/depot-maven (l'AAR, ses sources, le POM et le module
// Gradle), à recopier dans la branche « maven » (sdk/publier-maven.sh).
// JitPack (jitpack.yml) reste possible, sous com.github.Defdjamel.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

// La version du SDK : publiée, et envoyée à l'API (X-Hooky-Client).
val versionSdk = "1.0.0"

// Les coordonnées publiées : com.heyhooky, à la version du SDK. Sur JitPack,
// com.github.Defdjamel, à la version qu'il donne (le tag) dans $VERSION.
val surJitpack = System.getenv("JITPACK") == "true"
val groupePublie = if (surJitpack) "com.github.Defdjamel" else "com.heyhooky"
val versionPubliee = System.getenv("VERSION")?.takeIf { surJitpack } ?: versionSdk

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
    repositories {
        // Le dépôt Maven statique servi par GitHub Pages (voir plus haut).
        maven {
            name = "depot"
            url = uri(layout.buildDirectory.dir("depot-maven"))
        }
    }
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
