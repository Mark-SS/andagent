package io.nous.andagent.tools

import io.nous.andagent.shell.TermuxEnv
import kotlinx.serialization.json.JsonObject

class TermuxTool(private val env: TermuxEnv, override val name: String) : Tool {
    override val description = when (name) {
        "termux_setup" -> "安装并校验脚本用户空间；失败时检测 Termux App 回退。"
        "shell_exec" -> "在 Termux 用户空间运行 shell 命令；stdout/stderr 分离且限长。"
        else -> "安装 Termux 软件包；白名单外需再次确认。"
    }
    override val destructive = name == "pkg_install"
    override val parameters = when (name) {
        "shell_exec" -> schemaOf("cmd" to strParam("命令"), "timeout_s" to intParam("超时秒数，默认 60"), required = listOf("cmd"))
        "pkg_install" -> schemaOf("pkg" to strParam("包名"), "confirm" to boolParam("白名单外确认"), required = listOf("pkg"))
        else -> schemaOf()
    }
    override suspend fun execute(args: JsonObject): String { return when (name) {
        "termux_setup" -> env.setup()
        "shell_exec" -> env.exec(args.strArg("cmd") ?: return "ERROR: 缺少 cmd", (args.intArg("timeout_s") ?: 60).coerceIn(1, 300))
        else -> {
            val pkg = args.strArg("pkg") ?: return "ERROR: 缺少 pkg"
            if (!pkg.matches(Regex("[a-z0-9][a-z0-9+.-]{0,80}"))) return "ERROR: 包名格式无效"
            if (pkg !in setOf("python", "nodejs-lts", "openssh", "curl", "wget", "git", "jq") && args.strArg("confirm") != "true")
                return "需要确认安装白名单外包 $pkg；确认后以 confirm=true 重试"
            env.exec("pkg install -y $pkg", 300)
        }
    }
    }
}
