package io.nous.andagent.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.nous.andagent.bridge.PermissionBroker
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import kotlin.math.abs

/**
 * GPS / location tools.
 *
 * location_get: best-effort single fix (.getLastLocation from all providers,
 *      falls back to a short requestSingleUpdate when all caches are stale).
 *      Needs FINE_LOCATION (or COARSE) runtime permission — requested on demand.
 * location_status: which providers are enabled, last known fix per provider,
 *      no permission needed beyond what's already granted (stale coords only
 *      returned when permission is granted).
 */
class LocationGetTool(
    private val context: Context,
    private val permissions: PermissionBroker,
) : Tool {
    override val name = "location_get"
    override val description =
        "获取当前 GPS 位置（经纬度、精度、海拔、速度）。首次调用会请求定位权限；室内可能无 GPS 信号，会返回网络定位或明确说明。"

    override val parameters = schemaOf(
        "max_age_seconds" to intParam("允许的缓存位置最大年龄（秒），默认 60。缓存更新鲜则不重新定位"),
    )

    override suspend fun execute(args: JsonObject): String {
        val maxAgeSec = (args.intArg("max_age_seconds") ?: 60).coerceIn(1, 3600)
        val fine = permissions.ensure(listOf(Manifest.permission.ACCESS_FINE_LOCATION))
        val coarse = fine || permissions.ensure(listOf(Manifest.permission.ACCESS_COARSE_LOCATION))
        if (!fine && !coarse) {
            return "ERROR: 定位权限未授予（FINE/COARSE 均被拒）。可到系统设置手动开启后重试"
        }
        val lm = context.getSystemService(LocationManager::class.java)
            ?: return "ERROR: 设备无 LocationManager 服务"

        // 1) freshest cached fix across providers
        var best: Location? = null
        val providers = mutableListOf<String>()
        for (p in lm.allProviders) {
            if (!lm.isProviderEnabled(p)) continue
            providers.add(p)
            val loc = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
            if (best == null || loc.time > best!!.time) best = loc
        }
        val ageOk = best != null && (System.currentTimeMillis() - best!!.time) <= maxAgeSec * 1000L

        // 2) stale cache → short single update via passive/any provider
        if (!ageOk) {
            val waited = runCatching {
                val latch = java.util.concurrent.CountDownLatch(1)
                var got: Location? = null
                val listener = object : android.location.LocationListener {
                    override fun onLocationChanged(l: Location) { got = l; latch.countDown() }
                    @Deprecated("deprecated in api30") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
                    override fun onProviderEnabled(p: String) {}
                    override fun onProviderDisabled(p: String) {}
                }
                val criteria = Criteria().apply {
                    accuracy = if (fine) Criteria.ACCURACY_FINE else Criteria.ACCURACY_COARSE
                    powerRequirement = Criteria.POWER_LOW
                }
                val provider = lm.getBestProvider(criteria, true) ?: LocationManager.PASSIVE_PROVIDER
                lm.requestSingleUpdate(provider, listener, null)
                latch.await(12, java.util.concurrent.TimeUnit.SECONDS)
                runCatching { lm.removeUpdates(listener) }
                got ?: lm.getLastKnownLocation(provider)
            }.getOrNull()
            if (waited != null && (best == null || waited.time >= best!!.time)) best = waited
        }

        if (best == null) {
            return buildJsonObject {
                put("error", "无法获取位置：所有定位缓存为空且实时定位超时（12s）")
                put("enabledProviders", providers.joinToString(","))
                put("hint", "GPS 在室内常不可用；确保系统定位开关已开，稍后重试或增大 max_age_seconds")
            }.toString()
        }

        val ageSec = (System.currentTimeMillis() - best.time) / 1000
        return buildJsonObject {
            put("latitude", best.latitude)
            put("longitude", best.longitude)
            put("accuracyMeters", if (best.hasAccuracy()) best.accuracy else -1.0)
            if (best.hasAltitude()) put("altitudeMeters", best.altitude)
            if (best.hasSpeed()) put("speedMps", best.speed.toDouble())
            if (best.hasBearing()) put("bearingDegrees", best.bearing.toDouble())
            put("provider", best.provider ?: "unknown")
            put("ageSeconds", ageSec)
            put("timestamp", best.time)
            put(
                "mapsUrl",
                "https://maps.google.com/?q=${best.latitude},${best.longitude}",
            )
        }.toString()
    }
}

class LocationStatusTool(private val context: Context) : Tool {
    override val name = "location_status"
    override val description =
        "查询定位服务状态：GPS/网络/被动定位提供器是否启用、各自最后已知位置与年龄（有权限时）。不触发定位，不弹权限窗。"

    override val parameters = schemaOf()

    override suspend fun execute(args: JsonObject): String {
        val lm = context.getSystemService(LocationManager::class.java)
            ?: return "ERROR: 设备无 LocationManager 服务"
        val fineGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

        return buildJsonObject {
            put("gpsEnabled", lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
            put("networkEnabled", lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
            put("passiveEnabled", lm.isProviderEnabled(LocationManager.PASSIVE_PROVIDER))
            putJsonArray("providers") {
                for (p in lm.allProviders) add(p)
            }
            if (Build.VERSION.SDK_INT >= 30) {
                runCatching { put("isLocationEnabled", lm.isLocationEnabled) }
            }
            if (fineGranted) {
                putJsonArray("lastKnown") {
                    for (p in lm.allProviders) {
                        val loc = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
                        add(
                            buildJsonObject {
                                put("provider", p)
                                put("latitude", loc.latitude)
                                put("longitude", loc.longitude)
                                put("ageSeconds", abs(System.currentTimeMillis() - loc.time) / 1000)
                            },
                        )
                    }
                }
            } else {
                put("note", "无定位权限，不返回坐标；可用 location_get 触发授权")
            }
        }.toString()
    }
}
