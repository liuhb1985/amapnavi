package com.carrot.amapnavi

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

/* =========================================================
 * USB 采集卡插入「跳板 Activity」
 *
 * 移植自 navipilot 的 UsbIntentReceiver.kt。
 *
 * 作用：Manifest 里用 usb_device_filter 声明了 MS2109 / 通用 UVC 设备后，
 * 插入采集卡时系统会弹出「打开方式」chooser。用户选中本 App 并勾选「始终」时，
 * 【系统会自动授予 USB 设备权限，不弹任何授权弹窗】——
 * 这是华为/小米等 ROM 上绕过 requestPermission 秒拒的唯一可靠路径
 * （ROM 拒绝记录在吊被试多次后会永久生效，届时连 CHOOSER 也是唯一出路）。
 *
 * 本 Activity 完全透明、无历史，只做一件事：把 intent 转交给 MainActivity。
 * 直接在 MainActivity 上声明 intent-filter 会导致每次插卡都可能重新拉起主界面，
 * 用跳板可以把这个副作用隔离掉。
 * ========================================================= */
class UsbAttachActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forward()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        forward()
    }

    private fun forward() {
        val src = intent
        Log.i(TAG, "USB 采集卡插入，跳板转发: action=${src?.action}")

        val target = Intent(this, MainActivity::class.java).apply {
            action = src?.action
            putExtra(com.carrot.amapnavi.EXTRA_OPEN_UVC, true)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // 附上原始 extras，让 MainActivity 能拿到 UsbDevice 与授权标记
        src?.extras?.let { target.putExtras(it) }
        startActivity(target)
        finish()
    }

    companion object {
        private const val TAG = "UsbAttachActivity"
    }
}
