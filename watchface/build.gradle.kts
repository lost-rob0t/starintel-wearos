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
        versionCode = 2
        versionName = "0.1.1-alpha"
    }
}
