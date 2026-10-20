package ch.etasystems.amsel.ui.compare

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatPlaybackPositionTest {

    @Test
    fun `Position wird als m ss mmm formatiert`() {
        assertEquals("0:00.000", formatPlaybackPosition(0f))
        assertEquals("0:05.250", formatPlaybackPosition(5.25f))
        assertEquals("1:01.500", formatPlaybackPosition(61.5f))
        assertEquals("12:00.000", formatPlaybackPosition(720f))
    }
}
