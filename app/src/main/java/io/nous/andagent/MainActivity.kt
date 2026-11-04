package io.nous.andagent

import android.Manifest
import android.app.Dialog
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Button
import android.nfc.NfcAdapter
import io.nous.andagent.tools.NfcSession
import io.nous.andagent.tools.NfcTool
import io.nous.andagent.tools.WebEmbedTool
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
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.webkit.WebViewAssetLoader
import io.nous.andagent.bridge.AgentBridge
import io.nous.andagent.bridge.DbGuiBridge
import io.nous.andagent.bridge.PermissionBroker
import io.nous.andagent.shell.ShizukuShell
import io.nous.andagent.shell.TermuxEnv
import io.nous.andagent.script.QuickJsEngine
import io.nous.andagent.script.JsEvalTool
import io.nous.andagent.tools.ScriptFsTool
import io.nous.andagent.tools.SqliteTool
import io.nous.andagent.tools.DbGuiTool
import io.nous.andagent.tools.TermuxTool
import io.nous.andagent.tools.ScreenCaptureTool
import io.nous.andagent.tools.ScreenRecordH264Tool
import io.nous.andagent.tools.InputTool
import io.nous.andagent.tools.BtScanTool
import io.nous.andagent.tools.BtPairedListTool
import io.nous.andagent.tools.BtConnectTool
import io.nous.andagent.tools.WifiConnectTool
import io.nous.andagent.tools.ShellConnectivityTool
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
import io.nous.andagent.tools.FileDownloadTool
import io.nous.andagent.tools.FileListTool
import io.nous.andagent.tools.FileReadTool
import io.nous.andagent.tools.FileSearchTool
import io.nous.andagent.tools.DocumentCreateTool
import io.nous.andagent.tools.FileWriteTool
import io.nous.andagent.tools.GetTimeTool
import io.nous.andagent.tools.NotifySendTool
import io.nous.andagent.tools.ScreenRecordListTool
import io.nous.andagent.tools.ScreenRecordStopTool
import io.nous.andagent.tools.ScreenRecordTool
import io.nous.andagent.tools.SensorListTool
import io.nous.andagent.tools.SensorReadTool
import io.nous.andagent.tools.StepsHistoryTool
import io.nous.andagent.tools.MicRecordTool
import io.nous.andagent.tools.LocationGetTool
import io.nous.andagent.tools.LocationStatusTool
import io.nous.andagent.tools.ImageSaveTool
import io.nous.andagent.tools.FilePickResultBridge
import io.nous.andagent.tools.FilePickTool
import io.nous.andagent.tools.OpenFolderTool
import io.nous.andagent.tools.AlbumOpenTool
import io.nous.andagent.tools.AlbumPhotosTool
import io.nous.andagent.tools.OpenSettingsTool
import io.nous.andagent.tools.OpenUriTool
import io.nous.andagent.widget.WidgetGenerateTool
import io.nous.andagent.widget.WidgetSaveAndPinTool
import io.nous.andagent.tools.PhoneCallTool
import io.nous.andagent.tools.SmsSendTool
import io.nous.andagent.tools.StepsTodayTool
import io.nous.andagent.tools.StorageScanTool
import io.nous.andagent.tools.TimerSetTool
import io.nous.andagent.widget.data.WidgetToolRuntime
import io.nous.andagent.tools.Tool
import io.nous.andagent.tools.WifiInfoTool
import io.nous.andagent.ui.SettingsStore
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicReference

/**
 * Thin shell: a full-screen WebView that hosts the pi agent + assistant-ui.
 * Native capabilities are exposed through [AgentBridge] only.
 */
class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var nfcSession: NfcSession
    private var embeddedDialog: Dialog? = null
    private var dbGuiDialog: Dialog? = null
    private lateinit var assetLoader: WebViewAssetLoader
    private lateinit var localMedia: LocalMediaHandler
    private lateinit var bridge: AgentBridge
    private lateinit var permissionBroker: PermissionBroker
    private lateinit var shizuku: ShizukuShell
    private lateinit var quickJsEngine: QuickJsEngine
    private val scriptToolExecutor = java.util.concurrent.Executors.newFixedThreadPool(2)

    private val healthPermissionResult = AtomicReference<Set<String>?>(null)
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Tool-level on-demand Health Connect authorization: launches the
     * consent screen and suspends until the user answers (or 90s timeout).
     * Called from StepsTools when a read hits the permission wall, so the
     * dialog appears exactly when the agent needs it.
     */
    private val healthPermissionRequester: suspend () -> Boolean = {
        suspendCancellableCoroutine { cont ->
            mainHandler.post {
                try {
                    val status = runCatching { HealthConnectClient.getSdkStatus(this) }
                        .getOrElse { HealthConnectClient.SDK_UNAVAILABLE }
                    if (status != HealthConnectClient.SDK_AVAILABLE) {
                        cont.resume(false)
                        return@post
                    }
                    healthPermissionResult.set(null)
                    healthPermissionLauncher.launch(
                        setOf(HealthPermission.getReadPermission(StepsRecord::class)),
                    )
                    // Poll the AtomicReference set by the launcher callback.
                    val deadline = System.currentTimeMillis() + 90_000
                    fun poll() {
                        if (!cont.isActive) return
                        val granted = healthPermissionResult.get()
                        if (granted != null) {
                            cont.resume(
                                granted.contains(
                                    HealthPermission.getReadPermission(StepsRecord::class),
                                ),
                            )
                        } else if (System.currentTimeMillis() > deadline) {
                            cont.resume(false)
                        } else {
                            mainHandler.postDelayed(::poll, 300)
                        }
                    }
                    poll()
                } catch (_: Throwable) {
                    if (cont.isActive) cont.resume(false)
                }
            }
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            // Tools/suspend callers observe the real grant state; a denial is
            // surfaced as an error string by each tool, never as a crash.
            if (::permissionBroker.isInitialized) permissionBroker.onResult(result)
        }

    private val healthPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { granted: Set<String> ->
        healthPermissionResult.set(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val settingsStore = SettingsStore(this)
        val appContext = applicationContext
        permissionBroker = PermissionBroker(this, permissionLauncher)
        shizuku = ShizukuShell(appContext).also { it.init() }

        val termuxEnv = TermuxEnv(appContext, shizuku)
        val btConnect = BtConnectTool(appContext, permissionBroker)
        nfcSession = NfcSession(this) { message ->
            if (::bridge.isInitialized) bridge.emitNotice(message)
        }
        val tools: MutableMap<String, Tool> = mutableMapOf()
        quickJsEngine = QuickJsEngine(tools, scriptToolExecutor)
        tools.putAll(mapOf(
            "get_time" to GetTimeTool(),
            "nfc_status" to NfcTool(nfcSession, "nfc_status"),
            "nfc_read" to NfcTool(nfcSession, "nfc_read"),
            "nfc_write_text" to NfcTool(nfcSession, "nfc_write_text"),
            "nfc_write_uri" to NfcTool(nfcSession, "nfc_write_uri"),
            "web_embed" to WebEmbedTool(),
            "device_info" to DeviceInfoTool(appContext),
            "storage_scan" to StorageScanTool(appContext),
            "album_stats" to AlbumStatsTool(appContext),
            "album_photos" to AlbumPhotosTool(appContext),
            "album_open" to AlbumOpenTool(appContext),
            "calendar_list" to CalendarListTool(appContext, permissionBroker),
            "calendar_events" to CalendarEventsTool(appContext, permissionBroker),
            "calendar_add" to CalendarAddTool(appContext, permissionBroker),
            "calendar_delete" to CalendarDeleteTool(appContext, permissionBroker),
            "alarm_set" to AlarmSetTool(appContext),
            "timer_set" to TimerSetTool(appContext),
            "battery_detail" to BatteryDetailTool(appContext),
            "wifi_info" to WifiInfoTool(appContext, permissionBroker),
            "termux_setup" to TermuxTool(termuxEnv, "termux_setup"),
            "shell_exec" to TermuxTool(termuxEnv, "shell_exec"),
            "pkg_install" to TermuxTool(termuxEnv, "pkg_install"),
            "bt_scan" to BtScanTool(appContext, permissionBroker),
            "bt_paired_list" to BtPairedListTool(appContext, permissionBroker),
            "bt_connect" to btConnect,
            "wifi_connect" to WifiConnectTool(appContext),
            "wifi_toggle" to ShellConnectivityTool(shizuku, "wifi_toggle"),
            "bt_toggle" to ShellConnectivityTool(shizuku, "bt_toggle"),
            "wifi_connect_strong" to ShellConnectivityTool(shizuku, "wifi_connect_strong"),
            "wifi_saved_list" to ShellConnectivityTool(shizuku, "wifi_saved_list"),
            "wifi_forget" to ShellConnectivityTool(shizuku, "wifi_forget"),
            "bt_connect_direct" to ShellConnectivityTool(shizuku, "bt_connect_direct", btConnect),
            "clipboard_write" to ClipboardWriteTool(appContext),
            "clipboard_read" to ClipboardReadTool(appContext),
            "notify_send" to NotifySendTool(appContext, permissionBroker),
            "app_list" to AppListTool(appContext),
            // R3a: Shizuku-backed screen recording
            "screen_capture" to ScreenCaptureTool(appContext, shizuku),
            "screen_record_h264" to ScreenRecordH264Tool(appContext, shizuku),
            "input_tap" to InputTool(shizuku, "input_tap"),
            "input_swipe" to InputTool(shizuku, "input_swipe"),
            "input_text" to InputTool(shizuku, "input_text"),
            "screen_record" to ScreenRecordTool(appContext, shizuku),
            "screen_record_stop" to ScreenRecordStopTool(appContext, shizuku),
            "screen_record_list" to ScreenRecordListTool(appContext, shizuku, permissionBroker),
            // R3a: files
            "file_download" to FileDownloadTool(appContext, permissionBroker),
            "file_search" to FileSearchTool(appContext, permissionBroker),
            "file_write" to FileWriteTool(appContext, permissionBroker),
            "file_read" to FileReadTool(appContext),
            "file_list" to FileListTool(appContext, permissionBroker),
            // R3a: sensors + health
            "sensor_list" to SensorListTool(appContext),
            "sensor_read" to SensorReadTool(appContext),
            "steps_today" to StepsTodayTool(appContext, healthPermissionRequester),
            "open_settings" to OpenSettingsTool(appContext),
            "open_uri" to OpenUriTool(appContext),
            "widget_generate" to WidgetGenerateTool(appContext),
            "widget_save_and_pin" to WidgetSaveAndPinTool(appContext),
            "mic_record" to MicRecordTool(appContext, permissionBroker),
            "phone_call" to PhoneCallTool(appContext, permissionBroker),
            "sms_send" to SmsSendTool(appContext, permissionBroker),
            "location_get" to LocationGetTool(appContext, permissionBroker),
            "location_status" to LocationStatusTool(appContext),
            "open_folder" to OpenFolderTool(appContext),
            "file_pick" to FilePickTool(appContext, FilePickResultBridge),
            "image_save" to ImageSaveTool(appContext, permissionBroker),
            "steps_history" to StepsHistoryTool(appContext, healthPermissionRequester),
            "js_eval" to JsEvalTool(quickJsEngine),
            "script_save" to ScriptFsTool(appContext, quickJsEngine, "script_save"),
            "script_list" to ScriptFsTool(appContext, quickJsEngine, "script_list"),
            "script_read" to ScriptFsTool(appContext, quickJsEngine, "script_read"),
            "script_run" to ScriptFsTool(appContext, quickJsEngine, "script_run"),
            "script_delete" to ScriptFsTool(appContext, quickJsEngine, "script_delete"),
            "script_check" to ScriptFsTool(appContext, quickJsEngine, "script_check"),
            "json_fix" to ScriptFsTool(appContext, quickJsEngine, "json_fix"),
            "db_create" to SqliteTool(appContext, "db_create"),
            "db_list" to SqliteTool(appContext, "db_list"),
            "db_tables" to SqliteTool(appContext, "db_tables"),
            "db_schema" to SqliteTool(appContext, "db_schema"),
            "db_exec" to SqliteTool(appContext, "db_exec"),
            "db_query" to SqliteTool(appContext, "db_query"),
            "db_drop" to SqliteTool(appContext, "db_drop"),
            "db_gui" to DbGuiTool { name -> dbGuiOpen(name) },
            "docx_create" to DocumentCreateTool(appContext, permissionBroker, "docx"),
            "pptx_create" to DocumentCreateTool(appContext, permissionBroker, "pptx"),
            "xlsx_create" to DocumentCreateTool(appContext, permissionBroker, "xlsx"),
        ))

        WidgetToolRuntime.attach(tools, quickJsEngine)

        bridge = AgentBridge(
            webViewProvider = { webView },
            appContext = appContext,
            settings = settingsStore,
            tools = tools,
            permissions = permissionBroker,
            appVersion = BuildConfig.VERSION_NAME,
            shizukuStatus = { shizuku.statusJson() },
            shizukuRequest = { shizuku.requestPermission() },
            healthPermissionRequest = { requestHealthPermission() },
            termuxStatus = { termuxEnv.status() },
            embeddedWebviewOpen = { uri -> openEmbeddedWebview(uri) },
            dbGuiOpen = { name -> dbGuiOpen(name) },
            termuxGrant = {
                if (!shizuku.isGranted()) "ERROR: 请先启动 Shizuku 并在设置页授权 AndAgent"
                else {
                    val result = shizuku.run("pm grant io.nous.andagent com.termux.permission.RUN_COMMAND")
                    if (!result.ok) "ERROR: pm grant 失败：${result.stderr.ifBlank { result.stdout.ifBlank { "exit=${result.exitCode}" } }}"
                    else if (appContext.checkSelfPermission("com.termux.permission.RUN_COMMAND") != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        "ERROR: pm grant 已返回成功，但权限状态仍未生效；请检查 Termux 安装及签名"
                    else "Termux RUN_COMMAND 已授权；${termuxEnv.status()}"
                }
            },
        )

        localMedia = LocalMediaHandler(this)
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/local/", localMedia)
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
                ): WebResourceResponse? = localMedia.intercept(request) ?: assetLoader.shouldInterceptRequest(request.url)

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

    override fun onResume() { super.onResume(); if (::nfcSession.isInitialized) nfcSession.onResume() }

    override fun onPause() { if (::nfcSession.isInitialized) nfcSession.onPause(); super.onPause() }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); if (::nfcSession.isInitialized) nfcSession.onNewIntent(intent) }

    private fun openEmbeddedWebview(raw: String): String {
        val uri = Uri.parse(raw)
        if (uri.scheme != "https" || uri.host.isNullOrBlank()) return "ERROR: 内嵌浏览器仅允许 HTTPS 网页"
        runOnUiThread {
            embeddedDialog?.dismiss()
            val browser = WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = localMedia.intercept(request) ?: assetLoader.shouldInterceptRequest(request.url)
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.url.scheme != "https"
                }
            }
            val dialog = Dialog(this)
            val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(16, 12, 16, 12) }
            header.addView(TextView(this).apply { text = uri.host; textSize = 16f; setSingleLine(true) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            header.addView(Button(this).apply { text = "关闭"; setOnClickListener { dialog.dismiss() } })
            val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(header); addView(browser, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)) }
            dialog.setContentView(container)
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            dialog.setOnDismissListener { browser.stopLoading(); browser.loadUrl("about:blank"); (browser.parent as? ViewGroup)?.removeView(browser); browser.destroy(); if (embeddedDialog === dialog) embeddedDialog = null }
            embeddedDialog = dialog
            dialog.show()
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            browser.loadUrl(raw)
        }
        return "OPENED"
    }

    private fun dbGuiOpen(name: String): String {
        if (!name.matches(Regex("[A-Za-z0-9_]{1,64}"))) return "ERROR: 数据库名只允许字母数字下划线，最长 64 字符"
        if (!getDatabasePath("andagent_${name}.db").isFile) return "ERROR: 数据库不存在，请先调用 db_list"
        runOnUiThread {
            dbGuiDialog?.dismiss()
            val dialog = Dialog(this)
            val browser = WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                addJavascriptInterface(DbGuiBridge(this@MainActivity, name) { runOnUiThread { dialog.dismiss() } }, "DbGuiBridge")
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        assetLoader.shouldInterceptRequest(request.url)
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        request.url.host != "appassets.androidplatform.net"
                }
            }
            dialog.setContentView(browser)
            dialog.setOnDismissListener {
                browser.stopLoading()
                browser.loadUrl("about:blank")
                browser.removeJavascriptInterface("DbGuiBridge")
                (browser.parent as? ViewGroup)?.removeView(browser)
                browser.destroy()
                if (dbGuiDialog === dialog) dbGuiDialog = null
            }
            dbGuiDialog = dialog
            dialog.show()
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            browser.loadUrl("https://appassets.androidplatform.net/assets/dbgui/index.html")
        }
        return "已打开数据库浏览器：$name"
    }

    override fun onDestroy() {
        dbGuiDialog?.dismiss()
        embeddedDialog?.dismiss()
        if (::nfcSession.isInitialized) nfcSession.close()
        bridge.shutdown()
        if (::quickJsEngine.isInitialized) { WidgetToolRuntime.detach(quickJsEngine); quickJsEngine.shutdown() }
        scriptToolExecutor.shutdownNow()
        if (::shizuku.isInitialized) shizuku.shutdown()
        webView.destroy()
        super.onDestroy()
    }

    /**
     * Health Connect uses the Activity Result API; the bridge only posts here.
     * Returns an immediate JSON ack / error for Settings.tsx.
     */
    private fun requestHealthPermission(): String {
        val status = runCatching { HealthConnectClient.getSdkStatus(this) }.getOrElse { -1 }
        when (status) {
            HealthConnectClient.SDK_UNAVAILABLE -> return buildJsonObject {
                put("requested", false)
                put("message", "未安装 Health Connect。请先安装应用（market://details?id=com.google.android.apps.healthdata）")
            }.toString()
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> return buildJsonObject {
                put("requested", false)
                put("message", "Health Connect 需要更新，请先在应用商店更新")
            }.toString()
        }
        val permissions = setOf(HealthPermission.getReadPermission(StepsRecord::class))
        runOnUiThread {
            runCatching { healthPermissionLauncher.launch(permissions) }
        }
        return buildJsonObject {
            put("requested", true)
            put("message", "已打开 Health Connect 授权页，请允许读取步数")
        }.toString()
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
