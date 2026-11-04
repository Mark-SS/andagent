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
class TermuxEnv(private val context: Context) {
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
                    else { t1.join(1000); t2.join(1000); "exit=${proc.exitValue()}\n$out\n[stderr] $err" }
                } catch (e: Exception) { "ERROR: embedded 执行失败：${e.message}" }
            }
            "termux_app" -> TermuxAppBackend(context).exec(command, timeoutSeconds)
            else -> "ERROR: 尚无可用脚本后端；请安装 Termux 或先运行 termux_setup"
        }
    }
}

class TermuxAppBackend(private val context: Context) {
    suspend fun exec(command: String, timeoutSeconds: Int): String = withContext(Dispatchers.IO) {
        if (context.checkSelfPermission("com.termux.permission.RUN_COMMAND") != PackageManager.PERMISSION_GRANTED)
            return@withContext "ERROR: 未获 Termux RUN_COMMAND 权限；请到应用系统设置授权，且在 Termux 的 termux.properties 设 allow-external-apps=true"
        val id = UUID.randomUUID().toString()
        val pending = TermuxResultService.Pending(CountDownLatch(1))
        TermuxResultService.pending[id] = pending
        try {
            val reply = Intent(context, TermuxResultService::class.java).putExtra("id", id)
            val pi = PendingIntent.getService(context, id.hashCode(), reply,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE)
            val intent = Intent("com.termux.RUN_COMMAND").apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/sh")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi)
            }
            context.startService(intent) ?: return@withContext "ERROR: Termux 服务未启动；请先打开 Termux"
            if (!pending.latch.await((timeoutSeconds + 10).toLong(), TimeUnit.SECONDS))
                "ERROR: Termux 命令未在时限内返回；请检查 Termux 的外部应用设置"
            else pending.result ?: "ERROR: Termux 未返回结果"
        } catch (e: Exception) { "ERROR: Termux App 执行失败：${e.message}" }
        finally { TermuxResultService.pending.remove(id) }
    }
}

class TermuxResultService : Service() {
    data class Pending(val latch: CountDownLatch, @Volatile var result: String? = null)
    companion object { val pending = ConcurrentHashMap<String, Pending>() }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra("id")
        val bundle = intent?.getBundleExtra("result")
        if (id != null) pending[id]?.let { p ->
            p.result = if (bundle == null) "ERROR: Termux 未提供结果包" else {
                val code = bundle.getInt("exitCode", -1)
                "exit=$code\n${bundle.getString("stdout", "").take(4000)}\n[stderr] ${bundle.getString("stderr", "").take(2000)}${bundle.getString("errmsg")?.let { "\n$it" } ?: ""}"
            }
            p.latch.countDown()
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
