plugins {
    id("com.android.library") version "9.3.3" apply false
}

val coreVersion = providers.gradleProperty("version").orNull ?: "0.1.0-SNAPSHOT"

allprojects {
    group = "com.heycyan.core"
    version = coreVersion
}
