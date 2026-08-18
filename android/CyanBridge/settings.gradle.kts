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

        // Meta Wearables DAT SDK (requires GitHub token with read:packages scope)
        val localProps = java.util.Properties()
        val localPropsFile = rootDir.resolve("local.properties")
        if (localPropsFile.exists()) {
            localProps.load(localPropsFile.inputStream())
        }
        val githubToken = System.getenv("META_GITHUB_TOKEN")
            ?: System.getenv("GITHUB_TOKEN")
            ?: localProps.getProperty("github_token")
        if (!githubToken.isNullOrBlank()) {
            maven {
                url = uri("https://maven.pkg.github.com/facebook/meta-wearables-dat-android")
                credentials {
                    username = ""
                    password = githubToken
                }
            }
        }
    }
}
rootProject.name = "CyanBridgeManagerApp"
include(":app")
include(":shared")

// Moonshine Voice (local wrapper module that builds vendored native sources)
include(":moonshine-voice")

// HeyCyan Core - bundled as composite build for easy compilation
val heycyanCoreDir = file("../../heycyan-core")
if (heycyanCoreDir.exists()) {
    includeBuild(heycyanCoreDir)
}
