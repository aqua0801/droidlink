package io.github.aqua0801.droidlink

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import io.github.aqua0801.droidlink.clipboard.ClipboardBridge
import io.github.aqua0801.droidlink.data.DeviceInfo
import io.github.aqua0801.droidlink.data.PairedPcStore
import io.github.aqua0801.droidlink.data.PhoneSettings
import io.github.aqua0801.droidlink.mirror.PhoneMirror
import io.github.aqua0801.droidlink.screenshot.ScreenshotWatcher
import io.github.aqua0801.droidlink.sync.PhoneSyncFolder
import io.github.aqua0801.droidlink.link.LinkManager
import io.github.aqua0801.droidlink.link.NsdDiscovery
import io.github.aqua0801.droidlink.transfer.PhoneFileTransfers
import io.github.aqua0801.droidlink.shared.crypto.Identity
import io.github.aqua0801.droidlink.shared.crypto.IdentityFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class DroidLinkApp : Application(), SingletonImageLoader.Factory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }

    /** Coil loader shared by thumbnails and the image viewer; also decodes video frames and animated GIF/WebP. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(AnimatedImageDecoder.Factory())
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(true)
            .build()
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
    val syncFolder = PhoneSyncFolder(app, settings)
    val files = PhoneFileTransfers(app, this)
    val screenshots = ScreenshotWatcher(app, this)
    val mirror = PhoneMirror(app, this)
}

val Context.graph: AppGraph get() = (applicationContext as DroidLinkApp).graph
