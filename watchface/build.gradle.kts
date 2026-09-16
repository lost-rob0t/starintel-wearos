plugins {
    id("com.android.application")
}

android {
    namespace = "actor.starintel.watchface"
    compileSdk = 36
    enableKotlin = false

    defaultConfig {
        applicationId = "actor.starintel.watchface"
        minSdk = 33
        targetSdk = 36
        versionCode = 4
        versionName = "0.3.0-alpha"
    }

    signingConfigs {
        getByName("debug") {
            // Keep package-manager updates installable across CI/master builds.
            // This repository key is public and debug-only; production uses a private signer.
            storeFile = rootProject.file("nix/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    flavorDimensions += "face"
    productFlavors {
        create("neon") {
            dimension = "face"
            applicationIdSuffix = ".neon"
        }
        create("command") {
            dimension = "face"
            applicationIdSuffix = ".command"
        }
        create("terminal") {
            dimension = "face"
            applicationIdSuffix = ".terminal"
        }
    }
}
