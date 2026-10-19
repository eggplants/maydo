package io.github.eggplants.maydo

import io.github.eggplants.maydo.player.TrackType
import io.github.eggplants.maydo.player.Tracks
import io.github.eggplants.maydo.player.formatTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TracksTest {
    @Test
    fun parsesMpvsTrackList() {
        val tracks =
            Tracks.parse(
                """
                [
                  {"id":1,"type":"video","selected":true,"codec":"vp9","demux-w":1920,"demux-h":1080},
                  {"id":1,"type":"audio","selected":true,"lang":"ja","title":"Japanese"},
                  {"id":2,"type":"audio","selected":false,"lang":"en"},
                  {"id":1,"type":"sub","selected":false,"lang":"en","external":true},
                  {"id":3,"type":"unknown"}
                ]
                """,
            )
        assertEquals(4, tracks.size)
        val video = tracks.first { it.type == TrackType.VIDEO }
        assertEquals("1920×1080 · vp9", video.label)
        assertEquals("Japanese · ja", tracks.first { it.type == TrackType.AUDIO }.label)
        assertTrue(tracks.first { it.type == TrackType.AUDIO }.selected)
    }

    @Test
    fun badJsonIsNoTracks() {
        assertTrue(Tracks.parse(null).isEmpty())
        assertTrue(Tracks.parse("not json").isEmpty())
    }

    @Test
    fun times() {
        assertEquals("0:00", formatTime(-1.0))
        assertEquals("1:05", formatTime(65.9))
        assertEquals("1:01:01", formatTime(3661.0))
    }
}
