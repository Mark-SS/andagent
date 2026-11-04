package io.nous.andagent.tools

import io.nous.andagent.shell.TermuxEnv
import kotlinx.serialization.json.JsonObject

class TermuxTool(private val env: TermuxEnv, override val name: String) : Tool {
    override val description = when (name) {
        "termux_setup" -> "安装并校验脚本用户空间；失败时检测 Termux App 回退。"
        "shell_exec" -> "在内嵌长驻 bash 会话运行 shell 命令；输出限长，会话状态可跨命令保留。"
        "apt_status" -> "检测内嵌用户空间的 apt/dpkg 包管理是否可用（ELF 编译期 PREFIX 是否已重定位）。"
        else -> "安装 Termux 软件包；apt/dpkg 不可用时明确报错而不误导用户。"
    }
    override val destructive = name == "pkg_install"
    override val parameters = when (name) {
        "shell_exec" -> schemaOf("cmd" to strParam("命令"), "timeout_s" to intParam("超时秒数，默认 60"), required = listOf("cmd"))
        "pkg_install" -> schemaOf("pkg" to strParam("包名"), "confirm" to boolParam("白名单外确认"), required = listOf("pkg"))
        else -> schemaOf()
    }

    /** Cached diagnostic: non-null means apt/dpkg must be treated as unavailable. */
    private var aptDiagnostic: String? = null
    private var aptProbed = false

    private suspend fun probeApt(): String? {
        if (aptProbed) return aptDiagnostic
        aptProbed = true
        val output = runCatching {
            env.exec("command -v apt >/dev/null 2>&1 && apt --version 2>&1 || echo ANDAGENT_APT_UNAVAILABLE", 30)
        }.getOrElse { "probe failed: ${it.message}" }
        // R5 only rewrote text paths: ELF binaries still contain the official
        // compile-time PREFIX, so apt/dpkg cannot resolve their data directory.
        aptDiagnostic = when {
            output.startsWith("ERROR:") -> output
            output.contains("ANDAGENT_APT_UNAVAILABLE") -> "apt 命令不可用"
            output.contains("/data/data/com.termux") -> "ELF 仍带 /data/data/com.termux 编译期 PREFIX：$output"
            output.isBlank() -> "apt 无输出"
            else -> null
        }
        return aptDiagnostic
    }

    override suspend fun execute(args: JsonObject): String {
        return when (name) {
            "termux_setup" -> env.setup()
            "shell_exec" -> env.exec(args.strArg("cmd") ?: return "ERROR: 缺少 cmd", (args.intArg("timeout_s") ?: 60).coerceIn(1, 300))
            "apt_status" -> probeApt()?.let {
                "UNAVAILABLE: 内嵌 Termux 包管理不可用 —— $it。bash/python 直跑层仍可用；如需包管理请安装外部 Termux 并授权 RUN_COMMAND，或对 ELF 做 patchelf 重定位。"
            } ?: "AVAILABLE: apt/dpkg 探测通过，可用 pkg_install 安装软件包"
            else -> run {
                val pkg = args.strArg("pkg") ?: return@run "ERROR: 缺少 pkg"
                if (!pkg.matches(Regex("[a-z0-9][a-z0-9+.-]{0,80}"))) return@run "ERROR: 包名格式无效"
                val diagnostic = probeApt()
                if (diagnostic != null) return@run "ERROR: 内嵌 Termux 的 apt/dpkg 当前不可用（$diagnostic）。ELF 仍带官方编译期 PREFIX，需要 patchelf 重定位；bash/python 直跑层可用。如需安装软件包，请安装外部 Termux 并授权 RUN_COMMAND。"
                if (pkg !in setOf("python", "nodejs-lts", "openssh", "curl", "wget", "git", "jq") && args.strArg("confirm") != "true")
                    return@run "需要确认安装白名单外包 $pkg；确认后以 confirm=true 重试"
                env.exec("pkg install -y $pkg", 300)
            }
        }
    }
}
