import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing: keystore.properties (gitignored) points at a keystore kept outside the repo.
// Without it, release builds are unsigned and debug builds keep using the debug key.
val keystoreProps = rootProject.file("keystore.properties").takeIf { it.isFile }
    ?.let { file -> Properties().apply { file.inputStream().use(::load) } }

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
        // Single source of truth in gradle.properties, shared with the desktop app.
        versionCode = providers.gradleProperty("droidlink.versionCode").get().toInt()
        versionName = providers.gradleProperty("droidlink.version").get()
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
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
    bundle {
        // The language is switched inside the app, so every language must be in the install.
        language { enableSplit = false }
    }
    androidResources {
        // Lists the app's languages for the system per-app language setting (Android 13+).
        // The default locale is declared in src/main/res/resources.properties.
        generateLocaleConfig = true
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
