package com.dc.andagent.tools

import android.content.pm.ActivityInfo
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class R41ToolsTest {
    @Test fun wavHeader() {
        val h = pcmWavHeader(32000)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44, h.size)
        assertEquals("RIFF", String(h, 0, 4))
        assertEquals(32036, b.getInt(4))
        assertEquals("WAVEfmt ", String(h, 8, 8))
        assertEquals(1, b.getShort(22).toInt())
        assertEquals(16000, b.getInt(24))
        assertEquals(32000, b.getInt(28))
        assertEquals(16, b.getShort(34).toInt())
        assertEquals(32000, b.getInt(40))
    }
    @Test fun orientationModes() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, orientationModeValue("portrait"))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, orientationModeValue("landscape"))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER, orientationModeValue("auto"))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, orientationModeValue("reverse_portrait"))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, orientationModeValue("reverse_landscape"))
        assertNull(orientationModeValue("invalid"))
    }
    @Test fun otgVolumeAndPath() {
        assertTrue(isMountedOtg("mounted", false, true))
        assertFalse(isMountedOtg("unmounted", false, true))
        assertFalse(isMountedOtg("mounted", true, false))
        assertEquals(listOf("foo", "bar"), otgPathParts("foo/bar"))
        assertNull(otgPathParts("../bad"))
        assertNull(otgPathParts("/absolute"))
        assertTrue(otgTreeMatchesVolume("ABCD-1234:", "ABCD-1234"))
        assertFalse(otgTreeMatchesVolume("primary:", "ABCD-1234"))
    }
}
