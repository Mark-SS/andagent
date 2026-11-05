package com.dc.andagent.bridge

import android.app.Activity
import android.os.Looper
import android.view.WindowManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * R20/R58: the CAS race in [UiCommands.onMain] has three outcomes that the
 * pure-function tests in [UiCommandsTest] cannot reach:
 *
 *  1. the caller's 2s wait expires while the task is still queued -> cancel it,
 *     no side effects;
 *  2. main started the task before that deadline -> wait 3s more and return
 *     the task's real result, never a misleading timeout;
 *  3. main is still running the task after 2s + 3s -> report the explicit
 *     "side effects may have happened" error instead of a plain timeout.
 *
 * Robolectric runs the test body on the main thread, so the test body *is* the
 * main looper: `idle()` executes posted tasks inline. That is why every
 * [UiCommands] call under test happens on its own background thread — the exact
 * position the JavaBridge occupies in production. `pause()` keeps posted tasks
 * queued until the test body releases them, which is the lever that places the
 * 2s deadline on either side of "started".
 *
 * The only sleeps are inside the deliberately slow blocks under test (a slow
 * UI command), never a sleep used to "wait for" a race: ordering comes from
 * the latch and the paused looper, and each test asserts the caller returned
 * within a bounded window.
 */
@RunWith(RobolectricTestRunner::class)
class UiCommandsRaceTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var activity: Activity

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        activity = controller.get()
        // Paused main looper: posted tasks only run when the test body idles.
        shadowOf(Looper.getMainLooper()).pause()
        // Drain anything `setup()` left queued so the next `idle()` can only be
        // running the task this test posts.
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tearDown() {
        // Drain whatever the test left queued so it cannot leak into the next test.
        shadowOf(Looper.getMainLooper()).idle()
        controller.pause().stop().destroy()
    }

    /**
     * Cancelled path: the caller times out while the task is still queued, so
     * CAS(0->2) wins, removeCallbacks drops the task, and the block's side
     * effects must never reach the window.
     */
    @Test
    fun cancelledPath_reportsTimeoutAndSkipsSideEffects() {
        val commands = UiCommands(activity)
        var result: String? = null
        var returnedAt = 0L
        val caller = Thread {
            result = commands.keepScreenOn(true)
            returnedAt = System.nanoTime()
        }

        val startedAt = System.nanoTime()
        caller.start()
        // Deliberately no idle() here: the task stays queued for the whole 2s
        // deadline, so the timeout thread is the one that wins the CAS.
        caller.join(10_000)
        val elapsedMs = (returnedAt - startedAt) / 1_000_000

        assertFalse("caller must return instead of hanging", caller.isAlive)
        // Exact message: the "side effects may have happened" variant is only
        // produced when the state is no longer 0 after the first wait, so
        // equality proves CAS(0->2) won while the task was still pending.
        assertEquals("ERROR: UI 命令超时", result)
        assertTrue("must wait out the 2s deadline, was ${elapsedMs}ms", elapsedMs >= 1_900)
        assertTrue("must give up after the deadline, was ${elapsedMs}ms", elapsedMs < 3_000)
        assertFalse("queued task must not have run", keepScreenOnSet())

        // Drain the queue: if removeCallbacks missed the task, its CAS(0->1)
        // must still lose against the committed 0->2 and skip the block.
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("CAS loser must not apply side effects", keepScreenOnSet())
    }

    /**
     * Started path: main began the block before the caller's 2s expired, so the
     * caller must wait out the remaining grace and surface the block's payload
     * rather than a timeout.
     */
    @Test
    fun startedPath_returnsRealResultAfterExtraWait() {
        val blockStarted = CountDownLatch(1)
        val commands = UiCommands(activity) {
            blockStarted.countDown()
            // 2.5s: starts before the 2s deadline, finishes inside the 3s grace.
            Thread.sleep(2_500)
            null
        }
        var result: String? = null
        var returnedAt = 0L
        val caller = Thread {
            result = commands.status()
            returnedAt = System.nanoTime()
        }

        val startedAt = System.nanoTime()
        caller.start()
        driveMainUntil(blockStarted)
        caller.join(10_000)
        val elapsedMs = (returnedAt - startedAt) / 1_000_000

        assertFalse("caller must return instead of hanging", caller.isAlive)
        assertNotNull(result)
        assertTrue("expected the block's real JSON payload, got: $result", result!!.startsWith("{"))
        assertTrue("expected ok:true from the block, got: $result", result!!.contains("\"ok\":true"))
        assertFalse("must not report a timeout once the task has started", result!!.contains("超时"))
        assertTrue("must outlive the 2s deadline, was ${elapsedMs}ms", elapsedMs >= 2_000)
        assertTrue("must return when the block ends, was ${elapsedMs}ms", elapsedMs < 5_000)
    }

    /**
     * Started-then-still-slow path: the block outlives the 2s deadline plus the
     * 3s grace, so the caller must return the explicit side-effect warning
     * within that fixed budget — and the test must not hang waiting for the
     * block to finish.
     */
    @Test
    fun startedThenStillSlowPath_reportsSideEffectWarning() {
        val blockStarted = CountDownLatch(1)
        val commands = UiCommands(activity) {
            blockStarted.countDown()
            // 6s: outlives the caller's 2s + 3s budget.
            Thread.sleep(6_000)
            null
        }
        var result: String? = null
        var returnedAt = 0L
        val caller = Thread {
            result = commands.status()
            returnedAt = System.nanoTime()
        }

        val startedAt = System.nanoTime()
        caller.start()
        driveMainUntil(blockStarted)
        caller.join(12_000)
        val elapsedMs = (returnedAt - startedAt) / 1_000_000

        assertFalse("caller must return instead of hanging", caller.isAlive)
        assertEquals(
            "ERROR: UI 命令超时（任务已在主线程执行，副作用可能已发生）",
            result,
        )
        assertTrue("must not give up before 2s + 3s, was ${elapsedMs}ms", elapsedMs >= 4_900)
        assertTrue("must not wait for the 6s block, was ${elapsedMs}ms", elapsedMs < 6_000)
    }

    private fun keepScreenOnSet(): Boolean =
        activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0

    /**
     * Idles the paused main looper until [latch] fires. Idling stops as soon as
     * the block signals, so the slow block runs exactly once and the test body
     * never waits on a second execution.
     */
    private fun driveMainUntil(latch: CountDownLatch) {
        val deadline = System.currentTimeMillis() + 3_000
        while (latch.count > 0 && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (latch.count > 0) latch.await(5, TimeUnit.MILLISECONDS)
        }
        assertTrue("main thread never started the task", latch.count == 0L)
    }
}
