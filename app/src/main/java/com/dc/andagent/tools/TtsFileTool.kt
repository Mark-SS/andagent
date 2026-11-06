package com.dc.andagent.tools

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/** Synthesizes with the installed Android speech engine; never plays through the speaker. */
class TtsFileTool(private val context: Context) : Tool {
    override val name = "tts_to_file"
    override val description = "用系统 TTS 将文本合成为 WAV 音频文件，返回可用 ```audio 围栏展示的本地路径。"
    override val parameters = schemaOf(
        "text" to strParam("待合成文本，最多 4000 字符"),
        "rate" to buildJsonObject { put("type", "number"); put("description", "语速倍率 0.5..2.0，默认 1.0") },
        required = listOf("text"),
    )

    override suspend fun execute(args: JsonObject): String {
        val text = args.strArg("text") ?: return "ERROR: 缺少 text，请提供待合成文本"
        if (text.length > MAX_TEXT_LENGTH) return "ERROR: 文本过长（最多 4000 字符），请分段调用 tts_to_file"
        val rateText = args.strArg("rate")
        val rate = rateText?.toFloatOrNull() ?: if (rateText == null) 1f else return "ERROR: rate 必须是 0.5 到 2.0 的数字"
        if (!rate.isFinite() || rate !in 0.5f..2.0f) return "ERROR: rate 必须在 0.5 到 2.0 之间"

        val dir = File(context.getExternalFilesDir(null) ?: return "ERROR: 应用文件目录不可用", "exports")
        if (!dir.isDirectory && !dir.mkdirs()) return "ERROR: 无法创建音频导出目录"
        val file = File(dir, "tts_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.wav")
        val result = CompletableDeferred<String>()
        val handler = Handler(Looper.getMainLooper())
        val utteranceId = UUID.randomUUID().toString()
        val engine = withContext(Dispatchers.Main) {
            // Posting the init callback also handles engines that initialize synchronously.
            lateinit var tts: TextToSpeech
            tts = TextToSpeech(context.applicationContext) { status ->
                handler.post {
                    if (!result.isActive) return@post
                    if (status != TextToSpeech.SUCCESS) {
                        result.complete("ERROR: 系统 TTS 引擎初始化失败，请检查系统语音引擎和语音数据")
                    } else {
                        runCatching {
                            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                                override fun onStart(id: String) = Unit
                                override fun onDone(id: String) {
                                    if (id == utteranceId) result.complete("OK")
                                }
                                override fun onError(id: String) {
                                    if (id == utteranceId) result.complete("ERROR: TTS 合成失败，请检查系统语音引擎和语音数据")
                                }
                                override fun onError(id: String, errorCode: Int) {
                                    if (id == utteranceId) result.complete("ERROR: TTS 合成失败（code=$errorCode），请检查系统语音引擎和语音数据")
                                }
                            })
                            tts.setSpeechRate(rate)
                            val code = tts.synthesizeToFile(text, null, file, utteranceId)
                            if (code != TextToSpeech.SUCCESS) result.complete("ERROR: TTS 合成请求被拒绝（code=$code）")
                        }.onFailure { result.complete("ERROR: TTS 合成失败：${it.message ?: it.javaClass.simpleName}") }
                    }
                }
            }
            tts
        }
        var succeeded = false
        try {
            val timeoutMs = (5_000L + text.length * 60L).coerceAtMost(30_000L)
            val status = withTimeoutOrNull(timeoutMs) { result.await() } ?: "ERROR: TTS 合成超时，请分段重试"
            if (status != "OK") return status
            if (!file.isFile || file.length() == 0L) return "ERROR: TTS 未生成音频文件，请检查系统语音引擎"
            succeeded = true
            return buildJsonObject {
                put("path", file.absolutePath)
                put("size", file.length())
                put("durationEstimate", "约 ${((text.length * 0.25f / rate).toInt()).coerceAtLeast(1)} 秒")
                put("hint", "向用户展示时输出 ```audio 围栏，JSON 中 uri 使用 path，title 可自定")
            }.toString()
        } finally {
            result.cancel()
            withContext(NonCancellable + Dispatchers.Main) { engine.shutdown() }
            if (!succeeded) file.delete()
        }
    }

    companion object { const val MAX_TEXT_LENGTH = 4000 }
}
