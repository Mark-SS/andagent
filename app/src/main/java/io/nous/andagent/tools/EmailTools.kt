package io.nous.andagent.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.util.Base64
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** R22: SMTP delivery + mailto fallback, without a third-party mail library. */
val EMAIL_TOOLS = listOf("email_send", "email_verify", "email_draft")

private fun addresses(raw: String?): List<String> = raw
    ?.split(",", ";")
    ?.map { it.trim() }
    ?.filter { it.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) }
    .orEmpty()

private fun mimeSubject(subject: String): String =
    "=?UTF-8?B?" + Base64.getEncoder().encodeToString(subject.toByteArray(Charsets.UTF_8)) + "?="

private fun base64Body(text: String): String =
    Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(text.toByteArray(Charsets.UTF_8))

/** Minimal synchronous SMTP client: EHLO, STARTTLS/SSL, AUTH LOGIN, DATA. */
private class SmtpClient(
    private val host: String,
    private val port: Int,
    private val security: String,
    private val user: String,
    private val password: String,
) : Closeable {
    private var socket: Socket = Socket()
    private lateinit var reader: BufferedReader
    private lateinit var writer: BufferedWriter

    fun connect() {
        socket = if (security == "ssl") {
            (SSLSocketFactory.getDefault().createSocket(host, port) as SSLSocket).apply { startHandshake() }
        } else Socket(host, port)
        socket.soTimeout = 20_000
        bindStreams()
        greeting(220)
        ehlo()
        if (security == "starttls") {
            greeting(220, "STARTTLS")
            socket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(socket, host, port, true) as SSLSocket
            (socket as SSLSocket).startHandshake()
            bindStreams()
            ehlo()
        }
        if (user.isNotBlank()) {
            greeting(334, "AUTH LOGIN")
            greeting(334, Base64.getEncoder().encodeToString(user.toByteArray(Charsets.UTF_8)))
            greeting(235, Base64.getEncoder().encodeToString(password.toByteArray(Charsets.UTF_8)))
        }
    }

    private fun bindStreams() {
        reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
        writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.US_ASCII))
    }

    private fun ehlo() { greeting(250, "EHLO andagent") }

    private fun greeting(expected: Int, cmd: String? = null) {
        val reply = command(cmd)
        if (reply.first != expected) error("SMTP 期望 $expected，实际 ${reply.first}：${reply.second}")
    }

    private fun command(line: String?): Pair<Int, String> {
        if (!line.isNullOrEmpty()) {
            writer.write(line)
            writer.write("\r\n")
            writer.flush()
        }
        val lines = mutableListOf<String>()
        while (true) {
            val response = reader.readLine() ?: error("SMTP 连接已断开")
            lines += response
            if (response.length < 4 || response[3] != '-') break
        }
        val code = lines.last().take(3).toIntOrNull() ?: error("SMTP 响应格式错误：${lines.last()}")
        return code to lines.joinToString(" | ").take(500)
    }

    fun send(from: String, recipients: List<String>, message: String) {
        greeting(250, "MAIL FROM:<$from>")
        recipients.forEach { greeting(250, "RCPT TO:<$it>") }
        greeting(354, "DATA")
        val stuffed = message.lines().joinToString("\r\n") { if (it.startsWith(".")) ".$it" else it }
        writer.write(stuffed)
        writer.write("\r\n.\r\n")
        writer.flush()
        val reply = command(null)
        if (reply.first != 250) error("邮件投递被拒绝：${reply.second}")
    }

    override fun close() {
        runCatching { command("QUIT") }
        runCatching { socket.close() }
    }
}

class EmailTool(private val context: Context, override val name: String) : Tool {
    override val description: String = when (name) {
        "email_send" -> "通过 SMTP 服务器直接发送邮件（支持 SSL/STARTTLS、AUTH LOGIN、HTML 正文与附件）。密码仅用于本次连接，不会保存。"
        "email_verify" -> "验证 SMTP 服务器连通性与账号登录（不发送邮件）。"
        "email_draft" -> "用系统邮件应用打开一封草稿（mailto 回退，无需 SMTP 配置）。"
        else -> "邮件工具"
    }

    override val parameters: JsonObject = when (name) {
        "email_send" -> schemaOf(
            "host" to strParam("SMTP 主机，如 smtp.example.com"),
            "port" to intParam("端口，SSL 通常 465，STARTTLS 通常 587"),
            "security" to strParam("ssl|starttls|none，默认 ssl"),
            "user" to strParam("SMTP 登录用户名；匿名服务器可省略"),
            "password" to strParam("SMTP 登录密码/应用专用密码"),
            "from" to strParam("发件人地址"),
            "to" to strParam("收件人，逗号分隔"),
            "cc" to strParam("抄送，逗号分隔"),
            "subject" to strParam("主题"),
            "body" to strParam("正文"),
            "html" to boolParam("正文是否为 HTML，默认 false"),
            "attachments_json" to strParam("附件绝对路径的 JSON 字符串数组"),
            required = listOf("host", "from", "to", "subject", "body"),
        )
        "email_verify" -> schemaOf(
            "host" to strParam("SMTP 主机"),
            "port" to intParam("端口"),
            "security" to strParam("ssl|starttls|none，默认 ssl"),
            "user" to strParam("用户名"),
            "password" to strParam("密码"),
            required = listOf("host"),
        )
        "email_draft" -> schemaOf(
            "to" to strParam("收件人，逗号分隔"),
            "subject" to strParam("主题"),
            "body" to strParam("正文"),
            required = listOf("to"),
        )
        else -> schemaOf()
    }

    override val destructive: Boolean = name == "email_send"

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        runCatching {
            when (name) {
                "email_send" -> send(args)
                "email_verify" -> verify(args)
                "email_draft" -> draft(args)
                else -> "ERROR: 未知邮件工具 $name"
            }
        }.getOrElse { "ERROR: $name 失败：${it.message ?: it.javaClass.simpleName}" }
    }

    private fun securityOf(args: JsonObject): String {
        val value = args.strArg("security")?.lowercase() ?: "ssl"
        if (value !in setOf("ssl", "starttls", "none")) error("security 仅支持 ssl|starttls|none")
        return value
    }

    private fun portOf(args: JsonObject, security: String): Int =
        args.intArg("port") ?: when (security) { "ssl" -> 465; "starttls" -> 587; else -> 25 }

    private fun verify(args: JsonObject): String {
        val host = args.strArg("host") ?: error("缺少 host")
        val security = securityOf(args)
        val client = SmtpClient(host, portOf(args, security), security, args.strArg("user") ?: "", args.strArg("password") ?: "")
        client.use {
            it.connect()
            return "OK: SMTP $host:${portOf(args, security)} ($security) 连接与登录成功"
        }
    }

    private fun send(args: JsonObject): String {
        val host = args.strArg("host") ?: error("缺少 host")
        val from = args.strArg("from") ?: error("缺少 from")
        val to = addresses(args.strArg("to"))
        if (to.isEmpty()) error("缺少有效的收件人地址")
        val cc = addresses(args.strArg("cc"))
        val subject = args.strArg("subject") ?: error("缺少 subject")
        val body = args.strArg("body") ?: error("缺少 body")
        val security = securityOf(args)
        val attachments = runCatching {
            (kotlinx.serialization.json.Json.parseToJsonElement(args.strArg("attachments_json") ?: "[]") as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        }.getOrElse { error("attachments_json 必须是 JSON 字符串数组") }
        val boundary = "andagent-${System.currentTimeMillis()}"
        val contentType = if (args.boolArg("html") == true) "text/html" else "text/plain"
        val message = buildString {
            append("From: <$from>\r\n")
            append("To: ${to.joinToString(", ") { "<$it>" }}\r\n")
            if (cc.isNotEmpty()) append("Cc: ${cc.joinToString(", ") { "<$it>" }}\r\n")
            append("Subject: ${mimeSubject(subject)}\r\n")
            append("MIME-Version: 1.0\r\n")
            if (attachments.isEmpty()) {
                append("Content-Type: $contentType; charset=UTF-8\r\n")
                append("Content-Transfer-Encoding: base64\r\n\r\n")
                append(base64Body(body))
            } else {
                append("Content-Type: multipart/mixed; boundary=\"$boundary\"\r\n\r\n")
                append("--$boundary\r\nContent-Type: $contentType; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n")
                append(base64Body(body)).append("\r\n")
                attachments.forEach { path ->
                    val file = File(path)
                    if (!file.isFile) error("附件不存在：$path")
                    if (file.length() > 10 * 1024 * 1024) error("附件超过 10MB：$path")
                    append("--$boundary\r\n")
                    append("Content-Type: application/octet-stream; name=\"${file.name}\"\r\n")
                    append("Content-Transfer-Encoding: base64\r\n")
                    append("Content-Disposition: attachment; filename=\"${file.name}\"\r\n\r\n")
                    append(Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(file.readBytes())).append("\r\n")
                }
                append("--$boundary--")
            }
        }
        val client = SmtpClient(host, portOf(args, security), security, args.strArg("user") ?: "", args.strArg("password") ?: "")
        client.use {
            it.connect()
            it.send(from, to + cc, message)
        }
        return "WRITTEN: 邮件已通过 $host 投递给 ${to.joinToString(", ")}${if (cc.isNotEmpty()) "（抄送 ${cc.size} 人）" else ""}"
    }

    private fun draft(args: JsonObject): String {
        val to = args.strArg("to") ?: error("缺少 to")
        val uri = Uri.parse("mailto:${Uri.encode(to)}")
        val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
            putExtra(Intent.EXTRA_SUBJECT, args.strArg("subject") ?: "")
            putExtra(Intent.EXTRA_TEXT, args.strArg("body") ?: "")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            "OPENED: 已在邮件应用中创建草稿"
        }.getOrElse { "ERROR: 设备上没有可用的邮件应用：${it.message}" }
    }
}
