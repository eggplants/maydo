package io.github.eggplants.maydo

import io.github.eggplants.maydo.core.SharedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedTextTest {
    @Test
    fun findsTheLink() {
        assertEquals("https://youtu.be/abc", SharedText.url("Title\nhttps://youtu.be/abc"))
        assertEquals(
            "https://x.com/a/status/1",
            SharedText.url("\"quote\" https://x.com/a/status/1."),
        )
        assertEquals("https://nico.ms/sm9", SharedText.url("https://nico.ms/sm9）より"))
        assertNull(SharedText.url("no link here"))
    }
}
