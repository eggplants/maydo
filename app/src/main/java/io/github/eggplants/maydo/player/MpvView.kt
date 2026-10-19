package io.github.eggplants.maydo.player

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceHolder
import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVLib.MpvFormat

/** What [MpvView] sets up mpv with; see [MpvView.initOptions]. */
data class PlayerOptions(
    val hardwareDecoding: Boolean,
    val ytdlFormat: String,
    val ytdlFirst: Boolean,
    /** native/ytdlp.c, for ytdl_hook's `ytdl_path`; YtdlRunner runs nothing else. */
    val ytdlPath: String,
    /** QuickJS, for yt-dlp's YouTube challenges. */
    val quickJsPath: String,
    val caFile: String,
    /** `assets/ytdl_hook.lua`, unpacked. */
    val ytdlHook: String,
    /** yt-dlp's `--yes-playlist`: a video link with a playlist plays all of it. */
    val wholePlaylists: Boolean,
    /** yt-dlp's `--format-sort`, see [HardwareCodecs.formatSort]; null for its own order. */
    val formatSort: String?,
)

/**
 * libmpv's surface. Inflated from `activity_player.xml`, since [BaseMPVView] wants an
 * [AttributeSet]; [options] has to be set before [initialize].
 */
class MpvView(context: Context, attrs: AttributeSet) : BaseMPVView(context, attrs) {
    lateinit var options: PlayerOptions

    override fun initOptions() {
        setVo("gpu")
        MPVLib.setOptionString("gpu-context", "android")
        MPVLib.setOptionString("opengl-es", "yes")
        MPVLib.setOptionString(
            "hwdec",
            if (options.hardwareDecoding) "mediacodec,mediacodec-copy,no" else "no",
        )
        MPVLib.setOptionString("hwdec-codecs", "all")
        MPVLib.setOptionString("ao", "audiotrack,opensles")
        MPVLib.setOptionString("tls-verify", "yes")
        MPVLib.setOptionString("tls-ca-file", options.caFile)
        MPVLib.setOptionString("input-default-bindings", "yes")
        // A phone's memory, not a desktop's.
        MPVLib.setOptionString("demuxer-max-bytes", "64MiB")
        MPVLib.setOptionString("demuxer-max-back-bytes", "32MiB")
        // Stay on the last frame instead of going idle, which Maydo takes for a failure.
        MPVLib.setOptionString("keep-open", "yes")

        // mpv's own ytdl_hook would start yt-dlp itself, which it cannot here; Maydo's copy has
        // the app do it (YtdlRunner). The script name stays ytdl_hook, and so its script-opts.
        MPVLib.setOptionString("ytdl", "no")
        MPVLib.setOptionString("scripts", options.ytdlHook)
        MPVLib.setOptionString("ytdl-format", options.ytdlFormat)
        // ytdl_hook reads these. Each value is quoted with mpv's %length% syntax, since the
        // app's paths may hold '=' and ','; the -append forms do not work before mpv starts.
        MPVLib.setOptionString(
            "script-opts",
            keyValues(
                "ytdl_hook-ytdl_path" to options.ytdlPath,
                "ytdl_hook-try_ytdl_first" to if (options.ytdlFirst) "yes" else "no",
                // One video and one audio stream, not every format as a track: less to fetch.
                "ytdl_hook-all_formats" to "no",
            ),
        )
        MPVLib.setOptionString(
            "ytdl-raw-options",
            keyValues(
                *listOfNotNull(
                        "js-runtimes" to "quickjs:${options.quickJsPath}",
                        options.formatSort?.let { "format-sort" to it },
                        ("yes-playlist" to "").takeIf { options.wholePlaylists },
                    )
                    .toTypedArray(),
            ),
        )
    }

    override fun postInitOptions() {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        super.surfaceChanged(holder, format, width, height)
        // Back from the background, after the screen went off, or turned: a paused video's
        // frame does not survive a new or resized surface (with mediacodec it lived in the old
        // one), so decode it again. Android calls this after every surfaceCreated as well.
        if (
            MPVLib.getPropertyBoolean("pause") == true &&
                MPVLib.getPropertyBoolean("idle-active") == false
        ) {
            MPVLib.command("seek", "0", "relative+exact")
        }
    }

    override fun observeProperties() {
        for ((name, format) in OBSERVED) MPVLib.observeProperty(name, format)
    }

    companion object {
        /** An mpv key/value list option, each value taken as it is. */
        fun keyValues(vararg pairs: Pair<String, String>): String =
            pairs.joinToString(",") { (key, value) ->
                "$key=%${value.toByteArray().size}%$value"
            }

        val OBSERVED =
            mapOf(
                "playlist-pos" to MpvFormat.MPV_FORMAT_INT64,
                "playlist-count" to MpvFormat.MPV_FORMAT_INT64,
                "time-pos" to MpvFormat.MPV_FORMAT_DOUBLE,
                "duration" to MpvFormat.MPV_FORMAT_DOUBLE,
                "demuxer-cache-time" to MpvFormat.MPV_FORMAT_DOUBLE,
                "pause" to MpvFormat.MPV_FORMAT_FLAG,
                "paused-for-cache" to MpvFormat.MPV_FORMAT_FLAG,
                "seeking" to MpvFormat.MPV_FORMAT_FLAG,
                "idle-active" to MpvFormat.MPV_FORMAT_FLAG,
                "eof-reached" to MpvFormat.MPV_FORMAT_FLAG,
                "media-title" to MpvFormat.MPV_FORMAT_STRING,
                "speed" to MpvFormat.MPV_FORMAT_DOUBLE,
                "video-params/w" to MpvFormat.MPV_FORMAT_INT64,
                "video-params/h" to MpvFormat.MPV_FORMAT_INT64,
                "video-params/rotate" to MpvFormat.MPV_FORMAT_INT64,
                YtdlRunner.REQUEST_PROPERTY to MpvFormat.MPV_FORMAT_STRING,
            )
    }
}
