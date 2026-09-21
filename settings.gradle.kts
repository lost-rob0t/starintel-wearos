pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "starintel-wearos"
include(":starintel-android")
include(":phone-app")
include(":quasar-app")
include(":wear-app")
include(":watchface")

include(":collector-app")

include(":hackmode-app")
