package com.dc.andagent.shell

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch

/**
 * Receives Termux RUN_COMMAND results.
 *
 * Must be a BroadcastReceiver, not a Service: Android 12+ forbids background
 * apps from calling startService(), and Termux posts the result PendingIntent
 * while itself being backgrounded. getBroadcast() PendingIntent works from
 * any state (the receiver runs in a transient broadcast slice).
 *
 * Bundle keys per TermuxConstants:
 *   result -> { stdout, stderr, exitCode, errmsg }
 */
class TermuxResultReceiver : BroadcastReceiver() {
    data class Pending(val latch: CountDownLatch, @Volatile var result: String? = null)
    companion object {
        val pending = ConcurrentHashMap<String, Pending>()
        const val ACTION = "com.dc.andagent.TERMUX_RESULT"
        const val EXTRA_ID = "id"
        const val EXTRA_RESULT = "result"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val bundle = intent.getBundleExtra(EXTRA_RESULT)
        pending[id]?.let { p ->
            p.result = if (bundle == null) {
                "ERROR: Termux 未提供结果包"
            } else {
                val code = bundle.getInt("exitCode", -1)
                val stdout = bundle.getString("stdout", "")
                val stderr = bundle.getString("stderr", "")
                val errmsg = bundle.getString("errmsg")
                buildString {
                    append("exit=").append(code).append('\n')
                    if (stdout.isNotBlank()) append(stdout.take(4000))
                    if (stderr.isNotBlank()) append("\n[stderr] ").append(stderr.take(2000))
                    if (!errmsg.isNullOrBlank()) append("\n[errmsg] ").append(errmsg.take(500))
                }
            }
            p.latch.countDown()
        }
    }
}

/** Retired: kept as a no-op only so stale manifest references never crash. */
@Deprecated("Replaced by TermuxResultReceiver (Android 12+ background service limit)")
class TermuxResultService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Legacy path: forward to the receiver's parser so old flows still work.
        val id = intent?.getStringExtra("id")
        val bundle = intent?.getBundleExtra("result")
        if (id != null) TermuxResultReceiver.pending[id]?.let { p ->
            p.result = if (bundle == null) "ERROR: Termux 未提供结果包" else {
                val code = bundle.getInt("exitCode", -1)
                "exit=$code\n${bundle.getString("stdout", "").take(4000)}\n[stderr] ${bundle.getString("stderr", "").take(2000)}"
            }
            p.latch.countDown()
        }
        return START_NOT_STICKY
    }
}
