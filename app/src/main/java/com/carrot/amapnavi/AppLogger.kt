package com.carrot.amapnavi

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

object AppLogger {

    private val ioScope = CoroutineScope(Dispatchers.IO)

    /**
     * 写入日志到文件
     * @param context 上下文
     * @param tag 日志来源（比如 "UDP"、"UI"）
     * @param message 日志内容
     */
    fun log(context: Context, tag: String, message: String) {
        ioScope.launch {
            try {
                // 日志目录：外部存储 /sdcard/Android/data/<包名>/files/logs/
                val logDir = File(context.getExternalFilesDir(null), "logs")
                if (!logDir.exists()) logDir.mkdirs()

                // 以日期命名文件
                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val logFile = File(logDir, "$dateStr.log")

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
     * 获取当前日志文件路径
     */
    fun getTodayLogFile(context: Context): File {
        val logDir = File(context.getExternalFilesDir(null), "logs")
        if (!logDir.exists()) logDir.mkdirs()
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        return File(logDir, "$dateStr.log")
    }

    /**
     * 分享日志文件（通过系统分享菜单）
     */
    fun shareTodayLog(context: Context) {
        val logFile = getTodayLogFile(context)
        if (!logFile.exists()) {
            android.widget.Toast.makeText(context, "今天还没有日志文件", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                logFile
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "分享日志文件"))
    }
}
