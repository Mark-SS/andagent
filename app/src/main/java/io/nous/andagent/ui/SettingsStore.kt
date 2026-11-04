package io.nous.andagent.ui

import android.content.Context
import android.content.SharedPreferences

/** Minimal settings store; M5 will add a proper settings screen. */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("andagent_settings", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString("base_url", "https://api.deepseek.com/v1")!!
        set(value) = prefs.edit().putString("base_url", value).apply()

    var apiKey: String
        get() = prefs.getString("api_key", "")!!
        set(value) = prefs.edit().putString("api_key", value).apply()

    var model: String
        get() = prefs.getString("model", "deepseek-chat")!!
        set(value) = prefs.edit().putString("model", value).apply()
}
