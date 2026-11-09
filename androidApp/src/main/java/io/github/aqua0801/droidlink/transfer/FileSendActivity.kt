package io.github.aqua0801.droidlink.transfer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import io.github.aqua0801.droidlink.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Invisible share-sheet target ("傳送檔案到電腦"): ACTION_SEND / ACTION_SEND_MULTIPLE with any
 * file type. Stays alive until the streams are opened, so the URI read grants are still valid.
 */
class FileSendActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isEmpty()) {
            finish()
            return
        }
        lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) { graph.files.send(uris) }
            Toast.makeText(applicationContext, summary.message(applicationContext), Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
