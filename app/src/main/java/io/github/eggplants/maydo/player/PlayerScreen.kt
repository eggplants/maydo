package io.github.eggplants.maydo.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.eggplants.maydo.R
import java.util.Locale
import kotlinx.coroutines.delay

/** What the controls ask of the player; [PlayerActivity] does it with libmpv. */
interface PlayerController {
    fun togglePause()

    fun seekBy(seconds: Double)

    fun seekTo(seconds: Double)

    fun setSpeed(speed: Double)

    fun tracks(): List<Track>

    /** [id] null turns the track type off. */
    fun selectTrack(type: TrackType, id: Int?)

    fun rotate()

    val canEnterPictureInPicture: Boolean

    fun enterPictureInPicture()

    fun retry()

    fun close()
}

private val SPEEDS = listOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 3.0)

/** How long the controls stay up while playing, untouched. */
private const val HIDE_AFTER_MS = 4_000L

@Composable
fun PlayerScreen(state: PlayerState, controller: PlayerController) {
    var visible by remember { mutableStateOf(true) }
    // Bumped on every touch, so that hiding waits for the last one.
    var touched by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<PlayerDialog?>(null) }
    val playing = state.phase == Phase.Playing && !state.paused

    LaunchedEffect(visible, playing, touched, dialog) {
        if (visible && playing && dialog == null) {
            delay(HIDE_AFTER_MS)
            visible = false
        }
    }

    if (state.inPictureInPicture) return

    Box(
        Modifier.fillMaxSize().pointerInput(controller) {
            detectTapGestures(
                onTap = {
                    visible = !visible
                    touched++
                },
                onDoubleTap = { offset ->
                    touched++
                    when {
                        offset.x < size.width / 3f -> controller.seekBy(-10.0)
                        offset.x > size.width * 2 / 3f -> controller.seekBy(10.0)
                        else -> controller.togglePause()
                    }
                },
            )
        },
    ) {
        // Over the video, not over a surface of the theme.
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
                Controls(
                    state = state,
                    controller = controller,
                    onTouch = { touched++ },
                    onDialog = { dialog = it },
                )
            }
            Status(state, Modifier.align(Alignment.Center))
        }
        (state.phase as? Phase.Failed)?.let { failed ->
            ErrorCard(failed.message, controller, Modifier.align(Alignment.Center))
        }
    }

    when (dialog) {
        PlayerDialog.TRACKS ->
            TrackDialog(controller.tracks(), controller::selectTrack) { dialog = null }

        PlayerDialog.SPEED -> SpeedDialog(state.speed, controller::setSpeed) { dialog = null }

        null -> {}
    }
}

private enum class PlayerDialog {
    TRACKS,
    SPEED,
}

@Composable
private fun Controls(
    state: PlayerState,
    controller: PlayerController,
    onTouch: () -> Unit,
    onDialog: (PlayerDialog) -> Unit,
) {
    val scrim = Color.Black.copy(alpha = 0.55f)
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(scrim, Color.Transparent)))
                .safeDrawingPadding()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = controller::close) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            }
            Text(
                state.title.orEmpty(),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = { onDialog(PlayerDialog.TRACKS) }) {
                Icon(Icons.Default.Subtitles, stringResource(R.string.player_tracks))
            }
            IconButton(onClick = { onDialog(PlayerDialog.SPEED) }) {
                Icon(Icons.Default.Speed, stringResource(R.string.player_speed))
            }
            IconButton(onClick = controller::rotate) {
                Icon(Icons.Default.ScreenRotation, stringResource(R.string.player_rotate))
            }
            if (controller.canEnterPictureInPicture) {
                IconButton(onClick = controller::enterPictureInPicture) {
                    Icon(Icons.Default.PictureInPictureAlt, stringResource(R.string.player_pip))
                }
            }
        }

        if (state.phase == Phase.Playing) {
            Row(
                Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundButton(Icons.Default.Replay10, stringResource(R.string.player_back10), 48) {
                    onTouch()
                    controller.seekBy(-10.0)
                }
                // The spinner of a stalled stream shows here instead.
                if (state.buffering || state.seeking) {
                    Spacer(Modifier.size(72.dp))
                } else {
                    RoundButton(
                        if (state.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        stringResource(
                            if (state.paused) R.string.player_play else R.string.player_pause,
                        ),
                        72,
                    ) {
                        onTouch()
                        controller.togglePause()
                    }
                }
                RoundButton(
                    Icons.Default.Forward10,
                    stringResource(R.string.player_forward10),
                    48,
                ) {
                    onTouch()
                    controller.seekBy(10.0)
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, scrim)))
                .safeDrawingPadding()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            SeekBar(state, controller, onTouch)
        }
    }
}

@Composable
private fun SeekBar(state: PlayerState, controller: PlayerController, onTouch: () -> Unit) {
    // While dragging, the thumb follows the finger rather than mpv's position.
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = state.duration
    val position = dragging?.toDouble() ?: state.position
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(formatTime(position), style = MaterialTheme.typography.labelMedium)
        Slider(
            value = position.toFloat().coerceIn(0f, duration.toFloat().coerceAtLeast(0f)),
            onValueChange = {
                dragging = it
                onTouch()
            },
            onValueChangeFinished = {
                dragging?.let { controller.seekTo(it.toDouble()) }
                dragging = null
            },
            valueRange = 0f..duration.toFloat().coerceAtLeast(0.01f),
            enabled = duration > 0,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            colors =
                SliderDefaults.colors(
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                ),
        )
        Text(formatTime(duration), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun RoundButton(
    icon: ImageVector,
    description: String,
    size: Int,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier =
            Modifier.size(
                    size.dp,
                )
                .background(Color.Black.copy(alpha = 0.4f), MaterialTheme.shapes.extraLarge),
    ) {
        Icon(icon, description, Modifier.size((size * 0.6).dp), tint = Color.White)
    }
}

/** A spinner, with what is going on while nothing plays yet. */
@Composable
private fun Status(state: PlayerState, modifier: Modifier) {
    val message =
        when (val phase = state.phase) {
            Phase.Preparing -> stringResource(R.string.player_preparing)
            is Phase.Loading ->
                stringResource(
                    if (phase.web) R.string.player_resolving else R.string.player_loading,
                )
            Phase.Playing -> if (state.buffering || state.seeking) "" else return
            is Phase.Failed -> return
        }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(Modifier.size(56.dp), color = Color.White)
        if (message.isNotEmpty()) {
            Text(
                message,
                Modifier.padding(top = 16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), MaterialTheme.shapes.small)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                color = Color.White,
            )
        }
    }
}

@Composable
private fun ErrorCard(message: String?, controller: PlayerController, modifier: Modifier) {
    Card(modifier.safeDrawingPadding().padding(24.dp).widthIn(max = 560.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text(
                stringResource(R.string.player_failed),
                style = MaterialTheme.typography.titleLarge,
            )
            SelectionContainer {
                Text(
                    message ?: stringResource(R.string.player_failed_generic),
                    Modifier.padding(top = 12.dp)
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = controller::close) { Text(stringResource(R.string.close)) }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = controller::retry) { Text(stringResource(R.string.retry)) }
            }
        }
    }
}

@Composable
private fun TrackDialog(
    tracks: List<Track>,
    onSelect: (TrackType, Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = onDismiss,
            ) {
                Text(stringResource(R.string.close))
            }
        },
        title = { Text(stringResource(R.string.player_tracks)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (type in TrackType.entries) {
                    val ofType = tracks.filter { it.type == type }
                    if (ofType.isEmpty() && type != TrackType.SUB) continue
                    Text(
                        stringResource(
                            when (type) {
                                TrackType.VIDEO -> R.string.player_tracks_video
                                TrackType.AUDIO -> R.string.player_tracks_audio
                                TrackType.SUB -> R.string.player_tracks_sub
                            },
                        ),
                        Modifier.padding(top = 12.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (type == TrackType.SUB) {
                        TrackRow(
                            stringResource(R.string.player_tracks_off),
                            ofType.none { it.selected },
                        ) {
                            onSelect(type, null)
                            onDismiss()
                        }
                    }
                    for (track in ofType) {
                        TrackRow(track.label, track.selected) {
                            onSelect(type, track.id)
                            onDismiss()
                        }
                    }
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                }
            }
        },
    )
}

@Composable
private fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SpeedDialog(current: Double, onSelect: (Double) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = onDismiss,
            ) {
                Text(stringResource(R.string.close))
            }
        },
        title = { Text(stringResource(R.string.player_speed)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (speed in SPEEDS) {
                    TrackRow("×${speed.toString().removeSuffix(".0")}", speed == current) {
                        onSelect(speed)
                        onDismiss()
                    }
                }
            }
        },
    )
}

/** `m:ss`, or `h:mm:ss` from an hour on. */
fun formatTime(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0).toLong()
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.ROOT, "%d:%02d", m, s)
    }
}
