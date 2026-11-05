package com.dc.andagent.shell

import java.io.File

/**
 * In-process ELF execution via memfd + fexecve.
 *
 * Android 10+ blocks execve() on files inside the app data directory
 * (SELinux execute_no_trans) for targetSdk>=29 — but fexecve() on an
 * anonymous memfd never presents a /data path to the kernel, so the check
 * never fires. The child forks from our own process (same uid, data-dir
 * LD_LIBRARY_PATH is fine for loading dependent .so files).
 */
object MemExec {
    init {
        System.loadLibrary("memexec")
    }

    /** NUL-separated, double-NUL terminated argv / envp. */
    private external fun nativeExec(
        elfBytes: ByteArray,
        argvBytes: ByteArray,
        envpBytes: ByteArray,
        pipeFds: Array<Any>,
        outFds: IntArray,
        workDir: String?,
    ): Int

    private external fun nativeWait(pid: Int): Int

    class Child(
        val pid: Int,
        stdoutFd: Int,
        stderrFd: Int,
        stdinFd: Int,
    ) {
        // Raw int fds need ParcelFileDescriptor wrappers (no FileInputStream(int) on Android).
        private val stdoutStream: java.io.InputStream =
            android.os.ParcelFileDescriptor.adoptFd(stdoutFd).let { pfd ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)
            }
        private val stderrStream: java.io.InputStream =
            android.os.ParcelFileDescriptor.AutoCloseInputStream(android.os.ParcelFileDescriptor.adoptFd(stderrFd))
        private val stdinStream: java.io.OutputStream =
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(android.os.ParcelFileDescriptor.adoptFd(stdinFd))

        /** Returns (exitCode, stderrText). exitCode -1 = timed out or wait failed. */
        fun waitFor(timeoutSeconds: Long): Pair<Int, String> {
            val out = StringBuilder()
            val err = StringBuilder()
            val t1 = Thread {
                runCatching {
                    stdoutStream.use { r ->
                        val b = ByteArray(2048)
                        var n = r.read(b)
                        while (n >= 0) { out.append(String(b, 0, n)); n = r.read(b) }
                    }
                }
            }
            val t2 = Thread {
                runCatching {
                    stderrStream.use { r ->
                        val b = ByteArray(2048)
                        var n = r.read(b)
                        while (n >= 0) { err.append(String(b, 0, n)); n = r.read(b) }
                    }
                }
            }
            t1.isDaemon = true; t2.isDaemon = true
            t1.start(); t2.start()
            val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
            val result = java.util.concurrent.atomic.AtomicInteger(-1)
            val waiter = Thread { result.set(nativeWait(pid)) }
            waiter.isDaemon = true
            waiter.start()
            waiter.join(deadline - System.currentTimeMillis())
            t1.join(300); t2.join(300)
            val code = if (waiter.isAlive) -1 else result.get()
            return code to (err.toString() + if (out.isNotBlank()) "\n[stdout] ${out.take(3000)}" else "")
        }
    }

    fun exec(
        elfFile: File,
        args: List<String>,
        env: Map<String, String>,
        workDir: String?,
        timeoutSeconds: Long,
    ): Pair<Int, String> {
        val elf = elfFile.readBytes()
        val argv = (args.joinToString("\u0000") + "\u0000").toByteArray(Charsets.UTF_8)
        val envLine = env.entries.joinToString("\u0000") { "${it.key}=${it.value}" }
        val envp = (if (envLine.isEmpty()) "" else "$envLine\u0000").toByteArray(Charsets.UTF_8)
        val outFds = IntArray(4)
        val rc = nativeExec(elf, argv, envp, arrayOf(), outFds, workDir)
        if (rc != 0) return -1 to "memfd exec 失败 errno=${-rc}（${strerror(-rc)}）"
        val child = Child(pid = outFds[3], stdoutFd = outFds[0], stderrFd = outFds[1], stdinFd = outFds[2])
        return child.waitFor(timeoutSeconds)
    }

    private fun strerror(errno: Int): String = when (errno) {
        13 -> "权限被拒（EACCES）"
        8 -> "格式错误（ENOEXEC）"
        2 -> "文件不存在（ENOENT）"
        12 -> "内存不足（ENOMEM）"
        else -> "errno=$errno"
    }
}
