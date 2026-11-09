plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Firebase (вход, комментарии, админка) включается, только если рядом лежит google-services.json.
// Без него приложение собирается и работает как раньше — без входа.
val firebaseConfigured = file("google-services.json").exists()
if (firebaseConfigured) apply(plugin = "com.google.gms.google-services")

// Почта владельца: при входе с ней аккаунт сам получает роль «владелец» и может раздавать админки.
val ownerEmail = (project.findProperty("ownerEmail") as String?).orEmpty().trim()

val baseUrl = (project.findProperty("scheduleBaseUrl") as String?)
    ?.let { if (it.endsWith("/")) it else "$it/" }
    ?: "https://example.github.io/raspisanie/"

android {
    namespace = "ru.mocomk.raspisanie"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.mocomk.raspisanie"
        minSdk = 26
        targetSdk = 35
        // номер сборки GitHub Actions — каждая новая версия больше предыдущей
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.0.$build"
        buildConfigField("String", "BASE_URL", "\"$baseUrl\"")
        buildConfigField("String", "OWNER_EMAIL", "\"$ownerEmail\"")
    }

    // Постоянный ключ: в GitHub Actions берётся из секретов, локально — из signing/.
    // Один и тот же ключ нужен, чтобы обновления ставились поверх старой версии.
    val keystorePath = System.getenv("KEYSTORE_FILE")
        ?: rootProject.file("../signing/raspisanie.jks").takeIf { it.exists() }?.path
    val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
    signingConfigs {
        if (keystorePath != null && !keystorePassword.isNullOrEmpty()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = "raspisanie"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug") // без ключа — временная подпись
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // вход, комментарии, роли
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // реклама: Рекламная сеть Яндекса
    implementation("com.yandex.android:mobileads:7.8.0")
}
