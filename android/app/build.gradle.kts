import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Untracked secrets (TMDB key + release-signing credentials), read at build time from
// android/keys.properties. None of this is in version control.
val keysProps = Properties().apply {
    val f = rootProject.file("keys.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Bundled TMDB API key, from the TMDB_API_KEY env var or keys.properties. Released APKs carry it so
// posters work with no per-user signup; source builds without it fall back to the keyless
// iTunes/TVmaze sources plus the in-app key override.
val tmdbApiKey: String =
    (System.getenv("TMDB_API_KEY") ?: keysProps.getProperty("TMDB_API_KEY") ?: "").trim()

// Release signing is enabled only when the keystore is configured (so source builds still work,
// producing an unsigned release APK).
val releaseKeystore: String? = keysProps.getProperty("RELEASE_STORE_FILE")?.takeIf { it.isNotBlank() }

if (tmdbApiKey.isBlank()) {
    logger.warn(
        "\n" +
            "############################################################\n" +
            "##  WARNING: TMDB_API_KEY is not set for this build.       ##\n" +
            "##  Poster lookups will use the keyless iTunes/TVmaze      ##\n" +
            "##  sources only (smaller catalog). Release builds should  ##\n" +
            "##  set it in android/keys.properties (TMDB_API_KEY=...)   ##\n" +
            "##  or the TMDB_API_KEY environment variable.              ##\n" +
            "############################################################\n"
    )
}

android {
    namespace = "com.videotyper"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.videotyper"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = rootProject.file(releaseKeystore)
                storePassword = keysProps.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = keysProps.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = keysProps.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            // jcifs-ng ships duplicate license/notice files that trip the merger.
            excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val media3 = "1.10.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    // FFmpeg audio decoders (E-AC-3, DTS, ...) for media3 — prebuilt, matches media3 1.10.1.
    implementation("io.github.anilbeesetti:nextlib-media3ext:1.10.1-0.13.0")
    // SMB/CIFS client for smb:// network shares.
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10")

    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}
