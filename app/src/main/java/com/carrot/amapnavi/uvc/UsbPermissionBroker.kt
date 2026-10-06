package com.carrot.amapnavi.uvc

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/* =========================================================
 * USB 设备权限申请统一入口（单飞 + 自动请求退避 + 手动请求直通）
 *
 * 移植自 navipilot 的 ui/components/UsbPermissionBroker.kt
 * （去掉了 Compose 状态，改为回调；registerReceiver 因 targetSdk=28
 *  不需要 RECEIVER_EXPORTED 标记，故用系统默认导出行为）。
 *
 * 背景（华为 EMUI / 小米 MIUI 实测）：
 * 1. requestPermission() 的系统弹窗只在「用户主动、前台、无并发请求」时可靠出现。
 *    由插入广播/周期扫描驱动的自动请求常被 ROM 静默拒绝，且每次静默拒绝都会
 *    加深 ROM 的持久拒绝记录——此后秒拒，即便 targetSdk=28 + 全新安装也无法恢复。
 * 2. 同一设备多个组件在百毫秒内先后发起两次请求，第二次请求会顶掉第一次正在
 *    显示的系统弹窗——华为「勾选确定后无反应」的元凶。
 * 3. 实测可用的参考应用（USB Camera）弹窗可靠出现的关键：请求由用户点击按钮
 *    主动触发（前台、无并发、非插入风暴窗口）。
 *
 * 方案：本 App 所有 requestPermission 收敛到本对象。
 * - 单飞：同一时刻只允许一个在途请求（30s 窗口），保护正在显示的弹窗不被顶掉；
 * - 自动退避：被拒后 60s 内自动路径不再发起，仅用户手动点击可发起；
 * - 手动直通：manual=true 时绕过退避（仍受单飞保护）。
 * ========================================================= */
object UsbPermissionBroker {

    private const val TAG = "UsbPermissionBroker"

    /** 在途请求有效期：请求发出后 30s 内视为在途（覆盖系统弹窗显示的整段时间） */
    private const val IN_FLIGHT_MS = 30_000L

    /** 自动请求退避：被拒后 60s 内自动路径禁止再发（手动按钮不受限） */
    private const val AUTO_COOLDOWN_MS = 60_000L

    /** 任意两次请求（含手动）的最小间隔：连点会加深 ROM 防骚扰拦截记录 */
    private const val MIN_REQUEST_INTERVAL_MS = 5_000L

    /**
     * 插入优雅期：设备插入后此时间内禁止自动 requestPermission。
     * 实测（MIUI）：插入广播到达后 ~100ms App 即自动申请，而系统「打开方式」
     * chooser 要 ~2.5s 后才弹出——请求与 chooser 抢对话框被系统静默拒绝并记入
     * 拒绝记录。优雅期把 chooser 完整让给用户选择（选中 = 系统自动授权）。
     */
    private const val ATTACH_GRACE_MS = 6_000L

    enum class Status {
        ACCEPTED,        // 已受理，等待系统弹窗与结果回调
        ALREADY_GRANTED, // 权限已具备，无需申请
        IN_FLIGHT,       // 已有在途请求，本次被忽略
        COOLDOWN         // 自动路径退避中，本次被忽略
    }

    fun interface Callback {
        /** elapsedMs < 1000 视为「未弹窗秒拒」 */
        fun onResult(granted: Boolean, elapsedMs: Long)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var inFlightSince = 0L
    @Volatile private var lastDeniedAt = 0L
    @Volatile private var lastRequestAt = 0L
    @Volatile private var lastAttachAt = 0L

    /**
     * 设备插入广播回调：开启 chooser 优雅期。
     * 防抖：开机/重枚举时系统会在毫秒级连发多次插入事件（实测一次性 18 次），
     * 每次都 reset 会不断刷新优雅期——只认第一次。
     */
    fun markDeviceAttached() {
        val now = SystemClock.elapsedRealtime()
        if (lastAttachAt > 0 && now - lastAttachAt < 1000) return
        lastAttachAt = now
        reset()
        UvcLog.d(TAG, "设备插入：开启 ${ATTACH_GRACE_MS / 1000}s chooser 优雅期（自动申请延后）")
    }

    fun hasPermission(context: Context, device: UsbDevice): Boolean = try {
        (context.getSystemService(Context.USB_SERVICE) as UsbManager).hasPermission(device)
    } catch (_: Exception) {
        false
    }

    /** 清除退避与在途状态（拔插 / 反射清除授权记录成功后调用） */
    fun reset() {
        inFlightSince = 0
        lastDeniedAt = 0
        UvcLog.d(TAG, "重置：退避与在途状态已清空")
    }

    /**
     * 发起 USB 权限申请
     *
     * @param manual true = 用户点击按钮发起（绕过退避）；false = 自动路径
     * @param reason 日志标记（cam2-auto / direct-auto / ui-manual）
     */
    fun request(
        context: Context,
        device: UsbDevice,
        manual: Boolean,
        reason: String,
        cb: Callback
    ): Status {
        val now = SystemClock.elapsedRealtime()
        if (lastRequestAt > 0 && now - lastRequestAt < MIN_REQUEST_INTERVAL_MS) {
            UvcLog.d(TAG, "忽略请求($reason)：距上次请求仅${now - lastRequestAt}ms（节流${MIN_REQUEST_INTERVAL_MS / 1000}s）")
            return Status.COOLDOWN
        }
        if (now - inFlightSince < IN_FLIGHT_MS) {
            UvcLog.d(TAG, "忽略请求($reason)：已有在途权限请求（保护系统弹窗）")
            return Status.IN_FLIGHT
        }
        if (!manual && lastDeniedAt > 0 && now - lastDeniedAt < AUTO_COOLDOWN_MS) {
            UvcLog.d(TAG, "忽略自动请求($reason)：拒后退避中(${AUTO_COOLDOWN_MS / 1000}s)，手动按钮可绕过")
            return Status.COOLDOWN
        }
        if (!manual && lastAttachAt > 0 && now - lastAttachAt < ATTACH_GRACE_MS) {
            UvcLog.d(TAG, "忽略自动请求($reason)：插入优雅期内（chooser 弹窗中）")
            return Status.COOLDOWN
        }
        if (hasPermission(context, device)) {
            UvcLog.i(TAG, "权限已具备($reason)，无需申请")
            mainHandler.post { cb.onResult(true, 0) }
            return Status.ALREADY_GRANTED
        }

        val appContext = context.applicationContext
        val action = "${appContext.packageName}.USB_PERMISSION_BROKER"
        val requestAt = now
        inFlightSince = now
        lastRequestAt = now
        UvcLog.i(TAG, "发起USB权限请求($reason manual=$manual dev=${device.productName ?: "?"})")

        mainHandler.post {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    if (intent?.action != action) return
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val elapsed = SystemClock.elapsedRealtime() - requestAt
                    inFlightSince = 0
                    lastDeniedAt = if (granted) 0 else SystemClock.elapsedRealtime()
                    runCatching { appContext.unregisterReceiver(this) }
                    UvcLog.i(TAG, "USB权限结果($reason): granted=$granted 耗时${elapsed}ms")
                    cb.onResult(granted, elapsed)
                }
            }
            try {
                appContext.registerReceiver(receiver, IntentFilter(action))
                val pi = PendingIntent.getBroadcast(
                    appContext, device.deviceId,
                    Intent(action).setPackage(appContext.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT
                )
                (appContext.getSystemService(Context.USB_SERVICE) as UsbManager)
                    .requestPermission(device, pi)
            } catch (t: Throwable) {
                inFlightSince = 0
                runCatching { appContext.unregisterReceiver(receiver) }
                UvcLog.e(TAG, "USB权限请求异常($reason)", t)
                cb.onResult(false, 0)
            }
        }
        return Status.ACCEPTED
    }
}
