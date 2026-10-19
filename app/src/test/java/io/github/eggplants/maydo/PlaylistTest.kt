package io.github.eggplants.maydo

import io.github.eggplants.maydo.core.FolderEntry
import io.github.eggplants.maydo.core.FolderPlaylist
import io.github.eggplants.maydo.core.NaturalOrder
import io.github.eggplants.maydo.player.Playlist
import io.github.eggplants.maydo.player.fileLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistTest {
    @Test
    fun naturalOrderCountsNumbers() {
        val names = listOf("ep10.mkv", "Ep2.mkv", "ep1.mkv", "b/1.mp4", "a/10.mp4", "a/9.mp4")
        assertEquals(
            listOf("a/9.mp4", "a/10.mp4", "b/1.mp4", "ep1.mkv", "Ep2.mkv", "ep10.mkv"),
            names.sortedWith(NaturalOrder),
        )
        assertTrue(
            NaturalOrder.compare("x99999999999999999999.mp4", "x100000000000000000000.mp4") < 0,
        )
    }

    @Test
    fun mediaByTypeOrExtension() {
        assertTrue(FolderPlaylist.isMedia("video/mp4", "a.mp4"))
        assertTrue(FolderPlaylist.isMedia("audio/flac", "a.flac"))
        assertTrue(FolderPlaylist.isMedia("application/octet-stream", "a.MKV"))
        assertFalse(FolderPlaylist.isMedia("image/jpeg", "cover.jpg"))
        assertFalse(FolderPlaylist.isMedia("text/plain", "notes.txt"))
        assertFalse(FolderPlaylist.isMedia("audio/x-mpegurl", "list.m3u8"))
        assertFalse(FolderPlaylist.isMedia("application/octet-stream", "list.m3u"))
    }

    @Test
    fun folderAsAnM3uInMemory() {
        val m3u =
            FolderPlaylist.m3u(
                FolderPlaylist.sorted(
                    listOf(
                        FolderEntry("content://x/document/2", "s/10.mp4"),
                        FolderEntry("content://x/document/1", "s/9.mp4"),
                    ),
                ),
            )
        assertEquals(
            "memory://#EXTM3U\n#EXTINF:0,9.mp4\ncontent://x/document/1\n" +
                "#EXTINF:0,10.mp4\ncontent://x/document/2\n",
            m3u,
        )
    }

    @Test
    fun parsesMpvsPlaylist() {
        val entries =
            Playlist.parse(
                """
                [
                  {"filename":"ytdl://abc","title":"First","id":1},
                  {"filename":"content://com.android.externalstorage.documents/tree/primary%3AMovies/document/primary%3AMovies%2F%E5%8B%95%E7%94%BB.mp4","current":true,"playing":true,"id":2}
                ]
                """,
            )
        assertEquals(listOf("First", "動画.mp4"), entries.map { it.label })
        assertEquals(listOf(false, true), entries.map { it.current })
        assertTrue(Playlist.parse("nope").isEmpty())
    }

    @Test
    fun fileLabels() {
        assertEquals("a b.mkv", fileLabel("/sdcard/Movies/a%20b.mkv"))
        assertEquals(
            "Movies",
            fileLabel("content://com.android.externalstorage.documents/tree/primary%3AMovies"),
        )
        assertEquals("watch?v=x", fileLabel("https://www.youtube.com/watch?v=x"))
    }
}
