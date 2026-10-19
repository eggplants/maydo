package io.github.eggplants.maydo.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

sealed interface Phase {
    /** Python or yt-dlp is being set up, after an install or update of the app. */
    data object Preparing : Phase

    /** mpv is opening a file; for a web page, ytdl_hook is running yt-dlp on it first. */
    data class Loading(val web: Boolean) : Phase

    data object Playing : Phase

    /** What mpv and yt-dlp logged as errors, or a generic message when nothing. */
    data class Failed(val message: String?) : Phase
}

/** What the controls show, from mpv's properties and events. Written on the main thread only. */
class PlayerState {
    var phase by mutableStateOf<Phase>(Phase.Preparing)
    var title by mutableStateOf<String?>(null)
    var position by mutableDoubleStateOf(0.0)
    var duration by mutableDoubleStateOf(0.0)

    /** How far into the file the demuxer has read ahead, in seconds. */
    var cacheTime by mutableDoubleStateOf(0.0)
    var paused by mutableStateOf(false)

    /** Waiting for the network or a seek. */
    var buffering by mutableStateOf(false)
    var seeking by mutableStateOf(false)
    var speed by mutableDoubleStateOf(1.0)
    var videoWidth by mutableIntStateOf(0)
    var videoHeight by mutableIntStateOf(0)
    var videoRotate by mutableIntStateOf(0)
    var inPictureInPicture by mutableStateOf(false)

    /** Where in mpv's playlist, from 0, and how many entries it has. */
    var playlistPos by mutableIntStateOf(0)
    var playlistCount by mutableIntStateOf(0)

    /** The video's width over its height as shown, or null before mpv knows it. */
    val aspect: Double?
        get() {
            if (videoWidth <= 0 || videoHeight <= 0) return null
            val ratio = videoWidth.toDouble() / videoHeight
            return if (videoRotate % 180 == 90) 1 / ratio else ratio
        }
}
