package io.nous.andagent

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream

/** Read-only media route under the app's external files directory. */
class LocalMediaHandler(context: Context) : WebViewAssetLoader.PathHandler {
    private val root = context.getExternalFilesDir(null)?.canonicalFile
    private val types = mapOf("mp3" to "audio/mpeg", "m4a" to "audio/mp4", "mp4" to "video/mp4", "webm" to "video/webm", "ogg" to "audio/ogg", "wav" to "audio/wav", "flac" to "audio/flac", "html" to "text/html", "htm" to "text/html", "css" to "text/css", "js" to "application/javascript", "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif", "svg" to "image/svg+xml")
    override fun handle(path: String): WebResourceResponse? = response(path, null)
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (request.url.host != "appassets.androidplatform.net" || !request.url.path.orEmpty().startsWith("/local/")) return null
        if (request.method != "GET") return rejected(405)
        return response(request.url.encodedPath?.removePrefix("/local/").orEmpty(), request.requestHeaders["Range"])
    }
    private fun rejected(code: Int) = WebResourceResponse("text/plain", "UTF-8", code, if (code == 404) "Not Found" else "Method Not Allowed", emptyMap(), "".byteInputStream())
    private fun response(encoded: String, range: String?): WebResourceResponse? {
        val base = root ?: return rejected(404)
        val decoded = Uri.decode(encoded)
        if (decoded.startsWith('/') || decoded.contains('\\') || decoded.split('/').any { it == ".." || it == "." }) return rejected(404)
        val file = File(base, decoded).canonicalFile
        if (!file.path.startsWith(base.path + File.separator) || !file.isFile) return rejected(404)
        val mime = types[file.extension.lowercase()] ?: return rejected(404)
        val length = file.length()
        val match = range?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
        val start = match?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val end = match?.groupValues?.get(2)?.toLongOrNull()?.coerceAtMost(length - 1) ?: (length - 1)
        if (start >= length || end < start) return rejected(404)
        val stream = file.inputStream().apply { skip(start) }
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
