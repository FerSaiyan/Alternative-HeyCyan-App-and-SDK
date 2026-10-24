plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

val enableAppleTargets = providers.gradleProperty("enableAppleTargets").orNull == "true"

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    // A second host target keeps common code genuinely multiplatform-testable while
    // Apple framework linking and vendor integration remain behind the iOS gate.
    jvm("portability") {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    // Apple targets are opt-in so the Android/Linux build remains usable while
    // the framework is compiled and linked from Xcode on macOS.
    if (enableAppleTargets) {
        iosX64 {
            binaries.framework {
                baseName = "CyanBridgeShared"
                isStatic = false
            }
        }
        iosArm64 {
            binaries.framework {
                baseName = "CyanBridgeShared"
                // embedAndSignAppleFrameworkForXcode copies the framework into the app's
                // Frameworks/ folder; a static archive there makes device installs fail.
                isStatic = false
            }
        }
        iosSimulatorArm64 {
            binaries.framework {
                baseName = "CyanBridgeShared"
                isStatic = false
            }
        }

    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.materialIconsExtended)
            implementation(compose.components.resources)
        }
@OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
commonTest.dependencies {
    implementation(kotlin("test"))
    implementation(libs.kotlinx.coroutines.test)
    implementation(compose.uiTest)
}
    }
}

compose.resources {
    packageOfResClass = "com.fersaiyan.cyanbridge.shared.generated.resources"
    publicResClass = true
}

android {
    namespace = "com.fersaiyan.cyanbridge.shared"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Shared code logs through android.util.Log on Android; let host unit tests use the stubs.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}
