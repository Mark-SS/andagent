package io.nous.andagent.shell

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shell execution engine.
 *
 * M2 baseline: executes commands via the system shell (mksh, /system/bin/sh) in
 * the app's private directory. This gives the agent real command execution with
 * the toybox toolset available on every Android device (ls, find, du, rm, cp,
 * grep, cat, df, ...).
 *
 * Full Termux bootstrap (apt/pkg ecosystem) is a later upgrade: embed
 * termux-terminal-emulator + bootstrap extraction into files/usr, then run
 * commands through that $PREFIX. The [ShellEngine] interface stays identical;
 * only the session backend changes.
 */
class ShellEngine(private val workDir: File) {

    private val mutex = Mutex()

    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) {
        val ok: Boolean get() = exitCode == 0
        fun asToolResult(): String = buildString {
            append("exit=$exitCode\n")
            if (stdout.isNotBlank()) append(stdout.take(4000))
            if (stderr.isNotBlank()) append("\n[stderr] ").append(stderr.take(2000))
        }
    }

    /**
     * Run a shell command line via sh -c. Output capped to keep context small.
     * Sequential execution (mutex) — agent sessions are conversational, no
     * need for concurrent shell sessions yet.
     */
    suspend fun exec(command: String, timeoutMs: Long = 15_000): Result =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    val proc = ProcessBuilder("/system/bin/sh", "-c", command)
                        .directory(workDir)
                        .redirectErrorStream(false)
                        .start()
                    val finished = proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (!finished) {
                        proc.destroyForcibly()
                        return@runCatching Result(-1, "", "timeout after ${timeoutMs}ms")
                    }
                    Result(
                        exitCode = proc.exitValue(),
                        stdout = proc.inputStream.bufferedReader().use { it.readText() },
                        stderr = proc.errorStream.bufferedReader().use { it.readText() },
                    )
                }.getOrElse { Result(-1, "", "shell error: ${it.message}") }
            }
        }
}
