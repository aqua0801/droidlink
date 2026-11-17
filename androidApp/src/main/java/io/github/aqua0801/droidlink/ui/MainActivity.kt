package io.github.aqua0801.droidlink.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.aqua0801.droidlink.R
import io.github.aqua0801.droidlink.graph
import io.github.aqua0801.droidlink.i18n.AppLocales
import io.github.aqua0801.droidlink.shared.pairing.PairingInfo
import io.github.aqua0801.droidlink.shared.transfer.Transfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Screen { MAIN, SETTINGS, SYNC_BROWSER }

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocales.wrap(newBase))
    }

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let(::onScanned)
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
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
            val themeMode by graph.settings.theme.collectAsStateWithLifecycle()
            val dark = themeMode.isDark(isSystemInDarkTheme())
            // System bar icons follow the app theme, not only the OS setting.
            LaunchedEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }
            DroidLinkTheme(dark) {
                when (screen) {
                    Screen.SETTINGS -> SettingsScreen(graph.settings, onBack = { screen = Screen.MAIN })
                    Screen.SYNC_BROWSER -> FolderBrowserScreen(graph.syncFolder, onBack = { screen = Screen.MAIN })
                    Screen.MAIN -> MainScreen(
                        graph = graph,
                        onScan = ::launchScanner,
                        onSendClipboard = ::sendClipboardNow,
                        onPickFiles = { filePicker.launch(arrayOf("*/*")) },
                        onOpenFile = ::openReceived,
                        onOpenSettings = { screen = Screen.SETTINGS },
                        onBrowseSync = { screen = Screen.SYNC_BROWSER },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back to the app is an explicit "try again" after the retries ran out.
        graph.link.retryIfGaveUp()
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