plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Compile/package native code only for the explicit shared-Gemma prototype.
val enabled = providers.gradleProperty("sharedGemmaPrototype").orNull == "true"
android {
    namespace = "com.fersaiyan.cyanbridge.llama"
    compileSdk = 36
    ndkVersion = "27.2.12479018"
    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        if (enabled) {
            ndk { abiFilters += providers.gradleProperty("testAbi").orNull?.let(::listOf) ?: listOf("arm64-v8a", "x86_64") }
            externalNativeBuild {
                cmake {
                    arguments += listOf("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON", "-DCMAKE_BUILD_TYPE=Release")
                    providers.gradleProperty("llamaSourceDir").orNull?.let { arguments += "-DCYAN_LLAMA_SOURCE_DIR=$it" }
                    targets += "cyan_shared_gemma"
                }
            }
        }
    }
    if (enabled) {
        externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
