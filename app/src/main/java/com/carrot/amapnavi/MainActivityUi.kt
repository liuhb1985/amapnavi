package com.carrot.amapnavi

import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import androidx.appcompat.app.AlertDialog
import androidx.core.view.children
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
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
