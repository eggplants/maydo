package io.github.eggplants.maydo.player

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

enum class TrackType(val property: String) {
    VIDEO("vid"),
    AUDIO("aid"),
    SUB("sid"),
}

/** One entry of mpv's `track-list`. */
data class Track(
    val id: Int,
    val type: TrackType,
    val title: String?,
    val lang: String?,
    val codec: String?,
    val width: Int?,
    val height: Int?,
    val selected: Boolean,
) {
    /** What the track menu shows: the title, else what is known about it. */
    val label: String
        get() =
            listOfNotNull(
                    title,
                    lang,
                    height?.let { "${width ?: "?"}×$it" }.takeIf { title == null },
                    codec.takeIf { title == null },
                )
                .distinct()
                .joinToString(" · ")
                .ifEmpty { "#$id" }
}

object Tracks {
    /** mpv's `track-list` property as JSON, which mpv gives for it as a string. */
    fun parse(json: String?): List<Track> {
        if (json.isNullOrBlank()) return emptyList()
        val array = runCatching { Json.parseToJsonElement(json).jsonArray }.getOrNull()
        return array.orEmpty().mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            fun str(key: String) = o[key]?.jsonPrimitive?.contentOrNull
            fun int(key: String) = o[key]?.jsonPrimitive?.intOrNull
            val type =
                when (str("type")) {
                    "video" -> TrackType.VIDEO
                    "audio" -> TrackType.AUDIO
                    "sub" -> TrackType.SUB
                    else -> return@mapNotNull null
                }
            Track(
                id = int("id") ?: return@mapNotNull null,
                type = type,
                title = str("title")?.ifBlank { null },
                lang = str("lang")?.ifBlank { null },
                codec = str("codec")?.ifBlank { null },
                width = int("demux-w"),
                height = int("demux-h"),
                selected = o["selected"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
    }
}
