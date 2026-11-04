package io.nous.andagent.shell

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
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
        embeddedValidated && dash.canExecute() -> "embedded"
        termuxInstalled() -> "termux_app"
        else -> "none"
    }
    fun status(): String = "${backend()}: $lastSetupError"

    @Suppress("DEPRECATION")
    private fun termuxInstalled() = runCatching { context.packageManager.getPackageInfo("com.termux", 0); true }.getOrDefault(false)

    suspend fun setup(): String = withContext(Dispatchers.IO) {
        if (embeddedValidated) return@withContext "embedded 已就绪"
        if (android.os.Build.SUPPORTED_ABIS.none { it == "arm64-v8a" }) {
            lastSetupError = "当前仅提供 aarch64 bootstrap；设备架构不匹配"
            return@withContext "ERROR: $lastSetupError；后端 ${backend()}"
        }
        val urls = listOf(
            "https://github.com/termux/termux-packages/releases/download/bootstrap-2026.11.01-r1%2Bapt.android-7/bootstrap-aarch64.zip",
            "https://github.com/termux/termux-packages/releases/download/bootstrap-2026.09.13-r1%2Bapt.android-7/bootstrap-aarch64.zip",
        )
        val errors = mutableListOf<String>()
        for (url in urls) {
            val zip = File(context.cacheDir, "termux-bootstrap.zip")
            try {
                val conn = URL(url).openConnection().apply { connectTimeout = 12000; readTimeout = 30000 }
                conn.getInputStream().use { input -> zip.outputStream().use { output ->
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
                val test = ProcessBuilder(dash.path, "-c", "exit 0").apply { environment().putAll(environment()) }.start()
                embeddedValidated = test.waitFor(4, TimeUnit.SECONDS) && test.exitValue() == 0
                if (embeddedValidated) {
                    val pkgCheck = ProcessBuilder(File(prefix, "bin/pkg").path, "--version").apply { environment().putAll(environment()) }.start()
                    embeddedValidated = pkgCheck.waitFor(4, TimeUnit.SECONDS) && pkgCheck.exitValue() == 0
                }
                if (!embeddedValidated) error("dash 无法执行；官方包使用 com.termux 固定 PREFIX")
                lastSetupError = "已就绪"
                return@withContext "embedded 已安装：${prefix.path}"
            } catch (e: Exception) { errors += "${url.substringAfterLast('/')}: ${e.message}" }
            finally { zip.delete() }
        }
        lastSetupError = errors.joinToString("；").take(600)
        "ERROR: bootstrap 均未通过校验：$lastSetupError；可用后端 ${backend()}。Termux App 需授权 RUN_COMMAND 并在 termux.properties 开启 allow-external-apps=true"
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
                // Primary path: memfd + fexecve — reads the ELF into an
                // anonymous memory fd, so Android 10+ W^X (SELinux
                // execute_no_trans on /data files) never fires. Same process
                // tree, no Shizuku needed.
                runCatching {
                    val memExecEnv = environment().toMutableMap()
                    val (code, err) = MemExec.exec(
                        elfFile = dash,
                        args = listOf(dash.path, "-c", command),
                        env = memExecEnv,
                        workDir = home.path,
                        timeoutSeconds = timeoutSeconds.toLong(),
                    )
                    return@withContext formatResult(code, err, "", "memfd")  // waitFor mixes stdout+stderr into second field
                }.getOrElse { e ->
                    // Fall through to Shizuku if memfd itself failed to load.
                    "MEMFD_FAIL: ${e.message}"
                }
                // Secondary: Shizuku (shell uid). -1 means Shizuku itself
                // failed (not granted / dead); fall through to direct exec.
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
                    // -1 means Shizuku itself failed (not granted / dead);
                    // fall through to the legacy direct exec, then report.
                    if (viaShizuku.exitCode != -1 || !viaShizuku.stderr.contains("Shizuku")) {
                        return@withContext formatResult(viaShizuku.exitCode, viaShizuku.stdout, viaShizuku.stderr, "shizuku")
                    }
                }
                // Legacy direct exec (works on old devices / relaxed policies).
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
                    else { t1.join(500); t2.join(500); formatResult(proc.exitValue(), out.toString(), err.toString(), "direct") }
                } catch (e: Exception) {
                    "ERROR: 执行失败（${e.message ?: e.javaClass.simpleName}）。Android 10+ 禁止应用私有目录内 exec；请激活 Shizuku 后重试，或安装 Termux App 作为回退后端"
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
            return@withContext "ERROR: 未获 Termux RUN_COMMAND 权限。修复步骤：①设置→应用→AndAgent→权限→开通 Termux 权限（或在 Termux 目录执行 pm grant）；②在 Termux 会话里执行 mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties 后重启 Termux"
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

