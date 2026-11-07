package io.github.aqua0801.droidlink.clipboard

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.aqua0801.droidlink.R
import io.github.aqua0801.droidlink.graph
import io.github.aqua0801.droidlink.link.LinkState

/** Quick Settings tile: one tap sends the current clipboard to the PC. */
class ClipboardTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            label = getString(R.string.tile_label)
            state = if (graph.link.state.value is LinkState.Connected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun(::launchSender) else launchSender()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launchSender() {
        val intent = Intent(this, ClipboardSendActivity::class.java)
            .setAction(ClipboardSendActivity.ACTION_SEND_CLIPBOARD)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
