plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.cs2a.helper"
    compileSdk = 34

    // Номер сборки прокидывается из CI (-PappVersionCode=<github.run_number>), чтобы версия в
    // приложении всегда совпадала с номером GitHub Release, который проверяет автообновление.
    val ciVersionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1

    defaultConfig {
        applicationId = "ru.cs2a.helper"
        minSdk = 26
        targetSdk = 34
        versionCode = ciVersionCode
        versionName = "1.0.$ciVersionCode"
    }

    // Стабильный debug-keystore, зафиксированный в репозитории: без него каждый прогон CI
    // подписывал apk новым случайным ключом (свой ~/.android/debug.keystore на каждой машине),
    // и Android отказывался ставить «обновление», потому что сертификат не совпадал с уже
    // установленным — «пакет конфликтует с уже существующим».
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
}
