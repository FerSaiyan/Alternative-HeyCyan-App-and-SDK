plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

val testAbi = providers.gradleProperty("testAbi").orNull?.trim()?.takeIf { it.isNotEmpty() }
val fastBuildRoot = providers.gradleProperty("cyanbridgeBuildRoot").orNull

android {
    namespace = "com.fersaiyan.cyanbridge.llama"
    compileSdk = 36
    ndkVersion = "27.2.12479018"
    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += testAbi?.let(::listOf) ?: listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON", "-DCMAKE_BUILD_TYPE=Release")
                // Optional checked-out source override for offline development builds.
                providers.gradleProperty("llamaSourceDir").orNull?.let {
                    arguments += "-DCYAN_LLAMA_SOURCE_DIR=$it"
                }
                targets += "cyan_llama"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
            // Keep native compilation state outside buildDirectory so Gradle
            // clean does not force a complete upstream llama rebuild.
            fastBuildRoot?.let { buildStagingDirectory = file("$it/native/llama-runtime") }
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
