package com.carrot.amapnavi

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*

class UdpDeviceActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var tvJsonLog: TextView
    private lateinit var scrollJson: ScrollView
    private lateinit var adapter: DeviceAdapter
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private var uiUpdateJob: Job? = null
    private var top_pad_dp = 5

    fun setTopPaddingDp(view: View, dp: Int) {
        val px = (dp * view.resources.displayMetrics.density).toInt()
        view.setPadding(
            view.paddingLeft,
            px,
            view.paddingRight,
            view.paddingBottom
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_udp_client)

        recyclerView = findViewById(R.id.rvDevices)
        tvJsonLog = findViewById(R.id.tvJsonLog)
        scrollJson = tvJsonLog.parent as ScrollView

        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        // ===== 初始状态 =====
        btnStart.isEnabled = true       // 查找按钮可用
        btnStop.isEnabled = false       // 停止按钮不可用

        tvJsonLog.visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
        scrollJson.visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE

        val prefs = getSharedPreferences("user_settings", Context.MODE_PRIVATE)
        top_pad_dp = if (prefs.contains("top_pad_dp")) {
            prefs.getInt("top_pad_dp", 5)
        } else {
            5
        }
        val container = findViewById<LinearLayout>(R.id.containerLinearLayout)
        setTopPaddingDp(container, top_pad_dp)

        adapter = DeviceAdapter { device ->
            val port = 80  // lidar 固定端口 80
            val intent = Intent(this, WebViewActivity::class.java)
            intent.putExtra("url", "http://${device.ip}:$port")
            startActivity(intent)
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // 点击“查找”
        btnStart.setOnClickListener {
            UdpDeviceManager.start()
            startUiUpdateJob()

            // ===== 状态控制 =====
            btnStart.isEnabled = false   // 查找按钮禁用
            btnStop.isEnabled = true     // 停止按钮启用
        }

        // 点击“停止”
        btnStop.setOnClickListener {
            UdpDeviceManager.stop()
            uiUpdateJob?.cancel()

            // ===== 状态控制 =====
            btnStart.isEnabled = true    // 查找按钮启用
            btnStop.isEnabled = false    // 停止按钮禁用
        }
    }

    private fun startUiUpdateJob() {
        uiUpdateJob?.cancel()

        uiUpdateJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                val list = UdpDeviceManager.getDevices().values.toList()
                adapter.updateData(list)

                if (BuildConfig.DEBUG) {
                    tvJsonLog.text = ""
                    for (dev in list) {

                        // ---- 新增：设备类型转中文 ----
                        val deviceCn = when (dev.device) {
                            "lidar" -> "激光雷达"
                            "blinker" -> "控灯模块"
                            "camera" -> "摄像头"
                            else -> dev.device
                        }

                        tvJsonLog.append(
                            "[${dev.ip}:${dev.port}] id=${dev.device_id} | v${dev.version} | board=${dev.board}" +
                                    (if (dev.registered >= 0) " | ${if (dev.registered == 1) "已注册" else "未注册"}" else "") +
                                    (if (deviceCn.isNotEmpty()) " | $deviceCn" else "") +
                                    "\n\n"
                        )
                    }
                    scrollJson.post { scrollJson.fullScroll(ScrollView.FOCUS_DOWN) }
                }

                delay(500)
            }
        }
    }

    override fun onDestroy() {
        uiUpdateJob?.cancel()
        super.onDestroy()
    }
}
