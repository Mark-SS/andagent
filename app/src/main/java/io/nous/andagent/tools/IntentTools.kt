package io.nous.andagent.tools

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * R30: 通用 Intent 处理器。agent 可直接构造任意 Intent（action / data / mime /
 * extras / categories / package / component / flags）发给系统，突破 open_uri
 * （ACTION_VIEW）与各工具硬编码 action 的局限。
 *
 * 安全边界：
 * - 系统敏感广播方向（BOOT_COMPLETED / SHUTDOWN / PACKAGE_REMOVED 等）在黑名单，
 *   intent_send 与 intent_broadcast 一律拒绝；
 * - component 模式只允许 exported activity（非 exported 报「不可从外部调用」）；
 * - extras 类型白名单（string/int/bool/double/字符串数组），不接收 Parcelable，
 *   杜绝注入面；
 * - 不设置 ClipData（不随 uri 传文件读写授权）；
 * - SEND/SENDTO 属外发动作，intent_send 标记 destructive（需用户确认）。
 */

val INTENT_TOOLS = listOf("intent_send", "intent_resolve", "intent_broadcast")

/** 系统敏感广播方向黑名单（intent_send / intent_broadcast 共用）。 */
val INTENT_ACTION_BLACKLIST = setOf(
    "android.intent.action.BOOT_COMPLETED",
    "android.intent.action.LOCKED_BOOT_COMPLETED",
    "android.intent.action.SHUTDOWN",
    "android.intent.action.ACTION_SHUTDOWN",
    "android.intent.action.REBOOT",
    "android.intent.action.PACKAGE_REMOVED",
    "android.intent.action.PACKAGE_ADDED",
    "android.intent.action.PACKAGE_REPLACED",
    "android.intent.action.PACKAGE_RESTARTED",
    "android.intent.action.PACKAGE_DATA_CLEARED",
    "android.intent.action.TIME_SET",
    "android.intent.action.DATE_CHANGED",
    "android.intent.action.TIMEZONE_CHANGED",
    "android.intent.action.LOCALE_CHANGED",
    "android.intent.action.AIRPLANE_MODE",
    "android.intent.action.AIRPLANE_MODE_CHANGED",
    "android.intent.action.DEVICE_STORAGE_LOW",
    "android.intent.action.MANAGE_APP_PERMISSIONS",
)

/* --------------------- 纯函数 helper（单测覆盖） --------------------- */

/** extras 白名单类型：基本类型 + 字符串数组，不收 Parcelable。 */
sealed class IntentExtra {
    abstract val key: String
    data class Str(override val key: String, val value: String) : IntentExtra()
    data class Int(override val key: String, val value: Int) : IntentExtra()
    data class Bool(override val key: String, val value: Boolean) : IntentExtra()
    data class Dbl(override val key: String, val value: Double) : IntentExtra()
    data class StrArray(override val key: String, val values: List<String>) : IntentExtra()
}

/** JSON 对象 → 类型化 extras；不支持的类型（嵌套对象/null）抛 IllegalArgumentException。 */
fun mapExtras(json: JsonObject?): List<IntentExtra> {
    if (json == null) return emptyList()
    return json.map { (key, element) ->
        if (element !is JsonPrimitive) {
            throw IllegalArgumentException("extra「$key」类型不支持：仅接受 string/int/bool/double/字符串数组")
        }
        val raw = element.contentOrNull ?: ""
        val asDouble = raw.toDoubleOrNull()
        when {
            element.isString -> IntentExtra.Str(key, raw)
            raw == "true" || raw == "false" -> IntentExtra.Bool(key, raw == "true")
            asDouble != null && raw.contains('.') -> IntentExtra.Dbl(key, asDouble)
            asDouble != null -> IntentExtra.Int(key, raw.toInt())
            else -> throw IllegalArgumentException("extra「$key」类型不支持：仅接受 string/int/bool/double/字符串数组")
        }
    }
}

/** JsonArray 形态的字符串数组 extra（与 mapExtras 互补：extras 值为数组时走这里）。 */
fun mapExtrasArray(key: String, array: JsonArray): IntentExtra.StrArray =
    IntentExtra.StrArray(key, array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull })

/** 把类型化 extra 按 putExtra 语义写入 Intent（只覆盖白名单基本类型）。 */
fun applyExtra(intent: Intent, extra: IntentExtra) {
    when (extra) {
        is IntentExtra.Str -> intent.putExtra(extra.key, extra.value)
        is IntentExtra.Int -> intent.putExtra(extra.key, extra.value)
        is IntentExtra.Bool -> intent.putExtra(extra.key, extra.value)
        is IntentExtra.Dbl -> intent.putExtra(extra.key, extra.value)
        is IntentExtra.StrArray -> intent.putExtra(extra.key, extra.values.toTypedArray())
    }
}

/** "pkg/.Class" 或 "pkg/full.qualified.Class" → (pkg, 完整类名)；非法返回 null。 */
fun parseComponent(raw: String): Pair<String, String>? {
    val parts = raw.split("/", limit = 2)
    if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return null
    val pkg = parts[0].trim()
    val cls = parts[1].trim()
    val full = if (cls.startsWith(".")) pkg + cls else cls
    if (!full.contains('.')) return null
    return pkg to full
}

/* --------------------------- 工具实现 --------------------------- */

class IntentTool(private val context: Context, override val name: String) : Tool {

    /** intent_send / intent_broadcast 都是外发动作，执行前需用户确认。 */
    override val destructive: Boolean = name != "intent_resolve"

    override val description: String = when (name) {
        "intent_send" ->
            "发送任意 Android Intent（通用版 open_uri）：指定 action 构造意图并发给系统，支持拍照（android.media.action.IMAGE_CAPTURE）、分享（android.intent.action.SEND）、选择（PICK）、播放等任意 action，可带 uri/mime/extras/categories/component。不确定设备上谁能响应时先 intent_resolve 探查；标准 scheme（https:// taobao:// geo: 等）优先用 open_uri。"
        "intent_resolve" ->
            "查询设备上哪些应用能响应指定 action 的 Intent（探查「这个动作手机能干吗」），返回接收方应用列表（名称/包名/是否 exported），不执行任何动作。"
        "intent_broadcast" ->
            "发送自定义广播（仅限自定义/厂商 action，系统保护广播被拦截；接收方需 exported 才能收到，否则广播静默无效，工具会返回说明）。"
        else -> "Intent 工具"
    }

    override val parameters: JsonObject = when (name) {
        "intent_send" -> schemaOf(
            "action" to strParam("Intent action，如 android.intent.action.SEND、android.media.action.IMAGE_CAPTURE、自定义 com.example.DO_THING"),
            "uri" to strParam("可选：Intent 的 data 部分，任意 scheme"),
            "mime_type" to strParam("可选：MIME 类型（与 uri 一起用 setDataAndType；无 uri 时 setType）"),
            "extras" to strParam("可选：JSON 对象，key → string/int/bool/double/字符串数组（按类型 putExtra）"),
            "categories" to strParam("可选：JSON 字符串数组，如 [\"android.intent.category.LAUNCHER\"]"),
            "package" to strParam("可选：限定目标应用包名"),
            "component" to strParam("可选：精确指定接收方，如 \"com.example/.MainActivity\"（仅 exported activity 可外部调用）"),
            "flags" to intParam("可选：额外 Intent flags（如 0x10000000 = NEW_TASK）；默认已自动加 NEW_TASK"),
            "mode" to strParam("chooser（默认，弹系统选择器）| direct（唯一接收方时直接发）| confirm（只列出接收方不发送）"),
            required = listOf("action"),
        )
        "intent_resolve" -> schemaOf(
            "action" to strParam("要探查的 action，如 android.intent.action.SEND"),
            "uri" to strParam("可选：data 部分"),
            "mime_type" to strParam("可选：MIME 类型"),
            required = listOf("action"),
        )
        "intent_broadcast" -> schemaOf(
            "action" to strParam("自定义/厂商广播 action（如 com.example.MY_EVENT）；android. 开头的系统广播被拦截"),
            "extras" to strParam("可选：JSON 对象，key → string/int/bool/double/字符串数组"),
            "package" to strParam("可选：限定显式广播目标包名"),
            required = listOf("action"),
        )
        else -> schemaOf()
    }

    override suspend fun execute(args: JsonObject): String = try {
        when (name) {
            "intent_send" -> send(args)
            "intent_resolve" -> resolve(args)
            "intent_broadcast" -> broadcast(args)
            else -> "ERROR: 未知 Intent 工具 $name"
        }
    } catch (e: IllegalArgumentException) {
        "ERROR: ${e.message}"
    } catch (e: Exception) {
        "ERROR: $name 失败（${e.message ?: e.javaClass.simpleName}）"
    }

    /* ------------------------- intent_send ------------------------- */

    private fun send(args: JsonObject): String {
        val action = args.strArg("action")?.trim() ?: return "ERROR: 缺少 action"
        if (action.isEmpty()) return "ERROR: action 为空"
        INTENT_ACTION_BLACKLIST[action]?.let {
            return "ERROR: action「$action」是系统敏感广播方向，禁止通过 intent_send 调用"
        }

        val intent = buildIntent(args, action)
        val mode = args.strArg("mode")?.lowercase()?.takeIf { it.isNotBlank() } ?: "chooser"
        if (mode !in setOf("chooser", "direct", "confirm")) {
            return "ERROR: mode 仅支持 chooser|direct|confirm"
        }

        val pm = context.packageManager
        val pkg = args.strArg("package")?.trim().takeUnless { it.isNullOrEmpty() }
        val componentRaw = args.strArg("component")?.trim().takeUnless { it.isNullOrEmpty() }
        if (pkg != null && componentRaw != null) {
            return "ERROR: package 与 component 二选一"
        }
        if (componentRaw != null) {
            val parsed = parseComponent(componentRaw)
                ?: return "ERROR: component 格式非法（应为 \"包名/.类名\" 或 \"包名/完整类名\"）：$componentRaw"
            val cn = ComponentName(parsed.first, parsed.second)
            val info = try {
                pm.getActivityInfo(cn, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                return "ERROR: 目标 activity 不存在（$componentRaw）；可先 app_list 查包名，或用 intent_resolve 探查"
            }
            if (!info.exported) {
                return "ERROR: $componentRaw 未声明 exported=true，不可从外部调用"
            }
            intent.component = cn
        } else if (pkg != null) {
            intent.setPackage(pkg)
        }

        // mode=confirm：只列接收方，不发送。
        if (mode == "confirm" && intent.component == null) {
            val targets = pm.queryIntentActivities(intent, 0)
            return formatTargets("CONFIRM", action, targets, pm)
                .ifEmpty { "CONFIRM: 设备上没有应用能响应 $action（${describeIntent(intent)}）" }
        }

        val start = intent.component != null || intent.getPackage() != null
        if (!start) {
            val targets = pm.queryIntentActivities(intent, 0)
            if (targets.isEmpty()) {
                return "ERROR: 设备上没有应用能响应 $action（${describeIntent(intent)}）。可先 intent_resolve 探查，或检查 action/mime 拼写"
            }
            if (mode == "direct" && targets.size > 1) {
                return formatTargets(
                    "AMBIGUOUS",
                    action,
                    targets,
                    pm,
                    "有 ${targets.size} 个应用可响应，direct 模式要求唯一接收方；请加 package 参数指定后重试，或改用 mode=chooser/confirm"
                )
            }
        }

        val toStart = if (mode == "chooser" && intent.component == null)
            Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        else intent

        return try {
            context.startActivity(toStart)
            val via = when {
                intent.component != null -> intent.component!!.flattenToString()
                else -> action
            }
            "OPENED: 已发送 Intent（$via${describeIntent(intent)}）"
        } catch (e: ActivityNotFoundException) {
            "ERROR: 没有应用能处理这个 Intent（${e.message ?: "ActivityNotFound"}）；可先 intent_resolve 探查可接收方"
        }
    }

    /* ------------------------ intent_resolve ------------------------ */

    private fun resolve(args: JsonObject): String {
        val action = args.strArg("action")?.trim() ?: return "ERROR: 缺少 action"
        if (action.isEmpty()) return "ERROR: action 为空"
        val intent = buildIntent(args, action)
        val pm = context.packageManager
        val targets = runCatching { pm.queryIntentActivities(intent, 0) }.getOrElse { emptyList() }
        val text = formatTargets("RESOLVED", action, targets, pm)
        return text.ifEmpty {
            "RESOLVED: 设备上没有应用能响应 $action（${describeIntent(intent)}）"
        }
    }

    /* ----------------------- intent_broadcast ----------------------- */

    private fun broadcast(args: JsonObject): String {
        val action = args.strArg("action")?.trim() ?: return "ERROR: 缺少 action"
        if (action.isEmpty()) return "ERROR: action 为空"
        if (INTENT_ACTION_BLACKLIST.contains(action) || action.startsWith("android.")) {
            return "ERROR: intent_broadcast 只接受自定义/厂商 action；action「$action」属于系统保护广播方向，已被拦截"
        }
        if (!action.contains('.')) {
            return "ERROR: 广播 action 必须是完全限定名（含 .），如 com.example.MY_EVENT"
        }
        val extras = try {
            parseExtras(args)
        } catch (e: IllegalArgumentException) {
            return "ERROR: ${e.message}"
        }
        val intent = Intent(action).apply {
            extras.forEach { applyExtra(this, it) }
            args.strArg("package")?.trim()?.takeUnless { it.isNullOrEmpty() }?.let { setPackage(it) }
        }
        context.sendBroadcast(intent)
        val pkgNote = args.strArg("package")?.trim()?.takeUnless { it.isNullOrEmpty() }
            ?.let { "，目标包 $it" } ?: ""
        return "SENT: 广播 $action 已发出$pkgNote。注意：接收方 receiver 需声明 exported=true 才能收到，未声明时广播静默无效"
    }

    /* --------------------------- 公共片段 --------------------------- */

    private fun buildIntent(args: JsonObject, action: String): Intent {
        val intent = Intent(action)
        val uriStr = args.strArg("uri")?.trim().takeUnless { it.isNullOrEmpty() }
        val mime = args.strArg("mime_type")?.trim().takeUnless { it.isNullOrEmpty() }
        if (uriStr != null) {
            val uri = runCatching { Uri.parse(uriStr) }
                .getOrElse { throw IllegalArgumentException("uri 无法解析（${it.message}）") }
            if (mime != null) intent.setDataAndType(uri, mime) else intent.setData(uri)
        } else if (mime != null) {
            intent.setType(mime)
        }
        val extras = try {
            parseExtras(args)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(e.message)
        }
        extras.forEach { applyExtra(intent, it) }
        try {
            (kotlinx.serialization.json.Json.parseToJsonElement(args.strArg("categories") ?: "[]") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.forEach { intent.addCategory(it) }
        } catch (e: Exception) {
            throw IllegalArgumentException("categories 必须是 JSON 字符串数组")
        }
        // 从非 Activity 上下文启动，NEW_TASK 必需；调用方传入的 flags 叠加。
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        args.intArg("flags")?.let { intent.addFlags(it) }
        return intent
    }

    /** extras JSON 对象：primitive 值走类型推断，数组值按字符串数组处理。 */
    private fun parseExtras(args: JsonObject): List<IntentExtra> {
        val obj = args["extras"] as? JsonObject
            ?: args.strArg("extras")?.let { raw ->
                runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw) as? JsonObject }
                    .getOrElse { throw IllegalArgumentException("extras 必须是 JSON 对象") }
                    ?: throw IllegalArgumentException("extras 必须是 JSON 对象")
            }
            ?: return emptyList()
        return obj.map { (key, element) ->
            when {
                element is JsonPrimitive -> mapExtras(JsonObject(mapOf(key to element))).first()
                element is JsonArray -> mapExtrasArray(key, element)
                else -> throw IllegalArgumentException("extra「$key」类型不支持：仅接受 string/int/bool/double/字符串数组")
            }
        }
    }

    private fun describeIntent(intent: Intent): String {
        val parts = mutableListOf<String>()
        intent.data?.let { parts.add("data=$it") }
        intent.type?.let { parts.add("type=$it") }
        return if (parts.isEmpty()) "" else "，${parts.joinToString(" ")}，未带 ClipData（不传文件读写权限）"
    }

    private fun formatTargets(prefix: String, action: String, targets: List<android.content.pm.ResolveInfo>, pm: PackageManager, note: String = ""): String {
        if (targets.isEmpty()) return ""
        return buildString {
            append("$prefix: $action 可由 ${targets.size} 个应用响应：")
            targets.forEachIndexed { i, info ->
                if (i > 0) append("；")
                append("${info.loadLabel(pm)}（${info.activityInfo.packageName}，exported=${info.activityInfo.exported}）")
            }
            if (note.isNotEmpty()) append("。$note")
        }
    }
}
