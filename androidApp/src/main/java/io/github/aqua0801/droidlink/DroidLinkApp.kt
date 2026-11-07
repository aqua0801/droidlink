package io.github.aqua0801.droidlink

import android.app.Application
import android.content.Context
import io.github.aqua0801.droidlink.clipboard.ClipboardBridge
import io.github.aqua0801.droidlink.data.DeviceInfo
import io.github.aqua0801.droidlink.data.PairedPcStore
import io.github.aqua0801.droidlink.data.PhoneSettings
import io.github.aqua0801.droidlink.link.LinkManager
import io.github.aqua0801.droidlink.link.NsdDiscovery
import io.github.aqua0801.droidlink.shared.crypto.Identity
import io.github.aqua0801.droidlink.shared.crypto.IdentityFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class DroidLinkApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

/** Process-wide singletons. Small enough that a DI framework isn't worth it yet. */
class AppGraph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val deviceName: String = DeviceInfo.name(app)

    /** Generated on first access (a few hundred ms); touch it off the main thread. */
    val identity: Identity by lazy {
        IdentityFile.loadOrCreate(File(app.filesDir, "identity.json"), "DroidLink Android $deviceName")
    }

    val settings = PhoneSettings(app)
    val pairedPcs = PairedPcStore(app)
    val nsd = NsdDiscovery(app)
    val link = LinkManager(app, this)
    val clipboard = ClipboardBridge(app, this)
}

val Context.graph: AppGraph get() = (applicationContext as DroidLinkApp).graph
