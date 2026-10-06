package com.carrot.amapnavi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/* =========================================================
 * 手机 GPS 采集（CP 侧的定位冗余通道）
 *
 * 为什么需要：CP(carrot_serv) 里有一段逻辑——
 *   「如果 3 秒内没有导航数据，则通过手机 GPS 更新」
 *   并读取 latitude / longitude / heading(→nPosAnglePhone) / accuracy，
 *   且 accuracy < 15 米才采信(phone_gps_frame)。
 * 隧道里高德数据断流时，这是 CP 唯一还能拿到的位置参考。
 *
 * 默认关闭（phone_gps_enable）：需要定位权限，且会额外耗电；
 * 在设置页打开后会随 App 一并申请运行时权限。
 * ========================================================= */
class PhoneGpsProvider(private val context: Context) {

    /** 最新一份有效定位（null = 还没有） */
    @Volatile
    var lastLocation: Location? = null
        private set

    /** 定位服务（无 GPS 的设备返回 null，必须可空，否则强转会崩） */
    private val manager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var started = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // accuracy <= 0 表示无精度信息，CP 侧要求 < 15 才采信，这里直接丢掉
            if (!location.hasAccuracy() || location.accuracy > MAX_ACCURACY_M) return
            lastLocation = location
        }

        @Deprecated("旧版回调，minSdk 21 仍可能被调用")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {
            lastLocation = null
        }
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    fun start(): Boolean {
        if (started) return true
        if (!hasPermission()) return false
        val enabled = runCatching {
            var ok = false
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (manager?.isProviderEnabled(p) == true) {
                    manager.requestLocationUpdates(p, MIN_INTERVAL_MS, MIN_DISTANCE_M, listener, mainHandler.looper)
                    ok = true
                }
            }
            ok
        }.getOrDefault(false)
        started = enabled
        return enabled
    }

    fun stop() {
        if (!started) return
        runCatching { manager?.removeUpdates(listener) }
        started = false
        lastLocation = null
    }

    companion object {
        private const val MIN_INTERVAL_MS = 1000L
        private const val MIN_DISTANCE_M = 5f
        /** 超过此精度 CP 不采信，没必要上报 */
        const val MAX_ACCURACY_M = 15f

        val PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }
}
