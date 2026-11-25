package com.carrot.amapnavi.probe

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.carrot.amapnavi.AppLogger
import com.carrot.amapnavi.LogStorage
import com.carrot.amapnavi.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* =========================================================
 * 导航源探针页
 *
 * 目的：为「接入百度车机版」这类新地图做前置调研——
 * 第三方地图的广播 action 与字段是私有协议、没有公开文档，
 * 必须先在本机抓到真实样本，才能写出可用的字段映射。
 *
 * 两种抓取手段：
 *  ① 广播：手填 action（可多个），收到即全量 dump extras
 *  ② 通知栏：不需要知道 action，几乎所有导航 App 都会往通知栏写转向/距离
 *     （需先在系统「通知使用权」里勾选本 App）
 * ========================================================= */
class NavProbeActivity : Activity() {

    private lateinit var tvPackages: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollProbe: ScrollView
    private lateinit var etActions: EditText

    private val handler = Handler(Looper.getMainLooper())
    private var broadcastReceiver: BroadcastReceiver? = null
    private var listening = false
    private var refreshTask: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nav_probe)

        tvPackages = findViewById(R.id.tvProbePackages)
        tvLog = findViewById(R.id.tvProbeLog)
        scrollProbe = findViewById(R.id.scrollProbe)
        etActions = findViewById(R.id.etProbeActions)

        findViewById<Button>(R.id.btnProbeBroadcast).setOnClickListener { toggleBroadcast() }
        findViewById<Button>(R.id.btnProbeNotify).setOnClickListener { openNotifySettings() }
        findViewById<Button>(R.id.btnProbeExport).setOnClickListener { export() }
        findViewById<Button>(R.id.btnProbeClear).setOnClickListener {
            NavProbeLog.clear()
            refresh()
            Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnProbeClose).setOnClickListener { finish() }

        scanPackages()
        startRefresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBroadcast()
        refreshTask?.let { handler.removeCallbacks(it) }
        refreshTask = null
    }

    // ==================== 本机地图包扫描 ====================

    private fun scanPackages() {
        val sb = StringBuilder()
        try {
            val pm = packageManager
            val pkgs = pm.getInstalledPackages(0)
            val hits = pkgs.filter { p ->
                NavProbeService.isCandidateMap(p.packageName)
            }.sortedBy { it.packageName }
            if (hits.isEmpty()) {
                sb.append("未发现候选地图包")
            } else {
                for (p in hits) {
                    val label = runCatching {
                        pm.getApplicationLabel(pm.getApplicationInfo(p.packageName, 0)).toString()
                    }.getOrDefault(p.packageName)
                    sb.append("• ").append(label)
                        .append('\n').append("  ").append(p.packageName)
                        .append("  v").append(p.versionName).append('\n')
                }
            }
        } catch (t: Throwable) {
            sb.append("扫描失败: $t")
        }
        tvPackages.text = sb.toString()

        // 扫描结果本身就是重要样本：确认目标地图包名与版本
        NavProbeLog.add("SCAN", "packages", sb.toString().replace('\n', ' '))
    }

    // ==================== 广播抓取 ====================

    private fun toggleBroadcast() {
        if (listening) {
            stopBroadcast()
            return
        }
        val raw = etActions.text?.toString()?.trim() ?: ""
        val actions = raw.split(',', '，', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (actions.isEmpty()) {
            Toast.makeText(
                this,
                "请先填要监听的广播 action（不知道就先用通知栏抓取）",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val filter = IntentFilter()
        actions.forEach { filter.addAction(it) }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val a = intent?.action ?: return
                NavProbeLog.add("BC", a, NavProbeLog.dumpExtras(intent.extras))
            }
        }
        broadcastReceiver = receiver
        try {
            registerReceiver(receiver, filter)
            listening = true
            findViewById<Button>(R.id.btnProbeBroadcast).text = "停止抓广播"
            actions.forEach { NavProbeLog.add("LISTEN", it, "开始监听") }
            Toast.makeText(this, "已监听 ${actions.size} 个 action", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            broadcastReceiver = null
            Toast.makeText(this, "注册失败: $t", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopBroadcast() {
        broadcastReceiver?.let { runCatching { unregisterReceiver(it) } }
        broadcastReceiver = null
        listening = false
        runCatching { findViewById<Button>(R.id.btnProbeBroadcast).text = "开始抓广播" }
        NavProbeLog.add("LISTEN", "-", "停止监听")
    }

    // ==================== 通知栏授权 ====================

    private fun openNotifySettings() {
        val enabled = isNotifyListenerEnabled()
        if (enabled) {
            Toast.makeText(this, "通知使用权已开启，直接开始导航即可抓取", Toast.LENGTH_LONG).show()
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            Toast.makeText(this, "请在列表里勾选 AmapNavi", Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            Toast.makeText(this, "无法打开通知使用权设置: $t", Toast.LENGTH_LONG).show()
        }
    }

    private fun isNotifyListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        )
        if (!TextUtils.isEmpty(flat)) {
            val names = flat.split(":").toTypedArray()
            for (n in names) {
                if (n.contains(packageName)) return true
            }
        }
        return false
    }

    // ==================== 导出 ====================

    private fun export() {
        val lines = NavProbeLog.snapshot()
        if (lines.isEmpty()) {
            Toast.makeText(this, "还没有抓到任何内容", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val dir = LogStorage.dir(this, SUB_DIR)
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "probe_$stamp.log")
            val sb = StringBuilder()
            sb.append("=== AmapNavi 导航源探针 ===\n")
            sb.append("导出时间: ${Date()}\n")
            sb.append("设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
            sb.append("系统: Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n\n")
            lines.forEach { sb.append(it).append('\n') }
            file.writeText(sb.toString())

            LogStorage.shareLogs(this, "分享探针日志", listOf(file), "${LogStorage.PUBLIC_DIR_NAME}/$SUB_DIR")
            AppLogger.log(this, "UVC", "导出探针日志: ${file.name} (${lines.size} 行)")
        } catch (t: Throwable) {
            Toast.makeText(this, "导出失败: $t", Toast.LENGTH_LONG).show()
        }
    }

    // ==================== 实时刷新 ====================

    private fun startRefresh() {
        val task = object : Runnable {
            override fun run() {
                refresh()
                handler.postDelayed(this, 1000)
            }
        }
        refreshTask = task
        handler.postDelayed(task, 1000)
    }

    private fun refresh() {
        val lines = NavProbeLog.snapshot()
        val show = if (lines.size > 40) lines.takeLast(40) else lines
        val text = show.joinToString("\n")
        if (tvLog.text.toString() != text) {
            tvLog.text = text
            scrollProbe.post { scrollProbe.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    companion object {
        private const val SUB_DIR = "probe"
    }
}
