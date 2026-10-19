package io.github.eggplants.maydo.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

/** A video or audio file in a folder picked with the system's folder picker. */
data class FolderEntry(val uri: String, val path: String) {
    val name: String
        get() = path.substringAfterLast('/')
}

/**
 * A folder as a playlist, like `mpv <directory>` on a desktop: its video and audio files and those
 * of its subfolders, in natural order by path. The files stay content URIs, which mpv opens through
 * FFmpeg's `android_content` protocol, so nothing is opened before it plays.
 */
object FolderPlaylist {
    /** Subfolders deeper than this are left out, against a picked storage root. */
    private const val MAX_DEPTH = 8

    private val MEDIA_EXTENSIONS =
        setOf(
            "3gp",
            "aac",
            "avi",
            "flac",
            "flv",
            "m2ts",
            "m4a",
            "m4v",
            "mka",
            "mkv",
            "mov",
            "mp3",
            "mp4",
            "mpeg",
            "mpg",
            "mts",
            "oga",
            "ogg",
            "ogv",
            "opus",
            "ts",
            "wav",
            "webm",
            "wma",
            "wmv",
        )

    /** Lists [tree], a URI from the folder picker; throws when it cannot be read. */
    fun list(context: Context, tree: Uri): List<FolderEntry> {
        val resolver = context.contentResolver
        val entries = mutableListOf<FolderEntry>()
        val columns =
            arrayOf(
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE,
            )

        fun walk(documentId: String, prefix: String, depth: Int) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
            resolver.query(children, columns, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2).orEmpty()
                    if (name.startsWith(".")) continue
                    when {
                        mime == Document.MIME_TYPE_DIR ->
                            if (depth < MAX_DEPTH) walk(id, "$prefix$name/", depth + 1)

                        isMedia(mime, name) ->
                            entries +=
                                FolderEntry(
                                    DocumentsContract.buildDocumentUriUsingTree(tree, id)
                                        .toString(),
                                    "$prefix$name",
                                )
                    }
                }
            }
        }

        walk(DocumentsContract.getTreeDocumentId(tree), "", 0)
        return sorted(entries)
    }

    /** The folder's name, for the history. */
    fun name(context: Context, tree: Uri): String? = runCatching {
        val document =
            DocumentsContract.buildDocumentUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree),
            )
        context.contentResolver
            .query(document, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }
        .getOrNull()

    fun sorted(entries: List<FolderEntry>): List<FolderEntry> =
        entries.sortedWith(compareBy(NaturalOrder) { it.path })

    /** Playlist files, which a folder's playlist leaves out as `mpv <directory>` does. */
    private val PLAYLIST_EXTENSIONS = setOf("m3u", "m3u8", "pls", "cue")

    /**
     * Video or audio by its type, or by its extension when the provider does not know it; not a
     * playlist file, which is typed as audio (`audio/x-mpegurl`).
     */
    fun isMedia(mime: String, name: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension in PLAYLIST_EXTENSIONS || "mpegurl" in mime) return false
        return mime.startsWith("video/") ||
            mime.startsWith("audio/") ||
            extension in MEDIA_EXTENSIONS
    }

    /** [entries] as a playlist mpv loads from memory, each with its file name as its title. */
    fun m3u(entries: List<FolderEntry>): String = buildString {
        append("memory://#EXTM3U\n")
        for (entry in entries) {
            append("#EXTINF:0,").append(entry.name.replace(Regex("[\r\n]"), " ")).append('\n')
            append(entry.uri).append('\n')
        }
    }
}

/** Orders names as people count: `2.mp4` before `10.mp4`, letters regardless of case. */
object NaturalOrder : Comparator<String> {
    private val CHUNK = Regex("""\d+|\D+""")

    override fun compare(a: String, b: String): Int {
        val x = CHUNK.findAll(a).map { it.value }.toList()
        val y = CHUNK.findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]
            val q = y[i]
            val c =
                if (p[0].isDigit() && q[0].isDigit()) {
                    val n = p.trimStart('0')
                    val m = q.trimStart('0')
                    // Longer is larger, then digit by digit: no overflow on long numbers.
                    n.length.compareTo(m.length).takeIf { it != 0 } ?: n.compareTo(m)
                } else {
                    p.compareTo(q, ignoreCase = true)
                }
            if (c != 0) return c
        }
        return x.size.compareTo(y.size).takeIf { it != 0 } ?: a.compareTo(b)
    }
}
