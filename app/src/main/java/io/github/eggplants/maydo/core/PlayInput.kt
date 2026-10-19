package io.github.eggplants.maydo.core

/** What the user types or shares, as something mpv can open. */
object PlayInput {
    private val SCHEME = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*://\S""")
    private val HOST = Regex("""^[\w-]+(\.[\w-]+)+(:\d+)?(/\S*)?$""")

    /**
     * A URL as it is, the first URL in a text around one, `https://` before a bare host such as
     * `youtu.be/...`, and otherwise a search: yt-dlp plays its first YouTube result. Null when
     * [text] is blank.
     */
    fun toUri(text: String): String? {
        val t = text.trim()
        return when {
            t.isEmpty() -> null
            SCHEME.containsMatchIn(t) && t.none { it.isWhitespace() } -> t
            SharedText.url(t) != null -> SharedText.url(t)
            HOST.matches(t) -> "https://$t"
            else -> "ytdl://ytsearch:$t"
        }
    }

    /** Whether [uri] goes through yt-dlp: a web page, or ytdl_hook's own `ytdl://`. */
    fun isWeb(uri: String): Boolean =
        listOf("http://", "https://", "ytdl://").any { uri.startsWith(it, ignoreCase = true) }
}
