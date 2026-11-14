package io.github.aqua0801.droidlink.ui.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import io.github.aqua0801.droidlink.R
import io.github.aqua0801.droidlink.sync.DocEntry
import io.github.aqua0801.droidlink.sync.SafTree

/** Top bar shared by the viewers: back, name, optional subtitle, share, open-with and delete. */
@Composable
fun ViewerTopBar(
    tree: SafTree,
    entry: DocEntry,
    subtitle: String?,
    visible: Boolean,
    onBack: () -> Unit,
    overMedia: Boolean,
    onDelete: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val uri = tree.uri(entry.id)
    val content = if (overMedia) Color.White else MaterialTheme.colorScheme.onSurface
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (overMedia) Color.Black.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surface)
                .statusBarsPadding(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.settings_back), tint = content)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.name, color = content, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, color = content.copy(alpha = 0.75f), style = MaterialTheme.typography.labelMedium)
            }
            IconButton(onClick = { shareFile(context, uri, entry.mime) }) {
                Icon(painterResource(R.drawable.ic_share), stringResource(R.string.browser_share), tint = content)
            }
            IconButton(onClick = { openExternally(context, uri, entry.mime) }) {
                Icon(painterResource(R.drawable.ic_open_in_new), stringResource(R.string.viewer_open_with), tint = content)
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.browser_delete), tint = content)
                }
            }
        }
    }
}
