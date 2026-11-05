package com.dc.andagent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiCommandsTest {
    @Test
    fun parsesOrientationModes() {
        assertEquals(UiOrientation.PORTRAIT, parseOrientationMode(" portrait "))
        assertEquals(UiOrientation.LANDSCAPE, parseOrientationMode("Landscape"))
        assertEquals(UiOrientation.AUTO, parseOrientationMode("AUTO"))
        assertNull(parseOrientationMode("reverse_portrait"))
        assertNull(parseOrientationMode(""))
        assertNull(parseOrientationMode(null))
    }

    @Test
    fun parsesHapticPatternsAndAliases() {
        assertEquals(UiHaptic.CLICK, parseHapticPattern("click"))
        assertEquals(UiHaptic.CLICK, parseHapticPattern("tap"))
        assertEquals(UiHaptic.DOUBLE, parseHapticPattern("double_click"))
        assertEquals(UiHaptic.HEAVY, parseHapticPattern(" Strong "))
        assertNull(parseHapticPattern("buzz"))
        assertNull(parseHapticPattern(null))
    }

    @Test
    fun detectsKeyboardFromVisibleFrame() {
        // 2400px window, IME covers 900px -> visible.
        assertTrue(keyboardVisibleFromInset(2400, 1500))
        // 11% covered stays below the 15% threshold.
        assertFalse(keyboardVisibleFromInset(2400, 2136))
        // No measured window -> cannot claim the keyboard is visible.
        assertFalse(keyboardVisibleFromInset(0, 0))
    }

    @Test
    fun nullActivityReturnsErrorInsteadOfThrowing() {
        val commands = UiCommands(null)
        assertTrue(commands.toast("hi", false).startsWith("ERROR:"))
        assertTrue(commands.status().startsWith("ERROR:"))
        assertTrue(commands.screenMetrics().startsWith("ERROR:"))
        assertTrue(commands.reload().startsWith("ERROR:"))
    }
}
