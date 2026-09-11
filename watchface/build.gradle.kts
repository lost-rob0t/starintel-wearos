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
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        getByName("debug") {
            // Public, debug-only key committed for reproducible local/Nix builds.
            // Never use this signing identity for a production release.
            storeFile = rootProject.file("nix/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
}
