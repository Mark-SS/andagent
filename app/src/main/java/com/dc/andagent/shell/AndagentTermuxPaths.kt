package com.dc.andagent.shell

import android.content.Context
import java.io.File

/*
 * Adapted from termux-app/termux-shared/.../TermuxConstants.java (MIT).
 * Copyright (c) Termux contributors. The app-private root replaces com.termux.
 */
internal class AndagentTermuxPaths(private val context: Context) {
    val prefix = File(context.filesDir, "usr")
    val home = File(context.filesDir, "home")
    val dash = File(prefix, "bin/dash")
    val bash = File(prefix, "bin/bash")
    val pkg = File(prefix, "bin/pkg")
    val path = "${prefix.path}/bin:/system/bin"

    /** apt/dpkg 的 ELF 把 /data/data/com.termux/files/usr 编进了默认配置，
     * 环境变量只能覆盖一部分。APT_CONFIG 指向这份配置，把全部目录重定位到
     * 内嵌 prefix。配置放在 filesDir 下，避免 bootstrap 重装时被一起删掉。 */
    val aptConfig = File(context.filesDir, "apt/andagent.conf")
    val aptConfigText: String
        get() = buildString {
            val p = prefix.path
            val cache = File(context.cacheDir, "apt").path
            fun item(key: String, value: String) {
                append(key).append(" \"").append(value).append("\";\n")
            }
            item("Dir", p)
            item("Dir::Etc", "$p/etc/apt")
            item("Dir::Etc::sourcelist", "$p/etc/apt/sources.list")
            item("Dir::Etc::sourceparts", "$p/etc/apt/sources.list.d")
            item("Dir::Etc::preferences", "$p/etc/apt/preferences")
            item("Dir::Etc::preferencesparts", "$p/etc/apt/preferences.d")
            item("Dir::Etc::trusted", "$p/etc/apt/trusted.gpg")
            item("Dir::Etc::trustedparts", "$p/etc/apt/trusted.gpg.d")
            item("Dir::State", "$p/var/lib/apt")
            item("Dir::State::lists", "$p/var/lib/apt/lists")
            item("Dir::State::status", "$p/var/lib/dpkg/status")
            // 与 termux-tools 的 pkg 脚本期望的 cache/apt/pkgcache.bin 对齐，
            // 否则每次 pkg install 都会判定缓存过期而强制 apt update。
            item("Dir::Cache", cache)
            item("Dir::Cache::archives", "$cache/archives")
            item("Dir::Cache::pkgcache", "$cache/pkgcache.bin")
            item("Dir::Cache::srcpkgcache", "$cache/srcpkgcache.bin")
            item("Dir::Log", "$p/var/log/apt")
            item("Dir::Bin::methods", "$p/lib/apt/methods")
            item("Dir::Bin::solvers", "$p/lib/apt/solvers")
            item("Dir::Bin::dpkg", "$p/bin/dpkg")
            // libapt-pkg.so 还把 apt-key、压缩程序和 dpkg 的绝对路径编了进去；
            // 不覆盖的话 apt update 会去 com.termux 下找 apt-key，签名校验失败。
            item("Dir::Bin::gzip", "$p/bin/gzip")
            item("Dir::Bin::bzip2", "$p/bin/bzip2")
            item("Dir::Bin::xz", "$p/bin/xz")
            item("Dir::Bin::lz4", "$p/bin/lz4")
            item("Dir::Bin::zstd", "$p/bin/zstd")
            item("Dir::Bin::lzma", "$p/bin/lzma")
            item("Dir::Bin::apt-key", "$p/bin/apt-key")
            item("Dir::Bin::solvers::", "$p/lib/apt/solvers")
            item("Dir::Bin::planners::", "$p/lib/apt/planners")
            item("DPkg::Path", "$p/bin")
            // apt 的 https method 用 gnutls 找系统证书，Android 上没有；
            // 显式指向 bootstrap 自带的 CA，否则 apt update 报证书不受信任。
            item("Acquire::https::CaInfo", "$p/etc/tls/cert.pem")
            // .deb 里的安装路径写死为 /data/data/com.termux/...，dpkg 解包时
            // 会在设备根下创建目录而被拒。把 instdir 指到 filesDir，包内路径
            // data/data/com.termux/files/usr 经 pkgRootLink 符号链接落到内嵌
            // prefix。instdir 必须是 filesDir 本身（包内路径直接拼在其下）。
            item("DPkg::Options::", "--instdir=${context.filesDir.path}")
            item("DPkg::Options::", "--admindir=${prefix.path}/var/lib/dpkg")
        }

    /** dpkg 解包的影子路径：filesDir/data/data/com.termux/files/usr 是指向
     * 内嵌 prefix 的符号链接（见 apt.conf 的 DPkg::Options）。 */
    val pkgRoot = File(context.filesDir, "data/data/com.termux/files")
    val pkgRootLink = File(pkgRoot, "usr")

    /** apt update/install 需要这些目录存在，bootstrap 归档里没有全部带上。 */
    val aptRuntimeDirs = listOf(
        File(prefix, "etc/apt/apt.conf.d"),
        File(prefix, "etc/apt/sources.list.d"),
        File(prefix, "etc/apt/preferences.d"),
        File(prefix, "etc/apt/trusted.gpg.d"),
        File(prefix, "var/lib/apt/lists/partial"),
        File(prefix, "var/log/apt"),
        File(prefix, "lib/apt/solvers"),
        File(prefix, "lib/apt/planners"),
        File(context.cacheDir, "apt/archives/partial"),
    )

    val environment = mapOf(
        "PREFIX" to prefix.path, "HOME" to home.path, "PATH" to path,
        "LD_LIBRARY_PATH" to "${prefix.path}/lib", "TMPDIR" to context.cacheDir.path,
        "TERMUX__PREFIX" to prefix.path, "TERMUX__HOME" to home.path,
        "SHELL" to bash.path,
        "TERMUX_APP_PACKAGE_MANAGER" to "apt",
        "DPKG_ADMINDIR" to "${prefix.path}/var/lib/dpkg",
        "APT_CONFIG" to aptConfig.path,
        // curl/wget/git 等也把官方 CA 路径编进了 ELF，这里一并重定向。
        "SSL_CERT_FILE" to "${prefix.path}/etc/tls/cert.pem",
        "CURL_CA_BUNDLE" to "${prefix.path}/etc/tls/cert.pem",
    )
}
