plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lpsm.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lpsm.player"

        // APK universal: Android 5.0+ em celular, tablet, TV Box e Android TV.
        minSdk = 21
        targetSdk = 35

        versionCode = 56
        versionName = "2.2.33"

        buildConfigField(
            "String",
            "API_BASE_URL",
            "\"https://lpsm-player-backend.onrender.com\""
        )
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    signingConfigs {

        create("release") {

            val keystorePath =
                System.getenv("LPSM_KEYSTORE_PATH") ?: ""

            if (keystorePath.isNotBlank()) {
                storeFile = file(keystorePath)
            }

            storePassword =
                System.getenv("LPSM_KEYSTORE_PASSWORD") ?: ""

            keyAlias =
                System.getenv("LPSM_KEY_ALIAS") ?: ""

            keyPassword =
                System.getenv("LPSM_KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {

        debug {
            isMinifyEnabled = false
        }

        release {

            signingConfig =
                signingConfigs.getByName("release")

            isMinifyEnabled = false

            proguardFiles(
                getDefaultProguardFile(
                    "proguard-android-optimize.txt"
                ),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility =
            JavaVersion.VERSION_17

        targetCompatibility =
            JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {

    implementation(
        "androidx.core:core-ktx:1.15.0"
    )

    implementation(
        "androidx.appcompat:appcompat:1.7.0"
    )

    implementation(
        "androidx.recyclerview:recyclerview:1.4.0"
    )

    implementation(
        "androidx.security:security-crypto:1.1.0-alpha06"
    )

    implementation(
        "androidx.media3:media3-exoplayer:1.5.1"
    )

    implementation(
        "androidx.media3:media3-exoplayer-hls:1.5.1"
    )

    implementation(
        "androidx.media3:media3-ui:1.5.1"
    )

    implementation(
        "com.google.android.material:material:1.12.0"
    )

    implementation(
        "io.coil-kt.coil3:coil:3.0.4"
    )

    implementation(
        "io.coil-kt.coil3:coil-network-okhttp:3.0.4"
    )
}
