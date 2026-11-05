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

/** Static bridge between the picker activity and the blocked tool thread. */
object FilePickResultBridge {
    val pending = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CountDownLatch>()
    val results = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    fun report(id: String, uris: List<String>) {
        results[id] = uris
        pending[id]?.countDown()
    }
}
