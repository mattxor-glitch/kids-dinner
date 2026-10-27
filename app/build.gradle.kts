plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.familybuzz"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.familybuzz"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // A fixed key so each new build installs as an update over the last one.
    // Keep this repository private.
    signingConfigs {
        create("family") {
            storeFile = file("../keystore/family.jks")
            storePassword = "familybuzz"
            keyAlias = "family"
            keyPassword = "familybuzz"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("family")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("family")
        }
    }

    flavorDimensions += "role"
    productFlavors {
        create("parent") {
            dimension = "role"
            applicationIdSuffix = ".parent"
            resValue("string", "app_name", "Family Buzz")
        }
        create("kid") {
            dimension = "role"
            applicationIdSuffix = ".kid"
            resValue("string", "app_name", "Buzz")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
