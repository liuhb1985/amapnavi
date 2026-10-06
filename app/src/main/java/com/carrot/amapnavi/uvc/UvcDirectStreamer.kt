package com.carrot.amapnavi.uvc

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.Handler
import android.os.SystemClock
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/* =========================================================
 * 用户态 UVC 直连取流（不依赖系统相机 HAL，也不需要任何三方库）
 *
 * 移植自 navipilot 的 ui/components/UvcDirectStreamer.kt（已适配 Kotlin 1.4 / API 28）。
 *
 * 适用场景：ROM 未把 UVC 采集卡暴露为 camera2 外置摄像头
 * （USB 层能枚举到 "MACROSILICON USB Video"，但 cameraIdList 中无 LENS_FACING_EXTERNAL）。
 * 这在廉价车机/盒子上是最常见的情况，也是本 App 唯一可靠的取流路径。
 *
 * 流程（与 libuvc / Linux uvcvideo 用户态等价）：
 * 1. 解析原始 USB 描述符：VideoStreaming 接口各 alt setting、IN 端点、MJPEG 帧描述符
 * 2. UVC Probe/Commit 控制协商（SET_CUR/GET_CUR，类请求 0x21/0xA1）
 * 3. SET_INTERFACE 切换到有带宽的 alt setting，claim 接口
 * 4. UsbRequest 异步队列接收 iso 数据（或 bulkTransfer 轮询），按 UVC Payload Header 重组 MJPEG 帧
 * 5. BitmapFactory 解码并绘制到 SurfaceTexture
 * ========================================================= */
class UvcDirectStreamer(private val context: Context) {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.carrot.amapnavi.UVC_USB_PERMISSION"

        // UVC VideoStreaming 类请求
        private const val UVC_SET_CUR = 0x01
        private const val UVC_GET_CUR = 0x81
        private const val UVC_GET_LEN = 0x85
        private const val UVC_GET_DEF = 0x87
        private const val CS_PROBE = 0x01
        private const val CS_COMMIT = 0x02

        private const val REQ_DIR_OUT_CLASS_IF = 0x21
        private const val REQ_DIR_IN_CLASS_IF = 0xA1

        // 标准 SET_INTERFACE 请求
        private const val REQ_SET_INTERFACE = 0x0B

        // 描述符类型
        private const val DT_INTERFACE = 0x04
        private const val DT_ENDPOINT = 0x05
        private const val DT_CS_INTERFACE = 0x24

        // VideoStreaming 类子描述符
        private const val VS_FORMAT_MJPEG = 0x06
        private const val VS_FRAME_MJPEG = 0x07
        private const val VS_FORMAT_UNCOMPRESSED = 0x04
        private const val VS_FRAME_UNCOMPRESSED = 0x05

        // UVC Payload Header 标志位
        private const val HDR_FID = 0x01
        private const val HDR_EOF = 0x02
    }

    var onStateChanged: ((UvcState) -> Unit)? = null

    /**
     * USB 服务。必须可空：getSystemService(USB_SERVICE) 在【无 USB Host】的设备上
     * 返回 null，非空强转会抛 ClassCastException（本对象在取流开始时创建，
     * 一崩就是 App 崩溃）。无 USB 服务时所有操作降级为"未检测到设备"。
     */
    private val usbManager: UsbManager? =
        context.getSystemService(Context.USB_SERVICE) as? UsbManager
    private val mainHandler = Handler(context.mainLooper)

    private var connection: UsbDeviceConnection? = null
    private var streamThread: Thread? = null
    private var permissionReceiver: BroadcastReceiver? = null
    private var streamNotified = false

    @Volatile
    private var running = false

    // ==================== 对外接口 ====================

    fun start(surfaceTexture: SurfaceTexture) {
        stop()
        streamNotified = false
        val device = findUvcDevice()
        if (device == null) {
            UvcLog.i(TAG, "直连模式：USB 总线上未发现视频类设备")
            post(UvcState.NoDevice(null))
            return
        }
        UvcLog.i(
            TAG, "直连模式：发现 ${device.manufacturerName ?: "?"} ${device.productName ?: "?"} " +
                    "(VID=${hex(device.vendorId)} PID=${hex(device.productId)})"
        )
        if (!hasPermission(device)) {
            requestUsbPermission(device, surfaceTexture)
            return
        }
        openAndStream(device, surfaceTexture)
    }

    fun stop() {
        running = false
        runCatching { permissionReceiver?.let { context.unregisterReceiver(it) } }
        permissionReceiver = null
        // 先关连接让 requestWait/controlTransfer 尽快失败返回，再同步等待流线程退出。
        // 关键：此前 stop() 立即返回，旧实例的流线程仍在后台协商/收流，新实例并发启动后
        // 互相 SET_INTERFACE/close 对方连接，引发 "device is closed in native_control_request"。
        connection?.let { runCatching { it.close() } }
        connection = null
        streamThread?.let { t ->
            runCatching { t.interrupt() }
            runCatching { t.join(1500) }
            if (t.isAlive) UvcLog.w(TAG, "旧流线程 1.5s 内未退出（可能仍阻塞在系统调用中）")
        }
        streamThread = null
    }

    // ==================== 设备查找与权限 ====================

    fun findUvcDevice(): UsbDevice? = try {
        usbManager?.deviceList?.values?.firstOrNull { dev ->
            (0 until dev.interfaceCount).any { i ->
                dev.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO
            }
        }
    } catch (t: Throwable) {
        UvcLog.w(TAG, "枚举 USB 设备失败: ${UvcLog.describe(t)}")
        null
    }

    private fun hasPermission(dev: UsbDevice): Boolean = try {
        usbManager?.hasPermission(dev) ?: false
    } catch (_: Exception) {
        false
    }

    private fun requestUsbPermission(device: UsbDevice, surfaceTexture: SurfaceTexture) {
        post(UvcState.AwaitingUsbPermission)
        UvcLog.i(TAG, "请求 USB 设备访问权限（请在车机弹窗中允许）…")

        // 统一走 UsbPermissionBroker：单飞（不顶掉在途弹窗）+ 自动退避（防加深 ROM 拒绝记录）
        val status = UsbPermissionBroker.request(context, device, manual = false, reason = "direct-auto") { granted, elapsed ->
            when {
                granted -> {
                    UvcLog.i(TAG, "USB 权限已授予（耗时 ${elapsed}ms），开始直连取流")
                    openAndStream(device, surfaceTexture)
                }
                elapsed in 1..999 -> {
                    UvcLog.e(TAG, "USB 权限请求 ${elapsed}ms 内即被拒（未弹窗秒拒）")
                    post(UvcState.UsbPermissionBlocked(silentDeniedGuideText()))
                }
                else -> {
                    UvcLog.w(TAG, "USB 权限被用户拒绝（弹窗，耗时 ${elapsed}ms）")
                    post(UvcState.UsbPermissionDenied)
                }
            }
        }
        if (status == UsbPermissionBroker.Status.IN_FLIGHT ||
            status == UsbPermissionBroker.Status.COOLDOWN
        ) {
            UvcLog.d(TAG, "直连权限请求受理状态=$status，等待其后结果即可")
        }
    }

    // ==================== 打开与启动 ====================

    private fun openAndStream(device: UsbDevice, surfaceTexture: SurfaceTexture) {
        post(UvcState.Opening)
        val usb = usbManager
        val conn = usb?.openDevice(device)
        if (conn == null) {
            UvcLog.e(TAG, "openDevice 失败（设备被占用或系统限制）")
            post(UvcState.Error("打开 USB 设备失败"))
            return
        }
        connection = conn
        running = true
        streamThread = Thread {
            try {
                streamLoop(device, conn, surfaceTexture)
            } catch (t: Throwable) {
                UvcLog.e(TAG, "直连流异常终止", t)
                if (running) post(UvcState.Error("USB 直连失败: ${t.message ?: t.javaClass.simpleName}"))
            } finally {
                running = false
                runCatching { conn.close() }
                if (connection === conn) connection = null
            }
        }.apply {
            name = "UvcDirectStream"
            isDaemon = true
            start()
        }
    }

    // ==================== 主流程 ====================

    private fun streamLoop(device: UsbDevice, conn: UsbDeviceConnection, st: SurfaceTexture) {
        UvcLog.i(TAG, "===== 开始 UVC 直连协商 =====")

        val raw = conn.rawDescriptors ?: throw IOException("读取 USB 描述符失败")
        UvcLog.i(TAG, "rawDescriptors: ${raw.size} 字节")

        val parsed = parseDescriptors(raw)
        UvcLog.i(TAG, "解析结果: 接口 alt 设置 ${parsed.ifaces.size} 个, 格式 ${parsed.formats.size} 个")
        parsed.ifaces.forEach {
            UvcLog.d(TAG, "  接口 if=${it.ifNum} alt=${it.alt} class=${it.cls} sub=${it.subCls} 端点=${it.eps.map { e -> hex(e.addr) }}")
        }
        parsed.formats.forEach { f ->
            UvcLog.d(
                TAG,
                "  格式 idx=${f.idx} subtype=${if (f.subtype == VS_FORMAT_MJPEG) "MJPEG" else "YUV"} " +
                        "帧=${f.frames.joinToString { fr -> "${fr.w}x${fr.h}" }}"
            )
        }

        // 1) claim 视频控制接口（部分固件要求先占用 VC 才能发流控请求）
        (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .filter { it.interfaceClass == UsbConstants.USB_CLASS_VIDEO && it.interfaceSubclass == 1 }
            .forEach {
                val ok = runCatching { conn.claimInterface(it, true) }.getOrDefault(false)
                UvcLog.d(TAG, "claim VC 接口 #${it.id} -> $ok")
            }

        // 2) 选定 VideoStreaming 接口、alt setting 与端点
        val vsAlts = parsed.ifaces.filter { it.cls == UsbConstants.USB_CLASS_VIDEO && it.subCls == 2 }
        if (vsAlts.isEmpty()) throw IOException("未找到 VideoStreaming 接口（设备可能不是标准 UVC）")
        val vsIfNum = vsAlts.first().ifNum
        val withEps = vsAlts.filter { ifa -> ifa.eps.any { it.addr and 0x80 != 0 } }
        if (withEps.isEmpty()) throw IOException("VideoStreaming 接口无数据端点")
        UvcLog.i(
            TAG,
            "VS 接口 if=$vsIfNum 可用带宽档: " +
                    withEps.sortedByDescending { it.alt }
                        .joinToString { "alt${it.alt}(maxPacket=${it.eps.first { e -> e.addr and 0x80 != 0 }.maxPacket})" }
        )

        // 3) Probe/Commit 协商器（wIndex 编码整个生命周期只锁定一次）
        val neg = Negotiator(conn, vsIfNum)
        val probeLen = neg.discoverProbeLength()
        val format = parsed.formats.firstOrNull { it.subtype == VS_FORMAT_MJPEG }
            ?: parsed.formats.firstOrNull()
            ?: throw IOException("描述符中未找到可用的视频格式（MJPEG/未压缩均无）")
        // 帧候选（降档顺序）：1080p → 720p → VGA → 描述符中最大帧
        val frameCandidates = (
                listOf(1920 to 1080, 1280 to 720, 640 to 480)
                    .mapNotNull { (w, h) -> format.frames.firstOrNull { it.w == w && it.h == h } }
                        + listOfNotNull(format.frames.maxByOrNull { it.w.toLong() * it.h })
                ).distinct().take(3)
        if (frameCandidates.isEmpty()) throw IOException("格式 ${format.idx} 无帧描述符")
        UvcLog.i(
            TAG,
            "帧候选(降档顺序): ${frameCandidates.joinToString { "${it.w}x${it.h}" }}, probeLen=$probeLen"
        )

        // 4) 定位 Android 侧 UsbInterface 并 claim（接口只 claim 一次，后续切 alt 无需重复 claim）
        val usbIf: UsbInterface = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { ifa ->
                ifa.id == vsIfNum &&
                        (0 until ifa.endpointCount).any { k -> ifa.getEndpoint(k).address and 0x80 != 0 }
            }
            ?: throw IOException("Android USB API 未暴露所选接口（厂商限制）")
        if (!conn.claimInterface(usbIf, true)) throw IOException("claimInterface(VideoStreaming) 失败")

        // 5) (分辨率 × alt 带宽) 多档降试。
        //    实测华为 Android 12：最高带宽档的 UsbRequest.initialize 可能失败，
        //    降到小带宽档后往往恢复可用。
        val altIfaceDescs = withEps.sortedByDescending { it.alt }
        val attempts = frameCandidates.flatMap { f ->
            altIfaceDescs.map { a -> Triple(f, a.alt, a.eps.first { it.addr and 0x80 != 0 }) }
        }

        var lastInitError: IOException? = null
        for ((frame, alt, epd) in attempts) {
            val usbEp = (0 until usbIf.endpointCount)
                .map { usbIf.getEndpoint(it) }
                .firstOrNull { it.address == epd.addr }
            if (usbEp == null) {
                UvcLog.w(TAG, "alt=$alt 端点 ${hex(epd.addr)} 未被 Android API 暴露，跳过该档")
                continue
            }
            val interval = frame.intervals.minOrNull() ?: 333333L
            UvcLog.i(TAG, "尝试档位 ${frame.w}x${frame.h} @ alt=$alt 端点=${hex(epd.addr)} maxPacket=${epd.maxPacket}")
            val probe = try {
                neg.negotiate(probeLen, format.idx, frame.idx, interval)
            } catch (e: IOException) {
                UvcLog.w(TAG, "该档协商失败: ${e.message}，降档重试")
                continue
            }
            // dwMaxPayloadTransferSize 偏移随 UVC 版本不同：
            // 34 字节布局在偏移 26；26 字节布局（MS2109 实测）在末尾 4 字节即偏移 22
            val payloadOffset = if (probeLen >= 34) 26 else probeLen - 4
            var maxPayload = readLe32(probe, payloadOffset).toInt()
            if (maxPayload !in 64..262144) {
                UvcLog.w(TAG, "dwMaxPayloadTransferSize 读数异常: $maxPayload，改用端点 maxPacket=${epd.maxPacket}")
                maxPayload = epd.maxPacket
            }
            val r = conn.controlTransfer(0x01, REQ_SET_INTERFACE, alt, vsIfNum, null, 0, 1000)
            if (r < 0) {
                UvcLog.w(TAG, "SET_INTERFACE alt=$alt 失败 ($r)，降档重试")
                continue
            }
            UvcLog.i(TAG, "SET_INTERFACE alt=$alt -> $r, maxPayload=$maxPayload")

            st.setDefaultBufferSize(frame.w, frame.h)
            val surface = Surface(st)
            UvcLog.i(TAG, "===== 档位就绪，开始接收视频流 (${frame.w}x${frame.h} @alt$alt) =====")
            try {
                if (epd.type == UsbConstants.USB_ENDPOINT_XFER_ISOC)
                    streamIso(conn, usbEp, maxPayload, surface, device)
                else
                    streamBulk(conn, usbEp, maxPayload, surface, device)
                return // 流正常结束（stop 触发）
            } catch (e: IOException) {
                UvcLog.e(TAG, "档位 ${frame.w}x${frame.h}@alt$alt 失败: ${e.message}，降档重试")
                if (lastInitError == null) lastInitError = e
            } finally {
                runCatching { surface.release() }
                // 恢复 alt 0 释放带宽
                runCatching { conn.controlTransfer(0x01, REQ_SET_INTERFACE, 0, vsIfNum, null, 0, 500) }
            }
        }
        throw lastInitError ?: IOException("所有 (分辨率×带宽) 档位均失败")
    }

    // ==================== 两种传输模式 ====================

    private fun streamIso(
        conn: UsbDeviceConnection,
        ep: UsbEndpoint,
        maxPayload: Int,
        surface: Surface,
        device: UsbDevice
    ) {
        // 等时传输缓冲须为 1024 的倍数（内核按 wMaxPacketSize 拆分 iso 包）
        val bufSize = (maxOf(maxPayload, 3072) + 1023) / 1024 * 1024
        val reqCount = 10
        val assembler = FrameAssembler(surface, device)

        val requests = ArrayList<UsbRequest>(reqCount)
        // UsbRequest.getBuffer() 在部分 SDK 中不可见，用 Map 自行维护请求->缓冲映射
        val bufByReq = HashMap<UsbRequest, ByteBuffer>(reqCount)
        repeat(reqCount) {
            val req = UsbRequest()
            if (!req.initialize(conn, ep)) {
                throw IOException(
                    "USB 异步等时传输初始化失败" +
                            "（此 ROM 的 Java UsbRequest 可能不支持 isochronous 端点，部分厂商 ROM 有此限制）"
                )
            }
            val buf = ByteBuffer.allocateDirect(bufSize)
            if (!req.queue(buf, bufSize)) throw IOException("UsbRequest.queue 失败")
            requests.add(req)
            bufByReq[req] = buf
        }
        UvcLog.i(TAG, "已入队 $reqCount 个异步请求, 每个 $bufSize 字节")

        var stall = 0
        while (running) {
            val req = try {
                conn.requestWait(1000) // 带超时，避免连接关闭后永久阻塞
            } catch (e: Exception) {
                if (running) UvcLog.w(TAG, "requestWait 异常: ${UvcLog.describe(e)}")
                null
            }
            if (req == null) {
                stall++
                if (stall >= 5) {
                    if (running) UvcLog.w(TAG, "requestWait 连续 $stall 次无响应，流终止")
                    break
                }
                continue
            }
            if (!running) break
            stall = 0
            val buf = bufByReq[req] ?: continue
            val len = buf.position()
            if (len > 0) {
                buf.rewind()
                val bytes = ByteArray(len)
                buf.get(bytes, 0, len)
                assembler.process(bytes, len)
            }
            buf.clear()
            if (running && !req.queue(buf, bufSize)) {
                UvcLog.w(TAG, "重新 queue 失败，流终止")
                break
            }
        }
        requests.forEach { runCatching { it.cancel() } }
    }

    private fun streamBulk(
        conn: UsbDeviceConnection,
        ep: UsbEndpoint,
        maxPayload: Int,
        surface: Surface,
        device: UsbDevice
    ) {
        val bufSize = maxOf(maxPayload, 16384)
        val buf = ByteArray(bufSize)
        val assembler = FrameAssembler(surface, device)
        UvcLog.i(TAG, "bulk 模式, 每次 $bufSize 字节")
        var idle = 0
        while (running) {
            val n = conn.bulkTransfer(ep, buf, bufSize, 1000)
            if (n > 0) {
                idle = 0
                assembler.process(buf, n)
            } else if (n < 0) {
                idle++
                if (idle > 10) {
                    UvcLog.w(TAG, "bulk 传输持续失败，流终止")
                    break
                }
            }
        }
    }

    // ==================== 帧重组与渲染 ====================

    private inner class FrameAssembler(
        private val surface: Surface,
        private val device: UsbDevice
    ) {
        private val frameBuf = ByteArrayOutputStream(512 * 1024)
        private var fid = -1
        private var reuseBmp: Bitmap? = null
        private var lastRender = 0L
        private var frames = 0L
        private var bytesAcc = 0L
        private var statStart = SystemClock.elapsedRealtime()

        fun process(bytes: ByteArray, len: Int) {
            if (len < 2) return
            val hLen = bytes[0].toInt() and 0xFF
            val flags = bytes[1].toInt() and 0xFF
            if (hLen < 2 || hLen > len) return // 无效包头，丢弃

            val newFid = flags and HDR_FID
            if (newFid != fid) {
                fid = newFid
                frameBuf.reset()
            }
            if (len > hLen) frameBuf.write(bytes, hLen, len - hLen)

            if (flags and HDR_EOF != 0) {
                val size = frameBuf.size()
                if (size > 0) {
                    frames++
                    bytesAcc += size
                    val jpg = frameBuf.toByteArray()
                    frameBuf.reset()
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastRender >= 66) { // 上限约 15fps，避免 Java 解码过载
                        lastRender = now
                        val bmp = decodeJpeg(jpg)
                        if (bmp != null) render(bmp)
                    }
                    if (now - statStart >= 5000) {
                        val secs = ((now - statStart) / 1000).coerceAtLeast(1)
                        val kbps = bytesAcc * 8 / 1000 / secs
                        UvcLog.i(TAG, "流统计: $frames 帧, ${bytesAcc / 1024} KB, ~$kbps kbps")
                        frames = 0; bytesAcc = 0; statStart = now
                    }
                }
            }
        }

        private fun decodeJpeg(jpg: ByteArray): Bitmap? {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inMutable = true
                val reused = reuseBmp
                if (reused != null && reused.config == Bitmap.Config.RGB_565) {
                    inBitmap = reused
                }
            }
            val bmp = try {
                BitmapFactory.decodeByteArray(jpg, 0, jpg.size, opts)
            } catch (e: IllegalArgumentException) {
                // 尺寸变化导致 inBitmap 不可用，降级为无复用解码
                UvcLog.w(TAG, "inBitmap 复用失败（${e.message}），重新分配")
                BitmapFactory.decodeByteArray(
                    jpg, 0, jpg.size,
                    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                )
            } catch (e: Exception) {
                UvcLog.w(TAG, "MJPEG 解码失败: ${e.message}")
                null
            }
            if (bmp != null) reuseBmp = bmp
            return bmp
        }

        private fun render(bmp: Bitmap) {
            var canvas: Canvas? = null
            try {
                canvas = surface.lockCanvas(null)
                canvas.drawColor(0xFF000000.toInt())
                canvas.drawBitmap(bmp, 0f, 0f, null)
            } catch (t: Throwable) {
                UvcLog.w(TAG, "渲染失败: ${UvcLog.describe(t)}")
            } finally {
                if (canvas != null) runCatching { surface.unlockCanvasAndPost(canvas) }
            }
            if (!streamNotified) {
                streamNotified = true
                val name = listOfNotNull(device.manufacturerName, device.productName)
                    .joinToString(" ").ifBlank { "USB 采集卡" }
                UvcLog.i(TAG, "首帧已渲染，直连预览就绪: $name")
                post(UvcState.Streaming(name))
            }
        }
    }

    // ==================== Probe/Commit 协商 ====================

    private inner class Negotiator(
        private val conn: UsbDeviceConnection,
        private val ifNum: Int
    ) {

        /** 部分 ROM/固件对 wIndex 编码不同，两种都试，锁定成功的那种 */
        private var wIndexBase = -1

        private fun ctrl(reqType: Int, request: Int, cs: Int, data: ByteArray?, len: Int): Int {
            val wIndexCandidates = if (wIndexBase >= 0) listOf(wIndexBase) else listOf(ifNum, ifNum shl 8)
            for (wIndex in wIndexCandidates) {
                val r = conn.controlTransfer(reqType, request, cs shl 8, wIndex, data, len, 1000)
                if (r >= 0 && (data == null || r == len)) {
                    if (wIndexBase < 0) {
                        wIndexBase = wIndex
                        UvcLog.d(TAG, "锁定 wIndex=$wIndex (ifNum=$ifNum)")
                    }
                    return r
                }
            }
            return -1
        }

        fun discoverProbeLength(): Int {
            // 优先 GET_LEN（UVC 标准方式），失败则依次尝试 34(UVC1.1)/26(UVC1.0)
            val lenBuf = ByteArray(2)
            val r = ctrl(REQ_DIR_IN_CLASS_IF, UVC_GET_LEN, CS_PROBE, lenBuf, 2)
            if (r == 2) {
                val len = ((lenBuf[1].toInt() and 0xFF) shl 8) or (lenBuf[0].toInt() and 0xFF)
                if (len == 26 || len == 34) {
                    UvcLog.i(TAG, "GET_LEN probe = $len 字节")
                    return len
                }
            }
            for (len in intArrayOf(34, 26)) {
                val buf = ByteArray(len)
                if (ctrl(REQ_DIR_IN_CLASS_IF, UVC_GET_DEF, CS_PROBE, buf, len) == len ||
                    ctrl(REQ_DIR_IN_CLASS_IF, UVC_GET_CUR, CS_PROBE, buf, len) == len
                ) {
                    UvcLog.i(TAG, "试探 probe 长度 = $len 字节")
                    return len
                }
            }
            throw IOException("Probe 控制请求无响应（wIndex 两种编码均失败）")
        }

        fun negotiate(probeLen: Int, formatIdx: Int, frameIdx: Int, interval: Long): ByteArray {
            var probe = ByteArray(probeLen)
            if (ctrl(REQ_DIR_IN_CLASS_IF, UVC_GET_DEF, CS_PROBE, probe, probeLen) != probeLen &&
                ctrl(REQ_DIR_IN_CLASS_IF, UVC_GET_CUR, CS_PROBE, probe, probeLen) != probeLen
            ) throw IOException("GET probe 失败")

            // 写入期望的格式/帧/帧率（bmHint=1 表示将应用 dwFrameInterval）
            probe[0] = 1
            probe[1] = 0
            probe[2] = formatIdx.toByte()
            probe[3] = frameIdx.toByte()
            writeLe32(probe, 4, interval)
            if (ctrl(REQ_DIR_OUT_CLASS_IF, UVC_SET_CUR, CS_PROBE, probe, probeLen) != probeLen) {
                UvcLog.w(TAG, "SET probe 失败，回退设备默认参数")
            }

            probe = ByteArray(probeLen)
            if (ctrl(REQ_DIR_IN_CLASS_IF, UVC_GET_CUR, CS_PROBE, probe, probeLen) != probeLen)
                throw IOException("GET probe(回读) 失败")

            if (ctrl(REQ_DIR_OUT_CLASS_IF, UVC_SET_CUR, CS_COMMIT, probe, probeLen) != probeLen)
                throw IOException("SET commit 失败")

            val commit = ByteArray(probeLen)
            if (ctrl(REQ_DIR_IN_CLASS_IF, UVC_GET_CUR, CS_COMMIT, commit, probeLen) != probeLen)
                throw IOException("GET commit 失败")

            return commit
        }
    }

    // ==================== 描述符解析 ====================

    private data class EpDesc(val addr: Int, val type: Int, val maxPacket: Int)
    private data class IfDesc(
        val ifNum: Int, val alt: Int, val cls: Int, val subCls: Int, val proto: Int, val eps: List<EpDesc>
    )
    private data class FrameDesc(val idx: Int, val w: Int, val h: Int, val intervals: List<Long>)
    private data class FormatDesc(val idx: Int, val subtype: Int, val frames: List<FrameDesc>)
    private class Parsed(val ifaces: List<IfDesc>, val formats: List<FormatDesc>)

    private fun parseDescriptors(d: ByteArray): Parsed {
        val ifaces = ArrayList<IfDesc>()
        val formats = ArrayList<FormatDesc>()
        val frameLists = ArrayList<MutableList<FrameDesc>>()

        var i = 0
        var curIfNum = -1
        var curAlt = 0
        var curCls = -1
        var curSub = -1
        var curProto = -1
        val curEps = ArrayList<EpDesc>()

        fun flushIf() {
            if (curIfNum >= 0) {
                val list: List<EpDesc> = if (curEps.isNotEmpty()) ArrayList(curEps) else emptyList()
                ifaces.add(IfDesc(curIfNum, curAlt, curCls, curSub, curProto, list))
            }
            curEps.clear()
        }

        while (i + 1 < d.size) {
            val len = d[i].toInt() and 0xFF
            if (len == 0) break
            val type = d[i + 1].toInt() and 0xFF
            if (i + len > d.size) break

            when (type) {
                DT_INTERFACE -> {
                    flushIf()
                    curIfNum = d[i + 2].toInt() and 0xFF
                    curAlt = d[i + 3].toInt() and 0xFF
                    curCls = d[i + 5].toInt() and 0xFF
                    curSub = d[i + 6].toInt() and 0xFF
                    curProto = d[i + 7].toInt() and 0xFF
                }

                DT_ENDPOINT -> {
                    if (curIfNum >= 0 && len >= 7) {
                        val addr = d[i + 2].toInt() and 0xFF
                        val attr = d[i + 3].toInt() and 0xFF
                        val maxPacket = readLe16(d, i + 4)
                        curEps.add(EpDesc(addr, attr and 0x03, maxPacket))
                    }
                }

                DT_CS_INTERFACE -> {
                    // 仅关心 VideoStreaming(ifSubClass==2) 接口内的类子描述符
                    if (curIfNum >= 0 && curSub == 2 && len >= 3) {
                        val subtype = d[i + 2].toInt() and 0xFF
                        when (subtype) {
                            VS_FORMAT_MJPEG, VS_FORMAT_UNCOMPRESSED -> {
                                if (len >= 5) {
                                    formats.add(FormatDesc(d[i + 3].toInt() and 0xFF, subtype, emptyList()))
                                    frameLists.add(ArrayList())
                                }
                            }

                            VS_FRAME_MJPEG, VS_FRAME_UNCOMPRESSED -> {
                                if (len >= 26 && formats.isNotEmpty()) {
                                    val idx = d[i + 3].toInt() and 0xFF
                                    val w = readLe16(d, i + 5)
                                    val h = readLe16(d, i + 7)
                                    val defaultInterval = readLe32(d, i + 21)
                                    val intervalType = d[i + 25].toInt() and 0xFF
                                    val intervals = ArrayList<Long>()
                                    if (intervalType == 0) {
                                        if (len >= 38) {
                                            intervals.add(readLe32(d, i + 26))
                                            if (defaultInterval > 0) intervals.add(defaultInterval)
                                        }
                                    } else {
                                        for (k in 0 until intervalType) {
                                            val off = i + 26 + 4 * k
                                            if (off + 4 <= i + len) intervals.add(readLe32(d, off))
                                        }
                                    }
                                    if (intervals.isEmpty() && defaultInterval > 0) intervals.add(defaultInterval)
                                    frameLists[frameLists.size - 1].add(FrameDesc(idx, w, h, intervals))
                                }
                            }
                        }
                    }
                }
            }
            i += len
        }
        flushIf()

        val merged = formats.mapIndexed { index, f ->
            FormatDesc(f.idx, f.subtype, frameLists.getOrNull(index) ?: emptyList())
        }
        return Parsed(ifaces, merged)
    }

    // ==================== 工具 ====================

    private fun post(state: UvcState) {
        mainHandler.post { onStateChanged?.invoke(state) }
    }

    private fun readLe16(b: ByteArray, off: Int): Int =
        ((b[off + 1].toInt() and 0xFF) shl 8) or (b[off].toInt() and 0xFF)

    private fun readLe32(b: ByteArray, off: Int): Long =
        ((b[off + 3].toLong() and 0xFF) shl 24) or ((b[off + 2].toLong() and 0xFF) shl 16) or
                ((b[off + 1].toLong() and 0xFF) shl 8) or (b[off].toLong() and 0xFF)

    private fun writeLe32(b: ByteArray, off: Int, v: Long) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
        b[off + 2] = ((v shr 16) and 0xFF).toByte()
        b[off + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun hex(v: Int): String = "0x%04x".format(v)
}

private const val TAG = "UvcDirectStreamer"
