package io.nous.andagent.shell

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Official Termux bootstrap is built for /data/data/com.termux/files/usr.
 * We validate executable behavior before advertising the embedded backend. */
class TermuxEnv(private val context: Context, private val shizuku: ShizukuShell? = null) {
    private val paths = AndagentTermuxPaths(context)
    private val prefix = paths.prefix
    private val home = paths.home.apply { mkdirs() }
    private val dash = paths.dash
    private val readyFile = File(prefix, ".andagent-ready")
    private var session: TermuxSession? = null
    @Volatile private var embeddedValidated = false
    @Volatile private var lastSetupError = "尚未安装"

    fun backend(): String = when {
        readyFile.exists() && dash.exists() && paths.bash.exists() && paths.pkg.exists() -> "embedded"
        termuxInstalled() -> "termux_app"
        else -> "none"
    }
    fun status(): String {
        val permission = if (context.checkSelfPermission("com.termux.permission.RUN_COMMAND") == PackageManager.PERMISSION_GRANTED)
            "granted" else "denied"
        return "${backend()}: $lastSetupError · PREFIX=${prefix.path} · PATH=${paths.path} · RUN_COMMAND=$permission"
    }

    @Suppress("DEPRECATION")
    private fun termuxInstalled() = runCatching { context.packageManager.getPackageInfo("com.termux", 0); true }.getOrDefault(false)

    suspend fun setup(): String = withContext(Dispatchers.IO) {
        if (embeddedValidated) return@withContext "embedded 已就绪"
        if (android.os.Build.SUPPORTED_ABIS.none { it == "arm64-v8a" }) {
            lastSetupError = "当前仅提供 aarch64 bootstrap；设备架构不匹配"
            return@withContext "ERROR: $lastSetupError；后端 ${backend()}"
        }
        try {
            session?.close()
            session = null
            if (!readyFile.exists()) TermuxBootstrapInstaller(context, paths).install()
            fun probe(label: String, vararg args: String) {
                val proc = ProcessBuilder(*args).directory(home).apply {
                    environment().putAll(paths.environment)
                }.start()
                if (!proc.waitFor(8, TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                    error("$label 实测超时")
                }
                if (proc.exitValue() != 0) {
                    val stderr = proc.errorStream.bufferedReader().use { it.readText().take(200) }
                    error("$label 实测失败(exit=${proc.exitValue()}): $stderr")
                }
            }
            probe("dash", dash.path, "-c", "exit 0")
            probe("bash", paths.bash.path, "--noprofile", "--norc", "-c", "exit 0")
            probe("pkg", paths.pkg.path, "list-installed")
            readyFile.writeText("dash bash pkg\n")
            embeddedValidated = true
            lastSetupError = "已就绪"
            "embedded 已安装：${prefix.path}（dash/bash/pkg list-installed 已通过；安装包待真机验证）"
        } catch (e: Exception) {
            readyFile.delete()
            lastSetupError = "内嵌 bootstrap 安装失败：${e.message ?: e.javaClass.simpleName}"
            "ERROR: $lastSetupError；可用后端 ${backend()}。Termux App 需授权 RUN_COMMAND 并在 termux.properties 开启 allow-external-apps=true"
        }
    }

    private fun environment(): Map<String, String> = paths.environment

    suspend fun exec(command: String, timeoutSeconds: Int): String = withContext(Dispatchers.IO) {
        if (Regex("(?i)(rm\\s+-[a-z]*r[a-z]*f\\s+/($|\\s)|mkfs\\b|dd\\s+[^\\n]*of=/dev/|flash_image\\b)").containsMatchIn(command))
            return@withContext "ERROR: 命令被危险路径黑名单拦截"
        when (backend()) {
            "embedded" -> {
                val current = try {
                    session?.takeIf { it.alive() } ?: TermuxSession(paths.bash, home, environment()).also { session = it }
                } catch (e: Exception) {
                    Log.w("TermuxEnv", "session startup failed, using one-shot fallback", e)
                    null
                }
                if (current != null) {
                    try {
                        return@withContext current.execute(command, timeoutSeconds)
                    } catch (e: Exception) {
                        Log.w("TermuxEnv", "session command failed; restarting on next call", e)
                        current.close()
                        session = null
                        return@withContext "ERROR: ${e.message ?: "shell 会话意外退出"}"
                    }
                }
                // Path order under targetSdk 28:
                //  1. DIRECT exec — data-dir execve is legal (W^X only for
                //     targetSdk>=29). Fastest, no Shizuku needed.
                //  2. memfd — for ROMs that somehow still restrict direct exec.
                //  3. Shizuku (shell uid) — for restricted ROMs.
                //  4. Termux App bridge.
                try {
                    val proc = ProcessBuilder(dash.path, "-c", command).directory(home).apply { environment().putAll(environment()) }.start()
                    val out = StringBuilder(); val err = StringBuilder()
                    fun drain(input: java.io.InputStream, sink: StringBuilder, cap: Int) = thread(isDaemon = true) {
                        input.bufferedReader().use { reader ->
                            val buf = CharArray(1024); while (true) { val n = reader.read(buf); if (n < 0) break
                                if (sink.length < cap) sink.append(buf, 0, minOf(n, cap - sink.length)) }
                        }
                    }
                    val t1 = drain(proc.inputStream, out, 4000); val t2 = drain(proc.errorStream, err, 2000)
                    if (!proc.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)) { proc.destroyForcibly(); "ERROR: 命令超过 ${timeoutSeconds}s" }
                    else { t1.join(500); t2.join(500); Log.i("TermuxEnv", "exec backend=direct exit=${proc.exitValue()}"); formatResult(proc.exitValue(), out.toString(), err.toString(), "direct") }
                } catch (e: java.io.IOException) {
                    // execve denied (e.g. OEM policy despite targetSdk 28) →
                    // fall through to memfd, then Shizuku.
                    Log.w("TermuxEnv", "direct exec failed (${e.message}); trying memfd", e)
                    val memExecEnv = environment().toMutableMap()
                    runCatching {
                        val (code, err) = MemExec.exec(
                            elfFile = dash,
                            args = listOf(dash.path, "-c", command),
                            env = memExecEnv,
                            workDir = home.path,
                            timeoutSeconds = timeoutSeconds.toLong(),
                        )
                        Log.i("TermuxEnv", "exec backend=memfd exit=$code")
                        if (code != 127 || !err.contains("errno=13"))
                            return@withContext formatResult(code, err, "", "memfd")
                        // errno=13 (EACCES) on the memfd itself → this ROM also
                        // blocks memfd exec; fall through to Shizuku.
                        "MEMFD_EACCES"
                    }.getOrElse { e2 ->
                        Log.w("TermuxEnv", "memfd failed; trying Shizuku", e2)
                        "MEMFD_FAIL: ${e2.message}"
                    }
                    shizuku?.let { sh ->
                        val shizukuCmd = buildString {
                            append("LD_LIBRARY_PATH=").append(prefix.path).append("/lib ")
                            append("PREFIX=").append(prefix.path).append(" ")
                            append("HOME=").append(home.path).append(" ")
                            append("PATH=").append(prefix.path).append("/bin:/system/bin ")
                            append("TMPDIR=").append(context.cacheDir.path).append(" ")
                            append(dash.path).append(" -c '")
                            append(command.replace("'", "'\\''"))
                            append("'")
                        }
                        val viaShizuku = sh.run(shizukuCmd, timeoutSeconds * 1000L)
                        if (viaShizuku.exitCode != -1 || !viaShizuku.stderr.contains("Shizuku")) {
                            return@withContext formatResult(viaShizuku.exitCode, viaShizuku.stdout, viaShizuku.stderr, "shizuku")
                        }
                    }
                    "ERROR: 三条执行路径都失败（direct 被拦/memfd EACCES/Shizuku 未授权）。请激活 Shizuku（设置页），或安装 Termux App 作回退"
                }
            }
            "termux_app" -> TermuxAppBackend(context).exec(command, timeoutSeconds)
            else -> "ERROR: 尚无可用脚本后端；请安装 Termux 或先运行 termux_setup"
        }
    }

    private fun formatResult(exitCode: Int, stdout: String, stderr: String, backend: String): String =
        buildString {
            append("exit=").append(exitCode).append("\n[backend] ").append(backend).append("\n")
            if (stdout.isNotBlank()) append(stdout.take(4000))
            if (stderr.isNotBlank()) append("\n[stderr] ").append(stderr.take(2000))
        }
}

class TermuxAppBackend(private val context: Context) {
    suspend fun exec(command: String, timeoutSeconds: Int): String = withContext(Dispatchers.IO) {
        if (context.checkSelfPermission("com.termux.permission.RUN_COMMAND") != PackageManager.PERMISSION_GRANTED)
            return@withContext "ERROR: 未获 Termux RUN_COMMAND 权限。①（推荐）在 AndAgent 设置页点击「Shizuku 代授权」；②在电脑执行 adb shell pm grant io.nous.andagent com.termux.permission.RUN_COMMAND。授权后还需在 Termux 的 termux.properties 设置 allow-external-apps=true 并重启 Termux。"
        val id = UUID.randomUUID().toString()
        val pending = TermuxResultReceiver.Pending(CountDownLatch(1))
        TermuxResultReceiver.pending[id] = pending
        try {
            // BroadcastReceiver result intent: works even though Termux is a
            // background app when it posts the result (startService would be
            // blocked by Android 12+ background service restrictions).
            val reply = Intent(TermuxResultReceiver.ACTION)
                .setPackage(context.packageName)
                .putExtra(TermuxResultReceiver.EXTRA_ID, id)
            val pi = PendingIntent.getBroadcast(
                context, id.hashCode(), reply,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE,
            )
            val intent = Intent("com.termux.RUN_COMMAND").apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/sh")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi)
            }
            val started = runCatching { context.startService(intent) }.getOrNull()
                ?: return@withContext "ERROR: 无法启动 Termux 的 RunCommandService；请先手动打开一次 Termux，并确认安装的是同一签名版本（F-Droid 与 Play 版签名不同，混装会 ServiceNotFound）"
            if (!pending.latch.await((timeoutSeconds + 10).toLong(), TimeUnit.SECONDS))
                "ERROR: Termux 命令未在时限内返回。最常见原因：Termux 的 ~/.termux/termux.properties 缺 allow-external-apps=true（改完需重启 Termux）"
            else pending.result ?: "ERROR: Termux 未返回结果"
        } catch (e: Exception) { "ERROR: Termux App 执行失败：${e.message}" }
        finally { TermuxResultReceiver.pending.remove(id) }
    }
}
