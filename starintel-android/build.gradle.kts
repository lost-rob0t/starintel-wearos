plugins {
    id("com.android.library")
}

android {
    namespace = "actor.starintel.android"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val edgeRuntimeRoot = providers.gradleProperty("starintel.edge.runtimeRoot").orNull
if (!edgeRuntimeRoot.isNullOrBlank()) {
    androidComponents.onVariants { variant ->
        variant.sources.kotlin?.addStaticSourceDirectory("$edgeRuntimeRoot/kotlin")
        variant.sources.jniLibs?.addStaticSourceDirectory("$edgeRuntimeRoot/jni")
        variant.sources.assets?.addStaticSourceDirectory("$edgeRuntimeRoot/assets")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("junit:junit:4.13.2")
}
