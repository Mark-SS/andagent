package com.dc.andagent.tools

import com.dc.andagent.live.LiveLoopRunner
import kotlinx.serialization.json.JsonObject

val LIVE_TOOLS = listOf("live_start", "live_stop", "live_list", "live_pause")

class LiveTool(private val runner: LiveLoopRunner, override val name: String, private val overlayReady: () -> Boolean = { true }) : Tool {
    override val description: String = when (name) {
        "live_start" -> "按间隔循环执行 JS；用 andagent.tool 取数据、andagent.kv 保存状态、live.result(data) 提交结果。"
        "live_stop" -> "停止指定实时循环；当前 tick 完成后退出。"
        "live_pause" -> "暂停或恢复指定实时循环。"
        else -> "列出实时循环与最近 10 次结果。"
    }
    override val parameters = when (name) {
        "live_start" -> schemaOf("name" to strParam("名称"), "code" to strParam("JS 循环体"),
            "interval_ms" to intParam("间隔毫秒，默认 1000，最小 200"),
            "consumer" to strParam("dialog/overlay/kv，默认 dialog"), required = listOf("name", "code"))
        "live_stop" -> schemaOf("id" to strParam("循环 ID"), required = listOf("id"))
        "live_pause" -> schemaOf("id" to strParam("循环 ID"), "paused" to boolParam("true 暂停，false 恢复"), required = listOf("id", "paused"))
        else -> schemaOf()
    }
    override suspend fun execute(args: JsonObject): String = runCatching {
        when (name) {
            "live_start" -> {
                val consumer = args.strArg("consumer") ?: "dialog"
                if (consumer == "overlay" && !overlayReady()) error("尚未授予悬浮窗权限；已打开授权页，授权后重试")
                runner.start(args.strArg("name") ?: error("缺少 name"),
                    args.strArg("code") ?: error("缺少 code"), args.longArg("interval_ms") ?: 1000, consumer)
            }
            "live_stop" -> runner.stop(args.strArg("id") ?: error("缺少 id"))
            "live_pause" -> runner.pause(args.strArg("id") ?: error("缺少 id"), args.boolArg("paused") ?: error("缺少 paused"))
            else -> runner.list()
        }.toString()
    }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }
}
