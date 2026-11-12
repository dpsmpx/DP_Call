plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
}

android {
    namespace = "com.dpsmpx.dpcall"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dpsmpx.dpcall"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Общий отладочный ключ лежит в репозитории, чтобы сборки CI обновляли друг друга
        // без переустановки (при переустановке теряется ключ пользователя, то есть его код).
        // Ключ публичный — годится только для тестовых сборок.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Релизный ключ берётся из переменных окружения (секреты GitHub Actions).
        val releaseStore = System.getenv("DPCALL_KEYSTORE_FILE")
        if (!releaseStore.isNullOrBlank()) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = System.getenv("DPCALL_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DPCALL_KEY_ALIAS")
                keyPassword = System.getenv("DPCALL_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // -PnoMinify собирает ту же версию без R8 — для сравнения при поиске ошибок.
            val minify = !project.hasProperty("noMinify")
            isMinifyEnabled = minify
            isShrinkResources = minify
            // Только ABI реальных телефонов; x86 нужен лишь эмуляторам и остаётся в debug.
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
            // Сведения о коммите в APK зависят от способа получения исходников и мешают
            // воспроизводимой сборке (F-Droid сверяет свой APK с опубликованным).
            vcsInfo.include = false
            // Без релизного ключа сборка подписывается общим тестовым ключом из репозитория.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
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
        compose = true
    }

    // Блок со списком зависимостей шифруется ключом Google и мешает воспроизводимой сборке.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    testOptions {
        unitTests {
            // Ресурсы нужны только съёмке скриншотов для каталога (StoreScreenshotsTest).
            isIncludeAndroidResources = true
            all { test ->
                // Скриншоты снимаются отдельно: ./gradlew testDebugUnitTest -Pscreenshots
                if (project.hasProperty("screenshots")) {
                    test.filter.includeTestsMatching("*StoreScreenshotsTest")
                    test.systemProperty("roborazzi.test.record", "true")
                    test.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                    test.maxHeapSize = "3g"
                } else {
                    test.exclude("**/StoreScreenshotsTest*")
                }
            }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-telecom:1.0.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("io.getstream:stream-webrtc-android:1.3.10")
    // Сигналинг через релеи Nostr: WebSocket и ключи secp256k1
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("fr.acinq.secp256k1:secp256k1-kmp-jni-android:0.16.0")
    // QR-коды: показ своего кода и сканирование кода собеседника
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("fr.acinq.secp256k1:secp256k1-kmp-jni-jvm:0.16.0")
    // Съёмка скриншотов для каталога на JVM, без эмулятора (см. docs/FDROID.md)
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.43.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.43.1")
    testImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
