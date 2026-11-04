package io.nous.andagent.ui

import android.content.Context
import android.content.SharedPreferences

/**
 * Settings persisted in SharedPreferences. The WebView reads/writes these
 * through AgentBridge, so no credentials ever ship inside web assets.
 */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("andagent_settings", Context.MODE_PRIVATE)

    companion object {
        // Zhipu GLM defaults (OpenAI-compatible endpoint).
        // NOTE (2026-11-04 live test): glm-5.3-flash returns "余额不足" (code 1113) on
        // this key; glm-4.5-flash works FREE incl. streaming tool calls. Kept as
        // default. glm-4-flash (non-thinking) also verified working.
        const val DEFAULT_BASE_URL = "https://open.bigmodel.cn/api/paas/v4"
        const val DEFAULT_API_KEY = "REDACTED"
        const val DEFAULT_MODEL = "glm-4.5-flash"
        const val DEFAULT_SYSTEM_PROMPT =
            "你是 AndAgent，一个运行在 Android 手机上的智能助手。\n" +
                "回答使用简体中文，简洁、直接、可执行；需要数据时先调用工具，不要编造。\n" +
                "可用工具覆盖：设备/存储/相册状态、日历（calendar_*）、闹钟与计时器、电池、Wi-Fi、剪贴板、本地通知、已安装应用。\n" +
                "写入类操作（日历新增/删除）执行前先向用户说明将要修改的内容。"
    }

    var baseUrl: String
        get() = prefs.getString("base_url", DEFAULT_BASE_URL)!!
        set(value) = prefs.edit().putString("base_url", value).apply()

    var apiKey: String
        get() = prefs.getString("api_key", DEFAULT_API_KEY)!!
        set(value) = prefs.edit().putString("api_key", value).apply()

    var model: String
        get() = prefs.getString("model", DEFAULT_MODEL)!!
        set(value) = prefs.edit().putString("model", value).apply()

    var systemPrompt: String
        get() = prefs.getString("system_prompt", DEFAULT_SYSTEM_PROMPT)!!
        set(value) = prefs.edit().putString("system_prompt", value).apply()
}
