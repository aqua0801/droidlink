import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Plain Kotlin/JVM library: both the Android app and the desktop app run on the JVM,
// so the protocol, crypto and session logic live here once and are shared verbatim.
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
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
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.bouncycastle.pkix)

    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
}
