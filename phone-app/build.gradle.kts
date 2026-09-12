plugins {
    id("com.android.application")
}

android {
    namespace = "actor.starintel.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "actor.starintel.wear"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1-alpha"
    }

    signingConfigs {
        getByName("debug") {
            // Must be identical to the Wear app certificate or Play Services will not
            // deliver Data Layer messages between phone and watch. Public debug-only key.
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
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    testImplementation("junit:junit:4.13.2")
}
