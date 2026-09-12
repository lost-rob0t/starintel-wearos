plugins {
    id("com.android.application")
}

val updateVersionCode = System.getenv("STARINTEL_VERSION_CODE")?.toIntOrNull() ?: 1
val updateVersionName = System.getenv("STARINTEL_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "0.1.0"
val updateKeystore = System.getenv("STARINTEL_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }

android {
    namespace = "actor.starintel.wear"
    compileSdk = 36

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
        applicationId = "actor.starintel.wear"
        minSdk = 30
        targetSdk = 36
        versionCode = updateVersionCode
        versionName = updateVersionName
    }

    buildTypes {
        getByName("debug") {
            if (updateKeystore != null) signingConfig = signingConfigs.getByName("update")
        }
    }

    sourceSets.getByName("main").java.srcDir("../shared/src/main/java")

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
    implementation("androidx.wear.tiles:tiles:1.6.2")
    implementation("androidx.wear.protolayout:protolayout:1.4.2")
    implementation("androidx.wear.protolayout:protolayout-material3:1.4.2")
    implementation("androidx.wear.protolayout:protolayout-expression:1.4.2")
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
