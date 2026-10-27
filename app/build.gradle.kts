plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.appboundary"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.appboundary"
        minSdk = 26
        targetSdk = 34
        versionCode = 49
        versionName = "49.0"
    }

    // Signing values come from environment variables (GitHub Actions secrets).
    // If KEYSTORE_FILE is not set, the release build is simply left unsigned.
    val keystorePath: String? = System.getenv("KEYSTORE_FILE")

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// Intentionally no third-party dependencies: keeps the build tiny and fast.
