plugins {
    id("com.android.application")
}

android {
    namespace = "actor.starintel.hackmode"
    compileSdk = 36

    defaultConfig {
        applicationId = "actor.starintel.hackmode"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.3.0-alpha"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("nix/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":starintel-android"))
}
