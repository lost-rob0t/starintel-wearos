plugins {
    id("com.android.library")
}

android {
    namespace = "actor.starintel.android"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 26
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DSTARINTEL_ECL_ADAPTER_ROOT=${providers.gradleProperty("starintel.ecl.adapterRoot").orNull.orEmpty()}",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
