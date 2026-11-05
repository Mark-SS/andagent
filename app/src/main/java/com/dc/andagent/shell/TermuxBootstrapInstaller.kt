package com.dc.andagent.shell

import android.content.Context
import android.system.Os
import java.io.File
import java.util.zip.ZipFile

/*
 * Independent implementation of the bootstrap archive format: staged extraction,
 * SYMLINKS.txt and executable permissions. The upstream TermuxInstaller.java
 * is GPLv3-only and is not copied here.
 */
internal class TermuxBootstrapInstaller(private val context: Context, private val paths: AndagentTermuxPaths) {
    fun install() {
        val archiveFile = File(context.cacheDir, "andagent-bootstrap.zip")
        val stage = File(context.filesDir, "usr.staging")
        try {
            context.assets.open("bootstrap/bootstrap-aarch64.zip").use { input ->
                archiveFile.outputStream().use { output ->
                    val bytes = input.copyTo(output, bufferSize = 8192)
                    require(bytes <= 80_000_000) { "bootstrap 归档超过 80MB" }
                }
            }
            stage.deleteRecursively()
            require(stage.mkdirs()) { "无法创建暂存目录" }
            ZipFile(archiveFile).use { zip ->
                require(zip.getEntry("bin/dash") != null && zip.getEntry("bin/bash") != null && zip.getEntry("bin/pkg") != null)
                val root = stage.canonicalPath + File.separator
                var total = 0L
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name == "SYMLINKS.txt") continue
                    require(!entry.name.startsWith('/')) { "ZIP 路径越界" }
                    val dest = File(stage, entry.name)
                    require(dest.canonicalPath.startsWith(root)) { "ZIP 路径越界" }
                    if (entry.isDirectory) {
                        require(dest.isDirectory || dest.mkdirs())
                    } else {
                        require(dest.parentFile?.isDirectory == true || dest.parentFile?.mkdirs() == true)
                        zip.getInputStream(entry).use { source ->
                            dest.outputStream().use { target ->
                                val copied = source.copyTo(target, bufferSize = 8192)
                                total += copied
                                require(total <= 300_000_000) { "ZIP 解压超过 300MB" }
                            }
                        }
                        if (entry.name.startsWith("bin/") || entry.name.startsWith("libexec/") ||
                            entry.name.startsWith("lib/apt/methods/") || entry.name == "lib/apt/apt-helper") {
                            Os.chmod(dest.path, 448) // 0700
                        }
                    }
                }
                val links = zip.getEntry("SYMLINKS.txt") ?: error("缺少 SYMLINKS.txt")
                zip.getInputStream(links).bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val parts = line.split('←', limit = 2)
                        require(parts.size == 2 && parts[1].startsWith("./")) { "无效 symlink: $line" }
                        val link = File(stage, parts[1].removePrefix("./"))
                        require(link.canonicalPath.startsWith(root)) { "symlink 路径越界" }
                        require(link.parentFile?.isDirectory == true || link.parentFile?.mkdirs() == true)
                        if (!link.exists()) Os.symlink(parts[0], link.path)
                    }
                }
            }
            paths.prefix.deleteRecursively()
            require(stage.renameTo(paths.prefix)) { "无法安装暂存目录" }
        } finally {
            archiveFile.delete()
            stage.deleteRecursively()
        }
    }
}
