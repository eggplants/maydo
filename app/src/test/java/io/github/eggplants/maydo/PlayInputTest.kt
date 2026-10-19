package io.github.eggplants.maydo

import io.github.eggplants.maydo.core.PlayInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayInputTest {
    @Test
    fun urlsStayAsTheyAre() {
        assertEquals(
            "https://www.youtube.com/watch?v=abc",
            PlayInput.toUri("  https://www.youtube.com/watch?v=abc "),
        )
        assertEquals("rtsp://cam.local/stream", PlayInput.toUri("rtsp://cam.local/stream"))
    }

    @Test
    fun aUrlIsFoundInText() {
        assertEquals("https://youtu.be/abc", PlayInput.toUri("見て https://youtu.be/abc より"))
    }

    @Test
    fun bareHostsGetHttps() {
        assertEquals("https://youtu.be/abc", PlayInput.toUri("youtu.be/abc"))
        assertEquals("https://example.com", PlayInput.toUri("example.com"))
    }

    @Test
    fun anythingElseIsASearch() {
        assertEquals("ytdl://ytsearch:lofi hip hop", PlayInput.toUri("lofi hip hop"))
        assertEquals("ytdl://ytsearch:猫", PlayInput.toUri("猫"))
        assertNull(PlayInput.toUri("   "))
    }

    @Test
    fun webUris() {
        assertTrue(PlayInput.isWeb("https://example.com/a"))
        assertTrue(PlayInput.isWeb("ytdl://ytsearch:x"))
        assertFalse(PlayInput.isWeb("content://media/external/video/1"))
        assertFalse(PlayInput.isWeb("rtsp://cam.local/stream"))
    }
}
