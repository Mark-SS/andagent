package io.nous.andagent.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private const val ANDROID_CHROME_UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

data class WebResult(val title: String, val url: String, val snippet: String, val engine: String) {
    fun toJson(): JsonObject = buildJsonObject {
        put("title", title); put("url", url); put("snippet", snippet); put("engine", engine)
    }
}

/** Lightweight HTML handling for search snippets and readable page text. */
internal object WebHtml {
    private val resultStart = Regex("<li\\b[^>]*class\\s*=\\s*['\"][^'\"]*\\bb_algo\\b[^'\"]*['\"][^>]*>", RegexOption.IGNORE_CASE)
    private val header = Regex("<div\\b[^>]*class\\s*=\\s*['\"][^'\"]*\\bb_algoheader\\b[^'\"]*['\"][^>]*>(.*?)</div>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val anchor = Regex("<a\\b[^>]*href\\s*=\\s*['\"]([^'\"]+)['\"][^>]*>(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val h2 = Regex("<h2\\b[^>]*>(.*?)</h2>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val paragraph = Regex("<p\\b[^>]*>(.*?)</p>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val entities = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "mdash" to "—", "ndash" to "–", "hellip" to "…")

    fun decode(s: String): String = Regex("&(#(?:x[0-9a-fA-F]+|[0-9]+)|[a-zA-Z]+);").replace(s) { m ->
        val key = m.groupValues[1]
        if (key.startsWith("#")) runCatching {
            val n = if (key.startsWith("#x", true)) key.drop(2).toInt(16) else key.drop(1).toInt()
            String(Character.toChars(n))
        }.getOrDefault(m.value) else entities[key.lowercase()] ?: m.value
    }

    fun plain(s: String): String = decode(s.replace(Regex("<[^>]*>"), " ")).replace(Regex("\\s+"), " ").trim()

    fun bing(html: String, limit: Int): List<WebResult> {
        val starts = resultStart.findAll(html).toList()
        return starts.mapNotNull { match ->
            val end = html.indexOf("</li>", match.range.last + 1).takeIf { it >= 0 } ?: html.length
            val block = html.substring(match.range.first, end)
            val heading = header.find(block)?.groupValues?.get(1) ?: block
            // The attribution/cite link precedes the real result link in Bing's current layout.
            val resultAnchor = anchor.findAll(heading).firstOrNull { h2.containsMatchIn(it.groupValues[2]) }
                ?: h2.find(heading)?.let { anchor.find(it.groupValues[1]) }
            val url = resultAnchor?.groupValues?.get(1)?.let(::decode)?.trim() ?: return@mapNotNull null
            if (!url.startsWith("https://") && !url.startsWith("http://")) return@mapNotNull null
            val title = plain(h2.find(resultAnchor.groupValues[2])?.groupValues?.get(1) ?: resultAnchor.groupValues[2])
            if (title.isBlank()) return@mapNotNull null
            val snippet = plain(paragraph.find(block)?.groupValues?.get(1) ?: "")
            WebResult(title, url, snippet, "bing")
        }.distinctBy { it.url }.take(limit)
    }

    fun readable(html: String, maxChars: Int): String {
        var s = html
        for (tag in listOf("script", "style", "svg", "noscript", "nav", "footer", "header")) {
            s = s.replace(Regex("<$tag\\b[^>]*>.*?</$tag\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
        }
        s = s.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), " ")
        s = s.replace(Regex("<(?:br|/p|/div|/article|/section|/h[1-6]|/li)\\b[^>]*>", RegexOption.IGNORE_CASE), "\n")
        return decode(s.replace(Regex("<[^>]*>"), " "))
            .replace(Regex("[ \\t\\r\\f]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim().take(maxChars)
    }
}

class WebSearchTool(override val name: String) : Tool {
    override val description = if (name == "web_search")
        "无需 API key 搜索互联网。Bing 网页搜索为主，DuckDuckGo 即时答案补充；返回标题、链接、摘要及来源。时效性问题先搜索。"
    else "读取 HTTPS 网页正文，去除脚本、样式和标签；最多返回 max_chars 字符。"
    override val parameters = if (name == "web_search") schemaOf(
        "query" to strParam("搜索词"), "limit" to intParam("结果上限，默认 8，最多 20"),
        "mkt" to strParam("Bing 市场，默认 zh-CN"), required = listOf("query"),
    ) else schemaOf("url" to strParam("HTTPS 网页地址"), "max_chars" to intParam("最大正文字数，默认 8000，最多 20000"), required = listOf("url"))

    private val http = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()

    override suspend fun execute(args: JsonObject): String = runCatching {
        if (name == "web_search") search(args) else read(args)
    }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }

    private fun fetch(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", ANDROID_CHROME_UA)
            .header("Accept", "text/html,application/json;q=0.9,*/*;q=0.8").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            if (!response.request.url.isHttps) error("重定向到非 HTTPS 页面")
            val body = response.body ?: error("空响应")
            // Bound memory for unusually large pages.
            val bytes = body.byteStream().use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (out.size() < 1_000_000) {
                    val n = stream.read(buf, 0, minOf(buf.size, 1_000_000 - out.size()))
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            return String(bytes, body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
        }
    }

    private fun search(args: JsonObject): String {
        val query = args.strArg("query") ?: error("缺少 query")
        val limit = (args.intArg("limit") ?: 8).coerceIn(1, 20)
        val mkt = args.strArg("mkt")?.takeIf { it.matches(Regex("[A-Za-z]{2}-[A-Za-z]{2}")) } ?: "zh-CN"
        val q = URLEncoder.encode(query, "UTF-8")
        var diagnostic = ""
        val bing = runCatching {
            val html = fetch("https://www.bing.com/search?q=$q&count=$limit&mkt=$mkt")
            val found = WebHtml.bing(html, limit)
            if (found.isEmpty()) diagnostic = "Bing 页面无可解析结果；b_algo 块数=${Regex("<li\\b[^>]*\\bb_algo\\b", RegexOption.IGNORE_CASE).findAll(html).count()}"
            found
        }.getOrElse { diagnostic = "Bing: ${it.message ?: it.javaClass.simpleName}"; emptyList() }
        val ddg = runCatching {
            val data = Json.parseToJsonElement(fetch("https://api.duckduckgo.com/?q=$q&format=json&no_html=1&skip_disambig=1")) as JsonObject
            val results = mutableListOf<WebResult>()
            val abstract = data.text("AbstractText")
            val abstractUrl = data.text("AbstractURL")
            if (abstract.isNotBlank() && abstractUrl.startsWith("http")) results += WebResult(data.text("Heading").ifBlank { query }, abstractUrl, abstract, "ddg_ia")
            fun addTopics(items: JsonArray?) {
                items?.forEach { item ->
                    val obj = item as? JsonObject ?: return@forEach
                    val text = obj.text("Text")
                    val url = obj.text("FirstURL")
                    if (text.isNotBlank() && url.startsWith("http")) results += WebResult(text.substringBefore(" - ").take(120), url, text.take(500), "ddg_ia")
                    addTopics(obj["Topics"] as? JsonArray)
                }
            }
            addTopics(data["RelatedTopics"] as? JsonArray)
            results
        }.getOrElse { if (diagnostic.isNotBlank()) diagnostic += "; "; diagnostic += "DDG: ${it.message ?: it.javaClass.simpleName}"; emptyList() }
        val results = (bing + ddg).distinctBy { it.url }.take(limit)
        if (results.isEmpty()) return "ERROR: 搜索无结果。$diagnostic"
        return JsonArray(results.mapIndexed { index, result ->
            if (index == 0 && bing.isEmpty() && diagnostic.isNotBlank()) buildJsonObject {
                result.toJson().forEach { (key, value) -> put(key, value) }
                put("diagnostic", diagnostic)
            } else result.toJson()
        }).toString()
    }

    private fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun read(args: JsonObject): String {
        val url = args.strArg("url") ?: error("缺少 url")
        val uri = java.net.URI(url)
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank()) { "仅支持 HTTPS 网页" }
        val maxChars = (args.intArg("max_chars") ?: 8000).coerceIn(1, 20_000)
        val content = WebHtml.readable(fetch(url), maxChars)
        if (content.isBlank()) error("网页无可读正文")
        return buildJsonObject { put("url", url); put("text", content) }.toString()
    }
}
