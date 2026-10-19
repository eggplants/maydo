package io.github.eggplants.maydo.player

import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.github.eggplants.maydo.R
import io.github.eggplants.maydo.container
import io.github.eggplants.maydo.core.PlayInput
import io.github.eggplants.maydo.core.RuntimeState
import io.github.eggplants.maydo.core.SharedText
import io.github.eggplants.maydo.ui.theme.MaydoTheme
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVLib.MpvEvent
import `is`.xyz.mpv.MPVLib.MpvLogLevel
import `is`.xyz.mpv.MPVNode
import java.io.File
import kotlinx.coroutines.launch

/** What was opened: [uri] as the user gave it, [path] as mpv opens it. */
private data class Target(val uri: String, val path: String, val web: Boolean)

/**
 * The player: libmpv draws into [MpvView] and the Compose controls sit on top.
 *
 * mpv opens web pages through ytdl_hook, which has [YtdlRunner] run yt-dlp. There is one libmpv per
 * process, so this activity is single-task and opens whatever comes next in the same instance.
 */
class PlayerActivity : ComponentActivity(), MPVLib.EventObserver, MPVLib.LogObserver {
    private lateinit var player: MpvView
    private val state = PlayerState()

    private var initialized = false
    private var ytdl: YtdlRunner? = null
    private var started = false
    private var target: Target? = null

    /** Between mpv's start-file and file-loaded: going idle then means the file failed. */
    private var loading = false
    private val errors = ArrayDeque<String>()

    /** The orientation follows each file's video once; the rotate button overrides it. */
    private var orientationSet = false

    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val focusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change ->
                if (
                    change == AudioManager.AUDIOFOCUS_LOSS ||
                        change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                ) {
                    runOnUiThread { setPaused(true) }
                }
            }
            .build()
    }

    /** Headphones unplugged: pause rather than play out loud. */
    private val noisyReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) setPaused(true)
            }
        }

    private val supportsPictureInPicture by lazy {
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    private val controller =
        object : PlayerController {
            override fun togglePause() {
                if (!initialized) return
                // keep-open holds the last frame, paused: play means from the start again.
                if (MPVLib.getPropertyBoolean("eof-reached") == true) {
                    MPVLib.command("seek", "0", "absolute")
                    MPVLib.setPropertyBoolean("pause", false)
                } else {
                    MPVLib.command("cycle", "pause")
                }
            }

            override fun seekBy(seconds: Double) {
                if (initialized) MPVLib.command("seek", seconds.toString(), "relative")
            }

            override fun seekTo(seconds: Double) {
                if (initialized) MPVLib.command("seek", seconds.toString(), "absolute")
            }

            override fun setSpeed(speed: Double) {
                if (initialized) MPVLib.setPropertyDouble("speed", speed)
            }

            override fun tracks(): List<Track> =
                if (initialized) {
                    Tracks.parse(MPVLib.getPropertyString("track-list"))
                } else {
                    emptyList()
                }

            override fun selectTrack(type: TrackType, id: Int?) {
                if (!initialized) return
                if (id == null) {
                    MPVLib.setPropertyString(type.property, "no")
                } else {
                    MPVLib.setPropertyInt(type.property, id)
                }
            }

            override fun rotate() {
                orientationSet = true
                val landscape =
                    resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                requestedOrientation =
                    if (landscape) {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }
            }

            override val canEnterPictureInPicture: Boolean
                get() = supportsPictureInPicture

            override fun enterPictureInPicture() {
                runCatching { enterPictureInPictureMode(pictureInPictureParams()) }
            }

            override fun retry() {
                // Again from the intent: a file's descriptor went to mpv, which closed it.
                if (initialized) {
                    targetOf(intent)?.let { open(it.also { target = it }) }
                } else {
                    start()
                }
            }

            override fun close() {
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_player)
        player = findViewById(R.id.player)
        findViewById<ComposeView>(R.id.controls).setContent {
            MaydoTheme(darkTheme = true) { PlayerScreen(state, controller) }
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        val first = targetOf(intent)
        if (first == null) {
            Toast.makeText(this, R.string.player_nothing_to_play, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        target = first
        start()
    }

    /** Waits for yt-dlp to be ready, then starts mpv on [target]. */
    private fun start() {
        state.phase = Phase.Preparing
        lifecycleScope.launch {
            val runtime = container.runtime
            if (!runtime.prepare()) {
                state.phase = Phase.Failed((runtime.state.value as? RuntimeState.Failed)?.message)
                return@launch
            }
            startMpv()
            target?.let(::open)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val next = targetOf(intent) ?: return
        target = next
        if (initialized) open(next)
    }

    private fun startMpv() {
        val settings = container.settings.state.value
        val runtime = container.runtime
        player.options =
            PlayerOptions(
                hardwareDecoding = settings.hardwareDecoding,
                ytdlFormat = settings.quality.ytdlFormat,
                ytdlFirst = settings.ytdlFirst,
                ytdlPath = runtime.launcher.path,
                quickJsPath = runtime.quickJs.path,
                caFile = runtime.caFile.path,
                ytdlHook = runtime.ytdlHook.path,
                formatSort =
                    if (settings.hardwareDecoding) {
                        runCatching { HardwareCodecs.formatSort(HardwareCodecs.decoderTypes()) }
                            .getOrNull()
                    } else {
                        null
                    },
            )
        ytdl = YtdlRunner(runtime.launcher, runtime.launcherEnvironment(), lifecycleScope)
        MPVLib.addObserver(this)
        MPVLib.addLogObserver(this)
        val configDir = File(runtime.configDir, "mpv").apply { mkdirs() }
        player.initialize(configDir.path, cacheDir.path)
        // BaseMPVView asks for idle=once, after which mpv would quit when a file fails to open;
        // Maydo shows the error and keeps the player for the next one instead.
        MPVLib.setPropertyString("idle", "yes")
        initialized = true
    }

    private fun open(next: Target) {
        errors.clear()
        orientationSet = false
        state.title = null
        state.phase = Phase.Loading(next.web)
        if (!started) {
            // The surface appears once the view is shown, and BaseMPVView loads the file then.
            started = true
            player.playFile(next.path)
            player.isVisible = true
        } else {
            // A yt-dlp still at the last one would hold up the hook.
            ytdl?.cancelAll()
            MPVLib.command("loadfile", next.path)
            MPVLib.setPropertyBoolean("pause", false)
        }
    }

    /** What [intent] asks to play: a link, a shared text with one in it, or a file. */
    private fun targetOf(intent: Intent?): Target? {
        intent ?: return null
        val uri: Uri? =
            when (intent.action) {
                Intent.ACTION_SEND -> {
                    val text =
                        SharedText.url(intent.getStringExtra(Intent.EXTRA_TEXT))
                            ?: SharedText.url(intent.getStringExtra(Intent.EXTRA_SUBJECT))
                    text?.let(Uri::parse)
                        ?: IntentCompat.getParcelableExtra(
                            intent,
                            Intent.EXTRA_STREAM,
                            Uri::class.java,
                        )
                }

                else -> intent.data
            }
        uri ?: return null
        val text = uri.toString()
        return when (uri.scheme?.lowercase()) {
            "content" -> {
                // mpv cannot open content URIs; it takes an open descriptor instead.
                val fd =
                    runCatching { contentResolver.openFileDescriptor(uri, "r")?.detachFd() }
                        .getOrNull() ?: return null
                Target(text, "fdclose://$fd", web = false)
            }

            "file" -> Target(text, uri.path ?: return null, web = false)

            else -> Target(text, text, PlayInput.isWeb(text))
        }
    }

    /** Remembers what played, if it can be played again: not a file shared for this once. */
    private fun remember(target: Target, title: String?) {
        val uri = target.uri.toUri()
        val again =
            uri.scheme != "content" ||
                contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        if (!again) return
        lifecycleScope.launch {
            container.settings.addHistory(target.uri, title?.takeIf { it != target.uri })
        }
    }

    private fun setPaused(paused: Boolean) {
        if (initialized) MPVLib.setPropertyBoolean("pause", paused)
    }

    private fun failed() {
        loading = false
        state.phase = Phase.Failed(errors.joinToString("\n").ifEmpty { null })
    }

    private fun followVideoOrientation() {
        if (orientationSet || state.inPictureInPicture) return
        val aspect = state.aspect ?: return
        orientationSet = true
        requestedOrientation =
            if (aspect >= 1) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
    }

    // mpv's events come on its own thread.

    override fun eventProperty(property: String) {
        runOnUiThread {
            when (property) {
                "time-pos" -> state.position = 0.0
                "duration" -> state.duration = 0.0
                "demuxer-cache-time" -> state.cacheTime = 0.0
            }
        }
    }

    override fun eventProperty(property: String, value: Long) {
        runOnUiThread {
            when (property) {
                "video-params/w" -> state.videoWidth = value.toInt()
                "video-params/h" -> state.videoHeight = value.toInt()
                "video-params/rotate" -> state.videoRotate = value.toInt()
            }
            followVideoOrientation()
            updatePictureInPicture()
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        runOnUiThread {
            when (property) {
                "pause" -> {
                    state.paused = value
                    if (!value) audioManager.requestAudioFocus(focusRequest)
                    updatePictureInPicture()
                }
                "paused-for-cache" -> state.buffering = value
                "seeking" -> state.seeking = value
                "idle-active" -> if (value && loading) failed()
            }
        }
    }

    override fun eventProperty(property: String, value: String) {
        if (property == YtdlRunner.REQUEST_PROPERTY) {
            if (value.isNotEmpty()) ytdl?.request(value)
            return
        }
        runOnUiThread { if (property == "media-title") state.title = value }
    }

    override fun eventProperty(property: String, value: Double) {
        runOnUiThread {
            when (property) {
                "time-pos" -> state.position = value
                "duration" -> state.duration = value
                "demuxer-cache-time" -> state.cacheTime = value
                "speed" -> state.speed = value
            }
        }
    }

    override fun eventProperty(property: String, value: MPVNode) {}

    override fun event(eventId: Int, data: MPVNode) {
        runOnUiThread {
            when (eventId) {
                MpvEvent.MPV_EVENT_START_FILE -> {
                    loading = true
                    errors.clear()
                    state.phase = Phase.Loading(target?.web == true)
                }

                MpvEvent.MPV_EVENT_FILE_LOADED -> {
                    loading = false
                    state.phase = Phase.Playing
                    target?.let { remember(it, MPVLib.getPropertyString("media-title")) }
                }

                MpvEvent.MPV_EVENT_SHUTDOWN -> finish()
            }
        }
    }

    override fun logMessage(prefix: String, level: Int, text: String) {
        if (level > MpvLogLevel.MPV_LOG_LEVEL_ERROR) return
        val line =
            text.trim().ifEmpty {
                return
            }
        runOnUiThread {
            errors.addLast("[$prefix] $line")
            while (errors.size > MAX_ERRORS) errors.removeFirst()
        }
    }

    // Picture-in-picture

    private fun pictureInPictureParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        state.aspect?.let { aspect ->
            // Android takes 1:2.39 to 2.39:1 and throws on anything else.
            val clamped = aspect.coerceIn(1 / MAX_PIP_ASPECT, MAX_PIP_ASPECT)
            builder.setAspectRatio(Rational((clamped * 10_000).toInt(), 10_000))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(state.phase == Phase.Playing && !state.paused)
        }
        return builder.build()
    }

    private fun updatePictureInPicture() {
        if (supportsPictureInPicture) {
            runCatching { setPictureInPictureParams(pictureInPictureParams()) }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Before Android 12, picture-in-picture has to be entered by hand on the way out.
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
                supportsPictureInPicture &&
                state.phase == Phase.Playing &&
                !state.paused
        ) {
            controller.enterPictureInPicture()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        state.inPictureInPicture = isInPictureInPictureMode
    }

    override fun onStop() {
        super.onStop()
        // Out of sight, and not in a window of its own: nobody is watching.
        if (!isInPictureInPictureMode) setPaused(true)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(noisyReceiver) }
        audioManager.abandonAudioFocusRequest(focusRequest)
        ytdl?.cancelAll()
        if (initialized) {
            MPVLib.removeObserver(this)
            MPVLib.removeLogObserver(this)
            player.destroy()
            initialized = false
        }
        super.onDestroy()
    }

    private companion object {
        const val MAX_ERRORS = 8
        const val MAX_PIP_ASPECT = 2.39
    }
}
