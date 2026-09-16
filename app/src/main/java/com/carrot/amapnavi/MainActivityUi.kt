package com.carrot.amapnavi

import android.graphics.Color
import android.os.Looper
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.children
import androidx.core.view.doOnLayout
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

            // 原为本机 Python 的 8088 超车设置页(web_interface)，Python 版已删除，
            // 这里不再加载页面（该按钮在布局里已按需求隐藏）
            android.widget.Toast.makeText(activity, "超车设置页已随 Python 版本移除", android.widget.Toast.LENGTH_SHORT).show()

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
// 限速偏移按钮（>> 面板：上=限速+5 / 中=重置 / 下=限速-5）
//   中间按钮上直接显示当前实际偏移量；偏移变化、高德限速变化都统一走这里刷新
// -----------------------------
fun MainActivity.refreshRoadLimitBtnUi() {
    //按钮点击(主线程)与高德广播(主线程)都会调用，仍统一兜底切主线程
    if (Looper.myLooper() != Looper.getMainLooper()) {
        uiHandler.post { refreshRoadLimitBtnUi() }
        return
    }
    //onCreate 早期(按钮还没绑定时)直接返回，避免 lateinit 未初始化崩溃
    if (!roadLimitBtnsBound()) return

    val offsetText = when {
        nRoadLimitOffset > 0 -> "+$nRoadLimitOffset"
        nRoadLimitOffset < 0 -> "$nRoadLimitOffset"
        else -> "0"
    }
    btnIncRoadLimit.text = "限速 +5"
    btnResetRoadLimit.text = "重置（当前偏移 $offsetText）"
    btnDecRoadLimit.text = "限速 -5"
}

// >> 面板底边落在内容区高度的 80% 处(底部留约20%给视频预览/日志区)
private const val ROAD_LIMIT_PANEL_BOTTOM_RATIO = 0.8f

// 面板保底高度(dp)：屏幕很矮时不能让按钮小到按不准
private const val ROAD_LIMIT_PANEL_MIN_HEIGHT_DP = 250f

// >> 面板高度：把面板撑到「内容区高度80%」的位置，
//   面板内第一行安装/测试按钮保持原高，下面三个限速大按钮用 weight 平分剩余高度
fun MainActivity.applyRoadLimitPanelHeight() {
    if (!roadLimitBtnsBound()) return

    //必须等这一次布局走完再量位置：面板刚从 GONE 变 VISIBLE 时 top 还是旧值(0)，
    //直接用 post 有可能跑在 layout 之前，算出来的高度会溢出屏幕底部
    layoutSystemButtons.doOnLayout {
        val container = layoutSystemButtons.parent as? View ?: return@doOnLayout
        //坐标以父容器(内容区)为基准，和 layoutSystemButtons.top 同一坐标系
        val baseH = if (container.height > 0) container.height else resources.displayMetrics.heightPixels

        val target = (baseH * ROAD_LIMIT_PANEL_BOTTOM_RATIO).toInt() - layoutSystemButtons.top
        val minH = (resources.displayMetrics.density * ROAD_LIMIT_PANEL_MIN_HEIGHT_DP).toInt()
        val newH = target.coerceAtLeast(minH)

        if (layoutSystemButtons.layoutParams.height != newH) {
            layoutSystemButtons.layoutParams = layoutSystemButtons.layoutParams.apply { height = newH }
        }
    }
}

// 高德限速发生变化：自动清除用户手动设置的限速偏移，并同步按钮显示
fun MainActivity.resetRoadLimitOffsetByAmap(newLimitSpeed: Int) {
    if (nRoadLimitOffset == 0 && !bRoadLimitIncDec) return //本来就没有偏移，不打扰用户

    if (Looper.myLooper() != Looper.getMainLooper()) {
        uiHandler.post { resetRoadLimitOffsetByAmap(newLimitSpeed) }
        return
    }

    val oldOffset = nRoadLimitOffset
    nRoadLimitOffset = 0
    bRoadLimitIncDec = false
    refreshRoadLimitBtnUi()

    val oldOffsetText = if (oldOffset > 0) "+$oldOffset" else "$oldOffset"
    updateRawLog("高德限速变为 ${newLimitSpeed} km/h，已自动重置限速偏移(原 $oldOffsetText)", bShowRawLog)
    Toast.makeText(this, "高德限速变化，已自动重置限速偏移", Toast.LENGTH_SHORT).show()
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
    val nowMs = System.currentTimeMillis()

    lifecycleScope.launch(Dispatchers.Main) {
        // 文本没变就不要反复 setText：setText 会触发重新排版，
        // 长文本每次重排都会让整块面板抖一下（看起来在闪）。
        if (head != overtakeDebugLastHead) {
            overtakeDebugLastHead = head
            tvOvertakeDebugHead.text = head
        }

        applyRadarCard(tvRadarFront, front)
        applyRadarCard(tvRadarLeftFront, leftFront)
        applyRadarCard(tvRadarRightFront, rightFront)
        applyRadarCard(tvRadarLeftRear, leftRear)
        applyRadarCard(tvRadarRightRear, rightRear)
        tvRadarEgo.text = ego

        // 详情里带实时数值(车速/距离/计时)，每帧都在变；限速到 400ms 刷新一次，
        // 既能看实时值，又不会因为每 200ms 重排一次而闪。
        if (detail != overtakeDebugLastText && nowMs - overtakeDebugLastTextTime >= 400L) {
            overtakeDebugLastText = detail
            overtakeDebugLastTextTime = nowMs
            tvOvertakeDebug.text = detail
        }
    }
}

// -----------------------------
// 方位雷达卡片
//   颜色只表达"这一侧能不能变道"：
//     level 3 = 禁止变道 / 盲区(红)     level 2 = 注意(橙，仅正前方按距离分级)
//     level 1 = 允许变道(绿)
//   卡片不再写"左前方"等方位标题（方位由位置本身表达），省下的行用来放数据。
// -----------------------------
internal data class RadarCard(
    val level: Int = 1,
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

// 数据行: [来源]距离/[相对速度]kph
//   来源: 波=毫米波(前角), 光=激光雷达；只在同一角有两个来源时才加
//   相对速度带正负号(负=正在接近)
private fun dataLine(tag: String, d: Float?, vr: Int?): String {
    val sb = StringBuilder(tag).append(distM(d))
    if (vr != null) sb.append('/').append(relKmh(vr)).append("kph")
    return sb.toString()
}

// 横向距离行(只有激光雷达会报)：单独一行，避免和上面的距离/速度挤成一行被折断
private fun xLine(latMm: Int?): String? =
    latMm?.let { "X:%.1fm".format(kotlin.math.abs(it) / 1000f) }

// 侧向卡片外壳：该角没有"禁止变道"的原因=绿(只显示数据)；有原因=红(原因+来源)
private fun MainActivity.sideRadarCard(action: LaneChangeAction, front: Boolean,
                                       lines: List<String>): RadarCard {
    val cause = cardBlockCause(action, front) ?: return RadarCard(1, lines)
    val out = mutableListOf("禁止变道", cause.text)
    out += lines
    return RadarCard(3, out)
}

// 正前方(毫米波前雷达)：与变道无关，仍按距离分级
private fun MainActivity.radarFrontCard(): RadarCard {
    val s = opStateManager.state
    if (!s.lead1) return RadarCard(1, listOf("无目标"))

    val d = s.drel?.toFloat()
    val level = when {
        d == null -> 1
        d < 20f -> 3
        d < 40f -> 2
        else -> 1
    }
    val lines = mutableListOf(distM(d) + (s.vlead?.let { "/${it}kph" } ?: ""))
    s.vrel?.let { lines += "相对" + relKmh(it) + "kph" }
    return RadarCard(level, lines)
}

// 左前方：毫米波(前角) + 激光(前角)；两者同时有时用 波/光 前缀区分
private fun MainActivity.radarLeftFrontCard(): RadarCard {
    val s = opStateManager.state
    val lines = mutableListOf<String>()

    // 激光前角：drel 为毫米，>0 表示目标在前方
    val lfM = s.lf_drel?.takeIf { it > 0 }?.let { it / 1000f }
    val both = s.l_lead && lfM != null
    if (s.l_lead) {
        lines += dataLine(if (both) "波" else "", s.l_drel?.toFloat(), s.l_vrel)
    }
    if (lfM != null) {
        lines += dataLine(if (both) "光" else "", lfM, s.lf_vrel)
    }
    return sideRadarCard(LaneChangeAction.LEFT, front = true, lines)
}

// 右前方：毫米波(前角) + 激光(前角)
private fun MainActivity.radarRightFrontCard(): RadarCard {
    val s = opStateManager.state
    val lines = mutableListOf<String>()

    val rfM = s.rf_drel?.takeIf { it > 0 }?.let { it / 1000f }
    val both = s.r_lead && rfM != null
    if (s.r_lead) {
        lines += dataLine(if (both) "波" else "", s.r_drel?.toFloat(), s.r_vrel)
    }
    if (rfM != null) {
        lines += dataLine(if (both) "光" else "", rfM, s.rf_vrel)
    }
    return sideRadarCard(LaneChangeAction.RIGHT, front = true, lines)
}

// 左后方(仅激光后角，drel<0 表示在后方)
private fun MainActivity.radarLeftRearCard(): RadarCard {
    val s = opStateManager.state
    val d = s.lb_drel?.takeIf { it < 0 }?.let { -it / 1000f }
    val lines = mutableListOf<String>()
    if (d != null) {
        lines += dataLine("", d, s.lb_vrel)
        xLine(s.lb_xrel)?.let { lines += it }
    }
    return sideRadarCard(LaneChangeAction.LEFT, front = false, lines)
}

// 右后方(仅激光后角，drel<0 表示在后方)
private fun MainActivity.radarRightRearCard(): RadarCard {
    val s = opStateManager.state
    val d = s.rb_drel?.takeIf { it < 0 }?.let { -it / 1000f }
    val lines = mutableListOf<String>()
    if (d != null) {
        lines += dataLine("", d, s.rb_vrel)
        xLine(s.rb_xrel)?.let { lines += it }
    }
    return sideRadarCard(LaneChangeAction.RIGHT, front = false, lines)
}

// 把卡片数据刷到界面上(配色只表达能否变道)
private fun applyRadarCard(tv: TextView, card: RadarCard) {
    val (bg, fg) = when {
        card.level >= 3 -> "#FFCDD2" to "#B71C1C"        // 禁止变道 / 危险：红
        card.level == 2 -> "#FFE0B2" to "#E65100"        // 注意：橙
        else -> "#C8E6C9" to "#1B5E20"                   // 允许变道：绿
    }
    tv.setBackgroundColor(Color.parseColor(bg))
    tv.setTextColor(Color.parseColor(fg))
    // 卡片要放"禁止变道 + 原因 + 两个来源的数据"最多 6 行，字号比常规小一号(13sp)，
    // 否则"波15.0m 相对-1"这种行会折行、原因行会被拆成"前雷/达"。
    tv.textSize = 13f
    tv.text = if (card.lines.isEmpty()) "无目标" else card.lines.joinToString("\n")
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
