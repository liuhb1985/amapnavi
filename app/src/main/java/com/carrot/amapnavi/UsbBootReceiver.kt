package com.carrot.amapnavi

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

/* =========================================================
 * 开机后采集卡状态恢复
 *
 * 移植自 navipilot 的 BootUsbRestoreReceiver.kt（适配本 App 的保活方式）。
 *
 * 场景：车机/手机重启后采集卡仍插着，但
 *   - USB 授权记录属于「上次会话」，与本次枚举出的设备实例可能不匹配；
 *   - 部分 ROM 的 OTG 供电默认关闭，设备被枚举但 openDevice 返回 null。
 * 这两种情况 App 都无解，必须让用户拔插一次 / 在系统 chooser 中重新选本应用。
 *
 * 做法：开机后在 20s 内轮询 USB 总线（用 goAsync 保进程不死），
 * 一旦出现 UVC 视频设备且本 App 尚未获得授权，发一条通知，
 * 点一下直达主界面的采集卡预览区。
 * ========================================================= */
class UsbBootReceiver : BroadcastReceiver() {

    /**
     * ★ 本 Receiver 走的是【开机广播】路径，此时没有任何 Activity，
     *   进程内一切都还没初始化，任何异常都会表现为"App 一开机就死"。
     *   因此这里对整段逻辑做完全隔离：出错只记日志，绝不让异常外泄。
     */
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = runCatching { goAsync() }.getOrNull()
        try {
            val action = intent.action
            if (action != Intent.ACTION_BOOT_COMPLETED &&
                action != ACTION_QUICKBOOT_POWERON
            ) return

            Log.i(TAG, "收到开机广播($action)，开始探测采集卡")
            AppLogger.log(context, "UVC", "$TAG: 收到开机广播($action)")

            val appContext = context.applicationContext
            val handler = Handler(Looper.getMainLooper())

            var elapsed = 0
            val step = 2000
            val maxElapsed = 20000
            val poll = object : Runnable {
                override fun run() {
                    try {
                        val dev = findUvcDevice(appContext)
                        if (dev != null) {
                            val granted = hasPermission(appContext, dev)
                            Log.i(TAG, "探测到 ${dev.productName ?: "USB 视频设备"} granted=$granted")
                            AppLogger.log(appContext, "UVC", "$TAG: 开机探测到 ${dev.productName ?: "USB 视频设备"} granted=$granted")
                            if (!granted) notifyRestore(appContext, dev.productName ?: "USB 采集卡")
                            pendingResult?.finish()
                            return
                        }
                        elapsed += step
                        if (elapsed < maxElapsed) {
                            handler.postDelayed(this, step.toLong())
                        } else {
                            Log.i(TAG, "开机探测超时：未发现 UVC 视频设备")
                            pendingResult?.finish()
                        }
                    } catch (t: Throwable) {
                        // 轮询回调里也必须吞掉异常，否则会冒到 Handler 主线程
                        Log.e(TAG, "开机探测异常(已隔离)", t)
                        AppLogger.log(appContext, "UVC", "$TAG: 开机探测异常已隔离: $t")
                        pendingResult?.finish()
                    }
                }
            }
            handler.postDelayed(poll, step.toLong())
        } catch (t: Throwable) {
            Log.e(TAG, "开机广播处理异常(已隔离)", t)
            runCatching { AppLogger.log(context, "UVC", "$TAG: 开机广播处理异常已隔离: $t") }
            pendingResult?.finish()
        }
    }

    /** 无 USB Host 的设备返回 null，必须可空，否则强转会崩 */
    private fun usbManager(context: Context): UsbManager? =
        runCatching { context.getSystemService(Context.USB_SERVICE) as? UsbManager }.getOrNull()

    private fun findUvcDevice(context: Context): UsbDevice? = try {
        usbManager(context)?.deviceList?.values?.firstOrNull { d ->
            (0 until d.interfaceCount).any { i ->
                d.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO
            }
        }
    } catch (t: Throwable) {
        null
    }

    private fun hasPermission(context: Context, dev: UsbDevice): Boolean = try {
        usbManager(context)?.hasPermission(dev) ?: false
    } catch (_: Exception) {
        false
    }

    private fun notifyRestore(context: Context, devName: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "采集卡恢复", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "开机后采集卡需要重新授权时提醒" }
            nm.createNotificationChannel(ch)
        }
        val click = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_OPEN_UVC, true)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pi = PendingIntent.getActivity(context, 1, click, PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("采集卡需要重新授权")
            .setContentText("检测到 $devName，点击打开采集卡预览并完成授权")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFY_ID, n)
        Log.i(TAG, "已发出采集卡恢复通知")
    }

    companion object {
        private const val TAG = "UsbBootReceiver"
        private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        private const val CHANNEL_ID = "uvc_restore"
        private const val NOTIFY_ID = 2101
    }
}
