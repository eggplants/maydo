package io.github.eggplants.maydo.ui.home

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.eggplants.maydo.AppContainer
import io.github.eggplants.maydo.R
import io.github.eggplants.maydo.core.HistoryEntry
import io.github.eggplants.maydo.core.PlayInput
import io.github.eggplants.maydo.core.RuntimeState
import io.github.eggplants.maydo.player.PlayerActivity
import io.github.eggplants.maydo.player.fileLabel
import kotlinx.coroutines.launch

/** Opens [uri] in the player. */
fun play(context: Context, uri: String) {
    context.startActivity(
        Intent(context, PlayerActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(uri.toUri()),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(container: AppContainer, onSettings: () -> Unit) {
    val context = LocalContext.current
    val layoutDirection = LocalLayoutDirection.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val runtime by container.runtime.state.collectAsStateWithLifecycle()
    val updates by container.updates.state.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }

    val ytdlpUpdate = updates.ytdlpLatest?.takeIf { container.updates.isNewerYtdlp(it) }
    val hasUpdate = updates.app != null || ytdlpUpdate != null

    val openDocument =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            // Kept, so that the history can play it again later.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            play(context, uri.toString())
        }

    val openFolder =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            play(context, uri.toString())
        }

    fun submit() {
        PlayInput.toUri(input)?.let { play(context, it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onSettings) {
                        BadgedBox(badge = { if (hasUpdate) Badge() }) {
                            Icon(Icons.Default.Settings, stringResource(R.string.settings))
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    // The navigation bar and cutouts are at the sides in landscape.
                    start = padding.calculateStartPadding(layoutDirection) + 16.dp,
                    end = padding.calculateEndPadding(layoutDirection) + 16.dp,
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + 16.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.home_input_label)) },
                    placeholder = { Text(stringResource(R.string.home_input_hint)) },
                    singleLine = true,
                    trailingIcon = {
                        if (input.isEmpty()) {
                            IconButton(onClick = { input = clipboardText(context).orEmpty() }) {
                                Icon(Icons.Default.ContentPaste, stringResource(R.string.paste))
                            }
                        } else {
                            IconButton(onClick = { input = "" }) {
                                Icon(Icons.Default.Close, stringResource(R.string.clear))
                            }
                        }
                    },
                    keyboardOptions =
                        KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                )
            }
            item {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = ::submit, enabled = input.isNotBlank()) {
                        Icon(Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.home_play))
                    }
                    // Media, and playlist files (.m3u, .m3u8), which mpv opens as playlists.
                    OutlinedButton(onClick = { openDocument.launch(PICKABLE) }) {
                        Icon(Icons.Default.VideoFile, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.home_open_file))
                    }
                    OutlinedButton(onClick = { openFolder.launch(null) }) {
                        Icon(Icons.Default.FolderOpen, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.home_open_folder))
                    }
                }
            }
            when (val r = runtime) {
                RuntimeState.Preparing ->
                    item {
                        Text(
                            stringResource(R.string.player_preparing),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                is RuntimeState.Failed ->
                    item {
                        Notice(
                            stringResource(R.string.home_runtime_failed),
                            r.message,
                            error = true,
                            onClick = onSettings,
                        )
                    }

                is RuntimeState.Ready -> {}
            }
            updates.app?.let { update ->
                item {
                    Notice(
                        stringResource(R.string.home_app_update, update.versionName),
                        stringResource(R.string.home_update_hint),
                        onClick = onSettings,
                    )
                }
            }
            ytdlpUpdate?.let { release ->
                item {
                    Notice(
                        stringResource(R.string.home_ytdlp_update, release.tagName),
                        stringResource(R.string.home_update_hint),
                        onClick = onSettings,
                    )
                }
            }
            if (settings.history.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.History, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.home_history),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        TextButton(
                            onClick = { scope.launch { container.settings.clearHistory() } },
                        ) {
                            Text(stringResource(R.string.home_history_clear))
                        }
                    }
                }
                items(settings.history, key = { it.uri }) { entry ->
                    HistoryRow(
                        entry,
                        onClick = { play(context, entry.uri) },
                        onRemove = { scope.launch { container.settings.removeHistory(entry.uri) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry, onClick: () -> Unit, onRemove: () -> Unit) {
    val uri = remember(entry.uri) { displayUri(entry.uri) }
    ListItem(
        headlineContent = {
            Text(entry.title ?: uri, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Column {
                if (entry.title != null) {
                    Text(uri, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    if (System.currentTimeMillis() - entry.time < DateUtils.MINUTE_IN_MILLIS) {
                        stringResource(R.string.home_history_just_now)
                    } else {
                        DateUtils.getRelativeTimeSpanString(entry.time).toString()
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
        trailingContent = {
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, stringResource(R.string.home_history_remove))
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun Notice(
    title: String,
    text: String?,
    error: Boolean = false,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors =
            if (error) {
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            } else {
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            },
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.SystemUpdate, null)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                text?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

/** What the file picker offers: media, and playlists of it. */
private val PICKABLE =
    arrayOf(
        "video/*",
        "audio/*",
        "application/x-mpegurl",
        "application/vnd.apple.mpegurl",
    )

/** A search as its words, a file or folder by its name. */
private fun displayUri(uri: String): String =
    when {
        uri.startsWith("ytdl://ytsearch:") -> uri.removePrefix("ytdl://ytsearch:")
        uri.startsWith("content://") -> fileLabel(uri)
        else -> uri
    }

private fun clipboardText(context: Context): String? =
    context
        .getSystemService(ClipboardManager::class.java)
        ?.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()
