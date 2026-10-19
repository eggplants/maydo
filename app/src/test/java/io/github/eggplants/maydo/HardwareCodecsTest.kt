package io.github.eggplants.maydo

import io.github.eggplants.maydo.player.HardwareCodecs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HardwareCodecsTest {
    @Test
    fun theBestHardwareCodecCapsYtdlp() {
        assertNull(HardwareCodecs.formatSort(setOf("video/av01", "video/avc")))
        assertEquals(
            "vcodec:vp9",
            HardwareCodecs.formatSort(setOf("video/x-vnd.on2.vp9", "video/hevc", "video/avc")),
        )
        assertEquals("vcodec:h265", HardwareCodecs.formatSort(setOf("video/hevc", "video/avc")))
        assertEquals("vcodec:h264", HardwareCodecs.formatSort(setOf("video/avc")))
        assertNull(HardwareCodecs.formatSort(emptySet()))
    }
}
