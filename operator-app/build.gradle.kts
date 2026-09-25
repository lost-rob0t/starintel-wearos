plugins {
    id("com.android.application")
}

android {
    namespace = "actor.starintel.operator"
    compileSdk = 36

    defaultConfig {
        applicationId = "actor.starintel.operator"
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
    implementation(project(":starintel-design"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
