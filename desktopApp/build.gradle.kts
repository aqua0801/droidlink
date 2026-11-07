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

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.jetbrains.compose.material3)
    implementation(libs.jetbrains.compose.resources)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.zxing.core)
    implementation(libs.jmdns)
    implementation(libs.jna)

    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
}

// UI strings: src/main/composeResources/values*/strings.xml (`values` is zh-TW for now).
compose.resources {
    packageOfResClass = "io.github.aqua0801.droidlink.desktop.resources"
    publicResClass = false
    generateResClass = always
}

compose.desktop {
    application {
        mainClass = "io.github.aqua0801.droidlink.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb)
            packageName = "DroidLink"
            packageVersion = "0.1.0"
        }
    }
}
