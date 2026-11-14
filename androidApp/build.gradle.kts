plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.aqua0801.droidlink"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "io.github.aqua0801.droidlink"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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
        buildConfig = true
    }
    packaging {
        resources {
            // BouncyCastle's multi-release jars all ship this file.
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.android.embedded)

    // Sync-folder viewer: thumbnails, zoomable images, video/audio playback.
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.coil.gif)
    implementation(libs.telephoto.zoomable)
    implementation(libs.telephoto.zoomable.image.coil3)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
}
