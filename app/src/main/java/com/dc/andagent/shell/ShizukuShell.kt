package com.dc.andagent.shell

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import rikka.shizuku.Shizuku
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Shizuku bridge.
 *
 * Shizuku 13.1.5 keeps `newProcess` private (the public Javadoc still documents
 * it, but the method is no longer part of the public ABI), so it is invoked
 * reflectively. Everything degrades to a readable Chinese error when Shizuku is
 * not installed / not running / not authorized; no call is allowed to crash the
 * tool executor.
 */
class ShizukuShell(private val context: Context) {

    data class Result(val exitCode: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = exitCode == 0
        fun asToolResult(): String = buildString {
            append("exit=$exitCode\n")
            if (stdout.isNotBlank()) append(stdout)
            if (stderr.isNotBlank()) append("\n[stderr] ").append(stderr)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val lastRequestResult = AtomicReference<String?>(null)
    private val lastRequestError = AtomicReference<String?>(null)

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { code, grantResult ->
        if (code == REQUEST_CODE) {
            lastRequestResult.set(
                if (grantResult == PackageManager.PERMISSION_GRANTED) "granted" else "denied",
            )
        }
    }

    private val newProcessMethod by lazy {
        runCatching {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            ).apply { isAccessible = true }
        }.getOrNull()
    }

    /** Register the permission-result listener; call once from Activity.onCreate. */
    fun init() {
        runCatching { Shizuku.addRequestPermissionResultListener(permissionListener) }
    }

    fun shutdown() {
        runCatching { Shizuku.removeRequestPermissionResultListener(permissionListener) }
    }

    /* ------------------------------ status ------------------------------ */

    private fun ping(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    private fun granted(): Boolean = runCatching {
        ping() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun isAvailable(): Boolean = ping()

    fun isGranted(): Boolean = granted()

    @Suppress("DEPRECATION")
    private fun managerVersion(): String? = runCatching {
        context.packageManager.getPackageInfo(MANAGER_PACKAGE, 0).versionName
    }.getOrNull()

    private fun isManagerInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(MANAGER_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /** JSON consumed by Settings.tsx: {"status":"granted"|"not_granted"|"unavailable",...} */
    fun statusJson(): String {
        val state = when {
            !ping() -> "unavailable"
            granted() -> "granted"
            else -> "not_granted"
        }
        return buildJsonObject {
            put("status", state)
            put("installed", isManagerInstalled())
            put("managerPackage", MANAGER_PACKAGE)
            managerVersion()?.let { put("managerVersion", it) }
            lastRequestResult.get()?.let { put("lastRequestResult", it) }
            lastRequestError.get()?.let { put("lastRequestError", it) }
            put(
                "note",
                when (state) {
                    "granted" -> "Shizuku 已授权，录屏等能力可用"
                    "not_granted" -> "Shizuku 服务已连接，但本应用尚未获得授权"
                    else -> if (isManagerInstalled()) {
                        "Shizuku 服务未运行：请打开 Shizuku 并启动服务"
                    } else {
                        "未安装 Shizuku：请先安装并启动 Shizuku 服务"
                    }
                },
            )
        }.toString()
    }

    /**
     * Ask Shizuku for permission. Must run on the main thread (Shizuku shows an
     * activity); returns an immediate acknowledgement, the result arrives in the
     * listener and is observable via [statusJson].
     */
    fun requestPermission(): String {
        if (!isManagerInstalled()) {
            return buildJsonObject {
                put("requested", false)
                put("status", "unavailable")
                put("message", "未安装 Shizuku：请先从应用商店安装 moe.shizuku.privileged.api")
            }.toString()
        }
        if (!ping()) {
            return buildJsonObject {
                put("requested", false)
                put("status", "unavailable")
                put("message", "Shizuku 服务未运行：请打开 Shizuku 并启动服务（无线调试配对）")
            }.toString()
        }
        lastRequestError.set(null)
        main.post {
            runCatching {
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    lastRequestResult.set("granted")
                } else {
                    Shizuku.requestPermission(REQUEST_CODE)
                }
            }.onFailure { lastRequestError.set(readableRequestError(it)) }
        }
        return buildJsonObject {
            put("requested", true)
            put("message", "已发起 Shizuku 授权请求，请在弹窗中点击允许")
        }.toString()
    }

    /**
     * R39: 把请求授权失败的底层异常映射为可读中文。Shizuku binder 报错常带
     * 无意义的 Java 底层串（如 "stack size ..."），直接透传会让用户看不懂。
     */
    private fun readableRequestError(t: Throwable): String = when (t) {
        is java.io.IOException ->
            "与 Shizuku 服务通信失败（I/O 异常）。请确认 Shizuku 正在运行，必要时重启 Shizuku 服务后重试"
        is IllegalArgumentException ->
            "Shizuku 授权请求参数无效（服务版本可能不兼容）。请升级 Shizuku 到最新版本后重试"
        is SecurityException ->
            "Shizuku 拒绝了本次授权请求。请在 Shizuku App 中确认本应用未被加入拒绝列表"
        is IllegalStateException ->
            "Shizuku 服务状态异常。请打开 Shizuku App 确认服务已启动，再回到本页重试"
        else ->
            "授权失败（${t.javaClass.simpleName}）。请查看 Shizuku App 日志定位具体原因"
    }

    /* ---------------------------- execution ---------------------------- */

    /**
     * Start `sh -c <command>` with Shizuku's shell uid. The returned process is
     * owned by the caller; [onExit] runs on a daemon thread when it ends.
     */
    fun start(command: String, onExit: ((Int, String) -> Unit)? = null): Process? {
        if (!isGranted()) return null
        val method = newProcessMethod ?: return null
        val proc = runCatching {
            method.invoke(
                null,
                arrayOf("/system/bin/sh", "-c", "$command 2>&1"),
                null,
                null,
            ) as? Process
        }.getOrNull() ?: return null

        thread(name = "shizuku-proc-${System.currentTimeMillis()}", isDaemon = true) {
            val output = StringBuilder()
            val code = runCatching {
                drain(proc.inputStream, output, STREAM_CAP)
                proc.waitFor()
            }.getOrDefault(-1)
            runCatching { onExit?.invoke(code, output.toString()) }
        }
        return proc
    }

    /** Execute a command and wait up to [timeoutMs] (default 30s). */
    suspend fun run(command: String, timeoutMs: Long = 30_000): Result =
        withContext(Dispatchers.IO) {
            if (!isGranted()) return@withContext Result(-1, "", SHIZUKU_REQUIRED)
            val method = newProcessMethod
                ?: return@withContext Result(-1, "", "Shizuku 不可用：无法访问 newProcess")
            val proc = runCatching {
                method.invoke(
                    null,
                    arrayOf("/system/bin/sh", "-c", command),
                    null,
                    null,
                ) as? Process
            }.getOrNull() ?: return@withContext Result(-1, "", "Shizuku 进程启动失败，请检查授权状态")

            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val outThread = thread(isDaemon = true) {
                runCatching { drain(proc.inputStream, stdout, STDOUT_CAP) }
            }
            val errThread = thread(isDaemon = true) {
                runCatching { drain(proc.errorStream, stderr, STDERR_CAP) }
            }

            val finished = runCatching { proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }
                .getOrDefault(false)
            if (!finished) {
                runCatching { proc.destroy() }
                outThread.join(500)
                errThread.join(500)
                return@withContext Result(
                    -1,
                    stdout.toString(),
                    "timeout after ${timeoutMs}ms",
                )
            }
            outThread.join(1_000)
            errThread.join(1_000)
            Result(
                exitCode = runCatching { proc.exitValue() }.getOrDefault(-1),
                stdout = stdout.toString(),
                stderr = stderr.toString(),
            )
        }

    private fun drain(stream: InputStream, sink: StringBuilder, cap: Int) {
        stream.bufferedReader(Charsets.UTF_8).use { reader ->
            val buf = CharArray(2048)
            var kept = 0
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                if (kept < cap) {
                    val take = minOf(n, cap - kept)
                    sink.append(buf, 0, take)
                    kept += take
                }
            }
        }
    }

    companion object {
        const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"
        const val REQUEST_CODE = 10001
        const val SHIZUKU_REQUIRED = "需要 Shizuku：请先在设置页激活并授权"

        private const val STREAM_CAP = 4_000
        private const val STDOUT_CAP = 4_000
        private const val STDERR_CAP = 2_000
    }
}
