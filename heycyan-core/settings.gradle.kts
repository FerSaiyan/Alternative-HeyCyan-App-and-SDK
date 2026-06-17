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

rootProject.name = "heycyan-android-core"
include(":core-connectivity")
include(":core-ble")
include(":core-audio")
include(":core-transcription-api")
include(":core-summarization-api")
include(":core-data")
include(":core-utils")
