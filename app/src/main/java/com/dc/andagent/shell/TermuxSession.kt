package com.dc.andagent.shell

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/*
 * Session lifecycle and stream reader adapted from termux-app/terminal-emulator/
 * .../TerminalSession.java (Apache-2.0), Copyright (c) Termux contributors.
 * This tool session uses pipes instead of the upstream interactive PTY.
 */
internal class TermuxSession(
    private val shell: File,
    private val home: File,
    private val environment: Map<String, String>,
) {
    private val process = ProcessBuilder(shell.path, "--noprofile", "--norc")
        .directory(home).redirectErrorStream(true)
        .apply { environment().putAll(this@TermuxSession.environment) }.start()
    private val monitor = Object()
    private val output = ByteArrayOutputStream()
    // R27: 对话流内嵌终端的增量输出缓冲——reader 持续收，terminalPoll() 拉走。
    // 与 output（marker 批处理用）独立，互不复位。
    private val terminalBytes = ByteArrayOutputStream()
    private val writer = process.outputStream.bufferedWriter()
    private val writeLock = Object()
    @Volatile private var startupDiagnostics = ""
    @Volatile private var closed = false

    init {
        thread(name = "andagent-termux-reader", isDaemon = true) {
            // close() destroys the process; the blocking read() then throws
            // InterruptedIOException. An uncaught throwable on any thread kills
            // the whole app (FATAL EXCEPTION), so swallow it here — the session
            // is closing anyway.
            try {
                val bytes = ByteArray(4096)
                while (!closed) {
                    val n = process.inputStream.read(bytes)
                    if (n < 0) break
                    synchronized(monitor) {
                        output.write(bytes, 0, n)
                        terminalBytes.write(bytes, 0, n)
                        if (output.size() > 131_072) {
                            val tail = output.toByteArray().takeLast(65_536).toByteArray()
                            output.reset()
                            output.write(tail)
                        }
                        if (terminalBytes.size() > 131_072) {
                            val tail = terminalBytes.toByteArray().takeLast(65_536).toByteArray()
                            terminalBytes.reset()
                            terminalBytes.write(tail)
                        }
                        monitor.notifyAll()
                    }
                }
            } catch (_: java.io.InterruptedIOException) {
                /* expected on close() — reader interrupted by stream teardown */
            } catch (_: Exception) {
                /* stream closed under us or process died — nothing to report */
            } finally { synchronized(monitor) { monitor.notifyAll() } }
        }
    }

    fun alive(): Boolean = process.isAlive

    @Synchronized fun execute(command: String, timeoutSeconds: Int): String {
        if (!alive()) failure("shell 已退出")
        val marker = "__ANDAGENT_END_${UUID.randomUUID().toString().replace("-", "")}__"
        synchronized(monitor) {
            // 启动期的 linker/execve 报错会先进入 output，保留下来供诊断。
            if (startupDiagnostics.isEmpty() && output.size() > 0) {
                startupDiagnostics = output.toString("UTF-8").take(600)
            }
            output.reset()
        }
        synchronized(writeLock) {
            writer.write(command)
            writer.write("\nprintf '$marker:%s\\n' \"\$?\"\n")
            writer.flush()
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds.toLong())
        synchronized(monitor) {
            while (true) {
                val value = output.toString("UTF-8")
                val start = value.indexOf("$marker:")
                if (start >= 0) {
                    val end = value.indexOf('\n', start)
                    if (end >= 0) {
                        val code = value.substring(start + marker.length + 1, end).toIntOrNull() ?: -1
                        return "exit=$code\n[backend] session\n${value.substring(0, start).take(4000)}"
                    }
                }
                if (!alive()) failure("shell 意外退出")
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) {
                    close()
                    failure("命令超过 ${timeoutSeconds}s")
                }
                TimeUnit.NANOSECONDS.timedWait(monitor, remaining)
            }
        }
    }

    /**
     * R27: 终端通道——不经 marker 等待，直接写入长驻 bash 的 stdin。
     * command 可为「命令行」（terminalExec 附加 tty 弱 marker 后调这里）
     * 或交互程序要的裸输入（terminalWrite）。与 execute() 的写入用 writeLock
     * 串行，避免半行交错；输出侧残余竞态（agent 与终端同时执行时输出交错）
     * 是共享会话的固有现象，可接受。
     */
    fun writeRaw(text: String) {
        if (!alive()) error("shell 已退出")
        synchronized(writeLock) {
            writer.write(text)
            writer.flush()
        }
    }

    /**
     * R27: 拉取自上次 poll 以来的终端增量输出（单次 ≤16KB，剩余留给下次）。
     * UTF-8 末尾的不完整序列暂扣不发，等下一个 chunk 凑齐，避免轮询切片
     * 把多字节字符撕成乱码。
     */
    fun terminalPoll(): String {
        synchronized(monitor) {
            val all = terminalBytes.toByteArray()
            if (all.isEmpty()) return ""
            var end = all.size
            var skip = 0
            while (skip < 4 && end - skip > 0) {
                val b = all[end - skip - 1].toInt() and 0xFF
                when {
                    b < 0x80 -> break
                    b and 0xC0 == 0x80 -> skip++
                    else -> {
                        val need = when {
                            b and 0xE0 == 0xC0 -> 2
                            b and 0xF0 == 0xE0 -> 3
                            b and 0xF8 == 0xF0 -> 4
                            else -> break
                        }
                        if (skip + 1 < need) end -= skip + 1
                        break
                    }
                }
            }
            val text = String(all, 0, end, Charsets.UTF_8)
            terminalBytes.reset()
            if (end < all.size) terminalBytes.write(all, end, all.size - end)
            return text.takeLast(16_384)
        }
    }

    /** 把 bash 的真实输出（linker 报错、启动错误）带进异常，避免只剩一句
     * "shell 意外退出" 而无法定位。 */
    private fun failure(reason: String): Nothing {
        val buffered = synchronized(monitor) { output.toString("UTF-8").take(600) }
        error(buildString {
            append(reason)
            if (startupDiagnostics.isNotBlank()) append("；shell 启动输出：").append(startupDiagnostics)
            if (buffered.isNotBlank() && buffered != startupDiagnostics) append("；shell 输出：").append(buffered)
        })
    }

    fun close() {
        closed = true
        process.destroyForcibly()
    }
}
