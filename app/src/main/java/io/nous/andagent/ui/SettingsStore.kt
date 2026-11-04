package io.nous.andagent.ui

import android.content.Context
import android.content.SharedPreferences

/**
 * Minimal settings store. Defaults are hardcoded to the Zhipu (智谱) GLM
 * endpoint; user can override any field in the settings screen.
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
}
