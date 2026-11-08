package io.github.aqua0801.droidlink.desktop.adb

import java.io.File

/** Extracts the bundled phone-side helper (DEX jar) to a temp file so ADB can push it. */
object AgentDex {
    private const val RESOURCE = "/droidlink-agent.jar"

    @Volatile
    private var cached: File? = null

    /** Returns the extracted jar, or null if the resource isn't on the classpath. */
    fun file(): File? {
        cached?.takeIf { it.isFile }?.let { return it }
        val stream = AgentDex::class.java.getResourceAsStream(RESOURCE) ?: return null
        val out = File.createTempFile("droidlink-agent", ".jar").apply { deleteOnExit() }
        stream.use { input -> out.outputStream().use { input.copyTo(it) } }
        cached = out
        return out
    }
}
