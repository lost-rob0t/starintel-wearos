plugins {
    id("com.android.application")
}

android {
    namespace = "actor.starintel.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "actor.starintel.wear"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.5.0-alpha"
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

    sourceSets.getByName("main").kotlin.srcDir("../shared/src/main/java")

    sourceSets.getByName("androidTest").kotlin.srcDir("../tests/android/src")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":starintel-design"))
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("junit:junit:4.13.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
