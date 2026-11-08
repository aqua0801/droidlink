import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// The agent runs on the phone through app_process, so its compiled classes are converted to
// a DEX with d8. It is a plain Kotlin/JVM module with no runtime dependency beyond the stdlib,
// and no dependency on the other modules, so the dexed artifact stays small and self-contained.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

val isWindows = System.getProperty("os.name").lowercase().contains("win")

fun resolveSdkDir(): File? {
    val local = rootProject.file("local.properties")
    if (local.exists()) {
        val props = Properties()
        local.inputStream().use { props.load(it) }
        props.getProperty("sdk.dir")?.let { return File(it) }
    }
    return (System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT"))?.let { File(it) }
}

/** d8 ships as a jar inside each build-tools version; pick the newest one present. */
fun resolveD8Jar(sdk: File): File? =
    File(sdk, "build-tools").listFiles()
        ?.sortedByDescending { it.name }
        ?.map { File(it, "lib/d8.jar") }
        ?.firstOrNull { it.exists() }

/** Picks the highest installed Android platform. */
fun resolveAndroidJar(sdk: File): File? =
    File(sdk, "platforms").listFiles()
        ?.filter { File(it, "android.jar").exists() }
        ?.maxByOrNull { Regex("""\d+""").find(it.name)?.value?.toInt() ?: -1 }
        ?.let { File(it, "android.jar") }

dependencies {
    resolveSdkDir()
        ?.let { resolveAndroidJar(it) }
        ?.let { compileOnly(files(it)) }
}

val dexAgent = tasks.register<Exec>("dexAgent") {
    group = "build"
    description = "Converts the agent classes to a DEX jar that can run on the phone via app_process."
    dependsOn(tasks.named("jar"))

    val jarTask = tasks.named<Jar>("jar")
    val runtimeClasspath = configurations.named("runtimeClasspath")
    val outputJar = layout.buildDirectory.file("agent-dex/droidlink-agent.jar")

    inputs.files(jarTask)
    inputs.files(runtimeClasspath)
    outputs.file(outputJar)

    doFirst {
        val sdk = resolveSdkDir() ?: error("Android SDK not found (set sdk.dir in local.properties or ANDROID_HOME)")
        val d8Jar = resolveD8Jar(sdk) ?: error("d8.jar not found under $sdk/build-tools/*/lib")
        val androidJar = resolveAndroidJar(sdk) ?: error("android.jar not found under $sdk/platforms/*")
        val javaExe = File(System.getProperty("java.home"), "bin/java" + if (isWindows) ".exe" else "").absolutePath
        val out = outputJar.get().asFile
        out.parentFile.mkdirs()
        val inputJars = (listOf(jarTask.get().archiveFile.get().asFile) + runtimeClasspath.get().files)
            .filter { it.extension == "jar" }

        commandLine(
            buildList {
                add(javaExe); add("-cp"); add(d8Jar.absolutePath); add("com.android.tools.r8.D8")
                add("--release")
                add("--min-api"); add("29")
                add("--lib"); add(androidJar.absolutePath)
                add("--output"); add(out.absolutePath)
                inputJars.forEach { add(it.absolutePath) }
            },
        )
    }
}