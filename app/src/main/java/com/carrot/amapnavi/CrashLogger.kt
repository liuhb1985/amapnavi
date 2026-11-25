package com.carrot.amapnavi

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* =========================================================
 * 崩溃捕获器：把未捕获异常的堆栈写进日志文件
 *
 * 为什么需要它：车上排查「闪退」最难的是拿不到 logcat（车机不一定连电脑、
 * 也没有 adb）。本类在进程内挂上默认异常处理，崩溃瞬间把完整堆栈同步写盘，
 * 用户用文件管理器就能把日志发出来——不需要 adb、不需要开发者选项。
 *
 * 落盘位置（都写，哪个能成就用哪个）：
 *   ① /sdcard/AmapNavi/crash/crash_<时间戳>.log   （文件管理器可见，首选）
 *   ② /data/data/<包名>/files/crash/…            （无存储权限时的兜底）
 * 同时照常 Log.e("CRASH")，有 adb 时也能直接看到。
 * ========================================================= */
object CrashLogger {

    private const val TAG = "CRASH"
    private const val SUB_DIR = "crash"
    private const val BOOT_LOG = "boot.log"

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 崩溃路径上不能再抛异常，全部吞掉
            runCatching { dump(appContext, thread, throwable) }
            runCatching {
                previous?.uncaughtException(thread, throwable)
                    ?: run {
                        Process.killProcess(Process.myPid())
                        System.exit(10)
                    }
            }
        }
        installed = true
    }

    private fun dump(context: Context, thread: Thread, throwable: Throwable) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "crash_$stamp.log"

        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            pw.println("========== AmapNavi 崩溃报告 ==========")
            pw.println("时间: ${Date()}")
            pw.println("设备: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.PRODUCT})")
            pw.println("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            pw.println("ABI : ${Build.SUPPORTED_ABIS.joinToString()}")
            runCatching {
                val pi = context.packageManager.getPackageInfo(context.packageName, 0)
                pw.println("版本: ${pi.versionName} (${pi.versionCode})")
            }
            pw.println("线程: ${thread.name} (id=${thread.id})")
            pw.println()
            pw.println("---- 异常 ----")
            pw.println(throwable.toString())
            pw.println()
            pw.println("---- 堆栈 ----")
            throwable.printStackTrace(pw)
            var cause = throwable.cause
            var depth = 0
            while (cause != null && depth < 5) {
                depth++
                pw.println()
                pw.println("---- Caused by (${depth}) ----")
                pw.println(cause.toString())
                cause.printStackTrace(pw)
                cause = cause.cause
            }
        }
        val text = sw.toString()

        // 同时打 logcat，方便有 adb 时直接看
        Log.e(TAG, "崩溃已捕获:\n$text")

        // ① 独立的 crash/ 子目录（有公共存储权限时，文件管理器直接可见）
        runCatching {
            write(File(LogStorage.dir(context, SUB_DIR), fileName), text)
        }
        // ② 关键：同时写一份进【主日志目录】。
        //    原因：没授予存储权限时 LogStorage 会回退到
        //    /sdcard/Android/data/<包名>/files/logs/，文件管理器往往看不到，
        //    但界面上的「分享主日志」按钮走的是同一个目录 —— 这样一定能导出来。
        runCatching {
            write(File(LogStorage.dir(context, AppLogger.SUB_DIR), fileName), text)
        }
        // ③ 私有目录兜底（连上面都失败时）
        runCatching {
            val dir = File(context.filesDir, SUB_DIR).apply { mkdirs() }
            write(File(dir, fileName), text)
        }
    }

    /** 同步写盘：崩溃时不能用协程，必须直接写完 */
    private fun write(file: File, text: String) {
        FileWriter(file, true).use { w ->
            w.append(text)
            w.append("\n\n")
            w.flush()
        }
        Log.i(TAG, "崩溃日志已写入: ${file.absolutePath}")
    }

    /* =========================================================
     * 启动面包屑：同步写一行到主日志目录下的 boot.log
     *
     * 用途：崩溃报告万一没写出来（权限/私有目录/进程被杀），
     * 只要看 boot.log 最后一个标记，就知道崩溃发生在哪两步之间。
     * 写在这里的文件会出现在「分享主日志」的文件列表里。
     * 必须同步写——异步(coroutine)在进程被杀时来不及落盘。
     * ========================================================= */
    fun mark(context: Context, msg: String) {
        runCatching {
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            val f = File(LogStorage.dir(context, AppLogger.SUB_DIR), BOOT_LOG)
            FileWriter(f, true).use { w ->
                w.append("[$ts] $msg\n")
                w.flush()
            }
            Log.i(TAG, "BOOT: $msg")
        }
    }

    /** 崩溃目录下所有日志（供分享） */
    fun listCrashLogs(context: Context): List<File> {
        val out = ArrayList<File>()
        runCatching {
            out.addAll(LogStorage.listLogs(LogStorage.dir(context, SUB_DIR), "crash_"))
        }
        runCatching {
            val dir = File(context.filesDir, SUB_DIR)
            out.addAll(dir.listFiles { f -> f.isFile && f.name.startsWith("crash_") } ?: emptyArray())
        }
        return out.sortedByDescending { it.name }
    }
}
