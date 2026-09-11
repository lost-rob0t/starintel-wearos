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
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    testImplementation("junit:junit:4.13.2")
}
