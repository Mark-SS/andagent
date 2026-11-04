package io.nous.andagent.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.serialization.json.JsonObject

/**
 * Opens any URI/scheme via ACTION_VIEW — the generic "jump into another app"
 * escape hatch. Covers web links, deep links (tbopen://, mtb://, weixin://,
 * alipays://, snssdk1233:// …), geo:, mailto:, market://, content:// etc.
 *
 * Optional package restricts resolution to one app (deep-link disambiguation);
 * optional action overrides the default VIEW action for exotic schemes.
 */
class OpenUriTool(private val context: Context) : Tool {
    override val name = "open_uri"
    override val description =
        "通过 URI/URL 跳转到其他应用或页面：网页链接（https://…）、应用深链（taobao://、weixin://、alipays://、bilibili:// 等任意 scheme）、地图（geo:）、邮件（mailto:）、应用商店（market://details?id=包名）等。无匹配应用时返回明确错误。"

    override val parameters = schemaOf(
        "uri" to strParam("要打开的 URI，如 https://example.com、taobao://s.taobao.com、geo:39.9,116.4、market://details?id=com.x"),
        "package" to strParam("可选：限定目标应用包名（深链歧义时指定）"),
        "action" to strParam("可选：自定义 Intent action（默认 android.intent.action.VIEW）"),
    )

    override suspend fun execute(args: JsonObject): String {
        val uriStr = args.strArg("uri")?.trim() ?: return "ERROR: 缺少 uri"
        if (uriStr.isEmpty()) return "ERROR: uri 为空"
        val pkg = args.strArg("package")?.trim().takeUnless { it.isNullOrEmpty() }
        val action = args.strArg("action")?.trim().takeUnless { it.isNullOrEmpty() }
            ?: Intent.ACTION_VIEW

        val uri = runCatching { Uri.parse(uriStr) }.getOrElse {
            return "ERROR: uri 无法解析（${it.message}）"
        }
        if (uri.scheme.isNullOrBlank() && !uriStr.startsWith("www.") && !uriStr.contains("://")) {
            // Bare "example.com" — assume https.
            return "ERROR: uri 缺少 scheme；网页请加 https://，应用深链加对应 scheme（如 taobao://）"
        }

        val resolved = uriStr.takeIf { it.contains("://") || it.contains(":") }
            ?: "https://$uriStr"

        val intent = Intent(action, Uri.parse(resolved)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (pkg != null) setPackage(pkg)
        }

        // Query first so we can tell the user which app will handle it.
        val pm = context.packageManager
        val targets = runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
        if (targets.isEmpty()) {
            return buildString {
                append("ERROR: 设备上没有应用能处理这个 URI（$resolved）")
                if (pkg != null) append("；指定包 $pkg 可能未安装或未注册该 scheme")
                append("。可尝试：去掉 package 参数让系统自动匹配，或换用 market://details?id=包名 先安装对应应用")
            }
        }
        val handler = targets.firstOrNull()?.loadLabel(pm) ?: "未知应用"

        return try {
            context.startActivity(intent)
            "OPENED: 已跳转到 $handler（$resolved）${if (targets.size > 1) "；共 ${targets.size} 个应用可处理，已选默认" else ""}"
        } catch (e: ActivityNotFoundException) {
            "ERROR: 跳转被系统拒绝（${e.message ?: "ActivityNotFound"}）"
        } catch (e: Exception) {
            "ERROR: 打开失败（${e.message ?: e.javaClass.simpleName}）"
        }
    }
}
