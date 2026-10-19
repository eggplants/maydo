package io.github.eggplants.maydo.player

import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** One entry of mpv's `playlist`. */
data class PlaylistEntry(val filename: String, val title: String?, val current: Boolean) {
    /** Its title (from yt-dlp or the playlist file), else the file's own name. */
    val label: String
        get() = title ?: fileLabel(filename)
}

object Playlist {
    /** mpv's `playlist` property as JSON, which mpv gives for it as a string. */
    fun parse(json: String?): List<PlaylistEntry> {
        if (json.isNullOrBlank()) return emptyList()
        val array = runCatching { Json.parseToJsonElement(json).jsonArray }.getOrNull()
        return array.orEmpty().mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            PlaylistEntry(
                filename = o["filename"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                title = o["title"]?.jsonPrimitive?.contentOrNull?.ifBlank { null },
                current = o["current"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
    }
}

/**
 * A file's name from its path or URI: the last segment, decoded, and for a content URI the end of
 * its document id (`primary:Movies/a.mp4` is `a.mp4`).
 */
fun fileLabel(filename: String): String {
    val last = filename.trimEnd('/').substringAfterLast('/')
    return percentDecode(last).substringAfterLast('/').substringAfterLast(':').ifEmpty { filename }
}

/** `%XX` escapes as UTF-8, leaving `+` and malformed escapes as they are. */
private fun percentDecode(text: String): String {
    val bytes = ByteArrayOutputStream()
    var i = 0
    while (i < text.length) {
        val hex = text.substring(i + 1, minOf(i + 3, text.length))
        if (text[i] == '%' && hex.length == 2 && hex.all { it.digitToIntOrNull(16) != null }) {
            bytes.write(hex.toInt(16))
            i += 3
        } else {
            bytes.write(text[i].toString().toByteArray())
            i++
        }
    }
    return bytes.toString(Charsets.UTF_8.name())
}
