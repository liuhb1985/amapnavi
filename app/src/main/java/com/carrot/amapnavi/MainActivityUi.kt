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
    val mmwLeft = radarMmwCard(left = true)
    val mmwRight = radarMmwCard(left = false)
    val lidarLf = radarLidarCard(left = true, front = true)
    val lidarRf = radarLidarCard(left = false, front = true)
    val lidarLr = radarLidarCard(left = true, front = false)
    val lidarRr = radarLidarCard(left = false, front = false)
    val blockLeft = radarBlockCard(left = true)
    val blockRight = radarBlockCard(left = false)
    // 本车卡片两行： 自车 / 速度
    val ego = "自车\n" + (opStateManager.state.vEgo ?: 0) + "km/h"
    val nowMs = System.currentTimeMillis()

    lifecycleScope.launch(Dispatchers.Main) {
        // 文本没变就不要反复 setText：setText 会触发重新排版，
        // 长文本每次重排都会让整块面板抖一下（看起来在闪）。
        if (head != overtakeDebugLastHead) {
            overtakeDebugLastHead = head
            tvOvertakeDebugHead.text = head
        }

        applyRadarCard(tvRadarFront, front)
        applyRadarCard(tvRadarMmwLeft, mmwLeft)
        applyRadarCard(tvRadarMmwRight, mmwRight)
        applyRadarCard(tvRadarLidarLeftFront, lidarLf)
        applyRadarCard(tvRadarLidarRightFront, lidarRf)
        applyRadarCard(tvRadarLidarLeftRear, lidarLr)
        applyRadarCard(tvRadarLidarRightRear, lidarRr)
        applyRadarCard(tvRadarLeftBlock, blockLeft)
        applyRadarCard(tvRadarRightBlock, blockRight)
        // 中列很窄(约容纳"100km/h")，本车卡片和正前车卡片同字号
        tvRadarEgo.textSize = 14f
        tvRadarEgo.text = ego

        // 详情里带实时数值(车速/距离/计时)，每帧都在变；限速到 400ms 刷新一次，
        // 既能看实时值，又不会因为每 200ms 重排一次而闪。
        if (detail != overtakeDebugLastText && nowMs - overtakeDebugLastTextTime >= 400L) {
            overtakeDebugLastText = detail
            overtakeDebugLastTextTime = nowMs
            tvOvertakeDebug.text = detail
        }
    }

    // 供自动化/现场排查回读：把卡片文本压成一行输出（不依赖 uiautomator 的 idle）
    //   adb logcat -d -s RadarCards      或 /sdcard/AmapNavi/app/<date>.log
    logRadarCardsOnce(listOf(
        "tvRadarMmwLeft" to mmwLeft.asText(),
        "tvRadarLidarLeftFront" to lidarLf.asText(),
        "tvRadarLidarLeftRear" to lidarLr.asText(),
        "tvRadarFront" to front.asText(),
        "tvRadarEgo" to ego,
        "tvRadarMmwRight" to mmwRight.asText(),
        "tvRadarLidarRightFront" to lidarRf.asText(),
        "tvRadarLidarRightRear" to lidarRr.asText(),
        "tvRadarLeftBlock" to blockLeft.asText(),
        "tvRadarRightBlock" to blockRight.asText(),
    ))
}

// 卡片显示文本（与 applyRadarCard 一致：空行列表显示"无目标"）
private fun RadarCard.asText(): String =
    (if (lines.isEmpty()) listOf("无目标") else lines).joinToString("\n")

// 文本变化时才输出一行，格式：
//   RADARCARDS tvRadarMmwLeft=15.0m/(-1km/h) tvRadarFront=前车/30.0m/45km/h/(-3km/h) ...
private fun MainActivity.logRadarCardsOnce(cards: List<Pair<String, String>>) {
    val line = cards.joinToString(" ") { (name, text) -> "$name=${text.replace("\n", "/")}" }
    if (line == radarCardsLogLast) return
    radarCardsLogLast = line
    android.util.Log.i("RadarCards", "RADARCARDS $line")
    AppLogger.log(this, "RadarCards", line)
}

// -----------------------------
// 超车页雷达卡片
//   level: 0=雷达数据(中性底) 1=允许变道(绿) 2=无目标(灰) 3=禁止变道(红)
//   前 3 行只显示雷达数据；是否禁止变道只由第 4 行的左右两个卡片表达
// -----------------------------
internal data class RadarCard(
    val level: Int = 0,
    val lines: List<String> = emptyList(),
    val textSize: Float = 16f,
    val distLine: Int = 0,
    val distScale: Float = 1.3f,
    // 只有 level=0(有目标的雷达数据)用得到：淡色背景 + 配对的深色文字
    val bg: String? = null,
    val fg: String? = null,
    // 该角"盲区成立"（CP 下发的该角盲区标志 或 App 自算判据命中）→ 底变红，
    // 这样第 4 行只说"禁止变道"，是哪个方向阻止的看哪张卡片是红的就知道了
    val danger: Boolean = false
)

// 距离格式：>=100m 取整，否则保留1位小数
private fun distM(m: Float?): String = when {
    m == null -> "-"
    m >= 100f -> "%.0fm".format(m)
    else -> "%.1fm".format(m)
}

// 相对速度格式(带符号)
private fun relKmh(v: Int?): String = if (v == null) "-" else "%+d".format(v)

// 横向距离格式(取绝对值)
private fun latM(latMm: Int?): String =
    if (latMm == null) "-" else "%.1fm".format(kotlin.math.abs(latMm) / 1000f)

// 正前车(中列)：前车 / 距离 / 速度 / (相对)
private fun MainActivity.radarFrontCard(): RadarCard {
    // 前车 / 距离m / 速度km/h / (相对km/h)
    val s = opStateManager.state
    if (!s.lead1) return RadarCard(2, listOf("无目标"), textSize = 14f)

    val lines = mutableListOf("前车", distM(s.drel?.toFloat()))
    s.vlead?.let { lines += "${it}km/h" }
    s.vrel?.let { lines += "(" + relKmh(it) + "km/h)" }
    // 有目标时淡绿底(无目标走 level=2 灰底)
    return RadarCard(0, lines, textSize = 14f, distLine = 1, bg = "#E8F5E9", fg = "#1B5E20")
}

// 原车毫米波雷达(前角)：只有距离/相对速度，没目标则"无目标"
private fun MainActivity.radarMmwCard(left: Boolean): RadarCard {
    val s = opStateManager.state
    val has = if (left) s.l_lead else s.r_lead
    if (!has) return RadarCard(2, listOf("无目标"))

    // 前雷达: 距离m / (相对km/h)；有目标时淡黄底
    val d = (if (left) s.l_drel else s.r_drel)?.toFloat()
    val vr = if (left) s.l_vrel else s.r_vrel
    val lines = mutableListOf(distM(d))
    vr?.let { lines += "(" + relKmh(it) + "km/h)" }
    // 原车前雷达的侧前盲区标志成立 → 卡面变红
    val danger = if (left) s.l_front_blind else s.r_front_blind
    return RadarCard(0, lines, bg = "#FFF8E1", fg = "#5D4037", danger = danger)
}

// 激光雷达目标(前角/后角)：  前方距离m/横向距离m / 相对: x km/h
//   front=true 取前角(数据>0 表示在前方)，front=false 取后角(数据<0 表示在后方)
private fun MainActivity.radarLidarCard(left: Boolean, front: Boolean): RadarCard {
    val s = opStateManager.state
    val mm = when {
        front && left -> s.lf_drel
        front -> s.rf_drel
        left -> s.lb_drel
        else -> s.rb_drel
    }
    val xrel = when {
        front && left -> s.lf_xrel
        front -> s.rf_xrel
        left -> s.lb_xrel
        else -> s.rb_xrel
    }
    val vr = when {
        front && left -> s.lf_vrel
        front -> s.rf_vrel
        left -> s.lb_vrel
        else -> s.rb_vrel
    }

    val d = if (front) mm?.takeIf { it > 0 }?.let { it / 1000f }
            else mm?.takeIf { it < 0 }?.let { -it / 1000f }
    if (d == null) return RadarCard(2, listOf("无目标"))

    // 激光雷达: 距离m/横向m / (相对km/h)；有目标时淡蓝底
    val lines = mutableListOf(distM(d) + (xrel?.let { "/" + latM(it) } ?: ""))
    vr?.let { lines += "(" + relKmh(it) + "km/h)" }
    // 该角盲区成立：CP 下发的该角激光盲区标志 或 App 自算判据命中 → 卡面变红
    val cpBlind = when {
        front && left -> s.lidar_lfblind
        front -> s.lidar_rfblind
        left -> s.lidar_lbblind
        else -> s.lidar_rbblind
    }
    return RadarCard(0, lines, bg = "#E3F2FD", fg = "#0D47A1",
                     danger = cpBlind || lidarCornerRisky(left, front))
}

// 第 4 行：该侧是否禁止变道 + 原因
private fun MainActivity.radarBlockCard(left: Boolean): RadarCard {
    val side = if (left) LaneChangeAction.LEFT else LaneChangeAction.RIGHT
    val who = if (left) "左" else "右"
    val cause = sideBlockCause(side)
    return if (cause == null) RadarCard(1, listOf("可${who}变道"))
    else RadarCard(3, listOf("禁止${who}变道", cause.text))
}

// 把卡片数据刷到界面上
//   level: 0=雷达数据(中性) 1=允许变道(绿) 2=无目标(灰) 3=禁止变道(红)
//   距离数字(行首数字)用 RelativeSizeSpan 再放大一档
private fun applyRadarCard(tv: TextView, card: RadarCard) {
    val (bg, fg) = when {
        // 该角盲区成立(有数据时)：红底 —— 一眼看出是哪个方向阻止了变道
        card.danger && card.level != 2 -> "#FFCDD2" to "#B71C1C"
        card.level == 3 -> "#FFCDD2" to "#B71C1C"           // 禁止变道：红
        card.level == 1 -> "#C8E6C9" to "#1B5E20"           // 允许变道：绿
        card.level == 2 -> "#EEEEEE" to "#9E9E9E"           // 无目标：灰
        else -> (card.bg ?: "#ECEFF1") to (card.fg ?: "#263238")  // 雷达数据：按来源淡色
    }
    tv.setBackgroundColor(Color.parseColor(bg))
    tv.setTextColor(Color.parseColor(fg))
    tv.textSize = card.textSize

    val text = android.text.SpannableStringBuilder()
    val lines = if (card.lines.isEmpty()) listOf("无目标") else card.lines
    lines.forEachIndexed { index, line ->
        if (index > 0) text.append('\n')
        val start = text.length
        text.append(line)
        if (index == card.distLine && card.distScale > 1f) {
            val m = Regex("^[0-9]+(\\.[0-9]+)?").find(line)
            if (m != null) {
                text.setSpan(android.text.style.RelativeSizeSpan(card.distScale),
                             start, start + m.value.length,
                             android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
    tv.text = text
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
