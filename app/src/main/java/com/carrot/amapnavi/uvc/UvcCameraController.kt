package com.carrot.amapnavi.uvc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat

/* =========================================================
 * UVC（HDMI 转 USB 采集卡）控制器 —— 双通道 + 自动降级 + 自愈
 *
 * 移植自 navipilot 的 ui/components/UvcCameraPreview.kt 中的控制器部分，
 * 并完成 amapnavi 侧的适配：
 *   - 去除 Compose，改为 onState 回调（界面用普通 View 渲染）
 *   - 去除三方 UVCAndroid(native libuvc) 路径：它依赖 androidx.core 1.13+，
 *     要求 compileSdk 34，与本项目 AGP 4.1.3 / compileSdk 28 不兼容。
 *     取而代之保留两条零依赖路径：camera2 外置摄像头 + Java 用户态 UVC 直连。
 *   - 去除了 ImageReader(YUV) 备用会话链（实测场景较少，留待需要时再补），
 *     camera2 不可用即降级到 UvcDirectStreamer。
 *
 * 两条取流通道（按优先级）：
 *  A. camera2 外置摄像头：ROM 把采集卡暴露为 LENS_FACING_EXTERNAL（API 28+）
 *  B. Java 用户态 UVC 直连：自行解析 USB 描述符 + Probe/Commit + iso/bulk 收流
 *     （ROM 缺 External Camera HAL 时的唯一出路，廉价车机上最常见）
 * ========================================================= */
class UvcCameraController(private val context: Context) {

    companion object {
        private const val TAG = "UvcCameraController"

        /** 外置摄像头回调卡死判定阈值（MIUI 无 USB 权限时 openCamera 永不回调） */
        private const val OPEN_TIMEOUT_MS = 8000L

        /** 常规重试间隔 */
        private const val RETRY_INTERVAL_MS = 5000L

        /** 系统层封锁后的廉价自检间隔（不创建任何实例） */
        private const val BLOCKED_CHECK_INTERVAL_MS = 15000L

        private const val PREF_NAME = "uvc_camera_prefs"
        private const val PREF_CAM2_HANG_ON_USB_PERM = "cam2_hang_on_usb_perm"
    }

    /** 状态回调（主线程） */
    var onState: ((UvcState) -> Unit)? = null

    /**
     * 相机服务。注意：必须用 as? + 可空——
     * getSystemService(CAMERA_SERVICE) 在【不具备相机服务的设备】上返回 null，
     * 非空强转(as)会抛 ClassCastException。而本对象是 MainActivity.onCreate 里
     * 同步创建的，一崩就是"App 打不开"。采集卡是附加功能，绝不能拖垮主流程。
     */
    private val cameraManager: CameraManager? =
        context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private val mainHandler = Handler(context.mainLooper)
    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var pendingSurface: SurfaceTexture? = null
    private var currentCameraId: String? = null

    private var directStreamer: UvcDirectStreamer? = null

    /** 直连硬失败计数（Java UsbRequest 不支持等时端点） */
    private var directHardFailures = 0

    /** camera2 在此设备上曾失败过，后续直接走直连，避免反复失败循环 */
    private var camera2FailedOnce = false

    /** 系统层打不开设备（hasPermission=true 但 openDevice 恒 null），停止自动重建 */
    private var usbOpenBlocked = false

    /** 陈旧授权自救尝试次数（本次拓扑周期内限制次数，防止死循环） */
    private var staleAuthCount = 0

    /** openCamera 是否异步在途 */
    private var openingCamera = false
    private var openingSince = 0L

    /** camera2 openCamera 卡死次数 */
    private var cam2HangCount = 0

    /** 分辨率候选（降试顺序） */
    private var sizeCandidates: List<Size> = emptyList()
    private var sizeAttempt = 0

    /** 直连实例单飞防抖 */
    private var lastDirectStartAt = 0L
    private var lastDirectState: UvcState? = null

    private val availabilityCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            UvcLog.i(TAG, "摄像头可用: $cameraId")
            notifyUsbTopologyChanged()
        }

        override fun onCameraUnavailable(cameraId: String) {
            UvcLog.i(TAG, "摄像头不可用: $cameraId")
        }
    }

    private var cam2HangOnUsbPermission: Boolean
        get() = prefs.getBoolean(PREF_CAM2_HANG_ON_USB_PERM, false)
        set(value) {
            prefs.edit().putBoolean(PREF_CAM2_HANG_ON_USB_PERM, value).apply()
        }

    // ==================== 生命周期 ====================

    fun start(surface: SurfaceTexture) {
        pendingSurface = surface
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("UvcCamera").also { it.start() }
            backgroundHandler = Handler(backgroundThread!!.looper)
        }
        val bg = backgroundHandler
        try {
            cameraManager?.registerAvailabilityCallback(availabilityCallback, bg)
        } catch (e: Exception) {
            UvcLog.w(TAG, "注册摄像头监听失败: ${e.message}")
        }
        logUsbDiagnostics()
        tryOpen()
        scheduleRetry()
    }

    fun stop() {
        retryHandler.removeCallbacks(retryRunnable)
        closeSession()
        stopDirect()
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        currentCameraId = null
        try {
            cameraManager?.unregisterAvailabilityCallback(availabilityCallback)
        } catch (_: Exception) {
        }
        releaseBackgroundThread()
        pendingSurface = null
    }

    fun setSurface(surface: SurfaceTexture) {
        pendingSurface = surface
    }

    // ==================== 重试调度 ====================

    private val retryHandler = Handler(context.mainLooper)
    private val retryRunnable = object : Runnable {
        override fun run() {
            val delay = if (usbOpenBlocked) {
                // 封锁态：只做廉价的 openDevice 自检，不创建任何实例，避免无谓 churn
                if (blockedSelfCheck()) {
                    UvcLog.i(TAG, "系统层恢复，立即重扫")
                    tryOpen()
                }
                BLOCKED_CHECK_INTERVAL_MS
            } else {
                val s = lastState
                if (s !is UvcState.Streaming && s !is UvcState.AwaitingUsbPermission) {
                    tryOpen()
                }
                RETRY_INTERVAL_MS
            }
            retryHandler.postDelayed(this, delay)
        }
    }

    private fun scheduleRetry() {
        retryHandler.removeCallbacks(retryRunnable)
        retryHandler.postDelayed(retryRunnable, RETRY_INTERVAL_MS)
    }

    @Volatile
    private var lastState: UvcState = UvcState.Idle

    private fun postState(state: UvcState) {
        lastState = state
        mainHandler.post { onState?.invoke(state) }
    }

    /**
     * USB 拓扑变化（拔出/换口重插）：清零防抖与失败锁，
     * 让重插后的首次 tryOpen 能立即重建链路。
     */
    fun notifyUsbTopologyChanged() {
        if (pendingSurface == null) return
        lastDirectStartAt = 0
        camera2FailedOnce = false
        cam2HangCount = 0
        staleAuthCount = 0
        // 拔插 = 新的设备实例：此前的「系统层打不开」封锁必须清零
        usbOpenBlocked = false
        UvcLog.i(TAG, "USB 拓扑变化：失败锁/封锁已复位，允许立即重建链路")
        tryOpen()
    }

    /** 用户主动「重新连接」：清除所有失败计数做一次全新尝试 */
    fun userRetry() {
        if (pendingSurface == null) {
            UvcLog.w(TAG, "userRetry：Surface 尚未就绪，忽略")
            return
        }
        UvcLog.i(TAG, "用户手动重新连接：清除封锁与失败计数，重新探测")
        usbOpenBlocked = false
        staleAuthCount = 0
        directHardFailures = 0
        cam2HangCount = 0
        camera2FailedOnce = false
        lastDirectStartAt = 0
        tryOpen()
    }

    // ==================== 主入口 ====================

    /**
     * 尝试建立取流链路。
     * @Synchronized：camera2 回调运行在后台线程，会与主线程并发调用 tryOpen/startDirect，
     * 不加锁时存在 check-then-set 竞态，会导致双实例互相关闭对方连接。
     */
    @Synchronized
    fun tryOpen() {
        val surface = pendingSurface ?: return

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            postState(UvcState.PermissionDenied)
            return
        }

        val cameraId = findExternalCameraId()
        if (cameraId == null) {
            val usbName = findUsbVideoDeviceName()
            if (usbName != null) {
                if (directHardFailures >= 2) {
                    UvcLog.w(TAG, "直连已熔断且 camera2 无外置摄像头，报告设备不支持")
                    postState(UvcState.Error("此设备 ROM 既未暴露外置摄像头，也不支持 USB 直连（等时传输不可用）"))
                    return
                }
                // USB 层已识别视频设备，但 camera2 未暴露 → 走用户态 UVC 直连
                UvcLog.i(TAG, "camera2 无外置摄像头（ROM 未暴露 External Camera），切换 USB 直连，设备: $usbName")
                if (usbOpenBlocked) {
                    UvcLog.w(TAG, "系统层 USB 打开已封锁，停止自动重扫，等待用户修复或拔插")
                    return
                }
                startDirect(surface)
            } else {
                postState(UvcState.NoDevice(null))
            }
            return
        }

        // camera2 曾在此设备失败过 → 直接走直连
        if (camera2FailedOnce && findUsbVideoDeviceName() != null) {
            if (directHardFailures >= 2) {
                UvcLog.w(TAG, "直连已熔断（硬失败 $directHardFailures 次），停止自动重试")
                return
            }
            if (usbOpenBlocked) return
            UvcLog.i(TAG, "camera2 此前已失败，跳过 camera2 直接走 USB 直连")
            startDirect(surface)
            return
        }

        // USB 权限门（条件化）：仅当本设备上 openCamera 已因缺 USB 权限卡死过才申请。
        // 华为等 ROM 的 External Camera HAL 由系统进程持有 USB 设备，应用无需权限——
        // 无条件拦截会导致华为上从未真正尝试过 camera2 直开。
        if (cam2HangOnUsbPermission) {
            val usbDev = findUsbVideoDevice()
            if (usbDev != null && !usbHasPermission(usbDev)) {
                requestUsbPermissionThenRetry(usbDev)
                return
            }
        }

        stopDirect()
        closeSession()
        if (currentCameraId != null && currentCameraId != cameraId) {
            runCatching { cameraDevice?.close() }
            cameraDevice = null
        }
        if (currentCameraId != cameraId) {
            currentCameraId = cameraId
            sizeCandidates = buildSizeCandidates(cameraId)
            sizeAttempt = 0
        }

        val device = cameraDevice
        if (device != null) {
            if (captureSession != null) return // 已在流中
            createSession(device, surface)
            return
        }

        // openCamera 异步在途 + 超时自愈
        if (openingCamera) {
            if (openingSince > 0 && SystemClock.elapsedRealtime() - openingSince > OPEN_TIMEOUT_MS) {
                cam2HangCount++
                UvcLog.w(TAG, "openCamera ${OPEN_TIMEOUT_MS}ms 无回调（HAL 卡死/无 USB 权限，第 $cam2HangCount 次），判定 camera2 失败，转 USB 直连")
                openingCamera = false
                openingSince = 0
                camera2FailedOnce = true
                cam2HangOnUsbPermission = true
                runCatching { cameraDevice?.close() }
                cameraDevice = null
                if (findUsbVideoDeviceName() != null && directHardFailures < 2) startDirect(surface)
            } else {
                UvcLog.d(TAG, "openCamera 在途中，跳过重复打开")
            }
            return
        }

        postState(UvcState.Opening)
        try {
            openingCamera = true
            openingSince = SystemClock.elapsedRealtime()
            val bg = backgroundHandler
            val cm = cameraManager
            if (bg == null || cm == null) {
                // 无相机服务：不要让这里抛异常，直接转 USB 直连
                openingCamera = false
                postState(UvcState.Error("本设备无相机服务，改用 USB 直连"))
                val st2 = pendingSurface
                if (findUsbVideoDeviceName() != null && st2 != null) startDirect(st2)
                return
            }
            cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    openingCamera = false
                    cameraDevice = device
                    pendingSurface?.let { createSession(device, it) }
                }

                override fun onDisconnected(device: CameraDevice) {
                    openingCamera = false
                    UvcLog.w(TAG, "摄像头断开（采集卡被拔出？）")
                    runCatching { device.close() }
                    if (cameraDevice == device) cameraDevice = null
                    postState(UvcState.NoDevice(null))
                }

                override fun onError(device: CameraDevice, error: Int) {
                    openingCamera = false
                    UvcLog.e(TAG, "camera2 打开失败: error=$error")
                    camera2FailedOnce = true
                    runCatching { device.close() }
                    if (cameraDevice == device) cameraDevice = null
                    postState(UvcState.Error("摄像头打开失败 ($error)"))
                    val st = pendingSurface
                    if (findUsbVideoDeviceName() != null && directHardFailures < 2 && st != null) {
                        startDirect(st)
                    }
                }
            }, bg)
        } catch (e: SecurityException) {
            openingCamera = false
            postState(UvcState.PermissionDenied)
        } catch (e: Exception) {
            openingCamera = false
            UvcLog.e(TAG, "openCamera 异常", e)
            postState(UvcState.Error("摄像头打开异常: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    // ==================== camera2 会话 ====================

    /**
     * 创建预览会话（分辨率降级重试版）
     *
     * 实测部分 ROM 的外置摄像头 HAL 会虚构不可用的「怪」分辨率（如 1440x480），
     * 会话配置失败时自动降试候选列表中的下一个。
     */
    private fun createSession(device: CameraDevice, surfaceTexture: SurfaceTexture) {
        if (sizeCandidates.isEmpty()) {
            sizeCandidates = buildSizeCandidates(currentCameraId ?: "")
            sizeAttempt = 0
        }
        val bg = backgroundHandler
        if (bg == null) {
            postState(UvcState.Error("后台线程未就绪"))
            return
        }
        if (sizeAttempt >= sizeCandidates.size) {
            UvcLog.e(TAG, "camera2 全部 ${sizeCandidates.size} 个候选分辨率均配置失败，转 USB 直连")
            camera2FailedOnce = true
            closeSession()
            runCatching { device.close() }
            if (cameraDevice == device) cameraDevice = null
            val st = pendingSurface
            if (findUsbVideoDeviceName() != null && directHardFailures < 2 && st != null) {
                postState(UvcState.Opening)
                startDirect(st)
            } else {
                postState(UvcState.Error("预览会话配置失败"))
            }
            return
        }

        val size = sizeCandidates[sizeAttempt]
        try {
            UvcLog.i(TAG, "camera2 尝试分辨率 ${size.width}x${size.height} (第 ${sizeAttempt + 1}/${sizeCandidates.size} 个候选)")
            surfaceTexture.setDefaultBufferSize(size.width, size.height)
            val surface = Surface(surfaceTexture)
            closeSession()
            device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        request.addTarget(surface)
                        session.setRepeatingRequest(request.build(), null, bg)
                        // 成功的分辨率提到候选列表首位，后续重建会话直接用它
                        sizeCandidates = listOf(size) + sizeCandidates.filterNot { it == size }
                        sizeAttempt = 0
                        UvcLog.i(TAG, "UVC 预览已启动 (${size.width}x${size.height})")
                        postState(UvcState.Streaming(getCameraName(currentCameraId ?: "")))
                    } catch (e: Exception) {
                        UvcLog.e(TAG, "setRepeatingRequest 失败", e)
                        postState(UvcState.Error("预览启动失败: ${e.message ?: e.javaClass.simpleName}"))
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    UvcLog.e(TAG, "camera2 会话配置失败 (cameraId=$currentCameraId, 分辨率=${size.width}x${size.height})，降试下一候选")
                    runCatching { session.close() }
                    closeSession()
                    sizeAttempt++
                    if (device === cameraDevice) createSession(device, surfaceTexture)
                }
            }, bg)
        } catch (e: Exception) {
            UvcLog.e(TAG, "创建预览会话异常 (分辨率=${size.width}x${size.height})", e)
            sizeAttempt++
            if (device === cameraDevice) {
                createSession(device, surfaceTexture)
            } else {
                camera2FailedOnce = true
                val st = pendingSurface
                if (findUsbVideoDeviceName() != null && st != null) startDirect(st)
                else postState(UvcState.Error("会话创建失败: ${e.message ?: e.javaClass.simpleName}"))
            }
        }
    }

    private fun closeSession() {
        captureSession?.let {
            try {
                it.stopRepeating()
                it.close()
            } catch (_: Exception) {
            }
        }
        captureSession = null
    }

    // ==================== 直连通道 ====================

    @Synchronized
    private fun startDirect(surface: SurfaceTexture) {
        if (usbOpenBlocked) {
            UvcLog.d(TAG, "usbOpenBlocked 已置位，跳过直连重建（等待用户修复或拓扑变化）")
            return
        }
        val now = SystemClock.elapsedRealtime()
        // 关键状态保护期：等待授权 / 正在打开 / 正在推流时 30s 内不重建实例。
        // 重建会注销待授权的 receiver 并重复 requestPermission，部分 ROM 直接不再弹窗。
        val st = lastDirectState
        val critical = st is UvcState.AwaitingUsbPermission ||
                st is UvcState.Opening || st is UvcState.Streaming
        if (critical && now - lastDirectStartAt < 30000) {
            UvcLog.d(TAG, "直连实例处于 ${st?.javaClass?.simpleName}，保护期内跳过重建")
            return
        }
        // 其余状态基础防抖 4s
        if (now - lastDirectStartAt < 4000) {
            UvcLog.d(TAG, "直连实例刚启动（<4s），跳过本次触发")
            return
        }
        lastDirectStartAt = now
        lastDirectState = null
        stopDirect()

        UvcLog.i(TAG, "启动用户态 UVC 直连（Java / 无三方依赖）")
        directStreamer = UvcDirectStreamer(context).apply {
            onStateChanged = { s ->
                lastDirectState = s
                if (s is UvcState.Error && s.message.contains("等时传输")) {
                    directHardFailures++
                    UvcLog.e(TAG, "直连硬失败（本 ROM 的 Java UsbRequest 不支持等时端点），累计 $directHardFailures 次，≥2 次后熔断")
                }
                if (s is UvcState.Streaming) {
                    staleAuthCount = 0
                    usbOpenBlocked = false
                }
                postState(s)
            }
            start(surface)
        }
    }

    @Synchronized
    private fun stopDirect() {
        directStreamer?.let { runCatching { it.stop() } }
        directStreamer = null
        lastDirectState = null
    }

    // ==================== USB 权限与自愈 ====================

    private fun requestUsbPermissionThenRetry(dev: UsbDevice) {
        postState(UvcState.AwaitingUsbPermission)
        UvcLog.i(TAG, "camera2 外置摄像头需要 USB 权限，发起请求（设备=${dev.productName ?: "?"}）…")
        val status = UsbPermissionBroker.request(context, dev, manual = false, reason = "cam2-auto") { granted, elapsed ->
            when {
                granted -> {
                    UvcLog.i(TAG, "USB 权限已授予（耗时 ${elapsed}ms），重新进入 camera2 流程")
                    tryOpen()
                }
                elapsed in 1..999 -> {
                    UvcLog.e(TAG, "USB 权限未弹窗即被拒（${elapsed}ms），降级 USB 直连")
                    camera2FailedOnce = true
                    val surf = pendingSurface
                    if (directHardFailures < 2 && surf != null) startDirect(surf)
                }
                else -> {
                    UvcLog.w(TAG, "USB 权限被用户拒绝（弹窗，耗时 ${elapsed}ms）")
                    postState(UvcState.UsbPermissionDenied)
                }
            }
        }
        if (status == UsbPermissionBroker.Status.COOLDOWN) {
            UvcLog.d(TAG, "camera2 自动权限请求退避中，降级 USB 直连")
            camera2FailedOnce = true
            val surf = pendingSurface
            if (directHardFailures < 2 && surf != null) startDirect(surf)
        }
    }

    /**
     * 用户点击「申请USB权限」：手动路径，绕过自动退避（仍受单飞保护）。
     * 实测这是唯一能可靠触发 ROM 授权弹窗的路径。
     */
    fun requestUsbPermissionManually(): String {
        val dev = findUsbVideoDevice()
            ?: return "未检测到 USB 视频设备（采集卡未插入或 OTG 未开启）"
        if (usbHasPermission(dev)) {
            UvcLog.i(TAG, "手动申请：权限已具备，直接重扫")
            userRetry()
            return "已有权限，正在重新连接…"
        }
        UsbPermissionBroker.reset()
        val status = UsbPermissionBroker.request(context, dev, manual = true, reason = "ui-manual") { granted, elapsed ->
            if (granted) {
                UvcLog.i(TAG, "手动 USB 授权成功（${elapsed}ms），重新打开")
                userRetry()
            } else {
                UvcLog.w(TAG, "手动 USB 授权失败（${elapsed}ms）")
                postState(UvcState.UsbPermissionDenied)
            }
        }
        return when (status) {
            UsbPermissionBroker.Status.ACCEPTED -> "已发起授权请求，请在系统弹窗中允许"
            UsbPermissionBroker.Status.IN_FLIGHT -> "已有授权请求在进行中，请查看系统弹窗"
            UsbPermissionBroker.Status.COOLDOWN -> "请求过于频繁，请稍候 5 秒后再试"
            UsbPermissionBroker.Status.ALREADY_GRANTED -> "已有权限，正在重新连接…"
        }
    }

    /**
     * 系统层封锁态下的廉价自检：仅用应用层 openDevice 试探一次，不创建任何实例。
     * 通过说明 OTG/设备节点已恢复，解除封锁。
     */
    private fun blockedSelfCheck(): Boolean {
        val dev = findUsbVideoDevice() ?: return false
        val ok = selfTestOpenDevice(dev)
        if (ok) {
            UvcLog.i(TAG, "系统层封锁态自检通过（OTG/设备节点已恢复），解除封锁")
            usbOpenBlocked = false
            staleAuthCount = 0
        }
        return ok
    }

    private fun selfTestOpenDevice(dev: UsbDevice): Boolean = try {
        val conn = usbManager()?.openDevice(dev)
        val ok = conn != null
        runCatching { conn?.close() }
        ok
    } catch (t: Throwable) {
        UvcLog.w(TAG, "openDevice 自检异常: ${UvcLog.describe(t)}")
        false
    }

    /**
     * 反射清除 USB 持久授权记录（系统签名 API，普通应用反射调用仅部分 ROM 允许）
     *
     * USB 授权状态保存在系统层，被记录为"永久拒绝"后 requestPermission() 毫秒级被拒。
     * 返回 true 表示确实清理了某些记录，可能让下次 requestPermission 弹窗重现。
     */
    private fun tryClearUsbPermissionRecord(dev: UsbDevice): Boolean {
        val usb = usbManager() ?: return false
        val candidates = mutableListOf<String>()
        candidates.add("clearPermission")
        candidates.add("revokePermission")
        candidates.add("removePermission")
        candidates.add("clearUsbPermission")
        val deviceCls = UsbDevice::class.java
        val stringCls = String::class.java
        for (name in candidates) {
            for (paramTypes in arrayOf(arrayOf<Class<*>>(deviceCls), arrayOf<Class<*>>(stringCls))) {
                try {
                    val m = usb.javaClass.getMethod(name, *paramTypes)
                    val args: Array<Any> = if (paramTypes[0] === deviceCls) {
                        arrayOf(dev)
                    } else {
                        arrayOf(dev.deviceName)
                    }
                    val ret = m.invoke(usb, *args)
                    val ok = when (ret) {
                        is Boolean -> ret
                        else -> true
                    }
                    UvcLog.i(TAG, "反射调用 UsbManager.$name(deviceName=${dev.deviceName}) 返回 $ret")
                    if (ok) return true
                } catch (_: NoSuchMethodException) {
                } catch (t: Throwable) {
                    UvcLog.d(TAG, "反射 $name 失败: ${UvcLog.describe(t)}")
                }
            }
        }
        return false
    }

    // ==================== 设备探测 ====================

    /**
     * 查找外置摄像头（UVC 采集卡）
     *
     * 严格匹配（绝不开内置摄像头）：
     * 1. LENS_FACING_EXTERNAL —— Android 9+ 标准外置摄像头通道
     * 2. INFO_SUPPORTED_HARDWARE_LEVEL == EXTERNAL —— 部分 ROM 用硬件层标记 UVC
     */
    fun findExternalCameraId(): String? {
        val cm = cameraManager ?: return null   // 无相机服务：直接判定"无外置摄像头"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return try {
            val ids = cm.cameraIdList
            ids.firstOrNull { facingOf(it) == CameraCharacteristics.LENS_FACING_EXTERNAL }
                ?: ids.firstOrNull { id ->
                    try {
                        val c = cm.getCameraCharacteristics(id)
                        c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ==
                                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL
                    } catch (_: Exception) {
                        false
                    }
                }
        } catch (e: Exception) {
            UvcLog.w(TAG, "枚举摄像头失败: ${e.message}")
            null
        }
    }

    private fun facingOf(id: String): Int? = try {
        cameraManager?.getCameraCharacteristics(id)?.get(CameraCharacteristics.LENS_FACING)
    } catch (_: Exception) {
        null
    }

    private fun getCameraName(cameraId: String): String {
        val chars = cameraManager?.getCameraCharacteristics(cameraId) ?: return "USB 采集卡"
        return try {
            @Suppress("DEPRECATION")
            chars.get(CameraCharacteristics.INFO_VERSION) as? String ?: "USB 采集卡"
        } catch (_: Exception) {
            "USB 采集卡"
        }
    }

    /**
     * 构建预览分辨率候选列表（按优先级降序）。
     *
     * 关键：外置摄像头 HAL 报告的尺寸才是唯一可信的候选来源（部分 ROM 会报
     * 1440x480 这类非常规长宽比，这是真实可用尺寸，按 16:9 过滤反而会全灭）。
     */
    private fun buildSizeCandidates(cameraId: String): List<Size> {
        val cm = cameraManager
        val sizes = try {
            if (cm == null) {
                emptyList()
            } else {
                val chars = cm.getCameraCharacteristics(cameraId)
                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                map?.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: emptyList()
            }
        } catch (e: Exception) {
            UvcLog.w(TAG, "获取分辨率列表失败: ${e.message}")
            emptyList()
        }
        UvcLog.i(TAG, "camera2 报告的分辨率: ${sizes.joinToString { "${it.width}x${it.height}" }}")

        val capped = sizes.filter {
            it.width in 320..1920 && it.height in 240..1080 &&
                    it.width.toFloat() / it.height <= 4.0f
        }
        val preferred = listOf(
            Size(1920, 1080), Size(1280, 720), Size(960, 540),
            Size(800, 600), Size(720, 576), Size(720, 480), Size(640, 480)
        )
        val ordered = preferred.filter { p -> capped.any { it.width == p.width && it.height == p.height } } +
                capped.filter { c -> preferred.none { it.width == c.width && it.height == c.height } }
                    .sortedByDescending { it.width.toLong() * it.height }
        val result = ordered.ifEmpty { preferred }.take(10)
        UvcLog.i(TAG, "分辨率候选（降试顺序）: ${result.joinToString { "${it.width}x${it.height}" }}")
        return result
    }

    /** 枚举 USB 总线上的 UVC 视频设备（任一接口 class = 0x0E Video） */
    fun findUsbVideoDevice(): UsbDevice? = try {
        usbManager()?.deviceList?.values?.firstOrNull { device ->
            (0 until device.interfaceCount).any { i ->
                device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO
            }
        }
    } catch (e: Exception) {
        UvcLog.w(TAG, "枚举 USB 设备失败: ${e.message}")
        null
    }

    fun findUsbVideoDeviceName(): String? {
        val d = findUsbVideoDevice() ?: return null
        return "${d.manufacturerName ?: d.productName ?: "USB 视频设备"}".trim()
    }

    /** USB 服务（无 USB Host 的设备返回 null，必须可空，否则强转会崩） */
    private fun usbManager(): UsbManager? =
        context.getSystemService(Context.USB_SERVICE) as? UsbManager

    private fun usbHasPermission(dev: UsbDevice): Boolean =
        try {
            usbManager()?.hasPermission(dev) ?: true
        } catch (_: Exception) {
            true
        }

    /** 启动诊断快照：一次性输出 USB 设备/权限/camera2 外置通道实况 */
    private fun logUsbDiagnostics() {
        try {
            val dev = findUsbVideoDevice()
            val external = findExternalCameraId()
            val perm = dev?.let { usbHasPermission(it) }
            UvcLog.i(
                TAG, "启动诊断: USB视频设备=${dev?.productName ?: "无"} " +
                        "(VID=0x${(dev?.vendorId ?: 0).toString(16)} PID=0x${(dev?.productId ?: 0).toString(16)}) " +
                        "USB权限=${perm ?: "-"} camera2外置摄像头=${external ?: "无"} " +
                        "camera2曾失败=$camera2FailedOnce 直连硬失败=$directHardFailures"
            )
        } catch (_: Exception) {
        }
    }

    private fun releaseBackgroundThread() {
        backgroundThread?.let {
            try {
                it.quitSafely()
                it.join(1000)
            } catch (_: Exception) {
            }
        }
        backgroundThread = null
        backgroundHandler = null
    }
}
