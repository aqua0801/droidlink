package io.github.aqua0801.droidlink.desktop.adb

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

data class AdbDevice(val serial: String, val model: String, val state: String) {
    val usable: Boolean get() = state == "device"
}

sealed interface AgentState {
    data object Off : AgentState
    data class Starting(val serial: String) : AgentState
    data class Connected(val serial: String, val model: String, val release: String, val sdk: String) : AgentState
    data class Failed(val serial: String, val message: String) : AgentState
}

/**
 * Enhanced mode. Drives the phone-side helper over ADB: pushes the bundled DEX, launches it
 * with app_process (so it runs under the shell user id, like scrcpy's server), forwards a
 * loopback port, and speaks [AgentChannel]. Exposes the device log stream and APK install.
 *
 * This is a separate subsystem from the app's P2P link: different transport (ADB), different
 * protocol, no shared runtime code. See ARCHITECTURE.md.
 */
class AgentController(private val scope: CoroutineScope) {
    private val _devices = MutableStateFlow<List<AdbDevice>>(emptyList())
    val devices: StateFlow<List<AdbDevice>> = _devices.asStateFlow()

    private val _state = MutableStateFlow<AgentState>(AgentState.Off)
    val state: StateFlow<AgentState> = _state.asStateFlow()

    private val _logcatRunning = MutableStateFlow(false)
    val logcatRunning: StateFlow<Boolean> = _logcatRunning.asStateFlow()

    private val _logcatLines = MutableStateFlow<List<String>>(emptyList())
    val logcatLines: StateFlow<List<String>> = _logcatLines.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events: SharedFlow<String> = _events.asSharedFlow()

    val adbAvailable: Boolean get() = Adb.executable != null

    private class Launched(val serial: String, val process: Process, val pcPort: Int, val channel: AgentChannel)

    @Volatile
    private var desired: String? = null

    @Volatile
    private var current: Launched? = null
    private var superviseJob: Job? = null
    private var scanJob: Job? = null

    // --- lifecycle ----------------------------------------------------------------------

    fun startScanning() {
        if (scanJob != null) return
        scanJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                _devices.value = scanDevices()
                delay(SCAN_INTERVAL_MS)
            }
        }
    }

    fun stopScanning() {
        scanJob?.cancel()
        scanJob = null
    }

    fun connect(serial: String) {
        desired = serial
        superviseJob?.cancel()
        current?.let(::teardown)
        current = null
        superviseJob = scope.launch(Dispatchers.IO) { supervise(serial) }
    }

    fun disconnect() {
        desired = null
        superviseJob?.cancel()
        superviseJob = null
        current?.let(::teardown)
        current = null
        _logcatRunning.value = false
        _state.value = AgentState.Off
    }

    fun shutdown() {
        stopScanning()
        disconnect()
    }

    // --- features (enabled only while connected) ----------------------------------------

    fun startLogcat(filter: String) {
        val channel = current?.channel ?: return
        val args = filter.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val command = (listOf("logcat.start") + args).joinToString("\t")
        _logcatRunning.value = true
        scope.launch(Dispatchers.IO) { runCatching { channel.sendText(AgentFrame.CMD, command) } }
    }

    fun stopLogcat() {
        val channel = current?.channel
        _logcatRunning.value = false
        scope.launch(Dispatchers.IO) { runCatching { channel?.sendText(AgentFrame.CMD, "logcat.stop") } }
    }

    fun clearLogcat() {
        _logcatLines.value = emptyList()
    }

    fun installApk(file: File) {
        val channel = current?.channel ?: return
        scope.launch(Dispatchers.IO) {
            try {
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        channel.send(AgentFrame.APK_DATA, buffer.copyOf(n))
                    }
                }
                channel.sendText(AgentFrame.APK_END, file.name)
            } catch (e: Exception) {
                _events.tryEmit("install error: ${e.message ?: e}")
            }
        }
    }

    // --- supervision --------------------------------------------------------------------

    private suspend fun supervise(serial: String) {
        while (scope.isActive && desired == serial) {
            _state.value = AgentState.Starting(serial)
            val launched = try {
                launch(serial)
            } catch (e: Exception) {
                _state.value = AgentState.Failed(serial, e.message ?: "launch failed")
                return
            }
            current = launched
            var gotHello = false
            try {
                while (scope.isActive) {
                    val frame = launched.channel.read() ?: break
                    if (frame.type == AgentFrame.HELLO) {
                        gotHello = true
                        applyHello(serial, String(frame.payload, Charsets.UTF_8))
                    } else {
                        onFrame(String(frame.payload, Charsets.UTF_8), frame.type)
                    }
                }
            } catch (_: IOException) {
                // link dropped
            } finally {
                teardown(launched)
                if (current === launched) current = null
                _logcatRunning.value = false
            }

            if (desired != serial) break
            // A clean drop right after launch usually means the helper couldn't start properly.
            if (!gotHello) {
                _state.value = AgentState.Failed(serial, "helper did not respond")
                return
            }
            // Brief reconnect only while the device is still present (e.g. transient USB hiccup).
            if (_devices.value.none { it.serial == serial && it.usable }) break
            delay(RECONNECT_DELAY_MS)
        }
        if (desired == serial && _state.value !is AgentState.Failed) _state.value = AgentState.Off
    }

    private fun launch(serial: String): Launched {
        if (Adb.executable == null) throw IllegalStateException("adb not found")
        val dex = AgentDex.file() ?: throw IllegalStateException("bundled helper not found")

        val push = Adb.run("-s", serial, "push", dex.absolutePath, DEVICE_PATH, timeoutMs = 30_000)
        if (!push.ok) throw IOException("push failed: ${push.output}")

        val process = Adb.start(
            "-s", serial, "shell",
            "CLASSPATH=$DEVICE_PATH", "app_process", "/", MAIN_CLASS,
        ) ?: throw IllegalStateException("adb not found")
        drain(process.errorStream, "agent-stderr")

        val stdout = process.inputStream.bufferedReader()
        val agentPort = try {
            readReady(stdout)
        } catch (e: Exception) {
            process.destroyForcibly()
            throw e
        }
        drainReader(stdout, "agent-stdout") // keep reading so the pipe never blocks

        val forward = Adb.run("-s", serial, "forward", "tcp:0", "tcp:$agentPort")
        val pcPort = forward.output.lines().firstNotNullOfOrNull { it.trim().toIntOrNull() }
        if (!forward.ok || pcPort == null) {
            process.destroyForcibly()
            throw IOException("forward failed: ${forward.output}")
        }

        val socket = try {
            Socket("127.0.0.1", pcPort).apply { tcpNoDelay = true }
        } catch (e: Exception) {
            Adb.run("-s", serial, "forward", "--remove", "tcp:$pcPort")
            process.destroyForcibly()
            throw e
        }
        return Launched(serial, process, pcPort, AgentChannel(socket))
    }

    private fun readReady(reader: BufferedReader): Int {
        val port = CompletableFuture<Int>()
        Thread {
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.startsWith(READY_PREFIX)) {
                        port.complete(line.removePrefix(READY_PREFIX).trim().toInt())
                        return@Thread
                    }
                }
                port.completeExceptionally(IOException("helper ended before it was ready"))
            } catch (e: Exception) {
                port.completeExceptionally(e)
            }
        }.apply { isDaemon = true; name = "agent-ready"; start() }

        return try {
            port.get(READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            throw IOException("helper did not start: ${e.cause?.message ?: e.message}")
        }
    }

    private fun applyHello(serial: String, payload: String) {
        val parts = payload.split('\t')
        _state.value = AgentState.Connected(
            serial = serial,
            model = parts.getOrElse(0) { "" }.ifBlank { serial },
            release = parts.getOrElse(1) { "" },
            sdk = parts.getOrElse(2) { "" },
        )
    }

    private fun onFrame(payload: String, type: Byte) {
        when (type) {
            AgentFrame.LOG -> _logcatLines.update { (it + payload).takeLast(MAX_LOG_LINES) }
            AgentFrame.EVENT -> _events.tryEmit(payload.replace('\t', ' '))
        }
    }

    private fun teardown(launched: Launched) {
        runCatching { launched.channel.sendText(AgentFrame.CMD, "shutdown") }
        runCatching { launched.channel.close() }
        runCatching { launched.process.destroy() }
        runCatching { Adb.run("-s", launched.serial, "forward", "--remove", "tcp:${launched.pcPort}", timeoutMs = 5_000) }
    }

    // --- devices ------------------------------------------------------------------------

    private fun scanDevices(): List<AdbDevice> {
        if (Adb.executable == null) return emptyList()
        val result = Adb.run("devices", "-l")
        if (!result.ok) return emptyList()
        return result.output.lines().drop(1).mapNotNull(::parseDeviceLine)
    }

    private fun parseDeviceLine(line: String): AdbDevice? {
        val tokens = line.trim().split(Regex("\\s+"))
        if (tokens.size < 2 || tokens[0].isEmpty()) return null
        val model = tokens.firstOrNull { it.startsWith("model:") }
            ?.removePrefix("model:")?.replace('_', ' ')
            ?: tokens[0]
        return AdbDevice(tokens[0], model, tokens[1])
    }

    private fun drain(stream: InputStream, name: String) =
        Thread { runCatching { stream.bufferedReader().forEachLine { } } }
            .apply { isDaemon = true; this.name = name; start() }

    private fun drainReader(reader: BufferedReader, name: String) =
        Thread { runCatching { reader.forEachLine { } } }
            .apply { isDaemon = true; this.name = name; start() }

    private companion object {
        const val DEVICE_PATH = "/data/local/tmp/droidlink-agent.jar"
        const val MAIN_CLASS = "io.github.aqua0801.droidlink.agent.Agent"
        const val READY_PREFIX = "DROIDLINK-AGENT READY "
        const val READY_TIMEOUT_MS = 12_000L
        const val SCAN_INTERVAL_MS = 3_000L
        const val RECONNECT_DELAY_MS = 1_500L
        const val MAX_LOG_LINES = 2_000
    }
}
