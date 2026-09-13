package com.carrot.amapnavi

import android.graphics.Color
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.children
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/* =========================================================
 * UI 显示 / 控件交互模块
 *   - WebView 界面初始化与按钮交互
 *   - 车道视图 / APP 控制区显示控制
 *   - 原始日志（RawLog）UI 更新
 *   - 车道级引导文本显示
 * 说明：本文件中的函数均为 MainActivity 的扩展函数。
 * ========================================================= */

//WEB浏览界面初始化
fun MainActivity.setupWebView() {
    val activity = this
    val scrollRawLog = findViewById<ScrollView>(R.id.scrollRawLog)

    // WebView 基础设置
    val webSettings = webView.settings
    webSettings.javaScriptEnabled = true
    webSettings.domStorageEnabled = true
    webSettings.useWideViewPort = true
    webSettings.loadWithOverviewMode = true
    webSettings.allowFileAccess = true

    webView.webChromeClient = WebChromeClient()
    webView.webViewClient = WebViewClient()

    // 点击“超车”按钮控制显示/隐藏，并调整高度
    btnAppOverTake.setOnClickListener {
        if (webView.visibility == View.GONE || webview_owner == 2 || webview_owner == 3) {
            setRawLogVisible(false)
            showOrHiddenAppCtrl(false)
            web_container.visibility = View.VISIBLE
            webView.visibility = View.VISIBLE

            // 如果需要重新加载网页
            webView.loadUrl("http://127.0.0.1:8088")

            //btnAppOverTake.text = "隐藏超车"
            //btnNavSetting.text = "导航设置"
            //btnImportSetting.text = "参数导入"
            webview_owner = 1

            if(app_speak) {
                //SoundPlayer.play(this, "engage")
            }

            hideLaneView()
        } else {
            webView.visibility = View.GONE
            showOrHiddenAppCtrl(true)
            //btnAppOverTake.text = "超车功能"
            webview_owner = 0

            if(app_speak) {
                //SoundPlayer.play(this, "disengage")
            }

            showLaneView()
        }
    }

    btnNavSetting.setOnClickListener {
        lifecycleScope.launch {
            if (webView.visibility == View.GONE || webview_owner == 1 || webview_owner == 3) {
                /*
                if (carrotManIp.isNullOrEmpty()) {
                    sendBroadcast()
                    delay(500)
                }
                 */

                // 如果需要重新加载网页
                if (!carrotManIp.isNullOrEmpty()) {
                    setRawLogVisible(false)
                    showOrHiddenAppCtrl(false)
                    web_container.visibility = View.VISIBLE
                    webView.visibility = View.VISIBLE
                    webView.loadUrl("http://$carrotManIp:8088/nav_params")

                    //btnAppOverTake.text = "超车功能"
                    //btnNavSetting.text = "隐藏设置"
                    //btnImportSetting.text = "参数导入"
                    webview_owner = 2

                    if (app_speak) {
                        //SoundPlayer.play(this@MainActivity, "engage")
                    }

                    hideLaneView()
                } else {
                    AlertDialog.Builder(activity)
                        .setTitle("提示")
                        .setMessage("请和OpenPilot连接同一个路由器或热点")
                        .setPositiveButton("确定", null)
                        .show()
                }

            } else {
                webView.visibility = View.GONE
                showOrHiddenAppCtrl(true)
                //btnNavSetting.text = "导航设置"
                webview_owner = 0

                if (app_speak) {
                    //SoundPlayer.play(this@MainActivity, "disengage")
                }

                showLaneView()
            }
        }
    }
}

fun MainActivity.showOrHiddenAppCtrl(bShow:Boolean){
    // 显示APP操作区域
    val container = findViewById<LinearLayout>(R.id.containerLinearLayout)
    for ((index, view) in container.children.withIndex()) {
        if (index < 4) {
            if(bShow) {
                view.visibility = View.VISIBLE
            }else {
                view.visibility = View.GONE
            }
        }
    }
}

fun MainActivity.setTopPaddingDp(view: View, dp: Int) {
    val px = (dp * view.resources.displayMetrics.density).toInt()
    view.setPadding(
        view.paddingLeft,
        px,
        view.paddingRight,
        view.paddingBottom
    )
}

fun MainActivity.hideLaneView() {
    imageViewLayoutShowEnable = false
    imageViewLayout.visibility = View.GONE
}

fun MainActivity.showLaneView() {
    imageViewLayoutShowEnable = true
}

// -----------------------------
// 原始日志（RawLog）UI
// -----------------------------
fun MainActivity.clearRawLog() {
    lifecycleScope.launch(Dispatchers.Main) {
        rawLog.text = ""
    }
}

fun MainActivity.setRawLogVisible(Visible: Boolean) {
    if((Visible && webView.visibility != View.VISIBLE) || (!Visible)) {
        lifecycleScope.launch(Dispatchers.Main) {
            scrollRawLog.visibility = if (Visible) View.VISIBLE else View.GONE
            if (Visible){
                web_container.visibility = View.VISIBLE
            }
        }
    }
}

fun MainActivity.updateRawLog(text: String, bShow: Boolean) {

    if(app_log) {
        AppLogger.log(this, "MainActivity", text)
    }

    if(scrollRawLog.visibility == View.VISIBLE && bShow == true) {
        lifecycleScope.launch(Dispatchers.Main) {
            rawLog.append(text + "\n\n")

            // 限制行数
            val lines = rawLog.lineCount
            if (lines > RAW_LOG_MAX_LINES) {
                val excess = lines - RAW_LOG_MAX_LINES
                val start = rawLog.layout.getLineStart(excess)
                rawLog.text = rawLog.text.substring(start)
            }

            scrollRawLog.post { scrollRawLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }
}

fun MainActivity.clearRawLogUI() {
    rawLog.text = ""
}

fun MainActivity.setRawLogUIVisible(Visible: Boolean) {
    if((Visible && webView.visibility != View.VISIBLE) || (!Visible)) {
        scrollRawLog.visibility = if (Visible) View.VISIBLE else View.GONE
        if (Visible){
            web_container.visibility = View.VISIBLE
        }
    }
}

fun MainActivity.updateRawLogUI(text: String, bShow: Boolean) {

    rawLog.append(text + "\n\n")
    // 限制行数
    val lines = rawLog.lineCount
    if (lines > RAW_LOG_MAX_LINES) {
        val excess = lines - RAW_LOG_MAX_LINES
        val start = rawLog.layout.getLineStart(excess)
        rawLog.text = rawLog.text.substring(start)
    }
    scrollRawLog.post { scrollRawLog.fullScroll(ScrollView.FOCUS_DOWN) }
}

fun MainActivity.updateRawLogNav(text: String) {
    if(app_log) {
        AppLogger.log(this, "MainActivity", text)
    }

    lifecycleScope.launch(Dispatchers.Main) {
        rawLogNav.text = text
        scrollRawLogNav.post { scrollRawLogNav.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}

// -----------------------------
// 超车诊断页 UI
//   页面铺满整个界面，显示期间按 ~200ms 定时刷新：
//     - 顶栏  ：最近一次决策 阶段 → 结论
//     - 雷达图：正前/左前/右前/左后/右后 各雷达的距离与速度，按危险程度变色
//     - 底部  ：完整决策详情文本(点击可复制)
//   数据来源：opStateManager(CP上报) + 最近一次决策结论
// -----------------------------
fun MainActivity.setOvertakeDebugPanelVisible(visible: Boolean) {
    overtakeDebugEnable = visible

    if (!visible) {
        overtakeDebugLastText = ""
        overtakeDebugRefreshJob?.cancel()
        overtakeDebugRefreshJob = null
    }

    lifecycleScope.launch(Dispatchers.Main) {
        layoutOvertakeDebug.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) {
            tvOvertakeDebug.text = ""
        }
    }

    if (visible) startOvertakeDebugRefresh()
}

// 面板显示期间常驻刷新协程(200ms一帧)
private fun MainActivity.startOvertakeDebugRefresh() {
    if (overtakeDebugRefreshJob?.isActive == true) return
    overtakeDebugRefreshJob = lifecycleScope.launch(Dispatchers.Main) {
        while (isActive) {
            renderOvertakeDebugPage()
            delay(200L)
        }
    }
}

// 渲染诊断页(可在任意线程调用，内部切主线程更新UI)
fun MainActivity.renderOvertakeDebugPage() {
    val stage = overtakeDebugStage.ifEmpty { "-" }
    val decision = overtakeDebugDecision.ifEmpty { "-" }
    val reason = overtakeDebugReason.ifEmpty { "-" }

    val head = "【超车诊断】$stage → $decision"
    val detail = buildOvertakeDebugText(stage, decision, reason)

    val front = radarFrontCard()
    val leftFront = radarLeftFrontCard()
    val rightFront = radarRightFrontCard()
    val leftRear = radarLeftRearCard()
    val rightRear = radarRightRearCard()
    val ego = "自车 " + (opStateManager.state.vEgo ?: 0) + " km/h"

    lifecycleScope.launch(Dispatchers.Main) {
        tvOvertakeDebugHead.text = head

        applyRadarCard(tvRadarFront, front)
        applyRadarCard(tvRadarLeftFront, leftFront)
        applyRadarCard(tvRadarRightFront, rightFront)
        applyRadarCard(tvRadarLeftRear, leftRear)
        applyRadarCard(tvRadarRightRear, rightRear)
        tvRadarEgo.text = ego

        if (detail != overtakeDebugLastText) {
            overtakeDebugLastText = detail
            tvOvertakeDebug.text = detail
        }
    }
}

// -----------------------------
// 方位雷达卡片
//   level: 0 无目标 / 1 安全 / 2 注意 / 3 危险
// -----------------------------
internal data class RadarCard(
    val title: String = "",
    val hasTarget: Boolean = false,
    val level: Int = 0,
    val lines: List<String> = emptyList()
)

// 距离格式：>=100m 取整，否则保留1位小数
private fun distM(m: Float?): String = when {
    m == null -> "-"
    m >= 100f -> "%.0fm".format(m)
    else -> "%.1fm".format(m)
}

// 相对速度格式(带符号)
private fun relKmh(v: Int?): String = if (v == null) "-" else "%+d".format(v)

// 侧向危险等级(按距离)
private fun sideLevel(m: Float?): Int = when {
    m == null -> 1
    m < 10f -> 3
    m < 25f -> 2
    else -> 1
}

// 单条雷达数据行：名称 + 距离 + 绝对速度 + 相对速度
private fun radarLine(tag: String, d: Float?, v: Int?, vr: Int?): String {
    val sb = StringBuilder(tag).append(' ').append(distM(d))
    if (v != null) sb.append("  ").append(v).append("km/h")
    if (vr != null) sb.append("  (相对 ").append(relKmh(vr)).append(')')
    return sb.toString()
}

// 正前方(毫米波前雷达)
private fun MainActivity.radarFrontCard(): RadarCard {
    val s = opStateManager.state
    if (!s.lead1) return RadarCard("正前方", false, 0, listOf("无目标"))

    val d = s.drel?.toFloat()
    val level = when {
        d == null -> 1
        d < 20f -> 3
        d < 40f -> 2
        else -> 1
    }
    return RadarCard(
        "正前方", true, level,
        listOf(
            radarLine("距离", d, s.vlead, null),
            "相对 " + relKmh(s.vrel) + "km/h"
        )
    )
}

// 左前方：毫米波(前角) + 激光(前角)
private fun MainActivity.radarLeftFrontCard(): RadarCard {
    val s = opStateManager.state
    val lines = mutableListOf<String>()
    var hasTarget = false
    var level = 0

    if (s.l_lead) {
        hasTarget = true
        level = maxOf(level, sideLevel(s.l_drel?.toFloat()))
        lines += radarLine("毫米波", s.l_drel?.toFloat(), s.l_vlead, s.l_vrel)
    }

    // 激光前角：drel 为毫米，>0 表示目标在前方
    val lfM = s.lf_drel?.takeIf { it > 0 }?.let { it / 1000f }
    if (lfM != null) {
        hasTarget = true
        level = maxOf(level, sideLevel(lfM))
        lines += radarLine("激光", lfM, null, s.lf_vrel)
    }

    if (lines.isEmpty()) lines += "无目标"
    return RadarCard("左前方", hasTarget, if (hasTarget) level else 0, lines)
}

// 右前方：毫米波(前角) + 激光(前角)
private fun MainActivity.radarRightFrontCard(): RadarCard {
    val s = opStateManager.state
    val lines = mutableListOf<String>()
    var hasTarget = false
    var level = 0

    if (s.r_lead) {
        hasTarget = true
        level = maxOf(level, sideLevel(s.r_drel?.toFloat()))
        lines += radarLine("毫米波", s.r_drel?.toFloat(), s.r_vlead, s.r_vrel)
    }

    val rfM = s.rf_drel?.takeIf { it > 0 }?.let { it / 1000f }
    if (rfM != null) {
        hasTarget = true
        level = maxOf(level, sideLevel(rfM))
        lines += radarLine("激光", rfM, null, s.rf_vrel)
    }

    if (lines.isEmpty()) lines += "无目标"
    return RadarCard("右前方", hasTarget, if (hasTarget) level else 0, lines)
}

// 左后方(仅激光后角，drel<0 表示在后方)
private fun MainActivity.radarLeftRearCard(): RadarCard {
    val s = opStateManager.state
    val d = s.lb_drel?.takeIf { it < 0 }?.let { -it / 1000f }

    if (d == null) {
        return if (s.lidar_lblind) {
            RadarCard("左后方", true, 3, listOf("盲区有车"))
        } else {
            RadarCard("左后方", false, 0, listOf("无目标"))
        }
    }

    val lines = mutableListOf(radarLine("激光", d, null, s.lb_vrel))
    s.lb_xrel?.let { lines += "横向 " + "%.1fm".format(kotlin.math.abs(it) / 1000f) }
    if (s.lidar_lblind) lines += "盲区 ●"
    return RadarCard("左后方", true, sideLevel(d), lines)
}

// 右后方(仅激光后角，drel<0 表示在后方)
private fun MainActivity.radarRightRearCard(): RadarCard {
    val s = opStateManager.state
    val d = s.rb_drel?.takeIf { it < 0 }?.let { -it / 1000f }

    if (d == null) {
        return if (s.lidar_rblind) {
            RadarCard("右后方", true, 3, listOf("盲区有车"))
        } else {
            RadarCard("右后方", false, 0, listOf("无目标"))
        }
    }

    val lines = mutableListOf(radarLine("激光", d, null, s.rb_vrel))
    s.rb_xrel?.let { lines += "横向 " + "%.1fm".format(kotlin.math.abs(it) / 1000f) }
    if (s.lidar_rblind) lines += "盲区 ●"
    return RadarCard("右后方", true, sideLevel(d), lines)
}

// 把卡片数据刷到界面上(含配色)
private fun applyRadarCard(tv: TextView, card: RadarCard) {
    val (bg, fg) = when {
        !card.hasTarget -> "#EEEEEE" to "#9E9E9E"        // 无目标：灰
        card.level >= 3 -> "#FFCDD2" to "#B71C1C"        // 危险：红
        card.level == 2 -> "#FFE0B2" to "#E65100"        // 注意：橙
        else -> "#C8E6C9" to "#1B5E20"                   // 安全：绿
    }
    tv.setBackgroundColor(Color.parseColor(bg))
    tv.setTextColor(Color.parseColor(fg))
    val body = if (card.lines.isEmpty()) "无目标" else card.lines.joinToString("\n")
    tv.text = card.title + (if (card.hasTarget) " ●" else " ○") + "\n" + body
}

fun MainActivity.updateDriveWayTextOnUI(text: String, show: Boolean) {
    lifecycleScope.launch(Dispatchers.Main) {
        driveWayTextView.text = if (text.isNotEmpty()) "导航: " + text else ""
        driveWayTextView.visibility = if (show && text.isNotEmpty()) View.VISIBLE else View.GONE
        val driveWaySpacer = findViewById<Space>(R.id.driveWaySpacer)
        driveWaySpacer.visibility = driveWayTextView.visibility
        if(driveWayTextView.visibility == View.GONE && vLaneTextView.visibility == View.GONE){
            vLaneTextViewLayout.visibility = View.GONE
        }else{
            vLaneTextViewLayout.visibility = View.VISIBLE
        }
    }
}
