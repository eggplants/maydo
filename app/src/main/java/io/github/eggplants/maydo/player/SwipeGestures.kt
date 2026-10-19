package io.github.eggplants.maydo.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/** What a swipe on the video is changing, for [SwipeOverlay] to show. */
sealed interface SwipeFeedback {
    data class Seek(val target: Double, val delta: Double, val duration: Double) : SwipeFeedback

    /** [level] from 0 to 1. */
    data class Brightness(val level: Float) : SwipeFeedback

    data class Volume(val level: Int, val max: Int) : SwipeFeedback
}

/** How far a swipe goes, kept apart from the gestures so that it can be tested. */
object SwipeMath {
    /** A swipe across the whole width seeks this far, or through the whole of a shorter file. */
    const val SEEK_FULL_WIDTH_SECONDS = 120.0

    /** A swipe over this share of the height goes from the lowest level to the highest. */
    const val LEVEL_FULL_HEIGHT = 0.6f

    /** Where a horizontal swipe of [dxFraction] of the width from [start] seeks to. */
    fun seekTarget(start: Double, duration: Double, dxFraction: Float): Double {
        val range = minOf(duration, SEEK_FULL_WIDTH_SECONDS)
        return (start + dxFraction * range).coerceIn(0.0, duration.coerceAtLeast(0.0))
    }

    /** A level from 0 to 1 after a vertical swipe of [dyFraction] of the height, up positive. */
    fun level(start: Float, dyFraction: Float): Float =
        (start - dyFraction / LEVEL_FULL_HEIGHT).coerceIn(0f, 1f)

    /**
     * The system's brightness [setting] as a fraction, for where a swipe starts. Its range is
     * [configuredMax] in AOSP (255), but OEMs go further (OPPO: 4095) without saying so where an
     * app can read it; a setting above the range is taken as out of the next power of two.
     */
    fun brightnessFraction(setting: Int, configuredMax: Int?): Float {
        var max = configuredMax?.takeIf { it > 0 } ?: 255
        while (setting > max) max = max * 2 + 1
        return (setting.toFloat() / max).coerceIn(0f, 1f)
    }

    /** [fraction] of [max] steps, as the volume takes them. */
    fun steps(fraction: Float, max: Int): Int = (fraction * max).roundToInt().coerceIn(0, max)
}

private enum class SwipeKind {
    SEEK,
    BRIGHTNESS,
    VOLUME,
}

/**
 * Swipes on the video, as most video players have them: across seeks (on release), up and down on
 * the left half sets the brightness, on the right half the volume. A swipe that starts near the top
 * or bottom edge is left to the system, which pulls its bars out from there; one that a control
 * below takes (the seek bar) never starts.
 */
fun Modifier.playerSwipes(
    state: PlayerState,
    controller: PlayerController,
    onFeedback: (SwipeFeedback?) -> Unit,
): Modifier =
    pointerInput(controller) {
        val edge = 48.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = true)
            if (down.position.y < edge || down.position.y > size.height - edge) {
                return@awaitEachGesture
            }
            var horizontal = false
            val first =
                awaitTouchSlopOrCancellation(down.id) { change, overSlop ->
                    horizontal = abs(overSlop.x) > abs(overSlop.y)
                    change.consume()
                } ?: return@awaitEachGesture
            val kind =
                when {
                    horizontal -> SwipeKind.SEEK.takeIf { state.duration > 0 }
                    down.position.x < size.width / 2f -> SwipeKind.BRIGHTNESS
                    else -> SwipeKind.VOLUME
                } ?: return@awaitEachGesture

            val startPosition = state.position
            val duration = state.duration
            val startBrightness = controller.brightness()
            val (startVolume, maxVolume) = controller.volume()
            var lastVolume = startVolume
            var seekTarget = startPosition

            fun update(total: Offset) {
                when (kind) {
                    SwipeKind.SEEK -> {
                        seekTarget =
                            SwipeMath.seekTarget(startPosition, duration, total.x / size.width)
                        onFeedback(
                            SwipeFeedback.Seek(seekTarget, seekTarget - startPosition, duration),
                        )
                    }

                    SwipeKind.BRIGHTNESS -> {
                        val level = SwipeMath.level(startBrightness, total.y / size.height)
                        controller.setBrightness(level)
                        onFeedback(SwipeFeedback.Brightness(level))
                    }

                    SwipeKind.VOLUME -> {
                        val start = if (maxVolume > 0) startVolume.toFloat() / maxVolume else 0f
                        val level =
                            SwipeMath.steps(
                                SwipeMath.level(start, total.y / size.height),
                                maxVolume,
                            )
                        if (level != lastVolume) {
                            controller.setVolume(level)
                            lastVolume = level
                        }
                        onFeedback(SwipeFeedback.Volume(level, maxVolume))
                    }
                }
            }

            var total = first.position - down.position
            update(total)
            drag(first.id) { change ->
                total += change.positionChange()
                change.consume()
                update(total)
            }
            if (kind == SwipeKind.SEEK) controller.seekTo(seekTarget)
            onFeedback(null)
        }
    }

/** What the swipe in progress sets, near the top of the screen. */
@Composable
fun SwipeOverlay(feedback: SwipeFeedback, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.large)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (feedback) {
            is SwipeFeedback.Seek -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (feedback.delta < 0) {
                            Icons.Default.FastRewind
                        } else {
                            Icons.Default.FastForward
                        },
                        null,
                        Modifier.size(28.dp),
                        tint = Color.White,
                    )
                    val sign = if (feedback.delta < 0) "-" else "+"
                    Text(
                        "$sign${formatTime(abs(feedback.delta))}",
                        Modifier.padding(start = 8.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                Text(
                    "${formatTime(feedback.target)} / ${formatTime(feedback.duration)}",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            is SwipeFeedback.Brightness ->
                LevelRow(
                    if (feedback.level < 0.5f) {
                        Icons.Default.BrightnessLow
                    } else {
                        Icons.Default.BrightnessHigh
                    },
                    feedback.level,
                    "${(feedback.level * 100).roundToInt()}%",
                )

            is SwipeFeedback.Volume ->
                LevelRow(
                    if (feedback.level == 0) {
                        Icons.AutoMirrored.Filled.VolumeOff
                    } else {
                        Icons.AutoMirrored.Filled.VolumeUp
                    },
                    if (feedback.max > 0) feedback.level.toFloat() / feedback.max else 0f,
                    "${feedback.level} / ${feedback.max}",
                )
        }
    }
}

@Composable
private fun LevelRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    fraction: Float,
    label: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(28.dp), tint = Color.White)
        Text(
            label,
            Modifier.padding(start = 8.dp),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
    }
    LinearProgressIndicator(
        progress = { fraction },
        modifier = Modifier.width(160.dp),
        color = Color.White,
        trackColor = Color.White.copy(alpha = 0.3f),
        drawStopIndicator = {},
    )
}
