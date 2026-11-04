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
import io.nous.andagent.tools.AlbumStatsTool
import io.nous.andagent.tools.DeviceInfoTool
import io.nous.andagent.tools.GetTimeTool
import io.nous.andagent.tools.StorageScanTool
import io.nous.andagent.tools.Tool
import io.nous.andagent.ui.SettingsStore

/**
 * Thin shell: a full-screen WebView that hosts the pi agent + assistant-ui.
 * Native capabilities are exposed through [AgentBridge] only.
 */
class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var bridge: AgentBridge

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            /* tools degrade gracefully when denied */
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val settingsStore = SettingsStore(this)
        val appContext = applicationContext
        val tools: Map<String, Tool> = mapOf(
            "get_time" to GetTimeTool(),
            "device_info" to DeviceInfoTool(appContext),
            "storage_scan" to StorageScanTool(appContext),
            "album_stats" to AlbumStatsTool(appContext),
        )

        bridge = AgentBridge(
            webViewProvider = { webView },
            settings = settingsStore,
            tools = tools,
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
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}
