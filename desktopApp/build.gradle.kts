import org.gradle.language.jvm.tasks.ProcessResources
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

/** bytedeco classifier for the build machine: windows-x86_64, linux-x86_64, linux-arm64, ... */
val nativeClassifier: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = when (System.getProperty("os.arch").lowercase()) {
        "amd64", "x86_64" -> "x86_64"
        "aarch64", "arm64" -> "arm64"
        else -> error("unsupported architecture: ${System.getProperty("os.arch")}")
    }
    when {
        os.startsWith("windows") -> "windows-$arch"
        os.startsWith("linux") -> "linux-$arch"
        os.startsWith("mac") -> "macosx-$arch"
        else -> error("unsupported OS: $os")
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.jetbrains.compose.material3)
    implementation(libs.jetbrains.compose.resources)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.zxing.core)
    implementation(libs.jmdns)
    implementation(libs.jna)
    implementation(libs.jna.platform)

    // Screen mirroring: FFmpeg (LGPL build) via bytedeco, with the natives of the OS that builds
    // the package (jpackage only packages for the OS it runs on, like compose.desktop.currentOs).
    implementation(libs.bytedeco.ffmpeg)
    implementation(libs.bytedeco.javacpp)
    implementation(variantOf(libs.bytedeco.ffmpeg) { classifier(nativeClassifier) })
    implementation(variantOf(libs.bytedeco.javacpp) { classifier(nativeClassifier) })

    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
    // Opt-in: `-PrealHotkeys=true` runs the test that registers a real global hotkey and
    // presses it with java.awt.Robot (it sends keystrokes to this desktop session).
    systemProperty("droidlink.realHotkeys", providers.gradleProperty("realHotkeys").getOrElse("false"))
    systemProperty("droidlink.realMirror", providers.gradleProperty("realMirror").getOrElse("false"))
}

// Bundle the phone-side helper (DEX jar from the :agent module) as a resource. At runtime it is
// extracted and pushed to the device over ADB. See ARCHITECTURE.md for how the two modes relate.
tasks.named<ProcessResources>("processResources") {
    dependsOn(":agent:dexAgent")
    from(project(":agent").layout.buildDirectory.dir("agent-dex")) {
        include("droidlink-agent.jar")
    }
}

// UI strings: src/main/composeResources/values*/strings.xml (`values` is English, the default).
compose.resources {
    packageOfResClass = "io.github.aqua0801.droidlink.desktop.resources"
    publicResClass = false
    generateResClass = always
}

// Packaging needs a full JDK with jpackage (Android Studio's JBR doesn't ship it). Gradle
// provisions Temurin 21 through the foojay resolver for packaging only; `run` is unaffected.
val packagingJdk = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
    vendor.set(JvmVendorSpec.ADOPTIUM)
}

compose.desktop {
    application {
        mainClass = "io.github.aqua0801.droidlink.desktop.MainKt"
        javaHome = packagingJdk.get().metadata.installationPath.asFile.absolutePath

        nativeDistributions {
            // Windows builds produce Msi/Exe, Linux builds Deb/Rpm (jpackage targets the host OS).
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "DroidLink"
            packageVersion = providers.gradleProperty("droidlink.version").get()
            description = "Phone-to-PC developer companion"
            vendor = "aqua0801"

            // jlink keeps only these JDK modules in the bundled runtime; a missing one only fails
            // at runtime in the packaged app. Start from `gradlew :desktopApp:suggestRuntimeModules`,
            // plus jdk.crypto.ec: the EC keys used for TLS are loaded by name, which that scan misses.
            modules("java.instrument", "java.naming", "java.sql", "jdk.unsupported", "jdk.crypto.ec")

            windows {
                menuGroup = "DroidLink"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                // Fixed so a newer installer upgrades the existing install instead of adding a second one.
                upgradeUuid = "6b3f2d0e-8c1a-4e5b-9f7d-2a4c6e8b1d30"
            }

            linux {
                // Debian and RPM package names must be lowercase.
                packageName = "droidlink"
                iconFile.set(project.file("packaging/droidlink.png"))
                menuGroup = "Development"
                appCategory = "Development"
                shortcut = true
                debMaintainer = "aqua0801@users.noreply.github.com"
            }
        }
    }
}
