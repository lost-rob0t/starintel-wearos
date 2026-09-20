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
include(":android-contracts")
include(":phone-app")
include(":quasar-app")
include(":operator-app")
include(":collector-app")
include(":maps-app")
include(":wear-app")
include(":watchface")
