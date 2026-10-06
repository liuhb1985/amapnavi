package com.carrot.amapnavi.uvc

import android.content.Context
import android.util.Log
import com.carrot.amapnavi.AppLogger

/* =========================================================
 * 采集卡模块日志：统一出口 = logcat + AppLogger 主日志文件
 *
 * 采集卡的排障几乎完全依赖日志（ROM 差异大、现场复现成本高），
 * 因此本模块所有关键步骤都会落盘到 /sdcard/AmapNavi/app/ 下的主日志，
 * 直接用主界面已有的「分享日志」导出即可，不额外增加导出入口。
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

    /** 把 Throwable 展开成一行（含 message，避免只看类名无法定位） */
    fun describe(t: Throwable): String =
        "${t.javaClass.simpleName}: ${t.message ?: ""}"
}
