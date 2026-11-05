package com.dc.andagent.tools

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.YuvImage
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import com.dc.andagent.shell.ShizukuShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val SERVER_PATH = "/data/local/tmp/andagent-scrcpy-server-v2.7.jar"
private fun q(value: String) = "'" + value.replace("'", "'\\''") + "'"

class ScrcpySession(private val context: Context, private val shizuku: ShizukuShell) {
    data class Packet(val pts: Long, val config: Boolean, val keyFrame: Boolean, val bytes: ByteArray)
    private var server: LocalServerSocket? = null
    private var socket: LocalSocket? = null
    private var process: Process? = null
    private val stderr = AtomicReference("")
    var width = 0; private set
    var height = 0; private set
    private lateinit var input: DataInputStream

    private fun push(): String? {
        val done = CountDownLatch(1); val output = AtomicReference(""); val code = AtomicReference(-1)
        val proc = shizuku.start("base64 -d > $SERVER_PATH") { exit, text -> code.set(exit); output.set(text); done.countDown() }
            ?: return "无法启动 Shizuku 文件传输"
        return try {
            context.assets.open("vendor/scrcpy-server-v2.7.jar").use { asset ->
                val bytes = asset.readBytes()
                proc.outputStream.use { it.write(Base64.encode(bytes, Base64.NO_WRAP)) }
            }
            if (!done.await(10, TimeUnit.SECONDS)) "server 传输超时" else if (code.get() != 0) "server 传输失败：${output.get().take(500)}" else null
        } catch (e: Exception) { "server 传输失败：${e.message}" }
    }

    fun open(maxSize: Int, bitRate: Int): String? {
        if (!shizuku.isGranted()) return "Shizuku 未激活：请在设置页激活并授权"
        push()?.let { return it }
        val scid = "%08x".format(java.util.Random().nextInt(Int.MAX_VALUE))
        val name = "scrcpy_$scid"
        return try {
            server = LocalServerSocket(name)
            val cmd = "CLASSPATH=$SERVER_PATH app_process / com.genymobile.scrcpy.Server 2.7 " +
                "scid=$scid log_level=info audio=false control=false video_codec=h264 " +
                "max_size=${maxSize.coerceIn(256, 1920)} video_bit_rate=${bitRate.coerceIn(500_000, 20_000_000)} " +
                "tunnel_forward=false send_dummy_byte=false"
            process = shizuku.start(cmd) { _, text -> stderr.set(text.take(2000)) }
                ?: return "Shizuku server 进程启动失败"
            val executor = Executors.newSingleThreadExecutor()
            val future = executor.submit<LocalSocket> { server!!.accept() }
            try { socket = future.get(12, TimeUnit.SECONDS) }
            finally { executor.shutdownNow() }
            input = DataInputStream(socket!!.inputStream)
            val deviceName = ByteArray(64); input.readFully(deviceName)
            val codec = input.readInt()
            width = input.readInt(); height = input.readInt()
            if (codec != 0x68323634 || width !in 1..4096 || height !in 1..4096)
                return "server 参数不匹配：视频头 codec=$codec size=${width}x$height"
            socket!!.soTimeout = 15000
            null
        } catch (e: Exception) {
            "${if (stderr.get().contains("Invalid") || stderr.get().contains("version")) "server 参数不匹配" else "设备不支持或 socket 握手失败"}：${stderr.get().ifBlank { e.message ?: "无错误详情" }.take(1000)}"
        }
    }

    fun next(): Packet {
        val flags = input.readLong(); val size = input.readInt()
        if (size !in 1..2_000_000) error("scrcpy 视频包长度无效：$size")
        val bytes = ByteArray(size); input.readFully(bytes)
        return Packet(flags and 0x3fffffffffffffffL, flags < 0, flags and (1L shl 62) != 0L, bytes)
    }
    fun close() {
        runCatching { socket?.close() }; runCatching { server?.close() }; runCatching { process?.destroy() }
    }
}

private fun imageJpeg(image: Image): ByteArray {
    val w = image.width; val h = image.height
    val y = image.planes[0]; val u = image.planes[1]; val v = image.planes[2]
    val nv21 = ByteArray(w * h * 3 / 2)
    fun get(plane: Image.Plane, row: Int, col: Int): Byte = plane.buffer.get(row * plane.rowStride + col * plane.pixelStride)
    for (row in 0 until h) for (col in 0 until w) nv21[row * w + col] = get(y, row, col)
    for (row in 0 until h / 2) for (col in 0 until w / 2) {
        val pos = w * h + row * w + 2 * col
        nv21[pos] = get(v, row, col); nv21[pos + 1] = get(u, row, col)
    }
    val out = ByteArrayOutputStream()
    YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(android.graphics.Rect(0, 0, w, h), 85, out)
    return out.toByteArray()
}

class ScreenCaptureTool(private val context: Context, private val shizuku: ShizukuShell) : Tool {
    override val name = "screen_capture"
    override val description =
        "截取一帧屏幕，保存 JPEG 并返回缩略图。优先 MediaProjection（一次系统弹窗授权后全自动）；" +
            "未授权且有 Shizuku 时走 scrcpy-server；两者都不可用时触发系统屏幕捕获授权弹窗。"
    override val parameters = schemaOf()
    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        when (ensureCaptureChannel(context, shizuku)) {
            CaptureChannel.PROJECTION -> projectionCaptureFrame(context)
            CaptureChannel.NEED_CONSENT ->
                "ERROR: 屏幕捕获未授权：请在系统弹窗中点「立即开始」后重试（也可到 设置 → 屏幕捕获 手动授权）"
            CaptureChannel.SCRCPY -> scrcpyCapture()
        }
    }

    private suspend fun scrcpyCapture(): String = withContext(Dispatchers.IO) {
        val session = ScrcpySession(context, shizuku)
        try {
            session.open(1280, 4_000_000)?.let { return@withContext "ERROR: $it；可调用 screen_record 兜底" }
            val reader = ImageReader.newInstance(session.width, session.height, ImageFormat.YUV_420_888, 2)
            val codec = MediaCodec.createDecoderByType("video/avc")
            try {
                codec.configure(MediaFormat.createVideoFormat("video/avc", session.width, session.height), reader.surface, null, 0)
                codec.start()
                val deadline = System.currentTimeMillis() + 12_000
                var jpeg: ByteArray? = null
                val info = MediaCodec.BufferInfo()
                while (System.currentTimeMillis() < deadline && jpeg == null) {
                    val packet = session.next()
                    val inIndex = codec.dequeueInputBuffer(500_000)
                    if (inIndex >= 0) {
                        codec.getInputBuffer(inIndex)!!.apply { clear(); put(packet.bytes) }
                        codec.queueInputBuffer(inIndex, 0, packet.bytes.size, packet.pts, if (packet.config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0)
                    }
                    val outIndex = codec.dequeueOutputBuffer(info, 500_000)
                    if (outIndex >= 0) {
                        codec.releaseOutputBuffer(outIndex, true)
                        Thread.sleep(80)
                        reader.acquireLatestImage()?.use { jpeg = imageJpeg(it) }
                    }
                }
                val data = jpeg ?: return@withContext "ERROR: 设备不支持：解码器未产生图像；可调用 screen_record 兜底"
                val path = File(context.cacheDir, "screen_${System.currentTimeMillis()}.jpg")
                path.writeBytes(data)
                val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, minOf(640, bitmap.width), (bitmap.height * minOf(640, bitmap.width) / bitmap.width).coerceAtLeast(1), true)
                var quality = 75; var thumb: ByteArray
                do { val out = ByteArrayOutputStream(); scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out); thumb = out.toByteArray(); quality -= 10 } while (thumb.size > 200_000 && quality >= 25)
                scaled.recycle(); bitmap.recycle()
                buildJsonObject { put("path", path.path); put("thumbnailBase64", Base64.encodeToString(thumb, Base64.NO_WRAP)); put("mime", "image/jpeg") }.toString()
            } finally { runCatching { codec.stop() }; codec.release(); reader.close() }
        } catch (e: Exception) { "ERROR: 设备不支持或取帧失败：${e.message}；可调用 screen_record 兜底" }
        finally { session.close() }
    }
}

private fun nalUnits(bytes: ByteArray): List<ByteArray> {
    val starts = mutableListOf<Int>(); var i = 0
    while (i < bytes.size - 3) {
        if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() && (bytes[i + 2] == 1.toByte() || (bytes[i + 2] == 0.toByte() && bytes[i + 3] == 1.toByte()))) {
            starts += i; i += if (bytes[i + 2] == 1.toByte()) 3 else 4
        } else i++
    }
    return starts.mapIndexed { idx, start -> bytes.copyOfRange(start, starts.getOrElse(idx + 1) { bytes.size }) }
}

class ScreenRecordH264Tool(private val context: Context, private val shizuku: ShizukuShell) : Tool {
    override val name = "screen_record_h264"
    override val description =
        "录制屏幕到 Movies/AndAgent MP4，可选时长、码率与最大边长。优先 MediaProjection（一次系统弹窗授权后全自动）；" +
            "未授权且有 Shizuku 时走 scrcpy-server；两者都不可用时触发系统屏幕捕获授权弹窗。"
    override val parameters = schemaOf("duration_s" to intParam("时长秒数，默认 30，最大 180"), "bit_rate" to intParam("码率 bps，默认 4000000"), "max_size" to intParam("最大边长，默认 1280"))
    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val duration = (args.intArg("duration_s") ?: 30).coerceIn(1, 180)
        val bitRate = args.intArg("bit_rate") ?: 4_000_000
        when (ensureCaptureChannel(context, shizuku)) {
            CaptureChannel.PROJECTION -> {
                // MediaRecorder writes H.264 directly; start → wait → stop
                // gives the same synchronous contract as the scrcpy path.
                val started = projectionStartRecording(context, duration, bitRate)
                if (started.startsWith("ERROR")) return@withContext started
                Thread.sleep(duration * 1000L)
                projectionStopRecording(context) ?: "ERROR: 录屏提前结束，请重试"
            }
            CaptureChannel.NEED_CONSENT ->
                "ERROR: 屏幕捕获未授权：请在系统弹窗中点「立即开始」后重试（也可到 设置 → 屏幕捕获 手动授权）"
            CaptureChannel.SCRCPY -> {
                if (Build.VERSION.SDK_INT < 29) return@withContext "ERROR: 此录制方式需要 Android 10+；可使用 screen_record"
                scrcpyRecord(duration, bitRate, args.intArg("max_size") ?: 1280)
            }
        }
    }

    private suspend fun scrcpyRecord(duration: Int, bitRate: Int, maxSize: Int): String = withContext(Dispatchers.IO) {
        val session = ScrcpySession(context, shizuku)
        var uri: android.net.Uri? = null
        try {
            session.open(maxSize, bitRate)?.let { return@withContext "ERROR: $it；可用 screen_record 兜底" }
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "andagent_h264_${System.currentTimeMillis()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/AndAgent")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext "ERROR: 无法创建 Movies 视频"
            val fd = context.contentResolver.openFileDescriptor(uri!!, "w") ?: return@withContext "ERROR: 无法打开视频文件"
            fd.use {
                val muxer = MediaMuxer(it.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                var track = -1; var firstPts = -1L; var samples = 0
                try {
                    val deadline = System.currentTimeMillis() + duration * 1000L
                    while (System.currentTimeMillis() < deadline) {
                        val packet = session.next()
                        if (packet.config) {
                            val units = nalUnits(packet.bytes)
                            val sps = units.firstOrNull { n -> n.size > 4 && n[if (n[2] == 1.toByte()) 3 else 4].toInt() and 31 == 7 }
                            val pps = units.firstOrNull { n -> n.size > 4 && n[if (n[2] == 1.toByte()) 3 else 4].toInt() and 31 == 8 }
                            if (sps != null && pps != null && track < 0) {
                                val format = MediaFormat.createVideoFormat("video/avc", session.width, session.height)
                                format.setByteBuffer("csd-0", ByteBuffer.wrap(sps)); format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                                track = muxer.addTrack(format); muxer.start()
                            }
                        } else if (track >= 0) {
                            if (firstPts < 0) firstPts = packet.pts
                            val info = MediaCodec.BufferInfo().apply { set(0, packet.bytes.size, (packet.pts - firstPts).coerceAtLeast(0), if (packet.keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0) }
                            muxer.writeSampleData(track, ByteBuffer.wrap(packet.bytes), info)
                            samples++
                        }
                    }
                    if (track < 0 || samples == 0) error("未收到可录制的 H.264 视频帧")
                } finally { try { if (track >= 0) muxer.stop() } finally { muxer.release() } }
            }
            context.contentResolver.update(uri!!, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            "WRITTEN: 已保存 MP4 到 $uri"
        } catch (e: Exception) {
            uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            "ERROR: 设备不支持或录制失败：${e.message}；可用 screen_record 兜底"
        } finally { session.close() }
    }
}

class InputTool(private val shizuku: ShizukuShell, override val name: String) : Tool {
    override val destructive = true
    override val description = when (name) { "input_tap" -> "经 Shizuku 模拟点击屏幕。"; "input_swipe" -> "经 Shizuku 模拟滑动屏幕。"; else -> "经 Shizuku 向焦点输入框输入文本。" }
    override val parameters = when (name) {
        "input_tap" -> schemaOf("x" to intParam("横坐标"), "y" to intParam("纵坐标"), required = listOf("x", "y"))
        "input_swipe" -> schemaOf("x1" to intParam("起点 X"), "y1" to intParam("起点 Y"), "x2" to intParam("终点 X"), "y2" to intParam("终点 Y"), "ms" to intParam("时长毫秒"), required = listOf("x1", "y1", "x2", "y2"))
        else -> schemaOf("s" to strParam("输入内容"), required = listOf("s"))
    }
    override suspend fun execute(args: JsonObject): String {
        if (!shizuku.isGranted()) return "ERROR: ${ShizukuShell.SHIZUKU_REQUIRED}"
        val cmd = when (name) {
            "input_tap" -> "input tap ${args.intArg("x") ?: return "ERROR: 缺少 x"} ${args.intArg("y") ?: return "ERROR: 缺少 y"}"
            "input_swipe" -> "input swipe ${args.intArg("x1") ?: return "ERROR: 缺少 x1"} ${args.intArg("y1") ?: return "ERROR: 缺少 y1"} ${args.intArg("x2") ?: return "ERROR: 缺少 x2"} ${args.intArg("y2") ?: return "ERROR: 缺少 y2"} ${(args.intArg("ms") ?: 300).coerceIn(50, 10000)}"
            else -> "input text ${q((args.strArg("s") ?: return "ERROR: 缺少 s").replace(" ", "%s"))}"
        }
        val r = shizuku.run(cmd)
        return if (r.ok) "WRITTEN: 输入命令已提交；请检查设备屏幕" else "ERROR: 输入失败：${r.asToolResult()}"
    }
}
