package io.github.aqua0801.droidlink.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aqua0801.droidlink.desktop.DesktopController
import io.github.aqua0801.droidlink.desktop.DropNotice
import io.github.aqua0801.droidlink.desktop.platform.FileReveal
import io.github.aqua0801.droidlink.desktop.resources.*
import io.github.aqua0801.droidlink.shared.protocol.TransferError
import io.github.aqua0801.droidlink.shared.transfer.Direction
import io.github.aqua0801.droidlink.shared.transfer.Transfer
import io.github.aqua0801.droidlink.shared.transfer.TransferStatus
import org.jetbrains.compose.resources.stringResource
import java.awt.FileDialog
import java.awt.Frame
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.util.Locale

@Composable
fun TransfersSection(controller: DesktopController) {
    val transfers by controller.files.transfers.collectAsState()
    val notice by controller.dropNotice.collectAsState()

    Section(stringResource(Res.string.section_transfers)) {
        notice?.let {
            Text(
                stringResource(
                    when (it) {
                        DropNotice.NO_DEVICE -> Res.string.drop_no_device
                        DropNotice.MULTIPLE_DEVICES -> Res.string.drop_multiple_devices
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (transfers.isEmpty()) Hint(stringResource(Res.string.transfers_empty, controller.downloadsDir.absolutePath))
        transfers.asReversed().forEach { t ->
            TransferRow(t, onCancel = { controller.files.cancel(t.id) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { FileReveal.openFolder(controller.downloadsDir) }) {
                Text(stringResource(Res.string.transfers_open_folder))
            }
            if (transfers.any { !it.isActive }) {
                TextButton(onClick = controller.files::clearFinished) { Text(stringResource(Res.string.transfers_clear)) }
            }
        }
    }
}

@Composable
private fun TransferRow(t: Transfer, onCancel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (t.direction == Direction.SEND) "↑ " else "↓ ") + t.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            when {
                t.isActive -> TextButton(onClick = onCancel) { Text(stringResource(Res.string.transfer_cancel)) }
                t.status == TransferStatus.DONE && t.direction == Direction.RECEIVE && t.location != null -> {
                    val file = File(t.location)
                    TextButton(onClick = { FileReveal.open(file) }) { Text(stringResource(Res.string.transfer_open)) }
                    TextButton(onClick = { FileReveal.reveal(file) }) { Text(stringResource(Res.string.transfer_reveal)) }
                }
            }
        }
        Text(
            stringResource(if (t.direction == Direction.SEND) Res.string.transfer_to else Res.string.transfer_from, t.peerName),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (t.isActive) {
            LinearProgressIndicator(progress = { t.progress }, modifier = Modifier.fillMaxWidth())
        }
        val (status, isError) = statusText(t)
        Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun statusText(t: Transfer): kotlin.Pair<String, Boolean> = when (t.status) {
    TransferStatus.WAITING -> stringResource(Res.string.transfer_waiting) to false
    TransferStatus.ACTIVE -> stringResource(Res.string.transfer_progress, formatBytes(t.transferred), formatBytes(t.size)) to false
    TransferStatus.DONE -> stringResource(
        if (t.direction == Direction.SEND) Res.string.transfer_sent else Res.string.transfer_saved,
        formatBytes(t.size),
    ) to false
    TransferStatus.CANCELLED -> stringResource(Res.string.transfer_cancelled) to false
    TransferStatus.FAILED -> stringResource(Res.string.transfer_failed, errorText(t.error)) to true
}

@Composable
private fun errorText(error: TransferError?): String = stringResource(
    when (error) {
        TransferError.DECLINED -> Res.string.error_declined
        TransferError.STORAGE -> Res.string.error_storage
        TransferError.TIMEOUT -> Res.string.error_timeout
        TransferError.CONNECTION_LOST -> Res.string.error_connection_lost
        TransferError.CHECKSUM_MISMATCH -> Res.string.error_checksum
        TransferError.CANCELLED -> Res.string.error_cancelled
        TransferError.IO, null -> Res.string.error_io
    },
)

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(Locale.ROOT, if (value < 10) "%.1f %s" else "%.0f %s", value, units[unit])
}

/** Native multi-select file picker. Modal; returns an empty list if cancelled. */
fun chooseFiles(title: String): List<File> {
    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD).apply {
        isMultipleMode = true
        isVisible = true
    }
    return dialog.files.orEmpty().toList()
}

/** Accepts files dragged in from the OS; [onHover] drives a highlight. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
fun Modifier.fileDropTarget(onHover: (Boolean) -> Unit, onFiles: (List<File>) -> Unit): Modifier = composed {
    val target = remember(onHover, onFiles) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) = onHover(true)
            override fun onExited(event: DragAndDropEvent) = onHover(false)
            override fun onEnded(event: DragAndDropEvent) = onHover(false)

            override fun onDrop(event: DragAndDropEvent): Boolean {
                onHover(false)
                val files = runCatching {
                    (event.awtTransferable.getTransferData(DataFlavor.javaFileListFlavor) as List<*>).filterIsInstance<File>()
                }.getOrDefault(emptyList())
                if (files.isEmpty()) return false
                onFiles(files)
                return true
            }
        }
    }
    dragAndDropTarget(
        shouldStartDragAndDrop = { event -> event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) },
        target = target,
    )
}

@Composable
fun DropOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), RoundedCornerShape(16.dp))
            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(Res.string.transfers_drop_here), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
    }
}
