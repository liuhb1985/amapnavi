package com.carrot.amapnavi

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbManager
import android.os.Build
import android.view.TextureView
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import com.carrot.amapnavi.uvc.UvcCameraController
import com.carrot.amapnavi.uvc.UvcLog
import com.carrot.amapnavi.uvc.UvcState
import com.carrot.amapnavi.uvc.UsbPermissionBroker
import com.carrot.amapnavi.uvc.needsUserAction
import com.carrot.amapnavi.uvc.toHint

/* =========================================================
 * 采集卡预览（MainActivity 的扩展函数）
 *
 * 职责：把 activity_main.xml 里的 uvcCard 区域与 uvc/UvcCameraController 接起来：
 *   - 展开/收起卡片时按需启停取流（收起即停，省电、避免后台空转）
 *   - 把 UvcState 渲染到界面
 *   - USB 权限：运行时 CAMERA 权限 + USB 设备权限入口
 *   - 热插拔监听：插入广播 → markDeviceAttached + 重扫
 * ========================================================= */

/** 采集卡控制器（主 Activity 生命周期内有效） */
private var uvcController: UvcCameraController? = null

/** 采集卡日志开关随 app_log 一起：界面上有地方可关，避免日志被采集卡刷屏 */
private const val PREF_UVC_LOG = "uvc_log"

private fun AppCompatActivity.uvcLogEnabled(): Boolean =
    getSharedPreferences("user_settings", Context.MODE_PRIVATE).getBoolean(PREF_UVC_LOG, true)

/**
 * MainActivity 入口：onCreate 中调用一次。
 *
 * ★ 隔离原则：采集卡是**附加功能**，它出任何问题都只能自己降级，
 *   绝不能影响导航主功能——"App 打不开"这类事故代价太大。
 *   因此这里把所有初始化包在 try 里：失败就隐藏卡片并记日志。
 */
internal fun MainActivity.setupUvcPreview() {
    try {
        setupUvcPreviewInternal()
    } catch (t: Throwable) {
        UvcLog.e("MainActivityUvc", "采集卡初始化失败，已隔离（不影响导航主功能）", t)
        AppLogger.log(this, "UVC", "采集卡初始化异常已隔离: $t")
        runCatching { uvcCard.visibility = View.GONE }
    }
}

private fun MainActivity.setupUvcPreviewInternal() {
    UvcLog.init(applicationContext)
    UvcLog.fileEnabled = uvcLogEnabled()

    val controller = UvcCameraController(this)
    uvcController = controller

    controller.onState = { state -> applyUvcState(state) }

    // 展开 / 收起
    btnUvcToggle.setOnClickListener {
        val expanded = uvcBody.visibility == View.VISIBLE
        if (expanded) collapseUvc() else expandUvc()
    }

    btnUvcRetry.setOnClickListener {
        controller.userRetry()
        tvUvcStatus.text = "正在重新连接…"
    }

    btnUvcUsbPerm.setOnClickListener {
        val msg = controller.requestUsbPermissionManually()
        tvUvcStatus.text = msg
        appendLogIfEnabled("采集卡: $msg")
    }

    uvcPreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            // 异步回调，不受 setupUvcPreview 的 try 保护：这里的异常会直接冒泡到主线程 → 闪退
            runCatching {
                UvcLog.i("MainActivityUvc", "TextureView Surface 就绪 ${width}x${height}")
                if (uvcBody.visibility == View.VISIBLE) {
                    ensureCameraPermission { controller.start(surface) }
                }
            }.onFailure { UvcLog.e("MainActivityUvc", "预览启动失败(已隔离)", it) }
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            UvcLog.i("MainActivityUvc", "TextureView Surface 销毁，停止取流")
            controller.stop()
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    registerUvcUsbReceiver()

    // 页面回到后台时停流（前台服务 KeepAliveService 不依赖采集卡，停流不影响其他功能）
    lifecycle.addObserver(object : LifecycleObserver {
        @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
        fun onStopped() {
            if (uvcBody.visibility == View.VISIBLE) controller.stop()
        }

        @OnLifecycleEvent(Lifecycle.Event.ON_START)
        fun onStarted() {
            runCatching {
                if (uvcBody.visibility != View.VISIBLE) return
                val st = uvcPreview.surfaceTexture ?: return
                runCatching { st.setDefaultBufferSize(uvcPreview.width, uvcPreview.height) }
                    .onFailure {
                        UvcLog.w("MainActivityUvc", "SurfaceTexture 已失效: $it")
                        return@runCatching
                    }
                ensureCameraPermission { controller.start(st) }
            }.onFailure { UvcLog.e("MainActivityUvc", "ON_START 恢复预览失败(已隔离)", it) }
        }
    })

    handleUvcIntent(intent)
}

/** 展开预览区 */
internal fun MainActivity.expandUvc() {
    uvcBody.visibility = View.VISIBLE
    btnUvcToggle.text = "收起"
    val st = uvcPreview.surfaceTexture
    if (st != null) {
        ensureCameraPermission { uvcController?.start(st) }
    } else {
        tvUvcStatus.text = "等待预览 Surface 就绪…"
    }
}

/** 收起预览区（同时停流） */
internal fun MainActivity.collapseUvc() {
    uvcBody.visibility = View.GONE
    btnUvcToggle.text = "展开"
    uvcController?.stop()
    tvUvcStatus.text = ""
    tvUvcBrief.text = "已关闭"
}

// ==================== 状态渲染 ====================

private fun MainActivity.applyUvcState(state: UvcState) {
    val hint = state.toHint()
    tvUvcStatus.text = hint
    tvUvcBrief.text = when (state) {
        is UvcState.Streaming -> "已连接"
        is UvcState.Opening, is UvcState.AwaitingUsbPermission -> "连接中"
        is UvcState.NoDevice -> "未检测"
        else -> "异常"
    }
    btnUvcUsbPerm.visibility = if (state.needsUserAction) View.VISIBLE else View.GONE

    // 只在状态变化的关键节点写日志，避免刷屏
    when (state) {
        is UvcState.Streaming -> appendLogIfEnabled("采集卡: $hint")
        is UvcState.Error -> appendLogIfEnabled("采集卡错误: $hint")
        is UvcState.NoDevice -> appendLogIfEnabled("采集卡: 未检测到设备")
        is UvcState.UsbPermissionDenied, is UvcState.UsbPermissionBlocked ->
            appendLogIfEnabled("采集卡权限: $hint")
        else -> Unit
    }
}

private fun MainActivity.ensureCameraPermission(onGranted: () -> Unit) {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        == PackageManager.PERMISSION_GRANTED
    ) {
        onGranted()
        return
    }
    UvcLog.w("MainActivityUvc", "缺少 CAMERA 运行时权限，发起申请")
    androidx.core.app.ActivityCompat.requestPermissions(
        this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA_PERMISSION
    )
    uvcCameraGrantedCallback = onGranted
}

/** 权限回调后的待执行动作 */
private var uvcCameraGrantedCallback: (() -> Unit)? = null

/** 在 MainActivity.onRequestPermissionsResult 中调用 */
internal fun MainActivity.onUvcPermissionResult(grantResults: IntArray) {
    val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
    val cb = uvcCameraGrantedCallback
    uvcCameraGrantedCallback = null
    if (granted) cb?.invoke() else applyUvcState(UvcState.PermissionDenied)
}

internal const val REQ_CAMERA_PERMISSION = 10086

// ==================== USB 热插拔 ====================

/** USB 插拔广播是否已注册（避免重复注销崩溃） */
private var uvcUsbRegistered = false

private val uvcUsbReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                UvcLog.i("MainActivityUvc", "收到 USB 设备插入广播")
                UsbPermissionBroker.markDeviceAttached()
                uvcController?.notifyUsbTopologyChanged()
            }
            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                UvcLog.i("MainActivityUvc", "收到 USB 设备拔出广播")
                uvcController?.notifyUsbTopologyChanged()
            }
        }
    }
}

/**
 * 动态注册 USB 插拔广播。
 * 注：Manifest 里另有 UsbAttachActivity 声明 USB_DEVICE_ATTACHED + usb_device_filter，
 * 作用是让系统弹出「打开方式」chooser——用户在 chooser 中选中本应用时系统会【自动授予
 * USB 权限、不弹授权窗】，这是华为等 ROM 上唯一可靠的授权路径。两者互补，缺一不可。
 */
private fun MainActivity.registerUvcUsbReceiver() {
    uvcUsbRegistered = true
    val filter = IntentFilter().apply {
        addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
    }
    registerReceiver(uvcUsbReceiver, filter)
}

/** MainActivity.onDestroy 中调用：注销广播并彻底释放控制器，避免 Activity 泄漏 */
internal fun MainActivity.unregisterUvcUsbReceiver() {
    if (uvcUsbRegistered) {
        runCatching { unregisterReceiver(uvcUsbReceiver) }
        uvcUsbRegistered = false
    }
    uvcCameraGrantedCallback = null
    uvcController?.stop()
    uvcController = null
}

// ==================== 外部入口 ====================

/**
 * 处理来自 UsbAttachActivity / 开机恢复通知的 Intent。
 * @return true 表示本 Intent 已被采集卡模块消费
 */
internal fun MainActivity.handleUvcIntent(intent: Intent?): Boolean {
    val i = intent ?: return false
    when {
        i.getBooleanExtra(EXTRA_OPEN_UVC, false) -> {
            UvcLog.i("MainActivityUvc", "外部请求展开采集卡预览")
            if (uvcBody.visibility != View.VISIBLE) expandUvc()
            return true
        }
        i.action == UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
            UvcLog.i("MainActivityUvc", "系统 USB_DEVICE_ATTACHED Intent（chooser 授权路径）")
            UsbPermissionBroker.markDeviceAttached()
            @Suppress("DEPRECATION")
            val dev = i.getParcelableExtra(UsbManager.EXTRA_DEVICE) as? android.hardware.usb.UsbDevice
            UvcLog.i(
                "MainActivityUvc",
                "chooser 授权路径: dev=${dev?.productName ?: "?"} " +
                        "hasPermission=" + i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            )
            uvcController?.notifyUsbTopologyChanged()
            return true
        }
        else -> return false
    }
}

internal const val EXTRA_OPEN_UVC = "open_uvc"

private fun MainActivity.appendLogIfEnabled(text: String) {
    // 复用主日志：采集卡自身的详细调试信息由 UvcLog 统一落到同一个日志文件
    AppLogger.log(this, "UVC", text)
}
