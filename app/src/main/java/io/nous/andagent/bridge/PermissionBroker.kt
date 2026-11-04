package io.nous.andagent.bridge

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.ArrayDeque
import kotlin.coroutines.resume

/**
 * Runtime permission requests, shared by tools (suspend [ensure]) and JS
 * (fire-and-forget [request] via AgentBridge.requestPermissions).
 *
 * The Activity Result API may only be touched on the main thread and only one
 * request may be in flight; requests are therefore queued on the main looper.
 * Any failure (activity finishing, launcher unavailable) degrades to "denied"
 * so callers can return a readable error string instead of crashing.
 */
class PermissionBroker(
    private val activity: Activity,
    private val launcher: ActivityResultLauncher<Array<String>>,
) {
    private val main = Handler(Looper.getMainLooper())

    private data class Pending(
        val names: List<String>,
        val callback: (Map<String, Boolean>) -> Unit,
    )

    private val queue = ArrayDeque<Pending>()
    private var inFlight: Pending? = null

    fun isGranted(name: String): Boolean = try {
        ContextCompat.checkSelfPermission(activity, name) == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
    }

    fun areGranted(names: List<String>): Boolean = names.all(::isGranted)

    /**
     * Ask for [names] if needed and suspend until the user answered. Returns
     * true only when every requested permission is granted afterwards.
     */
    suspend fun ensure(names: List<String>): Boolean {
        if (names.isEmpty()) return true
        val missing = names.filterNot(::isGranted)
        if (missing.isEmpty()) return true
        suspendCancellableCoroutine { cont ->
            request(missing) { granted ->
                if (cont.isActive) cont.resume(granted)
            }
        }
        return names.all(::isGranted)
    }

    /** Queue a request; [callback] runs on the main thread with the final state. */
    fun request(names: List<String>, callback: (Map<String, Boolean>) -> Unit) {
        main.post {
            val missing = names.filterNot(::isGranted)
            if (missing.isEmpty()) {
                callback(names.associateWith { true })
                return@post
            }
            queue.addLast(Pending(missing, callback))
            pump()
        }
    }

    /** Called from MainActivity's permissionLauncher callback (main thread). */
    fun onResult(@Suppress("UNUSED_PARAMETER") result: Map<String, Boolean>) {
        main.post {
            val current = inFlight ?: return@post
            inFlight = null
            current.callback(current.names.associateWith { isGranted(it) })
            pump()
        }
    }

    private fun pump() {
        if (inFlight != null) return
        val next = queue.pollFirst() ?: return
        val missing = next.names.filterNot(::isGranted)
        if (missing.isEmpty()) {
            next.callback(next.names.associateWith { true })
            pump()
            return
        }
        inFlight = Pending(missing, next.callback)
        try {
            launcher.launch(missing.toTypedArray())
        } catch (_: Throwable) {
            val failed = inFlight ?: return
            inFlight = null
            failed.callback(failed.names.associateWith { false })
            pump()
        }
    }
}
