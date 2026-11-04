package io.nous.andagent.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import io.nous.andagent.bridge.PermissionBroker
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Records microphone audio to an M4A/AAC file for transcription.
 * RECORD_AUDIO is requested at startup and re-checked here via
 * [PermissionBroker], so a previously denied grant can be re-requested.
 */
class MicRecordTool(
    private val context: Context,
    private val permissions: PermissionBroker,
) : Tool {
    override val name = "mic_record"
    override val description =
        "录制一段麦克风音频并保存为音频文件（M4A），返回文件路径。用于录音备忘、语音素材等；如需转文字请配合服务端转写。"

    override val parameters = schemaOf(
        "seconds" to intParam("录音时长（秒），默认 10，范围 1-300"),
    )

    private var recorder: MediaRecorder? = null

    override suspend fun execute(args: JsonObject): String {
        val seconds = (args.intArg("seconds") ?: 10).coerceIn(1, 300)
        if (!permissions.ensure(listOf(Manifest.permission.RECORD_AUDIO))) {
            return "ERROR: 麦克风权限未授予。已请求授权被拒；可到系统设置手动开启"
        }
        val out = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "mic_${System.currentTimeMillis()}.m4a",
        )
        val mr = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(96_000)
            mr.setAudioSamplingRate(44_100)
            mr.setOutputFile(out.absolutePath)
            mr.prepare()
            mr.start()
            recorder = mr
            Thread.sleep(seconds * 1000L)
            mr.stop()
            "WRITTEN: 录音完成 ${seconds}s，文件 ${out.absolutePath}（${out.length() / 1024}KB，AAC/M4A）"
        } catch (e: Exception) {
            "ERROR: 录音失败（${e.message ?: e.javaClass.simpleName}）"
        } finally {
            runCatching { mr.release() }
            recorder = null
        }
    }
}
