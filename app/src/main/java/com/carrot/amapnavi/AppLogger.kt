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
 * 目录：/sdcard/AmapNavi/app/yyyy-MM-dd_NNNN.log  ← 单独的子文件夹，跟超车日志分开
 *       文件名末尾 4 位是"当天第几次启动 App"的编号：**每次启动新建一份**，
 *       这样每次上车测试各占一个文件，不用在一天的长日志里翻找。
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

    // 旧版主日志文件名：yyyy-MM-dd.log（仅用于把历史文件从根目录搬进 app/ 子目录）
    private val MAIN_RE = Regex("^\\d{4}-\\d{2}-\\d{2}\\.log$")

    // 主日志文件名：yyyy-MM-dd_NNNN.log（NNNN = 当天第几次启动 App，0001 起）
    //   每次启动新建一份，方便区分每次上车测试的数据
    private val FILE_RE = Regex("^(\\d{4}-\\d{2}-\\d{2})_(\\d+)\\.log$")
    private const val SEQ_WIDTH = 4

    /** 本次启动(进程内)固定使用的日志文件：启动时算一次编号，之后所有写入都进这一份 */
    @Volatile
    private var sessionFile: File? = null

    @Volatile
    private var sessionDate: String = ""

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
     * 获取当前日志文件：yyyy-MM-dd_NNNN.log
     *   NNNN = 当天第几次启动 App（启动时确定一次，进程内固定）。
     *   每次上车测试各占一个文件，避免所有数据混在一天的长日志里；跨天自动新起一份。
     */
    fun getTodayLogFile(context: Context): File {
        val dateStr = today()
        sessionFile?.let { if (sessionDate == dateStr) return it }
        synchronized(this) {
            sessionFile?.let { if (sessionDate == dateStr) return it }
            val dir = LogStorage.dir(context, SUB_DIR)
            val seq = nextSeq(dir, dateStr)
            val f = File(dir, fileName(dateStr, seq))
            sessionFile = f
            sessionDate = dateStr
            return f
        }
    }

    /** 当天已用最大编号 + 1（本次启动新建一份） */
    private fun nextSeq(dir: File, date: String): Int {
        adoptPlainFiles(dir, date)
        var max = 0
        dir.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            val m = FILE_RE.matchEntire(f.name) ?: return@forEach
            if (m.groupValues[1] != date) return@forEach
            val n = m.groupValues[2].toIntOrNull() ?: return@forEach
            if (n > max) max = n
        }
        return max + 1
    }

    /** 把旧格式 yyyy-MM-dd.log 收编成 yyyy-MM-dd_NNNN.log，避免两种命名混在列表里 */
    private fun adoptPlainFiles(dir: File, date: String) {
        try {
            val legacy = File(dir, "$date.log")
            if (!legacy.isFile) return
            var seq = 1
            while (File(dir, fileName(date, seq)).exists()) seq++
            val dest = File(dir, fileName(date, seq))
            if (legacy.renameTo(dest)) {
                android.util.Log.i(TAG, "旧主日志已更名：${legacy.name} → ${dest.name}")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun fileName(date: String, seq: Int): String =
        "${date}_" + String.format(Locale.US, "%0${SEQ_WIDTH}d", seq) + ".log"

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

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
