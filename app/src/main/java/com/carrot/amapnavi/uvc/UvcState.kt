package com.carrot.amapnavi.uvc

/* =========================================================
 * UVC（HDMI 转 USB 采集卡）预览状态机
 *
 * 移植自 navipilot 的 ui/components/UvcCameraPreview.kt，
 * 去除了 Compose 依赖，改为普通 Kotlin 状态类 + 界面提示文案。
 * ========================================================= */

sealed class UvcState {

    /** 空闲/未初始化 */
    object Idle : UvcState()

    /** 无 Android CAMERA 运行时权限 */
    object PermissionDenied : UvcState()

    /** 已发起 USB 设备访问权限请求，等待用户在系统弹窗中允许 */
    object AwaitingUsbPermission : UvcState()

    /** USB 设备访问权限被用户拒绝 */
    object UsbPermissionDenied : UvcState()

    /** 未检测到 HDMI 转 USB 采集卡；usbVideoDeviceName 非空表示 USB 层有视频设备 */
    data class NoDevice(val usbVideoDeviceName: String?) : UvcState()

    /** 正在打开设备 */
    object Opening : UvcState()

    /** 视频流已就绪 */
    data class Streaming(val cameraName: String) : UvcState()

    /** USB 权限被 ROM 持久拦截 / 系统层打不开设备，需用户手工处理 */
    data class UsbPermissionBlocked(val message: String) : UvcState()

    /** 出错 */
    data class Error(val message: String) : UvcState()
}

/** 是否处于「需要用户介入」的状态（界面据此显示按钮） */
val UvcState.needsUserAction: Boolean
    get() = this is UvcState.PermissionDenied ||
            this is UvcState.UsbPermissionDenied ||
            this is UvcState.UsbPermissionBlocked ||
            this is UvcState.Error

/** 状态 → 界面提示文案 */
fun UvcState.toHint(): String = when (this) {
    is UvcState.Idle -> "未启动"
    is UvcState.PermissionDenied -> "缺少相机权限，请在系统设置中授予本应用「相机」权限"
    is UvcState.AwaitingUsbPermission -> "请在系统弹窗中允许访问 USB 采集卡…"
    is UvcState.UsbPermissionDenied -> "USB 采集卡权限被拒绝"
    is UvcState.NoDevice -> if (usbVideoDeviceName.isNullOrBlank()) {
        "未检测到 USB 视频设备，系统也未暴露外置摄像头（请确认采集卡已插入并开启 OTG）"
    } else {
        "USB 已识别：$usbVideoDeviceName，但系统未暴露外置摄像头且直连未建立"
    }
    is UvcState.Opening -> "正在打开采集卡…"
    is UvcState.Streaming -> "已连接：$cameraName"
    is UvcState.UsbPermissionBlocked -> message
    is UvcState.Error -> message
}

/** 陈旧授权 / 系统层打不开设备时的人工指引文案 */
internal fun staleAuthGuideText(): String =
    "采集卡已识别，但底层无法打开设备。\n\n" +
            "常见原因：OTG 供电已关闭 / 系统 USB 授权记录失效 / USB Host 驱动异常。\n\n" +
            "请依次尝试：\n" +
            "① 设置 → 更多设置 → OTG 连接（开启后再观察）\n" +
            "② 完全拔出采集卡 10 秒后重新插入\n" +
            "③ 重启手机后重新插入，在系统「打开方式」中选择本应用\n\n" +
            "修复后点下方「重新连接」即可恢复。"

/** 弹窗未出现即被拒的文案 */
internal fun silentDeniedGuideText(): String =
    "USB 权限申请被系统拦截（弹窗未出现即被拒，多次重试会加深拦截）。\n\n" +
            "推荐做法（最可靠）：\n" +
            "① 拔下采集卡 → 重启手机\n" +
            "② 重新插入采集卡 → 系统弹出「打开方式」→ 选本应用并勾选「始终」，系统会自动授权\n\n" +
            "或点下方「获取权限」手动申请（每次至少间隔 5 秒）。\n" +
            "若系统不弹窗，先点「权限设置」开启相机权限。"
