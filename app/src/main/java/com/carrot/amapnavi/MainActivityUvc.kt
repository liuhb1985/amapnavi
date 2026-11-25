package com.carrot.amapnavi

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import androidx.lifecycle.lifecycleScope
import com.carrot.amapnavi.LogStorage
import com.carrot.amapnavi.uvc.UvcCameraController
import com.carrot.amapnavi.uvc.UvcLog
import com.carrot.amapnavi.uvc.UvcState
import com.carrot.amapnavi.uvc.UsbPermissionBroker
import com.carrot.amapnavi.uvc.toHint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

/**
 * 权限 UI 在途保护：避免「获取权限」按钮连点产生重复 requestPermission，
 * 同时防止 CAMERA 权限申请与 USB 权限申请互相抢弹窗。
 */
private object UvcPermissionUiGuard {
    @Volatile var cameraRequesting = false
    @Volatile var usbRequesting = false
}

/** 权限弹窗兜底：部分车机 ROM 不弹运行时权限框，超时后引导去系统设置 */
private val uvcMainHandler = Handler(Looper.getMainLooper())

/** 权限回调与超时竞态保护：每次申请生成新 flowId，过期回调一律忽略 */
private var uvcCameraFlowId = 0L

/** 诊断结果缓存：applyUvcState 高频调用时避免主线程反复枚举 USB/Camera */
private var uvcDiagCache: Pair<Long, String>? = null

private fun MainActivity.cachedUvcDiagnose(): String {
    val now = SystemClock.elapsedRealtime()
    uvcDiagCache?.let { (at, text) ->
        if (now - at < 1000L) return text
    }
    val text = uvcController?.diagnose().orEmpty()
    uvcDiagCache = now to text
    return text
}

/** 采集卡日志开关随 app_log 一起：界面上有地方可关，避免日志被采集卡刷屏 */
private const val PREF_UVC_LOG = "uvc_log"

/** 采集卡预览展开状态持久化：Activity 重建后不再自动收起 */
private const val PREF_UVC_EXPANDED = "uvc_expanded"

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

    findViewById<Button>(R.id.btnUvcExportLog).setOnClickListener { exportUvcLogs() }
    findViewById<Button>(R.id.btnUvcPermissions).setOnClickListener { requestAllUvcPermissions() }
    findViewById<Button>(R.id.btnUvcPermissionSettings).setOnClickListener { openUvcPermissionSettings() }

    uvcPreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            // 异步回调，不受 setupUvcPreview 的 try 保护：这里的异常会直接冒泡到主线程 → 闪退
            runCatching {
                UvcLog.i("MainActivityUvc", "TextureView Surface 就绪 ${width}x${height}")
                if (uvcBody.visibility == View.VISIBLE) {
                    startUvcPreview(controller, surface)
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

    // 恢复上次展开状态：Activity 重建后不再被 XML 默认 gone “自动收起”
    if (getSharedPreferences("user_settings", Context.MODE_PRIVATE)
            .getBoolean(PREF_UVC_EXPANDED, false)
    ) {
        uvcBody.post { expandUvc() }
    }

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
                startUvcPreview(controller, st)
            }.onFailure { UvcLog.e("MainActivityUvc", "ON_START 恢复预览失败(已隔离)", it) }
        }
    })

    handleUvcIntent(intent)
}

/** 展开预览区 */
internal fun MainActivity.expandUvc() {
    uvcBody.visibility = View.VISIBLE
    btnUvcToggle.text = "收起"
    getSharedPreferences("user_settings", Context.MODE_PRIVATE)
        .edit().putBoolean(PREF_UVC_EXPANDED, true).apply()
    val st = uvcPreview.surfaceTexture
    if (st != null) {
        uvcController?.let { startUvcPreview(it, st) }
    } else {
        tvUvcStatus.text = "等待预览 Surface 就绪…"
    }
}

/** 收起预览区（同时停流） */
internal fun MainActivity.collapseUvc() {
    uvcBody.visibility = View.GONE
    btnUvcToggle.text = "展开"
    getSharedPreferences("user_settings", Context.MODE_PRIVATE)
        .edit().putBoolean(PREF_UVC_EXPANDED, false).apply()
    uvcController?.stop()
    tvUvcStatus.text = ""
    tvUvcBrief.text = "已关闭"
}

// ==================== 状态渲染 ====================

private fun MainActivity.applyUvcState(state: UvcState) {
    val hint = state.toHint()
    // 没出画面时把诊断摘要一起显示：车上看不到日志，靠这几行就能判断卡在哪一步
    tvUvcStatus.text = if (state is UvcState.Streaming) {
        hint
    } else {
        val diag = runCatching { cachedUvcDiagnose() }.getOrDefault("")
        if (diag.isEmpty()) hint else "$hint\n---\n$diag"
    }
    tvUvcBrief.text = when (state) {
        is UvcState.Streaming -> "已连接"
        is UvcState.Opening, is UvcState.AwaitingUsbPermission -> "连接中"
        is UvcState.NoDevice -> "未检测"
        else -> "异常"
    }
    // 只在状态变化的关键节点写日志，避免刷屏
    when (state) {
        is UvcState.Streaming -> appendLogIfEnabled("采集卡: $hint")
        is UvcState.Error -> appendLogIfEnabled(
            "采集卡错误: $hint\n${runCatching { cachedUvcDiagnose() }.getOrDefault("")}"
        )
        is UvcState.NoDevice -> appendLogIfEnabled("采集卡: 未检测到设备")
        is UvcState.UsbPermissionDenied, is UvcState.UsbPermissionBlocked ->
            appendLogIfEnabled("采集卡权限: $hint")
        else -> Unit
    }
}

/**
 * 一键导出采集卡异常日志：
 * 把当前诊断摘要 + 主日志中 UVC/USB/错误相关行汇总成单个文件并走系统分享，
 * 现场出问题时点一下即可把完整上下文发回来，不用手动翻日志文件。
 */
private fun MainActivity.exportUvcLogs() {
    val diag = runCatching { uvcController?.diagnose() }.getOrNull().orEmpty()
    lifecycleScope.launch(Dispatchers.IO) {
        // 即使车主关了 UVC 详细日志，也强制落一条本次诊断快照，保证导出内容可分析
        val prev = UvcLog.fileEnabled
        UvcLog.fileEnabled = true
        UvcLog.i("MainActivityUvc", "导出诊断快照: ${diag.replace('\n', ' ')}")
        UvcLog.fileEnabled = prev
        runCatching { Thread.sleep(120) }
        val file = UvcLog.exportErrorReport(this@exportUvcLogs, diag)
        withContext(Dispatchers.Main) {
            if (file == null) {
                Toast.makeText(this@exportUvcLogs, "导出失败，请检查存储权限", Toast.LENGTH_LONG).show()
                return@withContext
            }
            requestStorageThen {
                LogStorage.shareLogs(
                    this@exportUvcLogs,
                    "采集卡异常日志",
                    listOf(file),
                    "${LogStorage.PUBLIC_DIR_NAME}/export"
                )
            }
            Toast.makeText(
                this@exportUvcLogs,
                "已生成 ${file.name}\n${file.absolutePath}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}

/**
 * 「获取权限」按钮：主动一次性申请相机权限 + USB 设备权限，
 * 不等待系统弹窗/chooser 出现（部分车机 ROM 很少弹窗）。
 */
private fun MainActivity.requestAllUvcPermissions() {
    val camGranted = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED
    if (!camGranted) {
        // Android 9+ 上 Video Class USB 权限以 CAMERA 权限为前提，
        // 必须先拿到 CAMERA，再发 USB 请求；否则必秒拒且不弹窗。
        val text = "相机权限：已发起申请\n授予后将自动申请 USB 权限"
        tvUvcStatus.text = text
        appendLogIfEnabled("采集卡权限按钮: $text")
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
        ensureCameraPermission { requestUsbPermissionWithUi() }
        return
    }
    val text = "相机权限：已具备\nUSB权限：${requestUsbPermissionCore(false)}"
    tvUvcStatus.text = text
    appendLogIfEnabled("采集卡权限按钮: $text")
    Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}

/** UI 单按钮出口：带提示 */
private fun MainActivity.requestUsbPermissionWithUi(): String =
    requestUsbPermissionCore(true)

/**
 * USB 授权唯一核心：
 * - 在途时直接拒绝重复点击，防止顶掉系统弹窗；
 * - 接受请求后由 controller 回调解除保护；
 * - 60s 无广播也强制解除，避免 ROM 吞掉结果导致按钮永久卡死。
 */
private fun MainActivity.requestUsbPermissionCore(showUi: Boolean): String {
    if (UvcPermissionUiGuard.usbRequesting) {
        val msg = "已有 USB 授权流程在进行中，请查看系统弹窗"
        if (showUi) {
            tvUvcStatus.text = msg
            appendLogIfEnabled("采集卡: $msg")
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
        return msg
    }
    UvcPermissionUiGuard.usbRequesting = true
    val activity = this
    val msg = uvcController?.requestUsbPermissionManually {
        UvcPermissionUiGuard.usbRequesting = false
    } ?: "USB权限：采集卡控制器未就绪"
    if (!msg.startsWith("已发起授权请求")) {
        UvcPermissionUiGuard.usbRequesting = false
    } else {
        uvcMainHandler.postDelayed({
            if (!UvcPermissionUiGuard.usbRequesting) return@postDelayed
            val dev = uvcController?.findUsbVideoDevice()
            val stillNoPermission = dev == null || !UsbPermissionBroker.hasPermission(activity, dev)
            UvcPermissionUiGuard.usbRequesting = false
            if (stillNoPermission) {
                val t = "USB 权限请求 60s 无结果，请点「获取权限」重试或用 chooser 授权"
                activity.tvUvcStatus.text = t
                activity.appendLogIfEnabled("采集卡: $t")
                Toast.makeText(activity, t, Toast.LENGTH_LONG).show()
            }
        }, 60_000L)
    }
    if (showUi) {
        tvUvcStatus.text = msg
        appendLogIfEnabled("采集卡: $msg")
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
    return msg
}

/** 弹窗不出现时的兜底：直接打开本应用权限设置页，用户手动开启相机权限 */
private fun MainActivity.openUvcPermissionSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:$packageName")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val ok = runCatching { startActivity(intent); true }.getOrDefault(false)
    val msg = if (ok) "已打开应用权限设置，请开启相机权限后返回" else "无法打开系统设置，请到设置中手动搜索本应用"
    tvUvcStatus.text = msg
    appendLogIfEnabled("采集卡: $msg")
    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

/**
 * 启动预览：只有 camera2 外置摄像头通道才需要 CAMERA 权限；
 * USB 直连只需要 USB 设备权限，不能被相机权限误拦。
 */
private fun MainActivity.startUvcPreview(controller: UvcCameraController, surface: SurfaceTexture) {
    if (controller.needsCameraPermission()) {
        ensureCameraPermission { controller.startWithSurface(surface) }
    } else {
        controller.start(surface)
    }
}

private fun MainActivity.ensureCameraPermission(onGranted: () -> Unit) {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        == PackageManager.PERMISSION_GRANTED
    ) {
        onGranted()
        return
    }
    if (UvcPermissionUiGuard.cameraRequesting) {
        UvcLog.w("MainActivityUvc", "CAMERA 权限申请已在途中，忽略重复请求")
        return
    }
    UvcLog.w("MainActivityUvc", "缺少 CAMERA 运行时权限，发起申请")
    val flow = ++uvcCameraFlowId
    UvcPermissionUiGuard.cameraRequesting = true
    uvcCameraGrantedCallback = onGranted
    androidx.core.app.ActivityCompat.requestPermissions(
        this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA_PERMISSION
    )
    // 部分车机 ROM 会吞掉运行时权限弹窗且永不回调；超时后放开保护并引导去设置页
    uvcMainHandler.postDelayed({
        val activity = this@ensureCameraPermission
        if (flow != uvcCameraFlowId) return@postDelayed
        if (UvcPermissionUiGuard.cameraRequesting &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            uvcCameraFlowId++ // 超时后旧回调作废
            UvcPermissionUiGuard.cameraRequesting = false
            uvcCameraGrantedCallback = null
            UvcLog.w("MainActivityUvc", "CAMERA 权限弹窗 10s 未返回结果，引导去系统设置")
            Toast.makeText(
                activity, "系统未弹出相机权限框，请点「权限设置」手动开启", Toast.LENGTH_LONG
            ).show()
        }
    }, 10_000L)
}

/** 权限回调后的待执行动作 */
private var uvcCameraGrantedCallback: (() -> Unit)? = null

/** 在 MainActivity.onRequestPermissionsResult 中调用 */
internal fun MainActivity.onUvcPermissionResult(grantResults: IntArray) {
    uvcCameraFlowId++ // 结果已到，超时 Runnable 自动失效
    UvcPermissionUiGuard.cameraRequesting = false
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
                uvcController?.notifyUsbTopologyChanged(true)
            }
            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                UvcLog.i("MainActivityUvc", "收到 USB 设备拔出广播")
                uvcController?.notifyUsbTopologyChanged(false)
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
    uvcCameraFlowId++
    uvcCameraGrantedCallback = null
    UvcPermissionUiGuard.cameraRequesting = false
    UvcPermissionUiGuard.usbRequesting = false
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
            val granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            UvcLog.i(
                "MainActivityUvc",
                "chooser 授权路径: dev=${dev?.productName ?: "?"} hasPermission=$granted"
            )
            if (granted && dev != null) {
                uvcController?.onChooserPermissionGranted(dev)
            } else {
                uvcController?.notifyUsbTopologyChanged(true)
            }
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
