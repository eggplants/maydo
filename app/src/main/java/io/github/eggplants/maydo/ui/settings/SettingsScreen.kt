package io.github.eggplants.maydo.ui.settings

import android.content.Intent
import android.provider.Settings
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.eggplants.maydo.AppContainer
import io.github.eggplants.maydo.BuildConfig
import io.github.eggplants.maydo.R
import io.github.eggplants.maydo.core.RuntimeState
import io.github.eggplants.maydo.core.UpdateManager
import io.github.eggplants.maydo.core.UpdateState
import io.github.eggplants.maydo.core.UpdateStep
import io.github.eggplants.maydo.core.VideoQuality
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onEditConfig: (ConfigFile) -> Unit,
    onLicenses: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val runtime by container.runtime.state.collectAsStateWithLifecycle()
    val updates by container.updates.state.collectAsStateWithLifecycle()
    var qualityDialog by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    fun openUrl(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                title = { Text(stringResource(R.string.settings)) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Section(R.string.settings_playback)
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_quality)) },
                supportingContent = { Text(stringResource(settings.quality.label)) },
                modifier = Modifier.clickable { qualityDialog = true },
            )
            SwitchItem(
                R.string.settings_hwdec,
                R.string.settings_hwdec_summary,
                settings.hardwareDecoding,
            ) {
                scope.launch { container.settings.setHardwareDecoding(it) }
            }
            SwitchItem(
                R.string.settings_ytdl_first,
                R.string.settings_ytdl_first_summary,
                settings.ytdlFirst,
            ) {
                scope.launch { container.settings.setYtdlFirst(it) }
            }
            SwitchItem(
                R.string.settings_whole_playlists,
                R.string.settings_whole_playlists_summary,
                settings.wholePlaylists,
            ) {
                scope.launch { container.settings.setWholePlaylists(it) }
            }
            SwitchItem(
                R.string.settings_history,
                R.string.settings_history_summary,
                settings.keepHistory,
            ) {
                scope.launch { container.settings.setKeepHistory(it) }
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_mpv_conf)) },
                supportingContent = { Text(stringResource(R.string.settings_mpv_conf_summary)) },
                modifier = Modifier.clickable { onEditConfig(ConfigFile.MPV) },
            )

            Section(R.string.settings_ytdlp)
            val installed = (runtime as? RuntimeState.Ready)?.ytdlpVersion
            val latest = updates.ytdlpLatest
            val newer = latest?.takeIf { container.updates.isNewerYtdlp(it) }
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_ytdlp_version)) },
                supportingContent = {
                    Text(
                        when (val r = runtime) {
                            RuntimeState.Preparing -> stringResource(R.string.player_preparing)
                            is RuntimeState.Ready -> r.ytdlpVersion
                            is RuntimeState.Failed -> r.message
                        },
                    )
                },
            )
            UpdateBlock(
                step = updates.ytdlpStep,
                status =
                    when {
                        latest == null -> null
                        newer != null ->
                            stringResource(
                                R.string.settings_update_available,
                                newer.tagName,
                            )
                        else -> stringResource(R.string.settings_up_to_date, latest.tagName)
                    },
                actionLabel =
                    newer?.let { stringResource(R.string.settings_update_to, it.tagName) },
                onAction = container.updates::updateYtdlp,
                onCheck = container.updates::checkYtdlp,
                enabled = installed != null,
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_ytdlp_conf)) },
                supportingContent = { Text(stringResource(R.string.settings_ytdlp_conf_summary)) },
                modifier = Modifier.clickable { onEditConfig(ConfigFile.YTDLP) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_ytdlp_releases)) },
                supportingContent = { Text(YTDLP_RELEASES) },
                modifier = Modifier.clickable { openUrl(YTDLP_RELEASES) },
            )

            Section(R.string.settings_app)
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_app_version)) },
                supportingContent = {
                    Text("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                },
            )
            SwitchItem(
                R.string.settings_check_updates,
                if (BuildConfig.SELF_UPDATE) {
                    R.string.settings_check_updates_summary
                } else {
                    R.string.settings_check_updates_summary_ytdlp
                },
                settings.checkUpdates,
            ) {
                scope.launch { container.settings.setCheckUpdates(it) }
            }
            // F-Droid's builds leave updating the app to F-Droid.
            if (BuildConfig.SELF_UPDATE) AppUpdate(container.updates, updates)
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_source)) },
                supportingContent = { Text(APP_REPOSITORY) },
                modifier = Modifier.clickable { openUrl(APP_REPOSITORY) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_licenses)) },
                modifier = Modifier.clickable(onClick = onLicenses),
            )
        }
    }

    if (qualityDialog) {
        QualityDialog(
            settings.quality,
            onSelect = {
                scope.launch { container.settings.setQuality(it) }
                qualityDialog = false
            },
            onDismiss = { qualityDialog = false },
        )
    }

    if (updates.appNeedsPermission) {
        AlertDialog(
            onDismissRequest = container.updates::permissionHandled,
            title = { Text(stringResource(R.string.settings_install_permission)) },
            text = { Text(stringResource(R.string.settings_install_permission_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Installing is tried again by hand once the user is back.
                        container.updates.permissionHandled()
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                "package:${context.packageName}".toUri(),
                            ),
                        )
                    },
                ) {
                    Text(stringResource(R.string.settings_install_permission_open))
                }
            },
            dismissButton = {
                TextButton(onClick = container.updates::permissionHandled) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** The in-app updater's part of the settings: the newest release, its notes and its install. */
@Composable
private fun AppUpdate(manager: UpdateManager, updates: UpdateState) {
    val context = LocalContext.current
    val app = updates.app
    UpdateBlock(
        step = updates.appStep,
        status =
            when {
                app != null ->
                    stringResource(
                        R.string.settings_app_update_available,
                        app.versionName,
                        Formatter.formatShortFileSize(context, app.apk.size),
                    )
                updates.appChecked -> stringResource(R.string.settings_app_up_to_date)
                else -> null
            },
        actionLabel = app?.let { stringResource(R.string.settings_app_install, it.versionName) },
        onAction = manager::installApp,
        onCheck = manager::checkApp,
    )
    app?.release
        ?.body
        ?.takeIf { it.isNotBlank() }
        ?.let { notes ->
            Text(
                notes.trim(),
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 12,
            )
        }
}

/**
 * Where an update stands: [status] (what the last check found), a progress bar while it downloads,
 * the error when it failed, and buttons to check again and to update.
 */
@Composable
private fun UpdateBlock(
    step: UpdateStep,
    status: String?,
    actionLabel: String?,
    onAction: () -> Unit,
    onCheck: () -> Unit,
    enabled: Boolean = true,
) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        when (step) {
            UpdateStep.Checking -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.settings_checking),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            is UpdateStep.Downloading -> {
                LinearProgressIndicator({ step.fraction }, Modifier.fillMaxWidth())
                Text(
                    stringResource(
                        R.string.settings_downloading,
                        Formatter.formatShortFileSize(context, step.done),
                        Formatter.formatShortFileSize(context, step.total),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            UpdateStep.Installing -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.settings_installing),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            is UpdateStep.Failed ->
                Text(
                    step.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )

            UpdateStep.Idle -> {}
        }
        val busy =
            step == UpdateStep.Checking ||
                step is UpdateStep.Downloading ||
                step == UpdateStep.Installing
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCheck, enabled = !busy) {
                Text(stringResource(R.string.settings_check))
            }
            if (actionLabel != null) {
                Button(onClick = onAction, enabled = !busy && enabled) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun Section(title: Int) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(
        stringResource(title),
        Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SwitchItem(title: Int, summary: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(summary)) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@Composable
private fun QualityDialog(
    current: VideoQuality,
    onSelect: (VideoQuality) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_quality)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (quality in VideoQuality.entries) {
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(quality) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = quality == current, onClick = { onSelect(quality) })
                        Text(stringResource(quality.label))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
            ) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

private const val YTDLP_RELEASES = "https://github.com/yt-dlp/yt-dlp/releases"
private const val APP_REPOSITORY = "https://github.com/${BuildConfig.GITHUB_REPOSITORY}"
