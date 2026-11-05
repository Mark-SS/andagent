package com.dc.andagent.live

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LiveLoopRunnerTest {
    @Test fun pauseAndResumeChangeTickFlow() {
        val calls = AtomicInteger()
        val runner = LiveLoopRunner({ _, result -> calls.incrementAndGet(); result("42"); "null" },
            { _, _, _ -> }, {})
        try {
            val id = runner.start("pause", "live.result(42)", 200, "dialog")["id"]!!.jsonPrimitive.content
            Thread.sleep(260)
            assertEquals("paused", runner.pause(id, true)["state"]!!.jsonPrimitive.content)
            val before = calls.get()
            Thread.sleep(420)
            assertEquals(before, calls.get())
            assertEquals("running", runner.pause(id, false)["state"]!!.jsonPrimitive.content)
            Thread.sleep(260)
            assertTrue(calls.get() > before)
        } finally { runner.shutdown() }
    }

    @Test fun stopWaitsForCurrentTickAndPreventsNextTick() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val runner = LiveLoopRunner({ _, result ->
            calls.incrementAndGet(); entered.countDown(); release.await(2, TimeUnit.SECONDS)
            result("1"); "null"
        }, { _, _, _ -> }, {})
        try {
            val id = runner.start("stop", "live.result(1)", 200, "dialog")["id"]!!.jsonPrimitive.content
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals("stopped", runner.stop(id)["state"]!!.jsonPrimitive.content)
            release.countDown()
            Thread.sleep(450)
            assertEquals(1, calls.get())
        } finally { release.countDown(); runner.shutdown() }
    }

    @Test fun fiveConsecutiveFailuresAutoStop() {
        val calls = AtomicInteger()
        val notice = CountDownLatch(1)
        val runner = LiveLoopRunner({ _, _ -> calls.incrementAndGet(); "ERROR: failed" },
            { _, _, _ -> }, { notice.countDown() })
        try {
            val id = runner.start("fail", "throw 1", 200, "dialog")["id"]!!.jsonPrimitive.content
            assertTrue(notice.await(3, TimeUnit.SECONDS))
            val state = runner.list()["loops"].toString()
            assertTrue(state.contains(id))
            assertTrue(state.contains("stopped"))
            assertEquals(5, calls.get())
        } finally { runner.shutdown() }
    }
}
