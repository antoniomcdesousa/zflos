plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zfl.os"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zfl.os"
        minSdk = 29
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "202611201809"
    }

    // A fixed key, so every new build installs over the old one and keeps your data.
    signingConfigs {
        create("zfl") {
            storeFile = file("zflos.jks")
            storePassword = "zflos123"
            keyAlias = "zflos"
            keyPassword = "zflos123"
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("zfl")
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
