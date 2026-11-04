package io.nous.andagent.widget

import android.content.Context
import io.nous.andagent.bridge.PermissionBroker
import io.nous.andagent.tools.Tool
import io.nous.andagent.tools.schemaOf
import io.nous.andagent.tools.strArg
import io.nous.andagent.tools.strParam
import io.nous.andagent.widget.model.SpecCodec
import kotlinx.serialization.json.JsonObject

/**
 * Conversation-side widget tools: the agent generates a WidgetSpec JSON
 * (schema documented in the tool description), saves it to the widget
 * library, and optionally requests pinning to the home screen.
 */
class WidgetGenerateTool(private val context: Context) : Tool {
    override val name = "widget_generate"
    override val description =
        "生成一个 Android 桌面小组件配置（WidgetSpec JSON）。schema：{\"schemaVersion\":2,\"name\":\"名称\",\"background\":\"#RRGGBB\",\"layout\":{\"type\":\"column\",\"children\":[{\"type\":\"text\",\"text\":\"…\",\"textSize\":14,\"color\":\"#FFFFFF\"},{\"type\":\"progress\",\"value\":0-100}]},\"data\":{\"sources\":[{\"type\":\"SYSTEM\",\"provider\":\"BATTERY|STEPS_TODAY|CLOCK|STORAGE|DEVICE_INFO\"},{\"type\":\"SCRIPT\",\"script\":\"JS 脚本，可用 andagent.tool(name,args) 调设备工具\",\"ttlSeconds\":900}]}},\"refreshMinutes\":15}。类型限 text/progress/column/row/spacer；数据源字段 source/field 绑定 data.sources 里的 key。用户说「做一个显示电量的小组件」等时使用。生成后调用 widget_save_and_pin 保存。"

    override val parameters = schemaOf(
        "spec_json" to strParam("完整 WidgetSpec JSON 字符串"),
        "validate_only" to strParam("true 时只校验不保存，返回解析结果"),
    )

    override suspend fun execute(args: JsonObject): String {
        val raw = args.strArg("spec_json") ?: return "ERROR: 缺少 spec_json"
        val validateOnly = args.strArg("validate_only") == "true"
        return runCatching {
            val spec = SpecCodec.decode(raw) // throws on invalid
            if (validateOnly == true) {
                "VALID: WidgetSpec 合法（name=${spec.name}）。可调用 widget_save_and_pin 保存并请求上桌"
            } else {
                val id = WidgetApi.save(context, raw)
                "WRITTEN: 小组件已保存（id=$id，name=${spec.name}）。提示用户：到桌面长按空白 → 选择 AndAgent 小组件添加，或由 widget_save_and_pin 直接发起系统 PIN 确认"
            }
        }.getOrElse { "ERROR: WidgetSpec 校验失败：${it.message ?: it.javaClass.simpleName}。请修正 JSON 后重试（字段名/类型必须严格符合 schema）" }
    }
}

class WidgetSaveAndPinTool(private val context: Context) : Tool {
    override val name = "widget_save_and_pin"
    override val description =
        "保存 WidgetSpec 并请求添加到桌面（弹出系统确认框，用户确认后小组件出现在桌面）。spec_json 必须合法。"

    override val parameters = schemaOf(
        "spec_json" to strParam("完整 WidgetSpec JSON"),
    )

    override suspend fun execute(args: JsonObject): String {
        val raw = args.strArg("spec_json") ?: return "ERROR: 缺少 spec_json"
        return runCatching {
            val id = WidgetApi.save(context, raw)
            WidgetApi.pin(context, id)
        }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }
    }
}
