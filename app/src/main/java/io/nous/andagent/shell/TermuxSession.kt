package io.nous.andagent.shell

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
    private val writer = process.outputStream.bufferedWriter()
    @Volatile private var startupDiagnostics = ""

    init {
        thread(name = "andagent-termux-reader", isDaemon = true) {
            try {
                val bytes = ByteArray(4096)
                while (true) {
                    val n = process.inputStream.read(bytes)
                    if (n < 0) break
                    synchronized(monitor) {
                        output.write(bytes, 0, n)
                        if (output.size() > 131_072) {
                            val tail = output.toByteArray().takeLast(65_536).toByteArray()
                            output.reset()
                            output.write(tail)
                        }
                        monitor.notifyAll()
                    }
                }
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
        writer.write(command)
        writer.write("\nprintf '$marker:%s\\n' \"\$?\"\n")
        writer.flush()
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

    fun close() { process.destroyForcibly() }
}
