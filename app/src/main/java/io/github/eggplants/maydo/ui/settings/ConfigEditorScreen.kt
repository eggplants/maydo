package io.github.eggplants.maydo.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.eggplants.maydo.AppContainer
import io.github.eggplants.maydo.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The config files mpv and yt-dlp read themselves, under `YtdlpRuntime.configDir`. */
enum class ConfigFile(val path: String, @StringRes val title: Int) {
    /** mpv's; read when the player starts, after Maydo's own options, which it can override. */
    MPV("mpv/mpv.conf", R.string.settings_mpv_conf),

    /** yt-dlp's user config, `$XDG_CONFIG_HOME/yt-dlp/config`. */
    YTDLP("yt-dlp/config", R.string.settings_ytdlp_conf),
}

/** A plain text editor for one [ConfigFile]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigEditorScreen(container: AppContainer, file: ConfigFile, onBack: () -> Unit) {
    val savedMessage = stringResource(R.string.config_saved)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val target = remember(file) { File(container.runtime.configDir, file.path) }
    var text by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(target) {
        if (text == null) {
            text = withContext(Dispatchers.IO) { target.takeIf { it.isFile }?.readText().orEmpty() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                title = { Text(stringResource(file.title)) },
                actions = {
                    IconButton(
                        onClick = {
                            val content = text ?: return@IconButton
                            scope.launch {
                                val saved =
                                    withContext(Dispatchers.IO) {
                                        runCatching {
                                            target.parentFile?.mkdirs()
                                            target.writeText(content)
                                        }
                                    }
                                snackbar.showSnackbar(
                                    saved.fold(
                                        { savedMessage },
                                        { it.message ?: it.toString() },
                                    ),
                                )
                            }
                        },
                        enabled = text != null,
                    ) {
                        Icon(Icons.Default.Save, stringResource(R.string.save))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        OutlinedTextField(
            value = text.orEmpty(),
            onValueChange = { text = it },
            modifier = Modifier.fillMaxSize().padding(padding).imePadding().padding(16.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            placeholder = { Text(target.path, fontFamily = FontFamily.Monospace) },
            supportingText = { Text(stringResource(R.string.config_hint)) },
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                ),
            enabled = text != null,
        )
    }
}
