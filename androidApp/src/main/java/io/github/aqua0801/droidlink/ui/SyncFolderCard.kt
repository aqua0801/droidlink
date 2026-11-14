package io.github.aqua0801.droidlink.ui

import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aqua0801.droidlink.R
import io.github.aqua0801.droidlink.data.PhoneSettings
import io.github.aqua0801.droidlink.sync.PhoneSyncFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun SyncFolderCard(folder: PhoneSyncFolder, settings: PhoneSettings, onBrowse: () -> Unit) {
    val tree by folder.tree.collectAsStateWithLifecycle()
    val last by settings.lastSync.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) { folder.choose(uri) }
    }
    val pick = { picker.launch(DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, "primary:Documents")) }
    val name by produceState<String?>(null, tree) {
        value = tree?.let { t -> withContext(Dispatchers.IO) { folder.folderName(t) } }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.sync_title), style = MaterialTheme.typography.titleMedium)
            if (tree == null) {
                Text(stringResource(R.string.sync_not_set), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = pick) { Text(stringResource(R.string.sync_choose)) }
            } else {
                Text(stringResource(R.string.sync_folder, name ?: "…"), style = MaterialTheme.typography.bodyMedium)
                Text(
                    last?.let {
                        val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it.at))
                        stringResource(R.string.sync_last, time, it.updated, it.deleted)
                    } ?: stringResource(R.string.sync_never),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onBrowse) { Text(stringResource(R.string.sync_browse)) }
                    TextButton(onClick = pick) { Text(stringResource(R.string.sync_change)) }
                }
            }
            Text(
                stringResource(R.string.sync_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
