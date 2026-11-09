package io.github.aqua0801.droidlink.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aqua0801.droidlink.AppGraph
import io.github.aqua0801.droidlink.R
import io.github.aqua0801.droidlink.shared.protocol.TransferError
import io.github.aqua0801.droidlink.shared.transfer.Direction
import io.github.aqua0801.droidlink.shared.transfer.Transfer
import io.github.aqua0801.droidlink.shared.transfer.TransferStatus

@Composable
fun FilesCard(graph: AppGraph, connected: Boolean, onPick: () -> Unit, onOpen: (Transfer) -> Unit) {
    val transfers by graph.files.engine.transfers.collectAsStateWithLifecycle()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.section_files), style = MaterialTheme.typography.titleMedium)
            Button(onClick = onPick, enabled = connected) { Text(stringResource(R.string.files_pick)) }
            transfers.asReversed().forEach { t ->
                TransferRow(t, onCancel = { graph.files.engine.cancel(t.id) }, onOpen = { onOpen(t) })
            }
            if (transfers.any { !it.isActive }) {
                TextButton(onClick = graph.files.engine::clearFinished) { Text(stringResource(R.string.files_clear)) }
            }
            Text(
                stringResource(R.string.files_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TransferRow(t: Transfer, onCancel: () -> Unit, onOpen: () -> Unit) {
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (t.direction == Direction.SEND) "↑ " else "↓ ") + t.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            when {
                t.isActive -> TextButton(onClick = onCancel) { Text(stringResource(R.string.transfer_cancel)) }
                t.status == TransferStatus.DONE && t.direction == Direction.RECEIVE ->
                    TextButton(onClick = onOpen) { Text(stringResource(R.string.transfer_open)) }
            }
        }
        Text(
            stringResource(if (t.direction == Direction.SEND) R.string.transfer_to else R.string.transfer_from, t.peerName),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (t.isActive) LinearProgressIndicator(progress = { t.progress }, modifier = Modifier.fillMaxWidth())
        val failed = t.status == TransferStatus.FAILED
        Text(
            when (t.status) {
                TransferStatus.WAITING -> stringResource(R.string.transfer_waiting)
                TransferStatus.ACTIVE -> stringResource(R.string.transfer_progress, size(t.transferred), size(t.size))
                TransferStatus.DONE -> stringResource(
                    if (t.direction == Direction.SEND) R.string.transfer_sent else R.string.transfer_saved,
                    size(t.size),
                )
                TransferStatus.CANCELLED -> stringResource(R.string.transfer_cancelled)
                TransferStatus.FAILED -> stringResource(R.string.transfer_failed, errorText(t.error))
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun errorText(error: TransferError?): String = stringResource(
    when (error) {
        TransferError.DECLINED -> R.string.error_declined
        TransferError.STORAGE -> R.string.error_storage
        TransferError.TIMEOUT -> R.string.error_timeout
        TransferError.CONNECTION_LOST -> R.string.error_connection_lost
        TransferError.CHECKSUM_MISMATCH -> R.string.error_checksum
        TransferError.CANCELLED -> R.string.error_cancelled
        TransferError.IO, null -> R.string.error_io
    },
)
