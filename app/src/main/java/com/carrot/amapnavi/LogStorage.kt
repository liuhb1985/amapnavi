package com.carrot.amapnavi

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/* =========================================================
 * 日志存放目录 + 导出（主日志 / 超车日志共用）
 *
 * 目录优先级：
 *   1) /sdcard/AmapNavi/                       ← 手机存储根目录，文件管理器直接可见，
 *                                                用户可以自己挑文件发送
 *   2) /sdcard/Android/data/<包名>/files/logs  ← 没给存储权限时回退(文件管理器可能看不到)
 *
 * 目录划分：根目录下按类型分子文件夹，两种日志互不混杂——
 *   主日志   AmapNavi/app/       (见 AppLogger.SUB_DIR)
 *   超车日志 AmapNavi/overtake/  (见 OvertakeLogger.SUB_DIR)
 *
 * targetSdk 28 + requestLegacyExternalStorage，可直接用 File API 读写公共目录，
 * 但需要 WRITE_EXTERNAL_STORAGE 运行时权限；这里用"实际试写"探测，
 * 探测失败就回退，并在界面提示真实路径，避免"日志看不见又找不到原因"。
 * ========================================================= */
object LogStorage {

    /** 手机存储根目录下的日志文件夹名 */
    const val PUBLIC_DIR_NAME = "AmapNavi"

    @Volatile
    private var resolved: File? = null

    /** 权限授予后 / 切换记录时重新探测目录 */
    fun reset() {
        resolved = null
    }

    /** 日志根目录（自动创建）：主日志放这里 */
    fun dir(context: Context): File = rootDir(context)

    /**
     * 子目录（自动创建）：超车日志放在 overtake/ 子文件夹里，跟主日志分开，
     * 翻目录就知道哪份是哪种日志。sub 传空则等同于根目录。
     */
    fun dir(context: Context, sub: String?): File {
        val base = rootDir(context)
        if (sub.isNullOrEmpty()) return base
        val d = File(base, sub)
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun rootDir(context: Context): File {
        resolved?.let { return it }
        val pub = File(Environment.getExternalStorageDirectory(), PUBLIC_DIR_NAME)
        val d = if (writable(pub)) pub
        else File(context.getExternalFilesDir(null) ?: context.filesDir, "logs")
        if (!d.exists()) d.mkdirs()
        resolved = d
        return d
    }

    /** 是否正写在公共目录（也就是文件管理器里看得到） */
    fun isPublic(context: Context): Boolean {
        val base = Environment.getExternalStorageDirectory().absolutePath +
            File.separator + PUBLIC_DIR_NAME
        return dir(context).absolutePath == base
    }

    /** 目录展示路径，例如 /storage/emulated/0/AmapNavi（sub 非空则为子目录） */
    fun displayDir(context: Context, sub: String? = null): String = dir(context, sub).absolutePath

    /** 某个目录里符合前缀的日志文件（按文件名倒序，新的在前） */
    fun listLogs(directory: File, prefix: String): List<File> =
        directory.listFiles { f: File -> f.isFile && f.name.startsWith(prefix) && f.name.endsWith(".log") }
            ?.sortedByDescending { it.name } ?: emptyList()

    /** 需要申请的存储权限（写入手机存储公共目录用） */
    val STORAGE_PERMISSIONS = arrayOf(
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.READ_EXTERNAL_STORAGE
    )

    /** 是否已获得公共目录写权限 */
    fun hasWritePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 导出日志：多个文件时弹出多选列表让用户自己挑，选中后走系统分享；
     * 只有一个文件时直接分享。
     *
     * @param where 标题里显示的存放位置（主日志 AmapNavi、超车日志 AmapNavi/overtake）
     */
    fun shareLogs(context: Context, title: String, files: List<File>, where: String = PUBLIC_DIR_NAME) {
        if (files.isEmpty()) {
            Toast.makeText(context, "还没有日志文件，请先点「记录」", Toast.LENGTH_SHORT).show()
            return
        }
        if (files.size == 1) {
            sendFiles(context, listOf(files[0]))
            return
        }

        val names = files.map { it.name }.toTypedArray()
        val checked = BooleanArray(files.size) { true }
        AlertDialog.Builder(context)
            .setTitle("$title（存于 $where/ ，可多选）")
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("发送") { _, _ ->
                val picked = files.filterIndexed { i, _ -> checked[i] }
                if (picked.isEmpty()) {
                    Toast.makeText(context, "未选择日志", Toast.LENGTH_SHORT).show()
                } else {
                    sendFiles(context, picked)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun sendFiles(context: Context, files: List<File>) {
        val uris: List<Uri> = files.map {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uris[0])
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/plain"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "分享日志"))
    }

    /** 试写探测：能不能在这个目录里真正建出文件 */
    private fun writable(dir: File): Boolean = try {
        if (!dir.exists() && !dir.mkdirs()) {
            false
        } else {
            val probe = File(dir, ".probe")
            val ok = probe.createNewFile() || probe.exists()
            probe.delete()
            ok
        }
    } catch (e: Exception) {
        false
    }
}
