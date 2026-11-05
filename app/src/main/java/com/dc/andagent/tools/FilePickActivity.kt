package com.dc.andagent.tools

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * Transparent trampoline activity that launches the SAF picker and forwards
 * the picked Uri(s) to [FilePickResultBridge]. Needs to be an Activity
 * because ACTION_OPEN_DOCUMENT must be started for result.
 */
class FilePickActivity : Activity() {
    companion object {
        const val REQUEST_PICK = 4201
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 重建路径（系统回收后用户返回）：旧请求已死，新发起的 startActivityForResult
        // 不会再有结果回链——直接按取消结束，让工具线程走 timeout/取消语义。
        if (savedInstanceState != null) {
            finishWith(intent?.getStringExtra("requestId") ?: "", emptyList())
            return
        }
        val intent = getIntent()?.getParcelableExtra<Intent>("pickerIntent")
        if (intent == null) {
            finishWith(getIntent()?.getStringExtra("requestId") ?: "", emptyList())
            return
        }
        try {
            startActivityForResult(intent, REQUEST_PICK)
        } catch (_: Exception) {
            finishWith(getIntent()?.getStringExtra("requestId") ?: "", emptyList())
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val id = getIntent()?.getStringExtra("requestId") ?: ""
        if (requestCode != REQUEST_PICK) return
        val uris = mutableListOf<String>()
        if (resultCode == RESULT_OK && data != null) {
            if (getIntent()?.getBooleanExtra("persistTree", false) == true) {
                data.data?.let { tree ->
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    runCatching { contentResolver.takePersistableUriPermission(tree, flags) }
                    SafTreeStore.add(this, tree.toString())
                }
            }
            data.data?.let { uris.add(it.toString()) }
            val clip = data.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.toString()?.let { uris.add(it) }
            }
        }
        finishWith(id, uris)
    }

    private fun finishWith(id: String, uris: List<String>) {
        FilePickResultBridge.report(id, uris)
        finish()
    }
}

/** Shared result bridge for MainActivity's registered Activity Result launchers. */
object FilePickResultBridge {
    enum class Kind { SINGLE, MULTIPLE, TREE }

    val pending = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CountDownLatch>()
    val results = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    val failures = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val queued = Kind.entries.associateWith { java.util.ArrayDeque<String>() }
    private var activeId: String? = null

    @Volatile var launcher: ((String, String, Boolean, Boolean) -> Unit)? = null

    @Synchronized fun begin(id: String, kind: Kind, latch: java.util.concurrent.CountDownLatch): Boolean {
        if (activeId != null || launcher == null) return false
        activeId = id
        pending[id] = latch
        queued.getValue(kind).addLast(id)
        return true
    }

    @Synchronized fun finish(id: String) {
        pending.remove(id)
        results.remove(id)
        failures.remove(id)
        if (activeId == id) activeId = null
        // Keep the queued ID until its callback arrives: a late result must
        // never be attributed to a newer request using the same launcher.
    }

    @Synchronized fun reportNext(kind: Kind, uris: List<String>) {
        val id = queued.getValue(kind).pollFirst() ?: return
        report(id, uris)
    }

    @Synchronized fun fail(id: String, reason: String) {
        queued.values.forEach { it.remove(id) }
        failures[id] = reason
        pending[id]?.countDown()
    }

    fun report(id: String, uris: List<String>) {
        if (!pending.containsKey(id)) return
        results[id] = uris
        pending[id]?.countDown()
    }
}
