package io.github.aqua0801.droidlink.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.aqua0801.droidlink.R
import io.github.aqua0801.droidlink.graph
import androidx.lifecycle.lifecycleScope
import io.github.aqua0801.droidlink.shared.pairing.PairingInfo
import io.github.aqua0801.droidlink.shared.transfer.Transfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let(::onScanned)
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        // Open the streams while this activity (and so the URI grant) is alive.
        lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) { graph.files.send(uris) }
            graph.link.notify(summary.message(this@MainActivity))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            DroidLinkTheme {
                MainScreen(
                    graph = graph,
                    onScan = ::launchScanner,
                    onSendClipboard = ::sendClipboardNow,
                    onPickFiles = { filePicker.launch(arrayOf("*/*")) },
                    onOpenFile = ::openReceived,
                )
            }
        }
    }

    private fun sendClipboardNow() {
        val text = graph.clipboard.readClipboard()
        graph.scope.launch {
            graph.link.notify(getString(graph.clipboard.send(text).message))
        }
    }

    private fun openReceived(transfer: Transfer) {
        val intent = graph.files.viewIntent(transfer) ?: return
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            graph.link.notify(getString(R.string.transfer_no_viewer))
        }
    }

    private fun launchScanner() {
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(getString(R.string.scan_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(false),
        )
    }

    private fun onScanned(text: String) {
        val info = PairingInfo.parse(text)
        if (info == null) {
            graph.link.notify(getString(R.string.event_not_pairing_qr))
            return
        }
        // App scope, not the activity's: pairing must survive a rotation or the user leaving.
        graph.scope.launch { graph.link.pair(info) }
    }
}
