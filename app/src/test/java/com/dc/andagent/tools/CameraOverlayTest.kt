package com.dc.andagent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CameraOverlayTest {
    @Test fun acceptsCameraAndBounds() {
        assertEquals("front", CameraOverlaySpec("front", 480, 360, 0, 80).camera)
        assertThrows(IllegalArgumentException::class.java) { CameraOverlaySpec("wide", 480, 360, 0, 80) }
        assertThrows(IllegalArgumentException::class.java) { CameraOverlaySpec("back", 159, 360, 0, 80) }
        assertThrows(IllegalArgumentException::class.java) { CameraOverlaySpec("back", 480, 1001, 0, 80) }
        assertThrows(IllegalArgumentException::class.java) { CameraOverlaySpec("back", 480, 360, 2001, 80) }
    }

    @Test fun cameraRequiresForegroundFirst() {
        val gate = CameraStartGate()
        assertThrows(IllegalStateException::class.java) { gate.requireForeground() }
        gate.foregroundStarted()
        gate.requireForeground()
    }

    @Test fun missingOverlayPermissionGuidesUser() {
        assertNull(overlayPermissionError(true))
        assertTrue(overlayPermissionError(false)!!.contains("open_settings(panel=overlay)"))
    }

    @Test fun rejectsMalformedNumericArgument() {
        val spec = CameraOverlaySpec("back", 480, 360, 0, 80)
        assertThrows(IllegalArgumentException::class.java) {
            spec.withArgs(buildJsonObject { put("width_dp", "large") })
        }
    }
}
