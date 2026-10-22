pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://jitpack.io") }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        // JetBrains Compose Multiplatform (including Skiko native binaries for iOS).
        // Restrict this repository so unrelated dependencies do not query it.
        maven {
            url = uri("https://maven.pkg.jetbrains.space/public/p/compose/dev")
            content {
                includeGroupByRegex("org\\.jetbrains\\.compose.*")
                includeGroupByRegex("org\\.jetbrains\\.skiko.*")
            }
        }

    }
}
rootProject.name = "CyanBridgeManagerApp"
include(":app")
include(":shared")

// HeyCyan Core - bundled as composite build for easy compilation
val heycyanCoreDir = file("../../heycyan-core")
if (heycyanCoreDir.exists()) {
    includeBuild(heycyanCoreDir)
}
