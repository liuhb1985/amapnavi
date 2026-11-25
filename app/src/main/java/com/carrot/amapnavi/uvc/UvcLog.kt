package com.carrot.amapnavi.uvc

import android.content.Context
import android.util.Log
import com.carrot.amapnavi.AppLogger
import com.carrot.amapnavi.LogStorage
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* =========================================================
 * 采集卡模块日志：统一出口 = logcat + AppLogger 主日志文件
 *
 * 采集卡的排障几乎完全依赖日志（ROM 差异大、现场复现成本高），
 * 因此本模块所有关键步骤都会落盘到 /sdcard/AmapNavi/app/ 下的主日志，
 * 主界面「导出日志」可导出全部日志；采集卡卡片内另有「导出日志」一键导出异常摘要。
 *
 * 用法：UvcLog.init(applicationContext)（MainActivity.onCreate 中调用一次）
 * ========================================================= */
object UvcLog {

    private const val FILE_TAG = "UVC"

    @Volatile
    private var appContext: Context? = null

    /** 是否同时写日志文件（采集卡排障建议保持开启） */
    @Volatile
    var fileEnabled: Boolean = true

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun d(tag: String, msg: String) = write(Log.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = write(Log.INFO, tag, msg)
    fun w(tag: String, msg: String) = write(Log.WARN, tag, msg)
    fun e(tag: String, msg: String, tr: Throwable? = null) =
        write(Log.ERROR, tag, if (tr != null) "$msg ($tr)" else msg)

    private fun write(priority: Int, tag: String, msg: String) {
        when (priority) {
            Log.DEBUG -> Log.d(tag, msg)
            Log.WARN -> Log.w(tag, msg)
            Log.ERROR -> Log.e(tag, msg)
            else -> Log.i(tag, msg)
        }
        if (!fileEnabled) return
        val ctx = appContext ?: return
        AppLogger.log(ctx, FILE_TAG, "$tag: $msg")
    }

    /**
     * 一键导出采集卡异常日志：
     * 从主日志里挑出采集卡/USB/错误相关行，连同当前诊断摘要写成一个独立文件，
     * 用户点一次按钮即可分享给技术支持，不用在长日志里翻找。
     *
     * @param header 当前诊断摘要（UvcCameraController.diagnose()）
     * @return 生成的日志文件；失败返回 null
     */
    fun exportErrorReport(context: Context, header: String): File? = try {
        val ctx = context.applicationContext
        val dir = LogStorage.dir(ctx, "export")
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val out = File(dir, "uvc_error_$stamp.log")

        val keywords = listOf(
            "UVC", "采集卡", "UvcCameraController", "UvcNativeStreamer",
            "UsbPermissionBroker", "MainActivityUvc", "UsbAttachActivity", "UsbBootReceiver",
            "USB权限", "USB 权限", "USB_PERMISSION", "权限", "授权", "拒绝", "秒拒", "chooser",
            "失败", "错误", "未暴露", "熔断", "不支持", "解码", "档位", "预览"
        )
        // 当前会话优先，避免异步落盘时漏掉最新日志；再补最近的历史会话
        val current = runCatching { AppLogger.getTodayLogFile(ctx) }.getOrNull()
            ?.takeIf { it.exists() }
        val sources = (listOfNotNull(current) + LogStorage.listLogs(
            LogStorage.dir(ctx, AppLogger.SUB_DIR), ""
        ).sortedByDescending { it.name }
            .filter { it.absolutePath != current?.absolutePath })
            .distinctBy { it.absolutePath }
            .take(8)

        FileWriter(out, false).use { w ->
            w.write("===== 采集卡异常日志导出 $stamp =====\n")
            w.write("===== 当前诊断 =====\n")
            w.write(header.ifBlank { "（无）" })
            w.write("\n\n===== 过滤日志（当前会话优先，共 ${sources.size} 个文件）=====\n")
            sources.forEach { f ->
                w.write("\n----- ${f.name} -----\n")
                runCatching {
                    f.readLines().forEach { line ->
                        if (keywords.any { line.contains(it, ignoreCase = true) }) {
                            w.write(line)
                            w.write("\n")
                        }
                    }
                }.onFailure { w.write("（读取失败: ${describe(it)}）\n") }
            }
        }
        out
    } catch (t: Throwable) {
        Log.e(FILE_TAG, "导出采集卡异常日志失败: ${describe(t)}")
        null
    }

    /** 把 Throwable 展开成一行（含 message，避免只看类名无法定位） */
    fun describe(t: Throwable): String =
        "${t.javaClass.simpleName}: ${t.message ?: ""}"
}
