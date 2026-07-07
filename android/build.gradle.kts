plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
}

// The repo lives on a network share, where Gradle's heavy packaging I/O is flaky.
// Set videotyper.localBuildDir in gradle.properties to route build output to local disk.
providers.gradleProperty("videotyper.localBuildDir").orNull?.let { localBuildDir ->
    allprojects {
        layout.buildDirectory.set(file("$localBuildDir/$name"))
    }
}
