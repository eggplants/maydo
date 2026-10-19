package io.github.eggplants.maydo

import io.github.eggplants.maydo.core.Versions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {
    @Test
    fun ytdlpVersionsOrderByDate() {
        assertTrue(Versions.compareYtdlp("2026.08.19", "2026.08.20") < 0)
        assertTrue(Versions.compareYtdlp("2026.10.01", "2026.08.19") > 0)
        assertTrue(Versions.compareYtdlp("2026.08.19.1", "2026.08.19") > 0)
        assertEquals(0, Versions.compareYtdlp("2026.08.19", "2026.08.19"))
        assertEquals(0, Versions.compareYtdlp("2026.08.19", "2026.8.19.0"))
    }

    @Test
    fun appTags() {
        assertEquals("1.2.3" to 4, Versions.appTag("v1.2.3-4"))
        assertNull(Versions.appTag("v1.2.3"))
        assertNull(Versions.appTag("1.2.3-4"))
        assertNull(Versions.appTag("v1.2.3-4-rc"))
    }

    @Test
    fun pickApkPrefersTheDevicesAbi() {
        val names =
            listOf(
                "maydo-1.0.0-arm64-v8a.apk",
                "maydo-1.0.0-armeabi-v7a.apk",
                "maydo-1.0.0-universal.apk",
                "maydo-1.0.0-x86_64.apk",
                "maydo-1.0.0-arm64-v8a.apk.sha256",
            )
        assertEquals(
            "maydo-1.0.0-arm64-v8a.apk",
            Versions.pickApk(names, listOf("arm64-v8a", "armeabi-v7a", "armeabi")),
        )
        assertEquals(
            "maydo-1.0.0-armeabi-v7a.apk",
            Versions.pickApk(names, listOf("armeabi-v7a", "armeabi")),
        )
        assertEquals("maydo-1.0.0-universal.apk", Versions.pickApk(names, listOf("riscv64")))
        assertEquals("other.apk", Versions.pickApk(listOf("other.apk"), listOf("x86")))
        assertNull(Versions.pickApk(listOf("notes.txt"), listOf("x86")))
    }
}
