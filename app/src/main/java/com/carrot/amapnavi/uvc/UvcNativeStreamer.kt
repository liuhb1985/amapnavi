package com.carrot.amapnavi.uvc

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.os.Handler
import android.os.Looper
import com.serenegiant.usb.Size
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera
import java.io.IOException

/**
 * Native UVC 取流通道（libuvc + libusb via JNI）。
 *
 * 与旧 Java 直连的区别：
 * - Java UsbRequest 在 AOSP 只支持 Bulk/Interrupt，无法打开 MS2109 的 ISO 端点；
 * - Native libusb 通过 UsbDeviceConnection 的 fd 直接访问 usbfs，支持等时传输；
 * - 本类只负责 native 取流，USB 权限、状态机、日志仍复用 App 现有链路。
 */
class UvcNativeStreamer(private val context: Context) {

    var onStateChanged: (UvcState) -> Unit = {}

    private val mainHandler = Handler(Looper.getMainLooper())

    private var monitor: USBMonitor? = null
    private var camera: UVCCamera? = null
    private var controlBlock: USBMonitor.UsbControlBlock? = null
    private var surfaceRef: SurfaceTexture? = null
    private var device: UsbDevice? = null
    private var running = false

    /** 每次 start/stop 递增，旧实例的异步回调全部作废 */
    private var generation = 0

    /** listener 创建时绑定的代际与 monitor；回调时先校验，防止旧实例回调误伤新实例 */
    private var listenerGeneration = 0
    private var listenerMonitor: USBMonitor? = null

    private var streamInfo = "未协商"
    private var lastError: String? = null
    private var lastErrorCode: Int? = null

    private fun isStaleCallback(): Boolean =
        !running || listenerGeneration != generation || monitor == null || monitor !== listenerMonitor

    private val listener = object : USBMonitor.OnDeviceConnectListener {
        override fun onAttach(dev: UsbDevice?) {
            if (isStaleCallback()) return
            UvcLog.i(TAG, "Native USB 设备插入: ${dev?.deviceName}")
        }

        override fun onDettach(dev: UsbDevice?) {
            if (isStaleCallback() || dev == null) return
            if (device?.deviceId == dev.deviceId) {
                val gen = generation
                lastError = "设备已拔出"
                post(UvcState.NoDevice(null))
                mainHandler.post { if (gen == generation && running) stop() }
            }
        }

        override fun onConnect(
            dev: UsbDevice?,
            block: USBMonitor.UsbControlBlock?,
            openNew: Boolean
        ) {
            if (isStaleCallback()) return
            // 权限由 UsbPermissionBroker 统一申请；这里只记录，不重复 openDevice
            UvcLog.i(TAG, "Native USBMonitor onConnect: ${dev?.deviceName} openNew=$openNew")
        }

        override fun onDisconnect(dev: UsbDevice?, block: USBMonitor.UsbControlBlock?) {
            if (isStaleCallback()) return
            UvcLog.i(TAG, "Native USBMonitor onDisconnect: ${dev?.deviceName}")
        }

        override fun onCancel(dev: UsbDevice?) {
            if (isStaleCallback()) return
            UvcLog.w(TAG, "Native USBMonitor onCancel: ${dev?.deviceName}")
        }
    }

    @Synchronized
    fun start(surfaceTexture: SurfaceTexture) {
        stop()
        generation++
        listenerGeneration = generation
        running = true
        surfaceRef = surfaceTexture
        streamInfo = "未协商"
        lastError = null
        lastErrorCode = null

        try {
            val m = USBMonitor(context, listener)
            monitor = m
            listenerMonitor = m
            if (!m.isRegistered) m.register()

            val dev = m.deviceList.firstOrNull { isUvc(it) }
            if (dev == null) {
                post(UvcState.NoDevice(null))
                return
            }
            device = dev
            UvcLog.i(TAG, "Native UVC 发现设备: ${dev.deviceName} VID=0x${dev.vendorId.toString(16)} PID=0x${dev.productId.toString(16)}")

            if (!m.hasPermission(dev)) {
                requestPermission(dev)
                return
            }
            openCamera(dev)
        } catch (t: Throwable) {
            fail("Native UVC 初始化失败", t)
        }
    }

    @Synchronized
    fun stop() {
        generation++
        running = false
        listenerMonitor = null
        val cam = camera
        camera = null
        if (cam != null) {
            // destroy() 内部已 stopPreview + close（含 UsbControlBlock.close），不要重复 close
            runCatching { cam.destroy() }
        }
        controlBlock = null

        monitor?.let { m ->
            runCatching { if (m.isRegistered) m.unregister() }
            runCatching { m.destroy() }
        }
        monitor = null
        surfaceRef = null
        device = null
    }

    fun diagnostics(): String =
        "Native UVC 通道 ｜ 档位: $streamInfo ｜ 帧统计: 关闭 ｜ 最后错误: ${lastError ?: "无"}"

    private fun isUvc(dev: UsbDevice): Boolean = try {
        (0 until dev.interfaceCount).any { dev.getInterface(it).interfaceClass == 14 }
    } catch (_: Exception) {
        false
    }

    private fun requestPermission(dev: UsbDevice) {
        if (autoRequestBanned()) {
            val msg = "该 ROM 曾未弹窗秒拒 USB 权限，已禁用自动申请；请点「获取权限」按钮"
            UvcLog.w(TAG, msg)
            post(UvcState.UsbPermissionBlocked(msg))
            return
        }
        post(UvcState.AwaitingUsbPermission)
        UvcLog.i(TAG, "Native UVC 请求 USB 权限…")
        val gen = generation
        val status = UsbPermissionBroker.request(
            context, dev, manual = false, reason = "native-auto"
        ) { granted, elapsed ->
            if (!running || gen != generation) return@request
            if (granted) {
                UvcLog.i(TAG, "Native UVC USB 权限已授予（${elapsed}ms）")
                setAutoRequestBanned(false)
                openCamera(dev)
            } else if (elapsed in 1..999) {
                UvcLog.e(TAG, "Native UVC USB 权限 ${elapsed}ms 秒拒，禁用自动申请")
                setAutoRequestBanned(true)
                post(UvcState.UsbPermissionBlocked("USB 权限未弹窗即被拒，请点「获取权限」按钮"))
            } else {
                post(UvcState.UsbPermissionDenied)
            }
        }
        if (status != UsbPermissionBroker.Status.ACCEPTED &&
            status != UsbPermissionBroker.Status.ALREADY_GRANTED
        ) {
            UvcLog.w(TAG, "Native UVC 自动授权未受理($status)，等待用户手动授权")
            post(UvcState.UsbPermissionBlocked("USB 授权请求未受理($status)，请点「获取权限」按钮"))
        }
    }

    private fun openCamera(dev: UsbDevice) {
        if (!running) return
        post(UvcState.Opening)
        try {
            val m = monitor ?: throw IOException("USBMonitor 未初始化")
            val block = m.openDevice(dev)
            controlBlock = block

            val cam = UVCCamera()
            camera = cam
            cam.open(block)

            val sizes = runCatching { cam.supportedSizeList }.getOrNull().orEmpty()
            UvcLog.i(TAG, "Native UVC 支持分辨率: ${sizes.joinToString { "${it.width}x${it.height}/fmt${it.frame_type}" }}")
            val preferredSizes = listOf(
                1920 to 1080,  // 用户指定：HDMI 采集卡固定 1080p
                1280 to 720,
                640 to 480
            )
            var picked: Size? = null
            for ((w, h) in preferredSizes) {
                // 同尺寸优先 MJPEG 帧，避免误选 YUY2 导致带宽飙升
                picked = sizes.firstOrNull {
                    it.width == w && it.height == h && it.frame_type == UVCCamera.FRAME_FORMAT_MJPEG
                } ?: sizes.firstOrNull { it.width == w && it.height == h }
                if (picked != null) break
            }
            val size = picked ?: sizes.firstOrNull()
                ?: throw IOException("设备未上报可用预览分辨率")

            val format = try {
                cam.setPreviewSize(
                    size.width, size.height,
                    UVCCamera.FRAME_FORMAT_MJPEG,
                    UVCCamera.DEFAULT_BANDWIDTH
                )
                "MJPEG"
            } catch (e: Exception) {
                UvcLog.w(TAG, "MJPEG 预览设置失败，回退 YUYV: ${UvcLog.describe(e)}")
                cam.setPreviewSize(
                    size.width, size.height,
                    UVCCamera.FRAME_FORMAT_YUYV,
                    UVCCamera.DEFAULT_BANDWIDTH
                )
                "YUYV"
            }

            val surface = surfaceRef ?: throw IOException("SurfaceTexture 已释放")
            runCatching { surface.setDefaultBufferSize(size.width, size.height) }
            cam.setPreviewTexture(surface)
            // 不开帧回调：SurfaceTexture 已在渲染，额外 YUV420SP 转换会浪费车机 CPU
            cam.startPreview()

            val fps = runCatching { size.currentFrameRate }.getOrDefault(0f)
            streamInfo = "Native $format ${size.width}x${size.height} ${String.format("%.0f", fps)}fps"
            UvcLog.i(TAG, "===== $streamInfo 开始预览 =====")
            post(UvcState.Streaming(dev.deviceName ?: "USB Camera"))
        } catch (t: Throwable) {
            fail("Native UVC 打开失败", t)
        }
    }

    private fun fail(prefix: String, t: Throwable) {
        val raw = UvcLog.describe(t)
        val code = Regex("result=(-?\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull()
        lastErrorCode = code
        val hint = when (code) {
            -99 -> "（libusb 通用错误 -99：native open 阶段失败，常见于 ROM USB HAL、接口占用或设备描述符兼容问题）"
            else -> ""
        }
        lastError = "$prefix: $raw$hint"
        UvcLog.e(TAG, lastError!!)
        post(UvcState.Error(lastError!!))
    }

    private fun post(state: UvcState) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onStateChanged(state)
        } else {
            mainHandler.post { onStateChanged(state) }
        }
    }

    private fun autoRequestBanned(): Boolean = try {
        context.getSharedPreferences("uvc_camera_prefs", Context.MODE_PRIVATE)
            .getBoolean("usb_auto_banned", false)
    } catch (_: Exception) {
        false
    }

    private fun setAutoRequestBanned(banned: Boolean) {
        runCatching {
            context.getSharedPreferences("uvc_camera_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("usb_auto_banned", banned).apply()
        }
    }

    private companion object {
        const val TAG = "UvcNativeStreamer"
    }
}
