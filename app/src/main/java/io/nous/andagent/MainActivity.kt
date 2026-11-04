package io.nous.andagent

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.webkit.WebViewAssetLoader
import io.nous.andagent.bridge.AgentBridge
import io.nous.andagent.bridge.PermissionBroker
import io.nous.andagent.tools.AlarmSetTool
import io.nous.andagent.tools.AlbumStatsTool
import io.nous.andagent.tools.AppListTool
import io.nous.andagent.tools.BatteryDetailTool
import io.nous.andagent.tools.CalendarAddTool
import io.nous.andagent.tools.CalendarDeleteTool
import io.nous.andagent.tools.CalendarEventsTool
import io.nous.andagent.tools.CalendarListTool
import io.nous.andagent.tools.ClipboardReadTool
import io.nous.andagent.tools.ClipboardWriteTool
import io.nous.andagent.tools.DeviceInfoTool
import io.nous.andagent.tools.GetTimeTool
import io.nous.andagent.tools.NotifySendTool
import io.nous.andagent.tools.StorageScanTool
import io.nous.andagent.tools.TimerSetTool
import io.nous.andagent.tools.Tool
import io.nous.andagent.tools.WifiInfoTool
import io.nous.andagent.ui.SettingsStore

/**
 * Thin shell: a full-screen WebView that hosts the pi agent + assistant-ui.
 * Native capabilities are exposed through [AgentBridge] only.
 */
class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var bridge: AgentBridge
    private lateinit var permissionBroker: PermissionBroker

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            // Tools/suspend callers observe the real grant state; a denial is
            // surfaced as an error string by each tool, never as a crash.
            if (::permissionBroker.isInitialized) permissionBroker.onResult(result)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val settingsStore = SettingsStore(this)
        val appContext = applicationContext
        permissionBroker = PermissionBroker(this, permissionLauncher)
        val tools: Map<String, Tool> = mapOf(
            "get_time" to GetTimeTool(),
            "device_info" to DeviceInfoTool(appContext),
            "storage_scan" to StorageScanTool(appContext),
            "album_stats" to AlbumStatsTool(appContext),
            "calendar_list" to CalendarListTool(appContext, permissionBroker),
            "calendar_events" to CalendarEventsTool(appContext, permissionBroker),
            "calendar_add" to CalendarAddTool(appContext, permissionBroker),
            "calendar_delete" to CalendarDeleteTool(appContext, permissionBroker),
            "alarm_set" to AlarmSetTool(appContext),
            "timer_set" to TimerSetTool(appContext),
            "battery_detail" to BatteryDetailTool(appContext),
            "wifi_info" to WifiInfoTool(appContext, permissionBroker),
            "clipboard_write" to ClipboardWriteTool(appContext),
            "clipboard_read" to ClipboardReadTool(appContext),
            "notify_send" to NotifySendTool(appContext, permissionBroker),
            "app_list" to AppListTool(appContext),
        )

        bridge = AgentBridge(
            webViewProvider = { webView },
            settings = settingsStore,
            tools = tools,
            permissions = permissionBroker,
        )

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.parseColor("#0b0d11"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.textZoom = 100
            addJavascriptInterface(bridge, "AndroidBridge")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val url: Uri = request.url
                    if (url.host == "appassets.androidplatform.net") return false
                    return runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, url))
                        true
                    }.getOrDefault(true)
                }
            }
        }

        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        setContentView(webView)
        window.statusBarColor = Color.parseColor("#0b0d11")
        window.navigationBarColor = Color.parseColor("#0b0d11")
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        webView.loadUrl("https://appassets.androidplatform.net/assets/web/index.html")
        requestMediaPermissions()
    }

    override fun onDestroy() {
        bridge.shutdown()
        webView.destroy()
        super.onDestroy()
    }

    private fun requestMediaPermissions() {
        val needed = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            // Queue through the broker so it never races an on-demand request.
            permissionBroker.request(missing) { }
        }
    }
}
