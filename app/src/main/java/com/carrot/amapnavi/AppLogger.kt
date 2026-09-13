package com.carrot.amapnavi

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

/* =========================================================
 * 主日志（由 app_log 开关控制，内容为 App 各模块的运行记录）
 *
 * 目录：/sdcard/AmapNavi/app/yyyy-MM-dd.log   ← 单独的子文件夹，跟超车日志分开
 *       文件管理器打开 AmapNavi/ 只会看到 app/ 和 overtake/ 两个文件夹，
 *       要哪一份日志就进哪个文件夹，不会混在一起。
 *       （没有存储权限时回退 Android/data/<包名>/files/logs/app/，见 LogStorage）
 * ========================================================= */
object AppLogger {

    private const val TAG = "APPLOG"

    /** 主日志子目录名（位于 /sdcard/AmapNavi/ 下），与超车日志分开存放 */
    const val SUB_DIR = "app"

    private val ioScope = CoroutineScope(Dispatchers.IO)

    /** 已经搬过历史主日志的目标目录（目录变了要再搬一次；只记路径，避免每条日志都扫目录） */
    @Volatile
    private var migratedDir: String? = null

    // 主日志文件名：yyyy-MM-dd.log（超车日志是 yyyy-MM-dd_NNNN.log，见 OvertakeLogger）
    private val MAIN_RE = Regex("^\\d{4}-\\d{2}-\\d{2}\\.log$")

    /**
     * 写入日志到文件
     * 目录由 LogStorage 决定：手机存储 /sdcard/AmapNavi/app/（文件管理器可见），
     * 没有存储权限时回退 /sdcard/Android/data/<包名>/files/logs/app/
     *
     * @param context 上下文
     * @param tag 日志来源（比如 "UDP"、"UI"）
     * @param message 日志内容
     */
    fun log(context: Context, tag: String, message: String) {
        ioScope.launch {
            try {
                migrateLegacyFiles(context)
                val logFile = getTodayLogFile(context)

                // 每条日志加上时间戳
                val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
                val logLine = "[$timeStr][$tag] $message\n"

                FileWriter(logFile, true).use { writer ->
                    writer.append(logLine)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 获取当前日志文件（按日期命名）
     */
    fun getTodayLogFile(context: Context): File {
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        return File(LogStorage.dir(context, SUB_DIR), "$dateStr.log")
    }

    /** 主日志目录展示路径（提示用户去文件管理器哪里找） */
    fun logDirPath(context: Context): String = LogStorage.displayDir(context, SUB_DIR)

    /**
     * 导出主日志：目录里可能有多个日期的日志，弹多选框让用户自己挑，
     * 选中后走系统分享（超车日志由 OvertakeLogger 单独导出）。
     */
    fun shareLogs(context: Context) {
        migrateLegacyFiles(context)      // 保证根目录的历史主日志也出现在列表里
        val files = LogStorage.listLogs(LogStorage.dir(context, SUB_DIR), "")
        LogStorage.shareLogs(context, "分享主日志", files, "${LogStorage.PUBLIC_DIR_NAME}/$SUB_DIR")
    }

    /**
     * 把早期版本留在日志根目录的主日志搬进 app/ 子目录，让两种日志彻底分开：
     *   2026-09-12.log → app/2026-09-12.log
     * 幂等：同一个目标目录只扫一次（超车日志的搬迁由 OvertakeLogger 负责）。
     */
    private fun migrateLegacyFiles(context: Context) {
        val target = LogStorage.dir(context, SUB_DIR)
        val key = target.absolutePath
        if (migratedDir == key) return
        synchronized(this) {
            if (migratedDir == key) return
            migratedDir = key
            try {
                val root = LogStorage.dir(context)
                if (root.absolutePath == target.absolutePath) return
                val olds = root.listFiles()?.filter { it.isFile && MAIN_RE.matches(it.name) } ?: return
                for (f in olds) {
                    var dest = File(target, f.name)
                    if (dest.exists()) dest = File(target, f.name.removeSuffix(".log") + "_old.log")
                    if (dest.exists()) continue
                    if (f.renameTo(dest)) android.util.Log.i(TAG, "历史主日志已归档：${f.name} → ${dest.name}")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
