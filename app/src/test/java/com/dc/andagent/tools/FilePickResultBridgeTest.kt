package com.dc.andagent.tools

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FilePickResultBridgeTest {
    private val bridge = FilePickResultBridge

    @After fun tearDown() {
        bridge.launcher = null
    }

    @Test fun injectedLauncherReportsSelectedUrisBeforeWakingTool() {
        val id = "bridge_single"
        val latch = CountDownLatch(1)
        bridge.launcher = { requestId, mime, multiple, tree ->
            assertEquals(id, requestId)
            assertEquals("application/pdf", mime)
            assertFalse(multiple)
            assertFalse(tree)
            bridge.reportNext(FilePickResultBridge.Kind.SINGLE, listOf("content://picked/document"))
        }
        assertTrue(bridge.begin(id, FilePickResultBridge.Kind.SINGLE, latch))
        bridge.launcher!!.invoke(id, "application/pdf", false, false)
        assertTrue(latch.await(1, TimeUnit.SECONDS))
        assertEquals(listOf("content://picked/document"), bridge.results[id])
        bridge.finish(id)
    }

    @Test fun secondRequestRejectedAndEmptyResultMeansRealCancellation() {
        bridge.launcher = { _, _, _, _ -> }
        val first = CountDownLatch(1)
        assertTrue(bridge.begin("bridge_multi", FilePickResultBridge.Kind.MULTIPLE, first))
        assertFalse(bridge.begin("bridge_second", FilePickResultBridge.Kind.MULTIPLE, CountDownLatch(1)))
        bridge.reportNext(FilePickResultBridge.Kind.MULTIPLE, emptyList())
        assertTrue(first.await(1, TimeUnit.SECONDS))
        assertEquals(emptyList<String>(), bridge.results["bridge_multi"])
        bridge.finish("bridge_multi")
    }

    @Test fun lateCallbackDoesNotCompleteNewerRequest() {
        bridge.launcher = { _, _, _, _ -> }
        assertTrue(bridge.begin("bridge_expired", FilePickResultBridge.Kind.TREE, CountDownLatch(1)))
        bridge.finish("bridge_expired")
        val current = CountDownLatch(1)
        assertTrue(bridge.begin("bridge_current", FilePickResultBridge.Kind.TREE, current))
        bridge.reportNext(FilePickResultBridge.Kind.TREE, listOf("content://old"))
        assertFalse(current.await(10, TimeUnit.MILLISECONDS))
        bridge.reportNext(FilePickResultBridge.Kind.TREE, listOf("content://new"))
        assertTrue(current.await(1, TimeUnit.SECONDS))
        assertEquals(listOf("content://new"), bridge.results["bridge_current"])
        bridge.finish("bridge_current")
    }
}
