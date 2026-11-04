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
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.webkit.WebViewAssetLoader
import io.nous.andagent.bridge.AgentBridge
import io.nous.andagent.bridge.PermissionBroker
import io.nous.andagent.shell.ShizukuShell
import io.nous.andagent.shell.TermuxEnv
import io.nous.andagent.script.QuickJsEngine
import io.nous.andagent.script.JsEvalTool
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
import io.nous.andagent.tools.OpenSettingsTool
import io.nous.andagent.tools.PhoneCallTool
import io.nous.andagent.tools.SmsSendTool
import io.nous.andagent.tools.StepsTodayTool
import io.nous.andagent.tools.StorageScanTool
import io.nous.andagent.tools.TimerSetTool
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
        val tools: MutableMap<String, Tool> = mutableMapOf()
        quickJsEngine = QuickJsEngine(tools, scriptToolExecutor)
        tools.putAll(mapOf(
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
        ))

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
        if (::quickJsEngine.isInitialized) quickJsEngine.shutdown()
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
