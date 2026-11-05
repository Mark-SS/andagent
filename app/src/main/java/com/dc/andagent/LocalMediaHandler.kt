package com.dc.andagent

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream

/**
 * Read-only media route. Serves, in order:
 *  1. the app's external files directory (own media, web_embed staged pages),
 *  2. /sdcard/Download/andagent/ — tool outputs (R39),
 *  3. /sdcard/Download/ — public downloads, read-only (R39).
 * Public roots need media permissions granted at startup; if a path is not
 * readable on a given ROM the route answers 404 like a missing file.
 */
class LocalMediaHandler(context: Context) : WebViewAssetLoader.PathHandler {
    /** base 目录 + 可选的路由前缀（命中该前缀时从相对路径剥掉再拼到 base）。 */
    private data class Root(val base: File, val strip: String)

    private val roots: List<Root> = buildList {
        context.getExternalFilesDir(null)?.canonicalFile?.let { add(Root(it, "")) }
        // /local/Download/andagent/x → <Download>/andagent/x；/local/Download/x → <Download>/x
        add(Root(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "andagent"), "Download/andagent/"))
        add(Root(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Download/"))
    }.map { Root(it.base.canonicalFile, it.strip) }
    private val publicRootPrefix: String = roots.getOrNull(1)?.base?.path ?: "\u0000none"
    private val types = mapOf("mp3" to "audio/mpeg", "m4a" to "audio/mp4", "mp4" to "video/mp4", "webm" to "video/webm", "ogg" to "audio/ogg", "wav" to "audio/wav", "flac" to "audio/flac", "html" to "text/html", "htm" to "text/html", "css" to "text/css", "js" to "application/javascript", "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif", "svg" to "image/svg+xml")
    private val mediaMimes = setOf("audio/mpeg", "audio/mp4", "video/mp4", "video/webm", "audio/ogg", "audio/wav", "audio/flac", "image/png", "image/jpeg", "image/gif", "image/svg+xml")
    override fun handle(path: String): WebResourceResponse? = response(path, null)
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (request.url.host != "appassets.androidplatform.net" || !request.url.path.orEmpty().startsWith("/local/")) return null
        if (request.method != "GET") return rejected(405)
        return response(request.url.encodedPath?.removePrefix("/local/").orEmpty(), request.requestHeaders["Range"])
    }
    private fun rejected(code: Int) = WebResourceResponse("text/plain", "UTF-8", code, if (code == 404) "Not Found" else "Method Not Allowed", emptyMap(), "".byteInputStream())
    private fun response(encoded: String, range: String?): WebResourceResponse? {
        val decoded = Uri.decode(encoded)
        if (decoded.startsWith('/') || decoded.contains('\\') || decoded.split('/').any { it == ".." || it == "." }) return rejected(404)
        var file: File? = null
        for (root in roots) {
            val relative = if (root.strip.isNotEmpty() && decoded.startsWith(root.strip)) decoded.removePrefix(root.strip) else decoded
            if (relative.isEmpty()) continue
            val candidate = File(root.base, relative).canonicalFile
            if (candidate.path.startsWith(root.base.path + File.separator) && candidate.isFile && candidate.canRead()) {
                file = candidate
                break
            }
        }
        val found = file ?: return rejected(404)
        val mime = types[found.extension.lowercase()] ?: return rejected(404)
        // 公共 Download 目录只放行媒体/图片类型：HTML/JS 若也从 appassets 源
        // 提供，恶意下载页面就能在主 WebView 同源执行（可触达 AndroidBridge）。
        if (found.canonicalFile.path.startsWith(publicRootPrefix) &&
            mime !in mediaMimes
        ) return rejected(404)
        val length = found.length()
        val match = range?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
        val start = match?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val end = match?.groupValues?.get(2)?.toLongOrNull()?.coerceAtMost(length - 1) ?: (length - 1)
        if (start >= length || end < start) return rejected(404)
        val stream = found.inputStream().apply { skip(start) }
        val bounded = object : FilterInputStream(stream) {
            var remaining = end - start + 1
            override fun read(): Int { if (remaining <= 0) return -1; val n = super.read(); if (n >= 0) remaining--; return n }
            override fun read(b: ByteArray, off: Int, len: Int): Int { if (remaining <= 0) return -1; val n = super.read(b, off, minOf(len.toLong(), remaining).toInt()); if (n > 0) remaining -= n; return n }
        }
        val headers = mutableMapOf("Accept-Ranges" to "bytes", "Content-Length" to (end - start + 1).toString())
        if (match != null) headers["Content-Range"] = "bytes $start-$end/$length"
        return WebResourceResponse(mime, null, if (match != null) 206 else 200, if (match != null) "Partial Content" else "OK", headers, bounded)
    }
}
