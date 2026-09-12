plugins {
    id("com.android.application")
}

val updateVersionCode = System.getenv("STARINTEL_VERSION_CODE")?.toIntOrNull() ?: 1
val updateVersionName = System.getenv("STARINTEL_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "0.1.0"
val updateKeystore = System.getenv("STARINTEL_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }

android {
    namespace = "actor.starintel.watchface"
    compileSdk = 36
    enableKotlin = false

    signingConfigs {
        if (updateKeystore != null) {
            create("update") {
                storeFile = file(updateKeystore)
                storePassword = System.getenv("STARINTEL_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("STARINTEL_KEY_ALIAS")
                keyPassword = System.getenv("STARINTEL_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "actor.starintel.watchface"
        minSdk = 33
        targetSdk = 36
        versionCode = updateVersionCode
        versionName = updateVersionName
    }

    buildTypes {
        getByName("debug") {
            if (updateKeystore != null) signingConfig = signingConfigs.getByName("update")
        }
    }
}
