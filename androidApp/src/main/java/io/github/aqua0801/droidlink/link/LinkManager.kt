package io.github.aqua0801.droidlink.link

import android.content.Context
import io.github.aqua0801.droidlink.AppGraph
import io.github.aqua0801.droidlink.BuildConfig
import io.github.aqua0801.droidlink.data.PairedPc
import io.github.aqua0801.droidlink.service.LinkService
import io.github.aqua0801.droidlink.shared.crypto.Fingerprint
import io.github.aqua0801.droidlink.shared.link.LinkClient
import io.github.aqua0801.droidlink.shared.link.LinkRejectedException
import io.github.aqua0801.droidlink.shared.link.LinkSession
import io.github.aqua0801.droidlink.shared.link.ServerIdentityMismatchException
import io.github.aqua0801.droidlink.shared.pairing.PairingInfo
import io.github.aqua0801.droidlink.shared.protocol.Message
import io.github.aqua0801.droidlink.shared.protocol.RejectReason
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

sealed interface LinkState {
    data object Idle : LinkState
    data class Pairing(val serverName: String) : LinkState
    data class Connecting(val fingerprint: String, val pcName: String, val attempt: Int, val lastError: String?) : LinkState
    data class Connected(val fingerprint: String, val pcName: String, val host: String) : LinkState
}

val LinkState.fingerprint: String?
    get() = when (this) {
        is LinkState.Connected -> fingerprint
        is LinkState.Connecting -> fingerprint
        else -> null
    }

/**
 * Keeps the phone connected to one chosen PC: tries every known address, reconnects with
 * backoff, and jumps the queue when mDNS sees the PC appear on the LAN.
 */
class LinkManager(private val context: Context, private val graph: AppGraph) {
    private val scope = graph.scope

    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _session = MutableStateFlow<LinkSession?>(null)
    /** The live session, for features that push data to the PC. */
    val session: StateFlow<LinkSession?> = _session.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** One-off user-facing messages (snackbars). */
    val events: SharedFlow<String> = _events.asSharedFlow()

    /** Bumped whenever the target changes, so stale loops can't overwrite the state. */
    private val generation = AtomicInteger()
    private var loopJob: Job? = null

    private val client by lazy {
        LinkClient(graph.identity, graph.deviceName, platform = "android", appVersion = BuildConfig.VERSION_NAME)
    }

    fun notify(message: String) {
        _events.tryEmit(message)
    }

    fun connect(fingerprint: String) {
        graph.pairedPcs.autoConnectFingerprint = fingerprint
        LinkService.start(context)
        val gen = newGeneration()
        synchronized(this) {
            loopJob = scope.launch { connectLoop(gen, fingerprint) }
        }
    }

    /** Called when the service is (re)started without an explicit request. */
    fun resumeIfNeeded() {
        if (loopJob?.isActive == true || _state.value is LinkState.Pairing) return
        val fingerprint = graph.pairedPcs.autoConnectFingerprint ?: return
        if (graph.pairedPcs.get(fingerprint) != null) connect(fingerprint)
    }

    fun disconnect() {
        graph.pairedPcs.autoConnectFingerprint = null
        val session = _session.value
        newGeneration()
        _state.value = LinkState.Idle
        scope.launch { session?.closeGracefully("user disconnected") }
        LinkService.stop(context)
    }

    fun forget(fingerprint: String) {
        if (_state.value.fingerprint == fingerprint) disconnect()
        graph.pairedPcs.remove(fingerprint)
    }

    /** Pairs using a scanned QR code, then stays connected. Returns true on success. */
    suspend fun pair(info: PairingInfo): Boolean {
        val gen = newGeneration()
        _state.value = LinkState.Pairing(info.serverName)
        LinkService.start(context)

        var lastError: String? = null
        for (host in info.hosts) {
            if (generation.get() != gen) return false
            try {
                val session = client.connect(host, info.port, info.fingerprint, scope, pairToken = info.token, connectTimeoutMs = 3_000)
                val fingerprint = info.fingerprint.hex
                graph.pairedPcs.upsert(PairedPc(fingerprint, session.peerName, info.hosts, info.port, lastHost = host))
                graph.pairedPcs.autoConnectFingerprint = fingerprint
                notify("已與 ${session.peerName} 配對")
                synchronized(this) {
                    if (generation.get() != gen) {
                        session.close()
                        return false
                    }
                    loopJob = scope.launch {
                        runSession(gen, fingerprint, host, session)
                        connectLoop(gen, fingerprint)
                    }
                }
                return true
            } catch (e: LinkRejectedException) {
                lastError = describe(e.reason)
                break // the token is the problem; other hosts won't help
            } catch (e: ServerIdentityMismatchException) {
                lastError = "$host 不是這台電腦"
            } catch (e: IOException) {
                lastError = "$host：${e.message ?: e::class.simpleName}"
            }
        }
        if (generation.get() == gen) {
            _state.value = LinkState.Idle
            LinkService.stop(context)
        }
        notify("配對失敗：${lastError ?: "無法連線"}")
        return false
    }

    private fun newGeneration(): Int = synchronized(this) {
        loopJob?.cancel()
        loopJob = null
        generation.incrementAndGet()
    }

    private fun setState(gen: Int, state: LinkState) {
        if (generation.get() == gen) _state.value = state
    }

    private suspend fun connectLoop(gen: Int, fingerprint: String) {
        var attempt = 0
        var backoffMs = INITIAL_BACKOFF_MS
        var lastError: String? = null

        while (currentCoroutineContext().isActive && generation.get() == gen) {
            val pc = graph.pairedPcs.get(fingerprint) ?: run {
                setState(gen, LinkState.Idle)
                return
            }
            attempt++
            setState(gen, LinkState.Connecting(fingerprint, pc.name, attempt, lastError))

            when (val result = tryConnect(pc)) {
                is Attempt.Success -> {
                    attempt = 0
                    backoffMs = INITIAL_BACKOFF_MS
                    runSession(gen, fingerprint, result.host, result.session)
                    lastError = "連線中斷"
                    delay(500)
                }
                is Attempt.Fatal -> {
                    if (generation.get() == gen) {
                        graph.pairedPcs.autoConnectFingerprint = null
                        _state.value = LinkState.Idle
                        notify(result.message)
                    }
                    return
                }
                is Attempt.Failed -> {
                    lastError = result.message
                    // Wait out the backoff, but retry at once if mDNS reports a new address.
                    withTimeoutOrNull(backoffMs) {
                        graph.nsd.found.first { found ->
                            found[fingerprint]?.let { it.host !in result.triedHosts } == true
                        }
                    }
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                }
            }
        }
    }

    private sealed interface Attempt {
        class Success(val host: String, val session: LinkSession) : Attempt
        class Failed(val message: String, val triedHosts: Set<String>) : Attempt
        class Fatal(val message: String) : Attempt
    }

    private suspend fun tryConnect(pc: PairedPc): Attempt {
        val expected = Fingerprint(pc.fingerprint)
        val candidates = buildList {
            graph.nsd.found.value[pc.fingerprint]?.let { add(it.host to it.port) }
            pc.lastHost?.let { add(it to pc.port) }
            pc.manualHost?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it to pc.port) }
            pc.hosts.forEach { add(it to pc.port) }
        }.distinct()

        var lastError: String? = null
        for ((host, port) in candidates) {
            try {
                return Attempt.Success(host, client.connect(host, port, expected, scope, connectTimeoutMs = 3_000))
            } catch (e: LinkRejectedException) {
                return Attempt.Fatal(describe(e.reason))
            } catch (e: ServerIdentityMismatchException) {
                lastError = "$host 不是這台電腦"
            } catch (e: IOException) {
                lastError = "$host：${e.message ?: e::class.simpleName}"
            }
        }
        return Attempt.Failed(lastError ?: "沒有可用的位址", candidates.map { it.first }.toSet())
    }

    private suspend fun runSession(gen: Int, fingerprint: String, host: String, session: LinkSession) {
        try {
            graph.pairedPcs.update(fingerprint) { it.copy(lastHost = host, name = session.peerName) }
            _session.value = session
            setState(gen, LinkState.Connected(fingerprint, session.peerName, host))
            session.start(::onMessage)
            session.awaitClosed()
        } finally {
            session.close()
            _session.compareAndSet(session, null)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private suspend fun onMessage(message: Message) {
        // Nothing flows PC → phone yet; clipboard, APK push, etc. will be dispatched here.
    }

    private fun describe(reason: RejectReason): String = when (reason) {
        RejectReason.NOT_PAIRED -> "電腦已不再信任這支手機，請重新配對"
        RejectReason.BAD_TOKEN -> "配對碼無效或已過期，請在電腦上重新產生 QR 碼"
        RejectReason.PROTOCOL_MISMATCH -> "手機與電腦的 DroidLink 版本不相容"
    }

    private companion object {
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 15_000L
    }
}
