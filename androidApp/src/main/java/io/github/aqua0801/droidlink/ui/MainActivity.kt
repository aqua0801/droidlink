package io.github.aqua0801.droidlink.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.aqua0801.droidlink.graph
import io.github.aqua0801.droidlink.shared.pairing.PairingInfo
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let(::onScanned)
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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
                MainScreen(graph = graph, onScan = ::launchScanner)
            }
        }
    }

    private fun launchScanner() {
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("掃描電腦端 DroidLink 顯示的 QR 碼")
                .setBeepEnabled(false)
                .setOrientationLocked(false),
        )
    }

    private fun onScanned(text: String) {
        val info = PairingInfo.parse(text)
        if (info == null) {
            graph.link.notify("這不是 DroidLink 的配對 QR 碼")
            return
        }
        // App scope, not the activity's: pairing must survive a rotation or the user leaving.
        graph.scope.launch { graph.link.pair(info) }
    }
}
