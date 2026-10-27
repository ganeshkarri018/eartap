plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.eartap.recorder"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.eartap.recorder"
        minSdk = 30          // Android 11+
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
    }

    // One fixed key for every build, so a new APK installs over the old one
    // (no more uninstalling). It's a personal sideload key, not a Play Store key.
    signingConfigs {
        create("eartap") {
            storeFile = file("eartap-debug.jks")
            storePassword = "eartap123"
            keyAlias = "eartap"
            keyPassword = "eartap123"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("eartap")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("eartap")
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

// No extra libraries: everything uses built-in Android APIs.
