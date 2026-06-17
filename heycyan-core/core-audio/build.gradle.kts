import org.gradle.api.publish.maven.MavenPublication

val corePublishUrl = providers.gradleProperty("corePublishUrl").orNull
    ?: System.getenv("HEYCYAN_CORE_MAVEN_URL")
val corePublishUser = providers.gradleProperty("corePublishUser").orNull
    ?: System.getenv("HEYCYAN_CORE_MAVEN_USER")
val corePublishPassword = providers.gradleProperty("corePublishPassword").orNull
    ?: System.getenv("HEYCYAN_CORE_MAVEN_PASSWORD")

plugins {
    id("com.android.library")
    id("maven-publish")
}

android {
    namespace = "com.heycyan.core.audio"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.heycyan.core"
                artifactId = "core-audio"
                version = project.version.toString()
            }
        }

        repositories {
            if (!corePublishUrl.isNullOrBlank()) {
                maven {
                    name = "CoreMaven"
                    url = uri(corePublishUrl)
                    credentials {
                        username = corePublishUser
                        password = corePublishPassword
                    }
                }
            }
        }
    }
}
