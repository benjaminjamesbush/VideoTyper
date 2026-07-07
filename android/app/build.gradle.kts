import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
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
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
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
