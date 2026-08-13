plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lpsm.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lpsm.player"

        minSdk = 23
        targetSdk = 35

        /*
         * PRIMEIRA VERSÃO COM
         * ASSINATURA DEFINITIVA DO LPSM.
         *
         * Nas próximas atualizações:
         * versionCode precisa sempre aumentar.
         */
        versionCode = 17
        versionName = "2.2.0"

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

    /*
     * =====================================================
     * ASSINATURA DEFINITIVA
     * =====================================================
     *
     * Os valores NÃO ficam escritos
     * dentro do projeto.
     *
     * O GitHub Actions vai entregar:
     *
     * LPSM_KEYSTORE_PATH
     * LPSM_KEYSTORE_PASSWORD
     * LPSM_KEY_ALIAS
     * LPSM_KEY_PASSWORD
     */
    signingConfigs {

        create("release") {

            val keystorePath =
                System.getenv(
                    "LPSM_KEYSTORE_PATH"
                )
                    ?: ""

            if (
                keystorePath.isNotBlank()
            ) {

                storeFile =
                    file(
                        keystorePath
                    )
            }

            storePassword =
                System.getenv(
                    "LPSM_KEYSTORE_PASSWORD"
                )
                    ?: ""

            keyAlias =
                System.getenv(
                    "LPSM_KEY_ALIAS"
                )
                    ?: ""

            keyPassword =
                System.getenv(
                    "LPSM_KEY_PASSWORD"
                )
                    ?: ""
        }
    }

    buildTypes {

        debug {
            isMinifyEnabled = false
        }

        release {

            /*
             * Usa sempre a chave fixa
             * do LPSM.
             */
            signingConfig =
                signingConfigs
                    .getByName(
                        "release"
                    )

            /*
             * Primeiro vamos testar a
             * versão assinada sem minificação.
             *
             * Depois podemos ativar a
             * proteção novamente.
             */
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
