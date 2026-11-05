package com.dc.andagent.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import com.dc.andagent.bridge.PermissionBroker
import kotlinx.serialization.json.JsonObject

/**
 * Phone calls and SMS.
 *
 * Calls use ACTION_CALL (direct dial, needs CALL_PHONE runtime permission);
 * without the grant we fall back to ACTION_DIAL (pre-filled dialer, no
 * permission needed, user taps the call button).
 *
 * SMS: on a device without telephony (tablets) both tools degrade with a
 * clear error. SEND_SMS is a runtime permission requested on demand.
 */
class PhoneCallTool(private val context: Context, private val permissions: PermissionBroker) : Tool {
    override val name = "phone_call"
    override val description =
        "拨打电话：直接拨打（有 CALL_PHONE 权限时）或打开拨号盘预填号码（用户按通话键确认）。"

    override val parameters = schemaOf(
        "number" to strParam("电话号码，如 10086 或 +8613800138000"),
    )

    override suspend fun execute(args: JsonObject): String {
        val number = args.strArg("number")?.trim() ?: return "ERROR: 缺少 number"
        if (number.isEmpty()) return "ERROR: 号码为空"
        val hasTelephony = context.packageManager.hasSystemFeature("android.hardware.telephony")
        if (!hasTelephony) return "ERROR: 此设备不支持电话功能（可能是平板/Wi-Fi only 设备）"

        val direct = permissions.ensure(listOf(Manifest.permission.CALL_PHONE))
        val intent = if (direct) {
            Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number)))
        } else {
            // No CALL_PHONE → open dialer pre-filled; user confirms by hand.
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            if (direct) "CALLING: 已直接拨打 $number"
            else "DIALER: 已打开拨号盘并填入 $number，请按通话键确认（未获 CALL_PHONE 权限，系统策略要求人工确认）"
        } catch (e: Exception) {
            "ERROR: 拨打失败（${e.message ?: e.javaClass.simpleName}）"
        }
    }
}

class SmsSendTool(private val context: Context, private val permissions: PermissionBroker) : Tool {
    override val name = "sms_send"
    override val description =
        "发送短信到指定号码（需要 SEND_SMS 权限，首次调用会弹授权）。"

    override val parameters = schemaOf(
        "number" to strParam("接收方号码"),
        "text" to strParam("短信内容（超长会按系统分段自动发送）"),
    )

    override suspend fun execute(args: JsonObject): String {
        val number = args.strArg("number")?.trim() ?: return "ERROR: 缺少 number"
        val text = args.strArg("text") ?: return "ERROR: 缺少 text"
        if (number.isEmpty() || text.isEmpty()) return "ERROR: 号码或内容为空"
        val hasTelephony = context.packageManager.hasSystemFeature("android.hardware.telephony")
        if (!hasTelephony) return "ERROR: 此设备不支持短信功能"

        if (!permissions.ensure(listOf(Manifest.permission.SEND_SMS))) {
            return "ERROR: 短信权限未授予（SEND_SMS 被拒）。可到系统设置手动开启后重试"
        }
        return try {
            val sm = if (android.os.Build.VERSION.SDK_INT >= 31) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION") SmsManager.getDefault()
            } ?: return "ERROR: 无法获取 SmsManager"
            val parts = sm.divideMessage(text)
            if (parts.size == 1) {
                sm.sendTextMessage(number, null, text, null, null)
            } else {
                sm.sendMultipartTextMessage(number, null, parts, null, null)
            }
            "SENT: 短信已发送至 $number（${text.length} 字符${if (parts.size > 1) "，分 ${parts.size} 条" else ""}）"
        } catch (e: SecurityException) {
            "ERROR: 短信权限被系统拒绝（${e.message ?: "SecurityException"}）"
        } catch (e: Exception) {
            "ERROR: 发送失败（${e.message ?: e.javaClass.simpleName}）"
        }
    }
}
