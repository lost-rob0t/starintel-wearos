plugins {
    id("com.android.application")
}

android {
    namespace = "actor.starintel.quasar"
    compileSdk = 36

    defaultConfig {
        applicationId = "actor.starintel.quasar"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.5.0-alpha"
    }

    signingConfigs {
        getByName("debug") {
            // Stable repository-owned debug identity keeps catalog updates installable.
            // Production releases must replace this public development signer.
            storeFile = rootProject.file("nix/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    sourceSets.getByName("androidTest").java.srcDir("../tests/android/src")

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
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("junit:junit:4.13.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
