package io.github.eggplants.maydo.player

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

/**
 * Which video codecs the device decodes in hardware, so that yt-dlp does not pick one that only
 * software decodes: YouTube's AV1 at 1080p is too much for many phones' CPUs.
 */
object HardwareCodecs {
    /** yt-dlp's video codecs, best first, as `--format-sort vcodec:` takes them, by MIME type. */
    private val PREFERENCE =
        listOf(
            "video/av01" to "av01",
            "video/x-vnd.on2.vp9" to "vp9",
            "video/hevc" to "h265",
            "video/avc" to "h264",
        )

    /** The MIME types of the device's hardware video decoders. */
    fun decoderTypes(): Set<String> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS)
            .codecInfos
            .filter { !it.isEncoder && isHardware(it.name, it) }
            .flatMap { it.supportedTypes.map(String::lowercase) }
            .toSet()

    /**
     * yt-dlp's `--format-sort` for a device that decodes [hardwareTypes] in hardware: the best
     * codec among them as the upper bound, or null when that is AV1 already, the best there is.
     */
    fun formatSort(hardwareTypes: Set<String>): String? {
        val best = PREFERENCE.firstOrNull { (type, _) -> type in hardwareTypes } ?: return null
        return if (best.second == "av01") null else "vcodec:${best.second}"
    }

    private fun isHardware(name: String, info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isHardwareAccelerated
        } else {
            // Android's own software codecs, before it said which are which.
            val lower = name.lowercase()
            !lower.startsWith("omx.google.") && !lower.startsWith("c2.android.")
        }
}
