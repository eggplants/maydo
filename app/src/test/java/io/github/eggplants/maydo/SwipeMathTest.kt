package io.github.eggplants.maydo

import io.github.eggplants.maydo.player.SwipeMath
import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeMathTest {
    @Test
    fun seekingAcrossTheWidth() {
        // A long video: the whole width is two minutes.
        assertEquals(160.0, SwipeMath.seekTarget(100.0, 600.0, 0.5f), 1e-6)
        assertEquals(40.0, SwipeMath.seekTarget(100.0, 600.0, -0.5f), 1e-6)
        // Never before the start or after the end.
        assertEquals(0.0, SwipeMath.seekTarget(10.0, 600.0, -1f), 1e-6)
        assertEquals(600.0, SwipeMath.seekTarget(590.0, 600.0, 1f), 1e-6)
        // A short clip: the whole width is the whole clip.
        assertEquals(15.0, SwipeMath.seekTarget(5.0, 20.0, 0.5f), 1e-6)
    }

    @Test
    fun levelsUpAndDown() {
        // Up is a negative y on screen.
        assertEquals(0.8f, SwipeMath.level(0.5f, -0.18f), 1e-4f)
        assertEquals(0.2f, SwipeMath.level(0.5f, 0.18f), 1e-4f)
        assertEquals(1f, SwipeMath.level(0.5f, -1f), 1e-4f)
        assertEquals(0f, SwipeMath.level(0.5f, 1f), 1e-4f)
        assertEquals(8, SwipeMath.steps(0.5f, 15))
        assertEquals(15, SwipeMath.steps(1.2f, 15))
    }

    @Test
    fun brightnessSettingsBeyondAosp() {
        assertEquals(0.5f, SwipeMath.brightnessFraction(128, 255), 0.01f)
        assertEquals(1f, SwipeMath.brightnessFraction(255, 255), 1e-4f)
        // OPPO's 0..4095 setting, with the AOSP 255 in its resources.
        assertEquals(3118f / 4095, SwipeMath.brightnessFraction(3118, 255), 1e-4f)
        assertEquals(0.5f, SwipeMath.brightnessFraction(1024, 2047), 0.01f)
        assertEquals(0f, SwipeMath.brightnessFraction(0, null), 1e-4f)
    }
}
