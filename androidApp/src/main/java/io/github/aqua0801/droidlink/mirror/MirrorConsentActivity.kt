package io.github.aqua0801.droidlink.mirror

import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import io.github.aqua0801.droidlink.graph

/**
 * Invisible activity that shows the system "start recording or casting?" dialog for one PC
 * request and hands the result to MirrorCaptureService.
 */
class MirrorConsentActivity : ComponentActivity() {
    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val requestId = intent.getLongExtra(EXTRA_REQUEST_ID, 0)
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            MirrorCaptureService.start(
                context = this,
                resultCode = result.resultCode,
                data = data,
                requestId = requestId,
                token = intent.getStringExtra(EXTRA_TOKEN).orEmpty(),
                maxSize = intent.getIntExtra(EXTRA_MAX_SIZE, 1600),
                pcName = intent.getStringExtra(EXTRA_PC_NAME).orEmpty(),
            )
        } else {
            graph.mirror.declined(requestId)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph.mirror.clearRequestNotification()
        if (savedInstanceState == null) {
            val manager = getSystemService(MediaProjectionManager::class.java)
            consent.launch(manager.createScreenCaptureIntent())
        }
    }

    companion object {
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_MAX_SIZE = "max_size"
        const val EXTRA_PC_NAME = "pc_name"
    }
}
