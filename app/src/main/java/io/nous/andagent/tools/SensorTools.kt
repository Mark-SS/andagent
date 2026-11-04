package io.nous.andagent.tools

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Instantaneous sensor readings via SensorManager. No runtime permission is
 * needed for the sensors exposed here (location-derived sensors are excluded).
 */

private data class SensorDef(
    val type: Int,
    val label: String,
    val unit: String,
    val axes: List<String>,
    val note: String,
)

private val SENSOR_DEFS: Map<String, SensorDef> = linkedMapOf(
    "accelerometer" to SensorDef(
        Sensor.TYPE_ACCELEROMETER, "加速度计", "m/s²",
        listOf("x", "y", "z"),
        "包含重力加速度；手机平放时 z 约为 9.8，摇晃时各轴变化",
    ),
    "gyroscope" to SensorDef(
        Sensor.TYPE_GYROSCOPE, "陀螺仪", "rad/s",
        listOf("x", "y", "z"),
        "角速度；静止时接近 0，转动手机时对应轴出现明显数值",
    ),
    "magnetometer" to SensorDef(
        Sensor.TYPE_MAGNETIC_FIELD, "磁力计", "µT",
        listOf("x", "y", "z"),
        "环境磁场强度；地磁场一般 25–65µT",
    ),
    "light" to SensorDef(
        Sensor.TYPE_LIGHT, "环境光", "lx",
        emptyList(), "环境光照度；室内通常几十到几百 lux，阳光下可上万",
    ),
    "pressure" to SensorDef(
        Sensor.TYPE_PRESSURE, "气压计", "hPa",
        emptyList(), "大气压；海平面约 1013hPa，可用于估算海拔",
    ),
    "proximity" to SensorDef(
        Sensor.TYPE_PROXIMITY, "接近传感器", "cm",
        emptyList(), "距离；多数设备只返回 0（贴近）或 5（远离）",
    ),
    "humidity" to SensorDef(
        Sensor.TYPE_RELATIVE_HUMIDITY, "相对湿度", "%",
        emptyList(), "相对湿度百分比",
    ),
    "ambient_temp" to SensorDef(
        Sensor.TYPE_AMBIENT_TEMPERATURE, "环境温度", "°C",
        emptyList(), "环境温度；不是电池温度，很多设备没有此传感器",
    ),
)

class SensorListTool(private val context: Context) : Tool {
    override val name = "sensor_list"
    override val description = "枚举设备全部传感器：名称、类型、厂商、分辨率、最大量程、功耗、最小采样间隔。"
    override val parameters = schemaOf()

    override suspend fun execute(args: JsonObject): String {
        return runCatching {
            val sm = context.getSystemService(SensorManager::class.java)
                ?: return@runCatching "ERROR: 传感器服务不可用"
            val sensors = sm.getSensorList(Sensor.TYPE_ALL)
            buildJsonObject {
                put("count", sensors.size)
                put("sensors", buildJsonArray {
                    for (s in sensors) {
                        add(buildJsonObject {
                            put("name", s.name)
                            put("type", typeName(s.type))
                            put("typeId", s.type)
                            put("vendor", s.vendor)
                            put("version", s.version)
                            put("resolution", s.resolution)
                            put("maxRange", s.maximumRange)
                            put("powerMa", s.power)
                            put("minDelayUs", s.minDelay)
                            put("wakeUp", s.isWakeUpSensor)
                        })
                    }
                })
            }.toString()
        }.getOrElse { "ERROR: 读取传感器列表失败：${it.message ?: it.javaClass.simpleName}" }
    }

    private fun typeName(type: Int): String = when (type) {
        Sensor.TYPE_ACCELEROMETER -> "accelerometer"
        Sensor.TYPE_GYROSCOPE -> "gyroscope"
        Sensor.TYPE_MAGNETIC_FIELD -> "magnetometer"
        Sensor.TYPE_LIGHT -> "light"
        Sensor.TYPE_PRESSURE -> "pressure"
        Sensor.TYPE_PROXIMITY -> "proximity"
        Sensor.TYPE_RELATIVE_HUMIDITY -> "humidity"
        Sensor.TYPE_AMBIENT_TEMPERATURE -> "ambient_temp"
        Sensor.TYPE_STEP_COUNTER -> "step_counter"
        Sensor.TYPE_STEP_DETECTOR -> "step_detector"
        Sensor.TYPE_GRAVITY -> "gravity"
        Sensor.TYPE_LINEAR_ACCELERATION -> "linear_acceleration"
        Sensor.TYPE_ROTATION_VECTOR -> "rotation_vector"
        Sensor.TYPE_HEART_RATE -> "heart_rate"
        else -> "type_$type"
    }
}

class SensorReadTool(private val context: Context) : Tool {
    override val name = "sensor_read"
    override val description =
        "读取指定传感器当前值（采样 300ms 取平均）。支持 accelerometer|gyroscope|magnetometer|light|" +
            "pressure|proximity|humidity|ambient_temp；返回数值、单位与物理含义。"
    override val parameters = schemaOf(
        "sensor" to strParam("传感器：accelerometer | gyroscope | magnetometer | light | pressure | proximity | humidity | ambient_temp"),
        required = listOf("sensor"),
    )

    override suspend fun execute(args: JsonObject): String {
        val key = (args.strArg("sensor") ?: return "ERROR: 缺少 sensor").lowercase()
        val def = SENSOR_DEFS[key]
            ?: return "ERROR: 不支持的传感器「$key」。可用：${SENSOR_DEFS.keys.joinToString(" | ")}"
        return runCatching {
            val sm = context.getSystemService(SensorManager::class.java)
                ?: return@runCatching "ERROR: 传感器服务不可用"
            val sensor = sm.getDefaultSensor(def.type)
                ?: return@runCatching "ERROR: 设备没有${def.label}（${def.unit}）传感器"
            val sampled = sample(sm, sensor)
                ?: return@runCatching "ERROR: 读取${def.label}超时（300ms 内没有数据）"
            buildJsonObject {
                put("sensor", key)
                put("name", sensor.name)
                put("typeId", def.type)
                put("unit", def.unit)
                put("values", buildJsonArray {
                    sampled.values.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                })
                if (def.axes.isNotEmpty()) {
                    put("axes", buildJsonArray {
                        def.axes.take(sampled.values.size).forEach {
                            add(kotlinx.serialization.json.JsonPrimitive(it))
                        }
                    })
                }
                put("samples", sampled.samples)
                put("note", def.note)
            }.toString()
        }.getOrElse { "ERROR: 读取传感器失败：${it.message ?: it.javaClass.simpleName}" }
    }

    private data class Sampled(val values: List<Float>, val samples: Int)

    private suspend fun sample(sm: SensorManager, sensor: Sensor): Sampled? =
        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            val done = AtomicBoolean(false)
            var samples = 0
            var sx = 0.0
            var sy = 0.0
            var sz = 0.0
            var width = 1

            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val v = event.values
                    if (v.isEmpty()) return
                    samples++
                    width = v.size.coerceAtMost(3)
                    sx += v[0].toDouble()
                    if (v.size > 1) sy += v[1].toDouble()
                    if (v.size > 2) sz += v[2].toDouble()
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }

            fun finish() {
                if (!done.compareAndSet(false, true)) return
                runCatching { sm.unregisterListener(listener) }
                val result = if (samples == 0) {
                    null
                } else {
                    val values = mutableListOf((sx / samples).toFloat())
                    if (width > 1) values.add((sy / samples).toFloat())
                    if (width > 2) values.add((sz / samples).toFloat())
                    Sampled(values, samples)
                }
                if (cont.isActive) cont.resume(result)
            }

            val registered = runCatching {
                sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI, handler)
            }.getOrDefault(false)
            if (!registered) {
                finish()
                return@suspendCancellableCoroutine
            }
            handler.postDelayed({ finish() }, SAMPLE_WINDOW_MS)
            cont.invokeOnCancellation {
                runCatching { sm.unregisterListener(listener) }
            }
        }

    companion object {
        private const val SAMPLE_WINDOW_MS = 300L
    }
}
