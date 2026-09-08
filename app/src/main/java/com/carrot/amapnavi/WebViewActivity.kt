package com.carrot.amapnavi

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class WebViewActivity : AppCompatActivity() {

    private var webView: WebView? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val FILE_CHOOSER_REQUEST_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        webView?.settings?.javaScriptEnabled = true
        webView?.settings?.domStorageEnabled = true

        // 使用自定义 WebChromeClient
        webView?.webChromeClient = object : WebChromeClient() {

            // 支持 alert / confirm / prompt
            // 已经 WebChromeClient 默认支持 alert/confirm/prompt，如果需要自定义弹窗可重写

            // 文件选择
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@WebViewActivity.filePathCallback?.onReceiveValue(null) // 清掉上次回调
                this@WebViewActivity.filePathCallback = filePathCallback

                val intent = Intent(Intent.ACTION_GET_CONTENT)
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                intent.type = "*/*"  // 可改成 "image/*" 只选图片
                startActivityForResult(Intent.createChooser(intent, "选择文件"), FILE_CHOOSER_REQUEST_CODE)
                return true
            }
        }

        webView?.webViewClient = WebViewClient() // 内部打开页面

        val url = intent.getStringExtra("url")
        if (!url.isNullOrEmpty()) {
            webView?.loadUrl(url)
        } else {
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            val results: Array<Uri>? = if (data == null || resultCode != Activity.RESULT_OK) null else arrayOf(data.data!!)
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
        }
    }

    override fun onDestroy() {
        webView?.destroy()
        super.onDestroy()
    }
}
