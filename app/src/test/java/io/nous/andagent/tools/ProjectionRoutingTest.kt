package io.nous.andagent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R28: pure-logic coverage for the MediaProjection capture path — routing
 * order (projection → shizuku → consent flow) and the single-slot recording
 * tracker. No Android framework classes touched.
 */
class ProjectionRoutingTest {

    @Test
    fun `projection ready wins regardless of shizuku`() {
        assertEquals(
            CaptureChannel.PROJECTION,
            ProjectionRouting.route(projectionReady = true, shizukuGranted = true),
        )
        assertEquals(
            CaptureChannel.PROJECTION,
            ProjectionRouting.route(projectionReady = true, shizukuGranted = false),
        )
    }

    @Test
    fun `shizuku is the fallback when projection not ready`() {
        assertEquals(
            CaptureChannel.SCRCPY,
            ProjectionRouting.route(projectionReady = false, shizukuGranted = true),
        )
    }

    @Test
    fun `need consent when neither channel available`() {
        assertEquals(
            CaptureChannel.NEED_CONSENT,
            ProjectionRouting.route(projectionReady = false, shizukuGranted = false),
        )
    }

    @Test
    fun `recording tracker allows exactly one active recording`() {
        val tracker = ProjectionRecordings()
        assertTrue(tracker.start("/sdcard/Movies/AndAgent/a.mp4", "content://x"))
        assertFalse(tracker.start("/sdcard/Movies/AndAgent/b.mp4", null))
        assertEquals("/sdcard/Movies/AndAgent/a.mp4", tracker.active()?.path)
    }

    @Test
    fun `recording tracker stop returns and clears the active entry`() {
        val tracker = ProjectionRecordings()
        tracker.start("/sdcard/Movies/AndAgent/a.mp4", "content://x")
        val stopped = tracker.stop()
        assertEquals("/sdcard/Movies/AndAgent/a.mp4", stopped?.path)
        assertEquals("content://x", stopped?.pendingUri)
        assertNull(tracker.active())
        assertNull(tracker.stop())
        // Slot is reusable after stop.
        assertTrue(tracker.start("/sdcard/Movies/AndAgent/c.mp4", null))
    }

    @Test
    fun `recording tracker start without pending uri`() {
        val tracker = ProjectionRecordings()
        assertTrue(tracker.start("/storage/emulated/0/Movies/AndAgent/p.mp4", null))
        assertNull(tracker.active()?.pendingUri)
    }
}
