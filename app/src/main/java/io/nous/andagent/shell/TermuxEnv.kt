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
import java.util.zip.ZipFile
import kotlin.concurrent.thread

/** Official Termux bootstrap is built for /data/data/com.termux/files/usr.
 * We validate executable behavior before advertising the embedded backend. */
class TermuxEnv(private val context: Context, private val shizuku: ShizukuShell? = null) {
    private val prefix = File(context.filesDir, "usr")
    private val home = File(context.filesDir, "home").apply { mkdirs() }
    private val dash = File(prefix, "bin/dash")
    @Volatile private var embeddedValidated = false
    @Volatile private var lastSetupError = "尚未安装"

    fun backend(): String = when {
        dash.exists() -> "embedded"        // bootstrap files present → always try embedded first (direct exec works on targetSdk 28)
        termuxInstalled() -> "termux_app"
        else -> "none"
    }
    fun status(): String {
        val permission = if (context.checkSelfPermission("com.termux.permission.RUN_COMMAND") == PackageManager.PERMISSION_GRANTED)
            "granted" else "denied"
        return "${backend()}: $lastSetupError · RUN_COMMAND=$permission"
    }

    @Suppress("DEPRECATION")
    private fun termuxInstalled() = runCatching { context.packageManager.getPackageInfo("com.termux", 0); true }.getOrDefault(false)

    suspend fun setup(): String = withContext(Dispatchers.IO) {
        if (embeddedValidated) return@withContext "embedded 已就绪"
        if (android.os.Build.SUPPORTED_ABIS.none { it == "arm64-v8a" }) {
            lastSetupError = "当前仅提供 aarch64 bootstrap；设备架构不匹配"
            return@withContext "ERROR: $lastSetupError；后端 ${backend()}"
        }
        val zip = File(context.cacheDir, "termux-bootstrap.zip")
        try {
                context.assets.open("bootstrap/bootstrap-aarch64.zip").use { input -> zip.outputStream().use { output ->
                    val buffer = ByteArray(8192); var total = 0L
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        total += n; if (total > 80_000_000) error("归档超过 80MB")
                        output.write(buffer, 0, n)
                    }
                } }
                ZipFile(zip).use { archive ->
                    if (archive.getEntry("bin/dash") == null) error("bootstrap 缺少 bin/dash")
                    var expanded = 0L
                    val entries = archive.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.name == "SYMLINKS.txt") continue
                        expanded += entry.size.coerceAtLeast(0)
                        if (expanded > 300_000_000) error("ZIP 解压体积超过 300MB")
                        val dest = File(prefix, entry.name)
                        if (!dest.canonicalPath.startsWith(prefix.canonicalPath + File.separator)) error("ZIP 路径越界")
                        if (entry.isDirectory) dest.mkdirs() else {
                            dest.parentFile?.mkdirs()
                            archive.getInputStream(entry).use { source -> dest.outputStream().use { target -> source.copyTo(target) } }
                            if (entry.name.startsWith("bin/")) dest.setExecutable(true)
                        }
                    }
                    archive.getEntry("SYMLINKS.txt")?.let { linkEntry ->
                        archive.getInputStream(linkEntry).bufferedReader().forEachLine { line ->
                            val parts = line.split("←./", limit = 2)
                            if (parts.size == 2) {
                                val link = File(prefix, parts[1])
                                if (link.canonicalPath.startsWith(prefix.canonicalPath + File.separator) && !link.exists()) {
                                    link.parentFile?.mkdirs()
                                    runCatching { android.system.Os.symlink(parts[0], link.path) }
                                }
                            }
                        }
                    }
                }
                dash.setExecutable(true)
                // targetSdk 28: direct exec of data-dir binaries is allowed
                // (W^X only applies to targetSdk>=29). pkg may still fail if
                // its shebang points at com.termux paths — that's reported
                // honestly per-command, not as a setup failure.
                val test = ProcessBuilder(dash.path, "-c", "exit 0").apply { environment().putAll(environment()) }.start()
                embeddedValidated = test.waitFor(4, TimeUnit.SECONDS) && test.exitValue() == 0
                if (!embeddedValidated) {
                    val errText = test.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    error("dash 实测失败：${errText.take(200) ?: "exit!=0"}")
                }
                lastSetupError = "已就绪"
                return@withContext "embedded 已安装：${prefix.path}（dash 直跑正常；pkg 可能因官方包 com.termux 硬编码路径受限，逐命令如实报告）"
        } catch (e: Exception) {
            lastSetupError = "内嵌 bootstrap 安装失败：${e.message ?: e.javaClass.simpleName}"
        } finally {
            zip.delete()
        }
        "ERROR: $lastSetupError；可用后端 ${backend()}。Termux App 需授权 RUN_COMMAND 并在 termux.properties 开启 allow-external-apps=true"
    }

    private fun environment(): Map<String, String> = mapOf(
        "PREFIX" to prefix.path, "HOME" to home.path,
        "PATH" to "${prefix.path}/bin:/system/bin", "LD_LIBRARY_PATH" to "${prefix.path}/lib",
        "TMPDIR" to context.cacheDir.path,
    )

    suspend fun exec(command: String, timeoutSeconds: Int): String = withContext(Dispatchers.IO) {
        if (Regex("(?i)(rm\\s+-[a-z]*r[a-z]*f\\s+/($|\\s)|mkfs\\b|dd\\s+[^\\n]*of=/dev/|flash_image\\b)").containsMatchIn(command))
            return@withContext "ERROR: 命令被危险路径黑名单拦截"
        when (backend()) {
            "embedded" -> {
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
