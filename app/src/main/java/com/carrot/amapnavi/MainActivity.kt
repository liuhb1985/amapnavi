package com.carrot.amapnavi

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Inet4Address
import java.net.NetworkInterface
import com.google.gson.Gson
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
//import android.view.WindowManager
//import android.os.PowerManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.view.View
import androidx.core.view.children
import android.view.ViewGroup
import java.security.MessageDigest
import android.provider.Settings
import java.util.Locale
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.carrot.amapnavi.UdpDeviceActivity
import java.net.BindException
import androidx.appcompat.app.AlertDialog
import android.media.MediaPlayer
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.app.Activity
import android.content.*
import android.graphics.*
import android.net.Uri
import com.carrot.lane.LaneDetect
import com.carrot.lane.SegResult
import com.carrot.lane.inputSize
import com.carrot.lane.protoSize
import java.io.InputStream
import java.util.concurrent.Executors

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.os.*
import android.widget.*
import androidx.lifecycle.Lifecycle
import com.chaquo.python.PyObject
import org.json.JSONArray
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date

@Volatile
var wakelock_enable = 0

var navDataCount = 0
var udpStateShow = ""

val traffic_off = 0
val traffic_red = 1
val traffic_green = 2

val lead = 0
val cruise = 1
val e2eCruise = 2
val e2eStop = 3
val e2ePrepare = 4
val e2eStopped = 5

val red_light = 2
val green_light = 3
val yellow_light = 4

var bShowRawLog = false

object Global {
    //lateinit var deviceId: String
    var deviceId: String = "0000000000"
}

//UDP通讯类
var carrotManIp: String? = null
var carrotManPort: Int? = null
var carrotManValid = false
var carrotManSrvTime = 0L

var laneSrvIp: String? = null
var laneSrvPort: Int? = null
var laneSrvValid = false
var laneSrvTimeout = false

var naviSrvIp: String? = null
var naviSrvPort: Int? = null

val appServerHost = "nav.gitop.vip"
val appServerPort = 6666

/*
lateinit var cmUdpService: UdpService
lateinit var laneUdpService: UdpService
lateinit var naviUdpService: UdpService

 */

var cmUdpServiceSendEnable = false
var laneUdpServiceSendEnable = false
var naviUdpServiceSendEnable = false

@Volatile
private var lastUiUpdateTime = 0L

private const val UI_UPDATE_INTERVAL_MS = 200L


var lane_show = false
var video_show = false
var video_show_fast = false
var lane_detect = false
var lane_color = false
var lane_detect_enable = 0
var lane_show_enable = 0
var lane_detect_interval = 500

var show_param_log = true

var navi_lane_cnt = -1

const val LaneChangeOff = 0
const val PreLaneChange = 1
const val LaneChangeStarting = 2
const val LaneChangeFinishing = 3

const val TurnInfo_TurnLeft = 1
const val TurnInfo_TurnRight = 2
const val TurnInfo_ForkLeft = 3
const val TurnInfo_ForkRight = 4

enum class LaneChangeAction(val value: Int) {
    KEEP(0),
    LEFT(1),
    RIGHT(2),
    LEFT_TURN(3),
    RIGHT_TURN(4),
}

var carState = CarState()
val opStateManager = OpStateManager()
var vLaneResult = LaneCalcResult()
var vLaneText = ""
var vNavLaneText = ""
var navLane = NavLane(
    laneStrArray = arrayOf(),  // 空数组
    validCount = 0             // 初始有效数量为0
)
var lastNavLane = NavLane(
    laneStrArray = arrayOf(),  // 空数组
    validCount = 0             // 初始有效数量为0
)
var navLaneChange = false
var resetSuggestLaneTimer = false
var disableSuggestLane = false
var enableLaneChange = true
var enableLaneChangeTimer = 0L
var disableLaneChangeTimer = 0L

var laneLineValid = false

var appSpeakFile = ""

var lastOvertakeNavLane = NavLane(
    laneStrArray = arrayOf(),  // 空数组
    validCount = 0             // 初始有效数量为0
)
var overtakeNavLaneChange = false
var resetOvertakeSuggestLaneTimer = false
var disableOvertakeSuggestLane = false

fun nowTimeStr(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    return sdf.format(Date())
}

//主页面类
class MainActivity : AppCompatActivity() {

    private lateinit var layoutSystemButtons: androidx.constraintlayout.widget.ConstraintLayout
    private lateinit var web_container: LinearLayout
    private lateinit var tvSpeedLeft: TextView
    private lateinit var tvNavInfo: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvSpeedRight: TextView

    private lateinit var imageView: ImageView
    private lateinit var tvDevicID: TextView
    private lateinit var tvUdpStatus: TextView
    private lateinit var tvVersion: TextView
    private lateinit var tvServerData: TextView
    private lateinit var tvNavData: TextView
    private lateinit var tvCpData: TextView
    private lateinit var rawLog: TextView
    private lateinit var rawLogNav: TextView
    private lateinit var webView: WebView
    private lateinit var imageViewLayout: LinearLayout
    private lateinit var driveWayTextView: TextView
    private lateinit var vLaneTextViewLayout: LinearLayout
    private lateinit var vLaneTextView: TextView
    private lateinit var btnAppOverTake: Button
    //private lateinit var btnLaneDetect: Button
    private lateinit var btnNavSetting: Button
    //private lateinit var btnImportSetting: Button
    private lateinit var btnNavState: Button
    private lateinit var btnShowLog: Button
    private lateinit var btnMoreButton: Button
    private lateinit var btnIncRoadLimit: Button
    private lateinit var btnDecRoadLimit: Button
    private lateinit var btnResetRoadLimit: Button
    private lateinit var btnTbtTest: Button
    private lateinit var btnInstallFlask: Button
    private lateinit var btnInstallOpencv: Button
    //private lateinit var rawLogCam: TextView
    private lateinit var scrollRawLog: ScrollView
    private lateinit var scrollRawLogNav: ScrollView
    //private lateinit var scrollRawLogCam: ScrollView
    private lateinit var btnClearLog: Button
    private lateinit var btnCopyLog: Button

    private var webview_owner = 0
    private var imageViewLayoutShowEnable = true
    private var imageViewLayoutShow = false
    private var imageViewLayoutHide = false

    private var ipList: List<String> = emptyList()
    private var currentIpIndex = 0

    private var receiver: BroadcastReceiver? = null

    private var isTbtTestEnabled = false
    private var tbtTestTime = 0L

    var xState : Int =  -1
    var xTrafficState : Int =  -1
    //var xStateCnt =  0
    var xTrafficRedState = false
    var xTrafficRedStateTime = 0L
    var xTrafficRedStateTimeElapsed = 0L

    var lastUpdateTvDistanceTime = 0L

    // 新增：导航状态结构体
    private var naviState = NaviState()
    private var nTBTTurnStationTime = 0L
    private var nTBTTurnTypeStation = false
    private var nTBTTurnTypeChange = false
    private var nTBTTurnTypeMainChange = false
    private var nTBTTurnTypePrev: Int = -1
    private var nTBTTurnTypeMainPrev: Int = -1
    private var nTBTTurnTypeMain: Int = -1
    private var nTBTTurnType: Int = -1
    private var nSdiTypeMain: Int = -1
    private var nRoadLimitSpeedLast: Int = -1
    private var nRoadLimitSpeed: Int? = -1
    private var nRoadLimitSpeedRamp: Int = -1
    private var nRoadLimitSpeedRampCnt: Int = 0
    private var nTrafficCnt = -1
    private var nTotalTrafficCnt = -1
    private var nTrafficLightStatus = -1
    private var nTrafficLightSeconds = -1
    private var nTrafficSpeedLimit = -1
    private var nTrafficSpeedLimitChange = false
    private var navi_traffic_assist = false
    private var cp_traffic_assist = false
    private var redlight_traffic_assist = false
    private var tunnel_dec_speed = true
    private var app_speak = false
    private var app_log = false
    private var app_navi = false
    private var app_road_navi = false
    private var navi_speak = true
    private var navi_speak_first = true
    private var navi_blind_dec_speed = false
    private var enable_speed_limit_up = false
    private var py_overtake_disable = false
    private var py_overtake_disable_prev = false
    private var py_overtake_disable_reason = ""

    private var suggest_lane_en = false
    private var dis_lane_en = false
    private var keep_left_en = false
    private var keep_right_en = false
    private var navi_min_speed = 20

    private var road_median = true
    private var road_right_first = true
    private var lane_prob_enable = false
    private var vision_keep_right = true
    private var nav_turn = true
    private var nav_mode = 2
    private var smooth_speed_limit = false
    private var lane_overtake = false
    private var naviTrafficAssistLast = false
    private var red_light_min_speed_limit = 1
    private var cp_min_speed_limit = 20
    private var road_speed_limit_percent = 0
    private var road_speed_limit_kph = 0
    private var offset_highway = 20
    private var offset_road = 15
    private var min_speed_limit = 30
    private var max_speed_limit = 130
    private var station_dist_offset = 50
    private var station_speed = 10
    private var station_keep_time = 10
    private var navi_traffic_assist_time = 10
    private var nCameraId: Int? = -1
    private var bCameraIdChange = false
    private var bRoadLimitTest = false
    private var bRoadLimitIncDec = false
    private var nRoadLimitOffset = 0
    private var nRoadLimitvCruiseLast: Int = -1
    private var nRoadCateLast: Int = -1
    private var bHighWay = false
    private var gRoadCate: Int = 8
    private var atc_dist_h = 2000
    private var atc_dist_l = 500
    private var fork_dist_h = 80
    private var fork_dist_l = 30
    private var top_pad_dp = 5

    private var overtake_mode = 2
    private var vision_lane_mode = 1
    private var app_overtake = false
    private var app_road_overtake = false

    var tunnelEnterTime: Long? = null  // 毫秒时间戳
    var tunnelKeepTime: Long? = null  // 毫秒时间戳
    var tunnelSpeed = -1 //隧道速度

    private val sound_handler = Handler(Looper.getMainLooper())
    private val pollIntervalMs = 100L   // 每 100ms 检查一次
    private var pollRunnable: Runnable? = null

    @Volatile
    private var lastJsonToSend: String? = null
    private var sendJob: Job? = null
    private var appSrvSendJob: Job? = null

    //Lane Detect
    //private lateinit var laneDetector: LaneDetector
    //private lateinit var imageView: ImageView
    //private val executor = Executors.newSingleThreadExecutor() // 使用单线程池处理推理

    // 保存上一次的车道类型文本
    private var lastDriveWayText: String = ""

    private lateinit var py: Python
    private lateinit var overtakeModule: PyObject
    private lateinit var configModule: PyObject
    private lateinit var sound_centerModule: PyObject
    private lateinit var overtake_controllerModule: PyObject

    //加载参数
    private fun loadUserSettings() {
        val prefs = getSharedPreferences("user_settings", Context.MODE_PRIVATE)
        val editor = prefs.edit()

        // 1. 导航红灯辅助停车
        navi_traffic_assist = if (prefs.contains("navi_traffic_assist")) {
            prefs.getBoolean("navi_traffic_assist", false)
        } else {
            editor.putBoolean("navi_traffic_assist", false)
            false
        }
        cp_traffic_assist = if (prefs.contains("cp_traffic_assist")) {
            prefs.getBoolean("cp_traffic_assist", false)
        } else {
            editor.putBoolean("cp_traffic_assist", false)
            false
        }
        redlight_traffic_assist = if (prefs.contains("redlight_traffic_assist")) {
            prefs.getBoolean("redlight_traffic_assist", false)
        } else {
            editor.putBoolean("redlight_traffic_assist", false)
            false
        }
        tunnel_dec_speed = if (prefs.contains("tunnel_dec_speed")) {
            prefs.getBoolean("tunnel_dec_speed", true)
        } else {
            editor.putBoolean("tunnel_dec_speed", true)
            false
        }
        app_speak = if (prefs.contains("app_speak")) {
            prefs.getBoolean("app_speak", false)
        } else {
            editor.putBoolean("app_speak", false)
            false
        }
        app_log = if (prefs.contains("app_log")) {
            prefs.getBoolean("app_log", false)
        } else {
            editor.putBoolean("app_log", false)
            false
        }
        app_navi = if (prefs.contains("app_navi")) {
            prefs.getBoolean("app_navi", false)
        } else {
            editor.putBoolean("app_navi", false)
            false
        }
        app_road_navi = if (prefs.contains("app_road_navi")) {
            prefs.getBoolean("app_road_navi", false)
        } else {
            editor.putBoolean("app_road_navi", false)
            false
        }
        navi_speak = if (prefs.contains("navi_speak")) {
            prefs.getBoolean("navi_speak", true)
        } else {
            editor.putBoolean("navi_speak", true)
            true
        }
        navi_speak_first = if (prefs.contains("navi_speak_first")) {
            prefs.getBoolean("navi_speak_first", true)
        } else {
            editor.putBoolean("navi_speak_first", true)
            true
        }
        navi_blind_dec_speed = if (prefs.contains("navi_blind_dec_speed")) {
            prefs.getBoolean("navi_blind_dec_speed", false)
        } else {
            editor.putBoolean("navi_blind_dec_speed", false)
            false
        }
        lane_prob_enable = if (prefs.contains("lane_prob_enable")) {
            prefs.getBoolean("lane_prob_enable", false)
        } else {
            editor.putBoolean("lane_prob_enable", false)
            false
        }
        suggest_lane_en = if (prefs.contains("suggest_lane_en")) {
            prefs.getBoolean("suggest_lane_en", false)
        } else {
            editor.putBoolean("suggest_lane_en", false)
            false
        }
        dis_lane_en = if (prefs.contains("dis_lane_en")) {
            prefs.getBoolean("dis_lane_en", false)
        } else {
            editor.putBoolean("dis_lane_en", false)
            false
        }

        keep_left_en = if (prefs.contains("keep_left_en")) {
            prefs.getBoolean("keep_left_en", false)
        } else {
            editor.putBoolean("keep_left_en", false)
            false
        }
        keep_right_en = if (prefs.contains("keep_right_en")) {
            prefs.getBoolean("keep_right_en", false)
        } else {
            editor.putBoolean("keep_right_en", false)
            false
        }
        navi_min_speed = if (prefs.contains("navi_min_speed")) {
            prefs.getInt("navi_min_speed", 20)
        } else {
            editor.putInt("navi_min_speed", 20)
            20
        }

        road_median = if (prefs.contains("road_median")) {
            prefs.getBoolean("road_median", true)
        } else {
            editor.putBoolean("road_median", true)
            true
        }
        road_right_first = if (prefs.contains("road_right_first")) {
            prefs.getBoolean("road_right_first", true)
        } else {
            editor.putBoolean("road_right_first", true)
            true
        }
        vision_keep_right = if (prefs.contains("vision_keep_right")) {
            prefs.getBoolean("vision_keep_right", false)
        } else {
            editor.putBoolean("vision_keep_right", false)
            false
        }
        nav_turn = if (prefs.contains("nav_turn")) {
            prefs.getBoolean("nav_turn", false)
        } else {
            editor.putBoolean("nav_turn", false)
            false
        }
        nav_mode = if (prefs.contains("nav_mode")) {
            prefs.getInt("nav_mode", 2)
        } else {
            editor.putInt("nav_mode", 2)
            2
        }
        smooth_speed_limit = if (prefs.contains("smooth_speed_limit")) {
            prefs.getBoolean("smooth_speed_limit", false)
        } else {
            editor.putBoolean("smooth_speed_limit", false)
            false
        }
        lane_overtake = if (prefs.contains("lane_overtake")) {
            prefs.getBoolean("lane_overtake", false)
        } else {
            editor.putBoolean("lane_overtake", false)
            false
        }
        lane_show = if (prefs.contains("lane_show")) {
            prefs.getBoolean("lane_show", false)
        } else {
            editor.putBoolean("lane_show", false)
            false
        }
        video_show = if (prefs.contains("video_show")) {
            prefs.getBoolean("video_show", false)
        } else {
            editor.putBoolean("video_show", false)
            false
        }
        video_show_fast = if (prefs.contains("video_show_fast")) {
            prefs.getBoolean("video_show_fast", false)
        } else {
            editor.putBoolean("video_show_fast", false)
            false
        }
        lane_detect = if (prefs.contains("lane_detect")) {
            prefs.getBoolean("lane_detect", true)
        } else {
            editor.putBoolean("lane_detect", true)
            true
        }
        lane_color = if (prefs.contains("lane_color")) {
            prefs.getBoolean("lane_color", false)
        } else {
            editor.putBoolean("lane_color", false)
            false
        }
        // 2. 红灯减速最低速度
        red_light_min_speed_limit = if (prefs.contains("red_light_min_speed_limit")) {
            prefs.getInt("red_light_min_speed_limit", 1)
        } else {
            editor.putInt("red_light_min_speed_limit", 1)
            1
        }
        cp_min_speed_limit = if (prefs.contains("cp_min_speed_limit")) {
            prefs.getInt("cp_min_speed_limit", 20)
        } else {
            editor.putInt("cp_min_speed_limit", 20)
            20
        }

        road_speed_limit_percent = if (prefs.contains("road_speed_limit_percent")) {
            prefs.getInt("road_speed_limit_percent", 0)
        } else {
            editor.putInt("road_speed_limit_percent", 0)
            0
        }
        road_speed_limit_kph = if (prefs.contains("road_speed_limit_kph")) {
            prefs.getInt("road_speed_limit_kph", 0)
        } else {
            editor.putInt("road_speed_limit_kph", 0)
            0
        }

        // 3. 高速偏移
        offset_highway = if (prefs.contains("offset_highway")) {
            prefs.getInt("offset_highway", 20)
        } else {
            editor.putInt("offset_highway", 20)
            20
        }
        // 4. 公路偏移
        offset_road = if (prefs.contains("offset_road")) {
            prefs.getInt("offset_road", 15)
        } else {
            editor.putInt("offset_road", 15)
            15
        }
        //5. 最小限速
        min_speed_limit = if (prefs.contains("min_speed_limit")) {
            prefs.getInt("min_speed_limit", 30)
        } else {
            editor.putInt("min_speed_limit", 30)
            30
        }
        max_speed_limit = if (prefs.contains("max_speed_limit")) {
            prefs.getInt("max_speed_limit", 130)
        } else {
            editor.putInt("max_speed_limit", 130)
            130
        }
        //6. 导航红灯辅助降速时间
        navi_traffic_assist_time = if (prefs.contains("navi_traffic_assist_time")) {
            prefs.getInt("navi_traffic_assist_time", 10)
        } else {
            editor.putInt("navi_traffic_assist_time", 10)
            10
        }

        station_dist_offset = if (prefs.contains("station_dist_offset")) {
            prefs.getInt("station_dist_offset", 0)
        } else {
            editor.putInt("station_dist_offset", 0)
            0
        }
        station_speed = if (prefs.contains("station_speed")) {
            prefs.getInt("station_speed", 15)
        } else {
            editor.putInt("station_speed", 15)
            15
        }
        station_keep_time = if (prefs.contains("station_keep_time")) {
            prefs.getInt("station_keep_time", 0)
        } else {
            editor.putInt("station_keep_time", 0)
            0
        }
        lane_detect_interval = if (prefs.contains("lane_detect_interval")) {
            prefs.getInt("lane_detect_interval", 500)
        } else {
            editor.putInt("lane_detect_interval", 500)
            500
        }

        atc_dist_h = if (prefs.contains("atc_dist_h")) {
            prefs.getInt("atc_dist_h", 2000)
        } else {
            editor.putInt("atc_dist_h", 2000)
            2000
        }
        atc_dist_l = if (prefs.contains("atc_dist_l")) {
            prefs.getInt("atc_dist_l", 500)
        } else {
            editor.putInt("atc_dist_l", 500)
            500
        }
        fork_dist_h = if (prefs.contains("fork_dist_h")) {
            prefs.getInt("fork_dist_h", 80)
        } else {
            editor.putInt("fork_dist_h", 80)
            80
        }
        fork_dist_l = if (prefs.contains("fork_dist_l")) {
            prefs.getInt("fork_dist_l", 30)
        } else {
            editor.putInt("fork_dist_l", 30)
            30
        }

        top_pad_dp = if (prefs.contains("top_pad_dp")) {
            prefs.getInt("top_pad_dp", 5)
        } else {
            editor.putInt("top_pad_dp", 5)
            5
        }

        overtake_mode = if (prefs.contains("overtake_mode")) {
            prefs.getInt("overtake_mode", 2)
        } else {
            editor.putInt("overtake_mode", 2)
            2
        }
        vision_lane_mode = if (prefs.contains("vision_lane_mode")) {
            prefs.getInt("vision_lane_mode", 1)
        } else {
            editor.putInt("vision_lane_mode", 1)
            1
        }
        app_overtake = if (prefs.contains("app_overtake")) {
            prefs.getBoolean("app_overtake", false)
        } else {
            editor.putBoolean("app_overtake", false)
            false
        }
        app_road_overtake = if (prefs.contains("app_road_overtake")) {
            prefs.getBoolean("app_road_overtake", false)
        } else {
            editor.putBoolean("app_road_overtake", false)
            false
        }

        // 提交默认值（如果有写入的话）
        editor.apply()

        if(show_param_log) {
            show_param_log = false
            val info = "加载设置--->APP辅助停车: ${if (navi_traffic_assist) "开启" else "关闭"}，" +
                    "视觉红灯信号辅助: ${if (cp_traffic_assist) "开启" else "关闭"}，" +
                    "导航红灯信号辅助: ${if (redlight_traffic_assist) "开启" else "关闭"}，" +
                    "隧道减速: ${if (tunnel_dec_speed) "开启" else "关闭"}，" +
                    "APP播报: ${if (app_speak) "开启" else "关闭"}，" +
                    "最低限速=$min_speed_limit km/h, " +
                    "收费站提前减速距离=$station_dist_offset 米, " +
                    "收费站限速=$station_speed km/h, " +
                    "过收费站时间=$station_keep_time 秒, " +
                    "视觉红灯减速最低速度=$cp_min_speed_limit km/h, " +
                    "导航红灯减速最低速度=$red_light_min_speed_limit km/h, " +
                    "高速偏移=$offset_highway km/h，公路偏移=$offset_road km/h, 最低限速=$min_speed_limit km/h"
            if (!bShowRawLog) {
                clearRawLog()
            }
            setRawLogVisible(true)
            updateRawLog(info, true)

            if (!bShowRawLog) {
                // 取消上一次再重新计时
                uiHandler.removeCallbacks(hideRawLogRunnable)
                uiHandler.postDelayed(hideRawLogRunnable, 5000)
            }
        }

        val container = findViewById<LinearLayout>(R.id.containerLinearLayout)
        setTopPaddingDp(container, top_pad_dp)

        //参数应用
        PythonEnvInit()

        //val py = Python.getInstance()
        //val module = py.getModule("auto_overtake_controller")
        //module.callAttr("set_app_navi", app_navi || app_road_navi || app_overtake || app_road_overtake)
    }

    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideRawLogRunnable = Runnable {
        setRawLogVisible(false)
    }

    //WEB浏览界面初始化
    private fun setupWebView() {
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
                        AlertDialog.Builder(this@MainActivity)
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

    private fun isHighway(roadcate: Int?) : Boolean{
        if(roadcate == null)return false
        if(roadcate == 0 || roadcate == 1)return true //高速或快速
        else return false
    }

    private fun roadcate_conv(roadcate: Int?) : Int{
        if(roadcate == null) return 8 //公路
        else if(roadcate == 0) return 1 //高速
        else if(roadcate == 6) return 0 //快速
        else if(roadcate == 1) return 2 //国道
        else return 8
    }

    private fun pollSound() {
        try {
            if(!appSpeakFile.isNullOrEmpty()){
                if(navi_speak) {
                    SoundPlayer.play(this, appSpeakFile)
                }
                appSpeakFile = ""
                return
            }

            //Log.d("SoundPoll", "pollSound running")
            //val py = Python.getInstance()
            //val module = py.getModule("sound_center")

            if(::sound_centerModule .isInitialized) {
                val result = sound_centerModule.callAttr("get_requested_sound")

                if (result == null) {
                    //Log.d("SoundPoll", "get_requested_sound() result is null")
                    return  // 安全退出
                }

                val sound = result.toString()
                //Log.d("SoundPoll", "sound: ${sound}")
                //Log.d("SoundPoll", "sound raw bytes = " + sound.toByteArray().joinToString(","))

                if (sound.isNotEmpty() && app_speak) {
                    SoundPlayer.play(this, sound)
                }
            }

        } catch (e: Exception) {
            Log.e("SoundPoll", "pollSound error: ${e.message}")
        }
    }

    private fun startSoundPolling() {
        if (pollRunnable != null) return // 避免重复启动
        Log.d("SoundPoll", "startSoundPolling")
        pollRunnable = object : Runnable {
            override fun run() {
                pollSound()
                sound_handler.postDelayed(this, pollIntervalMs)
            }
        }

        sound_handler.post(pollRunnable!!)
    }

    private fun showOrHiddenAppCtrl(bShow:Boolean){
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

    fun setTopPaddingDp(view: View, dp: Int) {
        val px = (dp * view.resources.displayMetrics.density).toInt()
        view.setPadding(
            view.paddingLeft,
            px,
            view.paddingRight,
            view.paddingBottom
        )
    }

    fun PythonEnvInit(){
        if (!Python.isStarted()) {
            //Python.start(AndroidPlatform(this))
            Python.start(AndroidPlatform(this@MainActivity))
        }

        if (!::py.isInitialized) {
            py = Python.getInstance()
            overtake_controllerModule = py.getModule("auto_overtake_controller")
            overtakeModule = py.getModule("auto_overtake")
            sound_centerModule = py.getModule("sound_center")
            configModule = py.getModule("config")
        }
    }

    //程序创建函数
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_main)

        Global.deviceId = getDeviceId(this)

        webView = findViewById(R.id.webViewSection)
        btnAppOverTake = findViewById(R.id.btnAppOverTake)
        //btnLaneDetect = findViewById(R.id.btnLaneDetect)
        btnNavSetting = findViewById(R.id.btnNavSetting)
        //btnImportSetting = findViewById(R.id.btnImportSetting)

        layoutSystemButtons = findViewById(R.id.layoutSystemButtons)
        imageViewLayout = findViewById(R.id.imageViewLinear)
        tvSpeedLeft = findViewById(R.id.tvSpeedLeft)
        tvNavInfo = findViewById(R.id.tvNavInfo)
        tvDistance = findViewById(R.id.tvDistance)
        tvSpeedRight = findViewById(R.id.tvSpeedRight)

        driveWayTextView = findViewById(R.id.driveWayTextView)
        vLaneTextViewLayout = findViewById(R.id.vLaneTextViewLayout)
        vLaneTextView = findViewById(R.id.vLaneTextView)

        imageView = findViewById(R.id.imageView)
        tvDevicID = findViewById(R.id.tvDevicID)
        tvUdpStatus = findViewById(R.id.tvUdpStatus)
        tvVersion = findViewById(R.id.tvVersion)
        tvServerData = findViewById(R.id.tvServerData)
        tvNavData = findViewById(R.id.tvNavData)
        tvCpData = findViewById(R.id.tvCpData)
        rawLog = findViewById(R.id.rawLog)
        rawLogNav = findViewById(R.id.rawLogNav)
        //rawLogCam = findViewById(R.id.rawLogCam)
        scrollRawLog = findViewById(R.id.scrollRawLog)
        scrollRawLogNav = findViewById(R.id.scrollRawLogNav)
        //scrollRawLogCam = findViewById(R.id.scrollRawLogCam)
        btnClearLog = findViewById(R.id.btnClearLog)
        btnCopyLog = findViewById(R.id.btnCopyLog)
        btnNavState = findViewById(R.id.btnNavState)
        btnShowLog = findViewById(R.id.btnShowLog)
        btnInstallFlask = findViewById(R.id.btnInstallFlask)
        btnInstallOpencv = findViewById(R.id.btnInstallOpencv)
        btnMoreButton = findViewById(R.id.btnMoreButton)
        btnIncRoadLimit = findViewById(R.id.btnIncRoadLimit)
        btnDecRoadLimit = findViewById(R.id.btnDecRoadLimit)
        btnResetRoadLimit = findViewById(R.id.btnResetRoadLimit)
        btnTbtTest = findViewById(R.id.btnTbtTest)

        val container = findViewById<LinearLayout>(R.id.containerLinearLayout)
        web_container = findViewById<LinearLayout>(R.id.webLinearLayout)

        //显示版本号
        val versionName = this.packageManager
            .getPackageInfo(this.packageName, 0)
            .versionName
        tvVersion.text = "V" + versionName

        // python启动
        PythonEnvInit()
        /*
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))

            if (!::py.isInitialized) {
                py = Python.getInstance()
                overtake_controllerModule = py.getModule("auto_overtake_controller")
                overtakeModule = py.getModule("auto_overtake")
                sound_centerModule = py.getModule("sound_center")
                configModule = py.getModule("config")
            }
        }
         */
        //val py = Python.getInstance()
        //val module = py.getModule("config")
        if(::configModule.isInitialized) {
            val path = this.filesDir.absolutePath //设置 Python 的配置目录
            configModule.callAttr("set_config_dir", path)
        }

        //val auto_overtake_controller = py.getModule("auto_overtake_controller")
        if(::overtake_controllerModule.isInitialized) {
            overtake_controllerModule.callAttr("stop_python_heart")
        }

        startSoundPolling() // 启动声音轮询

        //val pyModule = py.getModule("auto_overtake")
        CoroutineScope(Dispatchers.IO).launch {
            if(::overtakeModule.isInitialized) {
                val result = overtakeModule.callAttr("main")
                Log.d("RunPython", result.toString())
            }
        }

        // 保存第1行之外控件的原始可见性
        val viewVisibilityMap = mutableMapOf<View, Int>()
        for ((index, view) in container.children.withIndex()) {
            if (index > 4) {  // 第0个子控件是顶部速度栏，保持可见
                viewVisibilityMap[view] = view.visibility
                if (view.visibility == View.VISIBLE) {
                    view.visibility = View.GONE
                }
            }
        }
        vLaneTextViewLayout.visibility = View.GONE
        //控制车道线界面是否显示
        if ((video_show && lane_show_enable > 0) || (lane_detect && lane_detect_enable > 0)) {
            if (imageViewLayout.visibility == View.GONE) {
                imageViewLayout.visibility = View.VISIBLE
            }
        } else {
            if (imageViewLayout.visibility == View.VISIBLE) {
                imageViewLayout.visibility = View.GONE
            }
        }

        var viewsAreVisible = false
        var tvNavInfoContinueClickCnt = 0
        var tvNavInfoClickCnt = 0
        var tvNavInfoClink = false
        var tvDistanceClick = true
        tvNavInfo.setOnClickListener {
            if(tvNavInfoClink == false || viewsAreVisible == true){
                tvNavInfoClickCnt += 1
            }
            tvNavInfoClink = true
            tvDistanceClick = false

            if(tvNavInfoClickCnt == 3) {
                viewsAreVisible = true
            }else if(tvNavInfoClickCnt > 3){
                viewsAreVisible = false
                tvNavInfoClickCnt = 0
                tvNavInfoClink = false
            }
            for ((view, originalVisibility) in viewVisibilityMap) {
                view.visibility = if (viewsAreVisible && originalVisibility == View.VISIBLE) View.VISIBLE else View.GONE
            }

            Log.d("UI","tvNavInfo Clicked, Cnt=$tvNavInfoClickCnt, Visible=$viewsAreVisible")
        }

        btnInstallFlask.setOnClickListener {
            if(laneSrvIp.isNullOrEmpty()){
                Toast.makeText(this, "未连接openpilot，请保持在同一个局域网内!", Toast.LENGTH_SHORT).show()
            }else {
                val strJson = JSONObject().apply {
                    put("echo_cmd", "pip install flask")
                    put("port", laneAppPort)
                }.toString()
                laneUdpServiceSend(strJson, port = laneOpPort)
                //laneUdpService.send(strJson, port = laneOpPort)
                clearRawLogUI()
                setRawLogUIVisible(true)
                updateRawLogUI("安装Flask库命令已发出，等待执行结果", true)
            }
        }

        btnInstallOpencv.setOnClickListener {
            if(laneSrvIp.isNullOrEmpty()){
                Toast.makeText(this, "未连接openpilot，请保持在同一个局域网内!", Toast.LENGTH_SHORT).show()
            }else {
                val strJson = JSONObject().apply {
                    put("echo_cmd", "pip install opencv-python")
                    put("port", laneAppPort)
                }.toString()
                laneUdpServiceSend(strJson, port = laneOpPort)
                //laneUdpService.send(strJson, port = laneOpPort)
                clearRawLogUI()
                setRawLogUIVisible(true)
                updateRawLogUI("安装OpenCV库命令已发出，等待执行结果", true)
            }
        }

        tvDistance.setOnClickListener {
            if(tvDistanceClick == false && tvNavInfoClink == true){
                tvNavInfoClickCnt += 1
            }
            tvDistanceClick = true
            tvNavInfoClink = false

            Log.d("UI","tvDistance Clicked, Cnt=$tvNavInfoClickCnt, Visible=$viewsAreVisible")
        }

        val btnExportLog = findViewById<Button>(R.id.btnExportLog)

        btnExportLog.setOnClickListener {
            AppLogger.shareTodayLog(this)
        }

        val btnSetting = findViewById<Button>(R.id.btnSetting)
        btnSetting.setOnClickListener {
            val intent = Intent(this, SettingActivity::class.java)
            startActivity(intent)
        }

        val btnPay = findViewById<Button>(R.id.btnPay)
        btnPay.setOnClickListener {
            val intent = Intent(this, WxPayActivity::class.java)
            startActivity(intent)
        }

        val btnScanDevice = findViewById<Button>(R.id.btnScanDevice)
        btnScanDevice.setOnClickListener {
            val intent = Intent(this, UdpDeviceActivity::class.java)
            startActivity(intent)
        }

        btnClearLog.setOnClickListener {
            rawLog.text = ""
        }

        btnCopyLog.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("RawLog", rawLog.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "主日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }

        btnNavState.setOnClickListener {
            if (scrollRawLogNav.visibility == View.GONE) {
                scrollRawLogNav.visibility = View.VISIBLE
                btnNavState.text = "隐藏"
            } else {
                scrollRawLogNav.visibility = View.GONE
                btnNavState.text = "概览"
            }
        }

        btnShowLog.setOnClickListener {
            if (bShowRawLog == false) {
                bShowRawLog = true
                scrollRawLog.visibility = View.VISIBLE
                btnShowLog.text = "隐藏"
                web_container.visibility = View.VISIBLE
            } else {
                bShowRawLog = false
                scrollRawLog.visibility = View.GONE
                btnShowLog.text = "日志"
                web_container.visibility = View.GONE
            }
        }

        btnIncRoadLimit.setOnClickListener {
            bRoadLimitIncDec = true
            var speed = naviState.nRoadLimitSpeed
            clearRawLogUI()
            setRawLogUIVisible(true)
            if(speed != null && speed > 0) { //有限速数据
                speed -= nRoadLimitOffset
                if((speed + nRoadLimitOffset) < 140) {
                    nRoadLimitOffset += 5
                    updateRawLogUI("限速加5成功，当前限速为${speed + nRoadLimitOffset} km/h", true)
                }else{
                    updateRawLogUI("限速已加至最大${nRoadLimitOffset} km/h", true)
                }
            }else{
                if(nRoadLimitOffset < 60){
                    nRoadLimitOffset += 5
                    updateRawLogUI("限速加5成功，当前偏移${nRoadLimitOffset} km/h", true)
                }else{
                    updateRawLogUI("限速已加至最大${nRoadLimitOffset} km/h", true)
                }
            }
            if(!bShowRawLog) {
                // 取消上一次再重新计时
                uiHandler.removeCallbacks(hideRawLogRunnable)
                uiHandler.postDelayed(hideRawLogRunnable, 5000)
            }
        }

        btnResetRoadLimit.setOnClickListener {
            nRoadLimitOffset = 0
            bRoadLimitIncDec = false
            clearRawLogUI()
            setRawLogUIVisible(true)
            updateRawLogUI("重置限速偏移成功", true)
            if(!bShowRawLog) {
                // 取消上一次再重新计时
                uiHandler.removeCallbacks(hideRawLogRunnable)
                uiHandler.postDelayed(hideRawLogRunnable, 5000)
            }

            //测试车道数据解析
            testDriveWaySimulation()
        }

        btnDecRoadLimit.setOnClickListener {
            bRoadLimitIncDec = true
            var speed = naviState.nRoadLimitSpeed
            clearRawLogUI()
            setRawLogUIVisible(true)
            if(speed != null && speed > 0) { //有限速数据
                speed -= nRoadLimitOffset
                if((speed + nRoadLimitOffset) > 35) {
                    nRoadLimitOffset -= 5
                    updateRawLogUI("限速减5成功，当前限速为${speed - nRoadLimitOffset} km/h", true)
                }else{
                    updateRawLogUI("限速已减至最小${nRoadLimitOffset} km/h", true)
                }
            }else {
                if(nRoadLimitOffset > -60) {
                    nRoadLimitOffset -= 5
                    updateRawLogUI("限速减5成功，当前偏移${nRoadLimitOffset} km/h", true)
                }else{
                    updateRawLogUI("限速已减至最小${nRoadLimitOffset} km/h", true)
                }
            }
            if(!bShowRawLog) {
                // 取消上一次再重新计时
                uiHandler.removeCallbacks(hideRawLogRunnable)
                uiHandler.postDelayed(hideRawLogRunnable, 5000)
            }
        }

        btnMoreButton.setOnClickListener {
            if(layoutSystemButtons.visibility == View.VISIBLE){
                layoutSystemButtons.visibility = View.GONE
            }else{
                layoutSystemButtons.visibility = View.VISIBLE
            }
        }

        btnTbtTest.setOnClickListener {
            isTbtTestEnabled = !isTbtTestEnabled  // 每次点击切换 true/false

            if (isTbtTestEnabled) {
                clearRawLog()
                btnTbtTest.text = "关闭引导测试"

                tbtTestTime = System.currentTimeMillis()
                nTBTTurnTypeMain = 14
                naviState.nTBTTurnType = convert_tbt_type(nTBTTurnTypeMain)
                naviState.nTBTDist = 500

            } else {
                btnTbtTest.text = "导航引导测试"

            }
        }

        tvDevicID.text = "软件ID: ${Global.deviceId}"

        //启动高德广播监听
        //startListening()

        //初始化WEB控件
        setupWebView()

        // ---------- 新增启动保活服务 ----------
        val intent = Intent(this, KeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        Log.i("MainActivity", "UdpService started")
    }

    fun isSameNavLane(a: NavLane, b: NavLane): Boolean {
        if (a.validCount != b.validCount) return false

        for (i in 0 until a.validCount) {
            if (a.laneStrArray[i] != b.laneStrArray[i]) {
                return false
            }
        }
        return true
    }

    var inSuggestLaneTime = 0L

    fun isStopPyOvertake(
            _navLane: NavLane
    ){
        fun isBlocked(s: String): Boolean = s.contains("X")
        fun isSuggested(s: String): Boolean = s.contains("*")

        val lanes = _navLane.laneStrArray
        val count = _navLane.validCount
        var blocked_flag = false
        var suggested_flag = false

        for (i in 0 until count) {
            if (isBlocked(lanes[i])) {
                blocked_flag = true
                break
            }
        }

        for (i in 0 until count) {
            if (isSuggested(lanes[i])) {
                suggested_flag = true
                break
            }
        }

        if(blocked_flag || suggested_flag) {
            py_overtake_disable = true
            if(blocked_flag){
                py_overtake_disable_reason = "有禁止车道"
            }else if(suggested_flag){
                py_overtake_disable_reason = "有推荐车道"
            }

        }
    }

    fun haveNavDisLane(_navLane: NavLane): Boolean{
        fun isBlocked(s: String): Boolean = s.contains("X")

        val lanes = _navLane.laneStrArray
        val count = _navLane.validCount
        var blocked_flag = false

        for (i in 0 until count) {
            if (isBlocked(lanes[i])) {
                blocked_flag = true
                break
            }
        }

        return blocked_flag
    }


    // -----------------------------
    // 车道决策函数
    // -----------------------------
    fun decideLaneWithAction(
            vLaneResult: LaneCalcResult,
            _navLane: NavLane,
            edgeType: LaneChangeAction,   // LEFT / RIGHT / KEEP
            highWay: Boolean,             // 高速公路
            roadMedian: Boolean           // 道路中间有隔离带
    ): Triple<Int, LaneChangeAction, Int> {

        fun isBlocked(s: String): Boolean = s.contains("X")
        fun isSuggested(s: String): Boolean = s.contains("*")

        val nowTime = nowTimeStr()
        val nav_lanes = _navLane.laneStrArray
        val nav_cnt = _navLane.validCount

        //导航引导车道是否变化? 导航引导有变化?
        if(!isSameNavLane(_navLane, lastNavLane) || resetSuggestLaneTimer){
            //重置推荐车道计时和禁止推荐车道的标志
            inSuggestLaneTime = System.currentTimeMillis() //重置在推荐车道的计时
            if (disableSuggestLane) {
                disableSuggestLane = false

                if(suggest_lane_en) {
                    val msg = "${nowTime} 导航车道或引导发行变化，恢复推荐车道变道功能"
                    Log.w("LaneDecision", msg)
                    showLaneDebug(msg)
                }
            }

            if (!navLaneChange) {
                //...
            }
            navLaneChange = true
            resetSuggestLaneTimer = false
        }else{
            navLaneChange = false
        }
        lastNavLane = _navLane

        // 导航未给车道引导数据 或者 推荐车道/禁行车道功能关闭+导航要求靠右行驶+设置了纯视觉靠右
        if (nav_cnt <= 0 || nav_lanes.isEmpty() || (!suggest_lane_en && !dis_lane_en && vision_keep_right && edgeType == LaneChangeAction.RIGHT)) {
            inSuggestLaneTime = System.currentTimeMillis()

            //导航要求靠右行驶+设置了纯视觉靠右
            if(vision_keep_right && edgeType == LaneChangeAction.RIGHT && vLaneResult.leftLaneId >= 1){
                val rightLaneId = vLaneResult.rightLaneId
                if(highWay){
                    if(rightLaneId > 2){ //车辆不在最右车道(应急车道除外)
                        return Triple(-1, LaneChangeAction.RIGHT, 1)
                    }
                }else{
                    if(rightLaneId > 1){ //车辆不在最右车道
                        return Triple(-1, LaneChangeAction.RIGHT, 0)
                    }
                }
            }

            //保持当前车道行驶
            return Triple(-1, LaneChangeAction.KEEP, -1)
        }

        //两侧车道线均不可靠，不进行车道领航
        if(opStateManager.state.prob && lane_prob_enable) {
            val lLineProb = opStateManager.state.l_line_prob ?: 0f
            val rLineProb = opStateManager.state.r_line_prob ?: 0f
            if (lLineProb < 0.5 && rLineProb < 0.5) {
                return Triple(-1, LaneChangeAction.KEEP, -1)
            }
        }

        //检查视觉车道数据是否有效
        if(vLaneResult.laneCnt == 0 || vLaneResult.leftLaneId == 0 || vLaneResult.rightLaneId == 0){
            return Triple(-1, LaneChangeAction.KEEP, -1)
        }

        //限制视觉车道编号不大于导航车道数量
        // ---------- leftLaneId（从1开始，从左往右数的车辆所在视觉车道）视觉车道整合导航车道 ----------
        val rawLeftLaneId = vLaneResult.leftLaneId
        val safeLeftLaneId = when {
            rawLeftLaneId < 1 -> 1
            rawLeftLaneId > nav_cnt -> nav_cnt
            else -> rawLeftLaneId
        }

        // ---------- rightLaneId（从1开始，从右往左数的车辆所在视觉车道）视觉车道整合导航车道 ----------
        val rawRightLaneId = vLaneResult.rightLaneId
        val safeRightLaneId = when {
            rawRightLaneId < 1 -> 1
            rawRightLaneId > nav_cnt -> nav_cnt
            else -> rawRightLaneId
        }

        // ---------- 计算车辆所在的导航车道currentIdx，即计算navLane.laneStrArray的数组索引号(索引0为最后导航车道) ----------
        var currentIdx = 0
        val leftVisibleLaneCnt = rawLeftLaneId - 1   // 实际看到的左侧车道数
        val rightVisibleLaneCnt = rawRightLaneId - 1 // 实际看到的右侧车道数

        if (roadMedian || highWay) { //路中有隔离带或者高速

            //-----------------特殊情况处理------------------
            if(dis_lane_en) { //启用了禁行车道
                //左侧无车道+右侧有一条车道
                if (leftVisibleLaneCnt == 0 && rightVisibleLaneCnt == 1) {
                    //导航车道数量大于2
                    if (nav_cnt > 2) {
                        //当前车道禁行+最右侧导航车道禁行，即导航|x|0|0|x|,|x|0|x|，视觉|c|x|
                        if (isBlocked(nav_lanes[0]) && isBlocked(nav_lanes[nav_cnt - 1])) {
                            //往右变道
                            return Triple(1, LaneChangeAction.RIGHT, 0)
                        }
                    }
                }
                //右侧无车道+左侧有一条车道
                else if (leftVisibleLaneCnt == 1 && rightVisibleLaneCnt == 0) {
                    //导航车道数量大于2
                    if (nav_cnt > 2) {
                        //当前车道禁行+最左侧导航车道禁行，即导航|x|0|0|x|,|x|0|x|，视觉|x|c|
                        if (isBlocked(nav_lanes[0]) && isBlocked(nav_lanes[nav_cnt - 1])) {
                            //没有靠左行驶的请求，则保持当前车道行驶
                            if (edgeType != LaneChangeAction.LEFT) {
                                return Triple(-1, LaneChangeAction.KEEP, -1)
                            }
                        }
                    }
                }
            }

            if(highWay || !road_right_first){ //高速 或者 非右侧边沿计算原则
                //-----------------特殊情况处理------------------
                //高速已在最右侧车道(不含应急车道)+导航指示该车道为禁行车道(为防止变道进应急车道，进行特殊处理)
                if(highWay && dis_lane_en){
                    //左侧有大于等于1条视觉车道和右侧有1条视觉车道 + 导航车道大于等于2
                    if (leftVisibleLaneCnt >= 1 && rightVisibleLaneCnt == 1 && nav_cnt >= 2) {
                        //当前车道禁行+右侧导航车道可以通行，即导航|...|x|0|，视觉|0|c|0|
                        if (isBlocked(nav_lanes[nav_cnt - 2]) && !isBlocked(nav_lanes[nav_cnt - 1])) {
                            //是否当前车道左侧全为禁行车道?
                            var all_left_blocked = true
                            for (i in 0 until (nav_cnt-2)) {
                                if (!isBlocked(nav_lanes[i])) {
                                    all_left_blocked = false
                                }
                            }

                            //当前车道往左全是禁行车道+没有靠左行驶请求+没有立即变道请求
                            if (all_left_blocked && edgeType != LaneChangeAction.LEFT && opStateManager.state.doLaneForkNow != LaneChangeAction.RIGHT.value) {
                                return Triple(-1, LaneChangeAction.KEEP, -1)
                            }
                        }
                    }
                }

                currentIdx =
                        if (leftVisibleLaneCnt < 2) {              // 左侧没到视觉上限 → 左可信
                            safeLeftLaneId - 1
                        } else if (rightVisibleLaneCnt < 2) {      // 右侧没到视觉上限 → 右可信
                            nav_cnt - safeRightLaneId
                        } else {                            // 两侧都可能被截断 → 固定用左
                            safeLeftLaneId - 1
                        }
            }else { //右侧边沿计算模式
                currentIdx =
                        if (rightVisibleLaneCnt < 2) {             // 右侧没到视觉上限 → 右可信
                            nav_cnt - safeRightLaneId
                        }else if (leftVisibleLaneCnt < 2) {        // 左侧没到视觉上限 → 左可信
                            safeLeftLaneId - 1
                        } else {                            // 两侧都可能被截断 → 右可信
                            nav_cnt - safeRightLaneId
                        }
            }
        } else { //公路
            currentIdx = nav_cnt - safeRightLaneId
        }
        currentIdx = currentIdx.coerceIn(0, nav_cnt - 1)

        //车辆是否已在推荐车道上？
        if (isSuggested(nav_lanes[currentIdx])) {
            //在推荐车道上的时间超过5秒后则临时禁用推荐车道功能（直到导航车道信息有变或者导航引导信息有变）
            if((System.currentTimeMillis() - inSuggestLaneTime) > 5000L){
                if(!disableSuggestLane) {
                    disableSuggestLane = true

                    if(suggest_lane_en) {
                        val msg = "${nowTime} 行驶在推荐车道已超过5秒，暂停推荐车道变道功能"
                        Log.w("LaneDecision", msg)
                        showLaneDebug(msg)
                    }
                }
            }
            return Triple(-1, LaneChangeAction.KEEP, -1)
        }else{
            if(!disableSuggestLane) {
                inSuggestLaneTime = System.currentTimeMillis()
            }
        }

        //currentIdx为车辆所在的导航车道数组的索引
        var targetIdx = currentIdx

        // =====================================================
        // ① 靠边策略优先（LEFT / RIGHT）
        // =====================================================
        if (edgeType == LaneChangeAction.LEFT) {
            // 找最左侧可通行车道
            for (i in 0 until nav_cnt) {
                if (!isBlocked(nav_lanes[i])) {
                    targetIdx = i
                    break
                }
            }
        } else if (edgeType == LaneChangeAction.RIGHT) {
            // 找最右侧可通行车道
            for (i in nav_cnt - 1 downTo 0) {
                if (!isBlocked(nav_lanes[i])) {
                    targetIdx = i
                    break
                }
            }
        } else {
            // 优先找推荐车道（带有*号的）
            var starIdx = -1
            if(suggest_lane_en && !disableSuggestLane) { //是否允许走推荐车道
                starIdx = nav_lanes.indexOfFirst { it.contains("*") && !isBlocked(it) }
            }
            if (starIdx != -1) {
                targetIdx = starIdx
            } else {
                // 当前车道禁止 → 找最近可通行
                if(dis_lane_en) { //禁行车道功能开启
                    if (isBlocked(nav_lanes[currentIdx])) {
                        for (offset in 1 until nav_cnt) {
                            val left = currentIdx - offset
                            val right = currentIdx + offset

                            if (left >= 0 && !isBlocked(nav_lanes[left])) {
                                targetIdx = left
                                break
                            }
                            if (right < nav_cnt && !isBlocked(nav_lanes[right])) {
                                targetIdx = right
                                break
                            }
                        }
                    }
                }else{//禁行车道功能未开启
                    return Triple(-1, LaneChangeAction.KEEP, -1)
                }
            }
        }

        // 兜底保护
        if (isBlocked(nav_lanes[targetIdx])) {
            targetIdx = currentIdx
        }

        //计算目标车道在视觉车道的第几车道
        fun targetIdxToRightBased(targetIdx: Int, nav_cnt: Int): Int {
            if (nav_cnt <= 0) return 0
            return (nav_cnt - 1 - targetIdx).coerceIn(0, nav_cnt - 1)
        }
        val visualLaneId = targetIdxToRightBased(targetIdx, _navLane.validCount)

        // ---------- 计算变道方向 ----------
        val action = when {
            targetIdx < currentIdx -> LaneChangeAction.LEFT
            targetIdx > currentIdx -> LaneChangeAction.RIGHT
            else -> LaneChangeAction.KEEP
        }

        return Triple(targetIdx, action, visualLaneId)
    }

    // -----------------------------
    // 车道决策函数
    // -----------------------------
    var inOvertakeSuggestLaneTime = 0L

    fun decideOvertakeLane(
        vLaneResult: LaneCalcResult,
        _navLane: NavLane,
        edgeType: LaneChangeAction,   // LEFT / RIGHT / KEEP
        highWay: Boolean,             // 高速公路
        roadMedian: Boolean           // 道路中间有隔离带
    ): Triple<Int, LaneChangeAction, Int> {

        //计算目标车道在视觉车道的第几车道
        fun targetIdxToRightBased(targetIdx: Int, count: Int): Int {
            if (count <= 0) return 0
            return (count - 1 - targetIdx).coerceIn(0, count - 1)
        }

        val nowTime = nowTimeStr()
        val lanes = _navLane.laneStrArray
        val count = _navLane.validCount

        //导航引导车道是否变化? 导航引导有变化?
        if(!isSameNavLane(_navLane, lastOvertakeNavLane) || resetOvertakeSuggestLaneTimer){
            //重置推荐车道计时和禁止推荐车道的标志
            inOvertakeSuggestLaneTime = System.currentTimeMillis() //重置在推荐车道的计时
            if (disableOvertakeSuggestLane) {
                disableOvertakeSuggestLane = false

                if(suggest_lane_en) {
                    val msg = "${nowTime} 导航车道或引导发行变化，恢复推荐车道变道功能"
                    Log.w("LaneDecision", msg)
                    showLaneDebug(msg)
                }
            }

            if (!overtakeNavLaneChange) {
                //...
            }
            overtakeNavLaneChange = true
            resetOvertakeSuggestLaneTimer = false
        }else{
            overtakeNavLaneChange = false
        }
        lastOvertakeNavLane = _navLane

        // 导航未给车道引导数据 或者 推荐车道/禁行车道功能关闭
        if (count <= 0 || lanes.isEmpty() || (!suggest_lane_en && !dis_lane_en)) {
            inOvertakeSuggestLaneTime = System.currentTimeMillis()

            //检测左侧是否允许行驶
            if(edgeType == LaneChangeAction.LEFT && vLaneResult.leftLaneId > 1){ //车辆不在最左侧
                val rightLaneId = vLaneResult.rightLaneId
                if(highWay){
                    if(rightLaneId > 2){ //车辆不在最右车道(应急车道除外)
                        return Triple(-1, LaneChangeAction.RIGHT, 1)
                    }
                }else{
                    if(rightLaneId > 1){ //车辆不在最右车道
                        return Triple(-1, LaneChangeAction.RIGHT, 0)
                    }
                }
            }
            //检测右侧是否允许行驶
            else if(edgeType == LaneChangeAction.RIGHT){
                val rightLaneId = vLaneResult.rightLaneId
                if(highWay){
                    if(rightLaneId > 2){ //车辆不在最右车道(应急车道除外)
                        return Triple(-1, LaneChangeAction.RIGHT, 1)
                    }else{
                        if(vLaneResult.leftLaneId > 1){ //左侧有车道
                            return Triple(-1, LaneChangeAction.LEFT, vLaneResult.leftLaneId-1)
                        }
                    }
                }else{
                    if(rightLaneId > 1){ //车辆不在最右车道
                        return Triple(-1, LaneChangeAction.RIGHT, 0)
                    }else{
                        if(vLaneResult.leftLaneId > 1){ //左侧有车道
                            return Triple(-1, LaneChangeAction.LEFT, vLaneResult.leftLaneId-1)
                        }
                    }
                }
            }

            return Triple(-1, LaneChangeAction.KEEP, -1)
        }

        // ---------- leftLaneId（从1开始，从左往右数的车辆所在视觉车道）视觉车道整合导航车道 ----------
        val rawLeftLaneId = vLaneResult.leftLaneId
        val safeLeftLaneId = when {
            rawLeftLaneId < 1 -> 1
            rawLeftLaneId > count -> count
            else -> rawLeftLaneId
        }

        // ---------- rightLaneId（从1开始，从右往左数的车辆所在视觉车道）视觉车道整合导航车道 ----------
        val rawRightLaneId = vLaneResult.rightLaneId
        val safeRightLaneId = when {
            rawRightLaneId < 1 -> 1
            rawRightLaneId > count -> count
            else -> rawRightLaneId
        }

        // ---------- 计算车辆所在的导航车道currentIdx，即计算navLane.laneStrArray的数组索引号(索引0为最后导航车道) ----------
        var currentIdx = 0
        val leftVisibleLaneCnt = rawLeftLaneId - 1   // 实际看到的左侧车道数
        val rightVisibleLaneCnt = rawRightLaneId - 1 // 实际看到的右侧车道数

        if (roadMedian || highWay) { //路中有隔离带或者高速
            if(highWay || !road_right_first){ //高速 或者 非右侧边沿计算原则
                currentIdx =
                    if (leftVisibleLaneCnt < 2) {              // 左侧没到视觉上限 → 左可信
                        safeLeftLaneId - 1
                    } else if (rightVisibleLaneCnt < 2) {      // 右侧没到视觉上限 → 右可信
                        count - safeRightLaneId
                    } else {                            // 两侧都可能被截断 → 固定用左
                        safeLeftLaneId - 1
                    }
            }else { //右侧边沿计算模式
                currentIdx =
                    if (rightVisibleLaneCnt < 2) {             // 右侧没到视觉上限 → 右可信
                        count - safeRightLaneId
                    }else if (leftVisibleLaneCnt < 2) {        // 左侧没到视觉上限 → 左可信
                        safeLeftLaneId - 1
                    } else {                            // 两侧都可能被截断 → 右可信
                        count - safeRightLaneId
                    }
            }
        } else { //公路
            currentIdx = count - safeRightLaneId
        }
        currentIdx = currentIdx.coerceIn(0, count - 1)

        fun isBlocked(s: String): Boolean = s.contains("X")
        fun isSuggested(s: String): Boolean = s.contains("*")

        //车辆是否已在推荐车道上？
        if (isSuggested(lanes[currentIdx])) {
            //在推荐车道上的时间超过5秒后则临时禁用超车功能（直到导航车道信息有变或者导航引导信息有变）
            if((System.currentTimeMillis() - inSuggestLaneTime) > 5000L){
                if(!disableOvertakeSuggestLane) {
                    disableOvertakeSuggestLane = true

                    if(suggest_lane_en) {
                        val msg = "${nowTime} 行驶在推荐车道已超过5秒，当前路段暂停超车"
                        Log.w("LaneDecision", msg)
                        showLaneDebug(msg)
                    }
                }
            }
            return Triple(-1, LaneChangeAction.KEEP, -1)
        }else{
            if(!disableOvertakeSuggestLane) {
                inOvertakeSuggestLaneTime = System.currentTimeMillis()
            }else{
                //当前路段已禁止超车
                return Triple(-1, LaneChangeAction.KEEP, -1)
            }
        }

        //currentIdx为车辆所在的导航车道数组的索引
        var targetIdx = currentIdx

        // =====================================================
        // 左右侧是否允许通行检测
        // =====================================================
        //检测左边车道是否允许通行
        if (edgeType == LaneChangeAction.LEFT) {
            //车辆不在最左侧车道
            if(currentIdx > 0){
                if(isBlocked(lanes[currentIdx-1]) && dis_lane_en){
                    //左边车道为禁行车道，则需要判断右侧车道是否可以通行
                }else {
                    //允许向左变道
                    targetIdx = currentIdx - 1
                    val visualLaneId = targetIdxToRightBased(targetIdx, _navLane.validCount)
                    return Triple(targetIdx, LaneChangeAction.LEFT, visualLaneId)
                }
            }

            //导航车道数量大于当前车道所在车道
            if(count > (currentIdx+1) && (isBlocked(lanes[currentIdx+1]) && dis_lane_en)){ //右边车道为禁行车道
                return Triple(-1, LaneChangeAction.KEEP, -1)
            }
            //从视觉车道上继续判断右侧车道是否可以通行
            val rightLaneId = vLaneResult.rightLaneId
            if(highWay){
                if(rightLaneId > 2){ //车辆不在最右车道(应急车道除外)
                    return Triple(-1, LaneChangeAction.RIGHT, 1)
                }
            }else{
                if(rightLaneId > 1){ //车辆不在最右车道
                    return Triple(-1, LaneChangeAction.RIGHT, 0)
                }
            }

            return Triple(-1, LaneChangeAction.KEEP, -1)
        } else if (edgeType == LaneChangeAction.RIGHT) {
            //车辆不在最右侧车道
            if((currentIdx+1) < count){
                if(isBlocked(lanes[currentIdx-1]) && dis_lane_en){
                    //右边车道为禁行车道，则需要判断左侧车道是否可以通行
                }else {
                    //允许向右变道
                    targetIdx = currentIdx + 1
                    val visualLaneId = targetIdxToRightBased(targetIdx, _navLane.validCount)
                    return Triple(targetIdx, LaneChangeAction.RIGHT, visualLaneId)
                }
            }else if(currentIdx == 0){
                return Triple(-1, LaneChangeAction.KEEP, -1)
            }

            //导航车道数量大于当前车道所在车道
            if(isBlocked(lanes[currentIdx-1]) && dis_lane_en){ //左边车道为禁行车道
                return Triple(-1, LaneChangeAction.KEEP, -1)
            }
            //从视觉车道上继续判断左侧车道是否可以通行
            if(vLaneResult.leftLaneId > 1){ //车辆不在最左车道
                return Triple(-1, LaneChangeAction.LEFT, vLaneResult.leftLaneId - 1)
            }

            return Triple(-1, LaneChangeAction.KEEP, -1)
        } else {
            return Triple(-1, LaneChangeAction.KEEP, -1)
        }
    }


    // ------------------- 变道控制相关 -------------------
    private val RESEND_INTERVAL = 1000L
    private val LANE_CHANGE_END_DELAY = 2000L
    private val USER_CALCEL_LANE_CHANGE_LONG_DELAY = 5000L
    private val USER_CALCEL_LANE_CHANGE_NORMAL_DELAY = 2000L
    private val USER_CALCEL_LANE_CHANGE_FAST_DELAY = 1000L
    private val BLIND_SPEAK_INTERVAL = 3000L
    private val BLIND_BLINKER_INTERVAL = 500L
    private val LANE_CHANGE_SPEAK_DELAY = 1500L
    private val LANE_TURN_DELAY = 800L
    private val FINISH_TIMEOUT = 10000L   // 10秒超时保护，可调整

    private val OVERTAKE_END_DELAY = 30000L
    private val USER_CALCEL_OVERTAKE_DELAY = 5000L
    private val OVERTAKE_FINISH_TIMEOUT = 10000L   // 10秒超时保护，可调整

    var laneAction: LaneChangeAction = LaneChangeAction.KEEP
    var curLaneAction: LaneChangeAction = LaneChangeAction.KEEP
    var lastValidLaneAction: LaneChangeAction = LaneChangeAction.KEEP
    var prevLaneAction: LaneChangeAction = LaneChangeAction.KEEP
    var lastTargetIdx = -1 //最后一次导航车道编号
    var lastVisualIdx = -1
    var targetLaneIdx = -1 //车辆所在的导航车道编号(从0开始，从左往右)
    var visualLaneIdx = -1 //车辆所在视觉车道编号(从0开始，从右往左)
    var laneChangeCmdIndex = 0 //变道命令id
    private var laneCmdSent = false //变道命令已发送标志
    private var laneCmdExecOk = false //变道命令已执行标志
    private var laneCmdSentRetryCnt = 0 //变道命令重发次数
    private var laneChangeSpeak = false //准备变道命令已发送标志
    private var inLaneChangeFinishing = false //变道正在完成中的标志
    private var inLaneChangeStarting = false //变道正在进行中的标志

    private var lastCmdSendTime = 0L //上次发送变道命令的时间
    private var lastBlindSpeakTime = 0L //上次播报盲区有车语音的时间
    private var lastBlindDecSpeedTime = 0L //上次减速时间
    private var laneSpeakTime = 0L //播报准备变道的时间点s
    private var laneChangeStartTime = 0L //变道进行中的时间
    private var lastAtcStateChangeTime = 0L //最后一次ATC状态变化的时间
    private var laneSpeakDelayTime = LANE_CHANGE_SPEAK_DELAY
    private var validLaneActionDbTime = 0L
    private var blindDecSpeedEnable = false
    private var blindDecSpeedPause = false
    private var blindDecSpeedOffset = 0
    private var lastBlindDbTime = 0L
    private var blindSafeTime = 0L
    private var lastBlindBlinkerTime = 0L

    private var app_navi_valid = -1 //APP导航条件成立
    private var atc_state_start = 0 //首次发送变道命令时的atc状态
    private var atc_state_curr = 0 //当前的atc状态
    private var laneActionPause = false //暂停变道动作
    private var atcInfoShow = 0
    private var atcInfoChange = false
    private var app_overtake_valid = -1

    var overtakeAction: LaneChangeAction = LaneChangeAction.KEEP
    var overtakeCmdIndex = 0 //变道命令id
    private var overtake_atc_state_start = 0 //首次发送变道命令时的atc状态
    private var overtake_atc_state_curr = 0 //当前的atc状态    var overtakeChangeCmdIndex = 0 //变道命令id
    private var overtakeActionPause = false //暂停变道动作
    private var overtakeActionPausePrev = false //暂停变道动作
    private var overtakeCmdSent = false //变道命令已发送标志
    private var overtakeCmdExecOk = false //变道命令已执行标志
    private var overtakeCmdSentRetryCnt = 0 //变道命令重发次数
    private var overtakeSpeak = false //准备变道命令已发送标志
    private var inOvertakeFinishing = false //变道正在完成中的标志
    private var inOvertakeStarting = false //变道正在进行中的标志

    private var lastOvertakeCmdSendTime = 0L //上次发送变道命令的时间
    private var lastOvertakeBlindSpeakTime = 0L //上次播报盲区有车语音的时间
    private var overtakeSpeakTime = 0L //播报准备变道的时间点s
    private var overtakeStartTime = 0L //变道进行中的时间
    private var lastOvertakeAtcStateChangeTime = 0L //最后一次ATC状态变化的时间
    private var overtakeSpeakDelayTime = LANE_CHANGE_SPEAK_DELAY
    //private var validLaneActionDbTime = 0L

    private fun showLaneDebug(msg: String) {
        // 显示在 UI 上的日志
        setRawLogVisible(true)
        updateRawLog(msg, true)
    }

    private fun LaneChangeActionStr(action: LaneChangeAction) : String{
        if (action == LaneChangeAction.KEEP){
            return "KEEP"
        }
        else if (action == LaneChangeAction.LEFT){
            return "LEFT"
        }
        else if (action == LaneChangeAction.RIGHT){
            return "RIGHT"
        }
        else if (action == LaneChangeAction.LEFT_TURN){
            return "LEFT_TURN"
        }
        else if (action == LaneChangeAction.RIGHT_TURN){
            return "RIGHT_TURN"
        }else{
            return "UNKNOW"
        }
    }

    //发送变道json命令
    private fun sendLaneCmd(index: Int, cmd: String, arg:String){
        val json = JSONObject() //组JSON
        json.put("index", index)
        json.put("cmd", cmd) //OVERTAKE
        json.put("arg", arg) //LEFT/RIGHT
        val jsonStr = json.toString()
        cmUdpServiceSend(jsonStr, port = naviOpPort)
    }

    private fun sendBlinkerCtrlComm(index: Int, ctrl: String, state:String){
        val json = JSONObject() //组JSON
        json.put("ctrlid", index)
        json.put("ctrl", ctrl) //blinker
        json.put("state", state) //LEFT/RIGHT
        val jsonStr = json.toString()
        cmUdpServiceSend(jsonStr, port = naviOpPort)
    }

    private fun sendLaneActionCmd(action: LaneChangeAction) {
        val dir = if (action == LaneChangeAction.LEFT || action == LaneChangeAction.LEFT_TURN) "LEFT" else "RIGHT"

        val nowTime = nowTimeStr()
        var msg = ""
        if (action == LaneChangeAction.LEFT || action == LaneChangeAction.RIGHT) {
            //if(nav_mode >= 2) {
            //    sendLaneCmd(laneChangeCmdIndex, "OVERTAKE", dir)
            //}else{
                sendLaneCmd(laneChangeCmdIndex, "LANECHANGE", dir)
            //}
            msg = "${nowTime} 发送变道命令: index=$laneChangeCmdIndex, 方向=$dir"
        }else{
            sendLaneCmd(laneChangeCmdIndex, "TURN", dir)
            msg = "${nowTime} 发送转弯命令: index=$laneChangeCmdIndex, 方向=$dir"
        }

        Log.d("LaneCmd", msg)
        showLaneDebug(msg)
    }

    private fun sendOvertakeActionCmd(action: LaneChangeAction) {
        val dir = if (action == LaneChangeAction.LEFT || action == LaneChangeAction.LEFT_TURN) "LEFT" else "RIGHT"

        val nowTime = nowTimeStr()
        var msg = ""
        if (action == LaneChangeAction.LEFT || action == LaneChangeAction.RIGHT) {
            //if(overtake_mode >= 2) {
            //    sendLaneCmd(overtakeCmdIndex, "OVERTAKE", dir)
            //}else{
                sendLaneCmd(overtakeCmdIndex, "LANECHANGE", dir)
            //}
            msg = "${nowTime} 发送超车变道命令: index=$overtakeCmdIndex, 方向=$dir"
        }else{
            sendLaneCmd(overtakeCmdIndex, "TURN", dir)
            msg = "${nowTime} 发送超车转弯命令: index=$overtakeCmdIndex, 方向=$dir"
        }

        Log.d("LaneCmd", msg)
        showLaneDebug(msg)
    }

    //检查盲区是否安全
    private fun LaneChangeSafetyCheck(action: LaneChangeAction) : Int{
        if(action == LaneChangeAction.LEFT) {
            //左侧盲区有车
            if(opStateManager.state.l_front_blind){ //原车左侧前方
                return -2
            } else if(opStateManager.state.left_blindspot){ //原车左侧后方
                return -4
            } else if(opStateManager.state.lidar_lblind){ //激光雷达或摄像头左侧
                return -8
            }else if(left_lane_type == 1){ //左侧实线
                return -1
            }
        }else if(action == LaneChangeAction.RIGHT){
            //右侧盲区有车
            if(opStateManager.state.r_front_blind){
                return -2
            } else if(opStateManager.state.right_blindspot){
                return -4
            } else if(opStateManager.state.lidar_rblind){
                return -8
            }else if(right_lane_type == 1){ //实线
                return -1
            }
        }

        if(action == LaneChangeAction.LEFT_TURN) {
            //左侧盲区有车
            if(opStateManager.state.l_front_blind){ //原车左侧前方
                return -2
            }
        }else if(action == LaneChangeAction.RIGHT_TURN){
            //右侧盲区有车
            if(opStateManager.state.r_front_blind){
                return -2
            }
        }

        return 0
    }

    private fun SendBlinkerControl(action: LaneChangeAction)
    {
        if (action == LaneChangeAction.LEFT) {
            sendBlinkerCtrlComm(0, "blinker", "left")
        } else if (action == LaneChangeAction.RIGHT) {
            sendBlinkerCtrlComm(0, "blinker", "right")
        } else if (action == LaneChangeAction.KEEP) {
            sendBlinkerCtrlComm(0, "blinker", "none")
        }
    }

    private fun ShowPrepareLaneChangeAction(action: LaneChangeAction, edgeAction: LaneChangeAction, targetIdx:Int, visualIdx:Int)
    {
        val now = System.currentTimeMillis()
        val nowTime = nowTimeStr()

        //发送转向灯控制命令
        SendBlinkerControl(action)

        var msg = "----------------------------------------"
        Log.d("LaneDecision", msg)
        showLaneDebug(msg)

        //车道信息显示
        val navPart = if (!vNavLaneText.isNullOrBlank()) {
            "导航$vNavLaneText, "
        } else {
            ""
        }
        msg = "${nowTime} ${navPart}视觉$vLaneText, 动作=$action"
        Log.d("LaneDecision", msg)
        showLaneDebug(msg)

        val laneArray = navLane.laneStrArray
        val targetLaneStr =
                if(targetIdx in navLane.laneStrArray.indices){
                    "${laneLetterToIcon(laneArray[targetIdx])}"
                }else{
                    ""
                }
        val targetIdxStr = if(targetIdx > -1){"${targetIdx + 1}"} else {"${targetIdx}"}
        val visualIdxStr = if(visualIdx > -1){"${visualIdx + 1}"} else {"${visualIdx}"}
        if(edgeAction == LaneChangeAction.KEEP) {
            msg = "${nowTime} 导航(左->${targetIdxStr}$targetLaneStr), 视觉(${visualIdxStr}<-右), 动作=$action"
        }else{
            msg = "${nowTime} 导航(左->${targetIdxStr}$targetLaneStr), 视觉(${visualIdxStr}<-右), 动作=$action，靠边=$edgeAction"
        }
        Log.d("LaneDecision", msg)
        showLaneDebug(msg)

        if(action == LaneChangeAction.LEFT || action == LaneChangeAction.RIGHT) {
            msg = "${nowTime} 准备发送变道命令: $action"
        }else if(action == LaneChangeAction.LEFT_TURN || action == LaneChangeAction.RIGHT_TURN) {
            msg = "${nowTime} 准备发送转弯命令: $action"
        }
        Log.d("LaneCmd", msg)
        showLaneDebug(msg)
    }

    //变道执行
    private fun doLaneChangeAction(action: LaneChangeAction, edgeAction: LaneChangeAction, targetIdx:Int, visualIdx:Int) {
        //暂停变道动作
        if(laneActionPause) return

        val now = System.currentTimeMillis()
        val nowTime = nowTimeStr()
        val atc_state = opStateManager.state.atc_state?:LaneChangeOff

        // 检查是否要发送变道命令
        if (!laneCmdSent || !laneChangeSpeak) {
            //无变道请求，退出函数
            if (action == LaneChangeAction.KEEP){
                laneChangeSpeak = false
                blindDecSpeedEnable = false
                blindDecSpeedPause = false
                blindDecSpeedOffset = 0
                blindSafeTime = 0
                return
            }

            //正在变道中或者变道完成中，退出函数
            if(atc_state >= LaneChangeStarting){
                laneChangeSpeak = false
                blindDecSpeedEnable = false
                blindDecSpeedPause = false
                blindDecSpeedOffset = 0
                blindSafeTime = 0
                return
            }

            //盲区不安全，播报盲区有车
            val lane_change_safe = LaneChangeSafetyCheck(action)
            if(lane_change_safe < 0){
                blindSafeTime = now
                lastBlindDbTime = now

                //定时播报盲区有车
                if(now - lastBlindSpeakTime >= BLIND_SPEAK_INTERVAL){
                    lastBlindSpeakTime = now

                    var msg = ""
                    if(lane_change_safe < -1){
                        appSpeakFile = "audio_car_watchout" //盲区有车
                        msg = "${nowTime} 侧面(${action})有车阻止变道"
                    }else{
                        appSpeakFile = "audio_solid_blocked" //实线阻止变道
                        msg = "${nowTime} 侧面(${action})实线阻止变道"
                    }
                    Log.d("LaneCmd", msg)
                    showLaneDebug(msg)
                }

                //盲区有车时减速
                if(navi_blind_dec_speed) {

                    //发送转向灯控制命令
                    if(now - lastBlindBlinkerTime >= BLIND_BLINKER_INTERVAL) {
                        lastBlindBlinkerTime = now

                        SendBlinkerControl(action)
                    }

                    val blindDecSpeedInterval = when(nav_mode){
                        1 -> 200L
                        2 -> 400L
                        3 -> 600L
                        else -> 400L
                    }

                    if (now - lastBlindDecSpeedTime >= blindDecSpeedInterval) { //每0.5秒降低1km/h的速度
                        lastBlindDecSpeedTime = now
                        blindDecSpeedEnable = true

                        //前雷达盲区和后雷达盲区
                        if((lane_change_safe == -2) || (lane_change_safe == -4)) {
                            if(!blindDecSpeedPause) {
                                if (blindDecSpeedOffset > -100) {
                                    blindDecSpeedOffset-- //速度减1
                                }

                                var msg = when(action) {
                                    LaneChangeAction.LEFT -> "${nowTime} 原车左盲区有车减速${blindDecSpeedOffset}km/h"
                                    LaneChangeAction.RIGHT -> "${nowTime} 原车右盲区有车减速${blindDecSpeedOffset}km/h"
                                    else -> "${nowTime} 原车盲区有车减速${blindDecSpeedOffset}km/h"
                                }
                                Log.d("LaneCmd", msg)
                                showLaneDebug(msg)
                            }
                        }
                        //激光雷达盲区
                        else if(lane_change_safe == -8){
                            if (action == LaneChangeAction.LEFT) {
                                if(laneLineValid && enableLaneChange && vLaneResult.leftLaneCnt >= 1){
                                    if(!blindDecSpeedPause) {
                                        if (blindDecSpeedOffset > -100) {
                                            blindDecSpeedOffset-- //速度减1
                                        }

                                        var msg = ""
                                        msg = "${nowTime} 激光左盲区有车减速${blindDecSpeedOffset}km/h"
                                        Log.d("LaneCmd", msg)
                                        showLaneDebug(msg)
                                    }
                                }
                            }
                            else if (action == LaneChangeAction.RIGHT) {
                                if(laneLineValid && enableLaneChange && ((vLaneResult.rightLaneCnt >= 1 && !bHighWay) || (vLaneResult.rightLaneCnt >= 2 && bHighWay))){
                                    if(!blindDecSpeedPause) {
                                        if (blindDecSpeedOffset > -100) {
                                            blindDecSpeedOffset-- //速度减1
                                        }

                                        var msg = ""
                                        msg = "${nowTime} 激光右盲区有车减速${blindDecSpeedOffset}km/h"
                                        Log.d("LaneCmd", msg)
                                        showLaneDebug(msg)
                                    }
                                }
                            }
                        }
                    }
                }

                laneChangeSpeak = false //清除已经发送准备变道语音的标志
                return
            }else{
                //盲区减速取消消抖1秒
                if(now - lastBlindDbTime >= 1000) {
                    blindDecSpeedEnable = false
                    blindDecSpeedPause = false
                }

                if(0L == blindSafeTime){
                    blindSafeTime = now
                    return
                }

                if(nav_mode == 2){
                    if(now - lastBlindDbTime < 300) {
                        return
                    }
                }
                else if(nav_mode == 3){
                    if(now - lastBlindDbTime < 600) {
                        return
                    }
                }
            }

            //准备变道语音提示
            if(action == LaneChangeAction.LEFT) {
                if(!laneChangeSpeak) {
                    ShowPrepareLaneChangeAction(action, edgeAction, targetIdx, visualIdx)

                    appSpeakFile = "audio_pre_lane_left"
                    laneChangeSpeak = true
                    laneSpeakTime = now
                    laneSpeakDelayTime = if(nav_mode == 3){LANE_CHANGE_SPEAK_DELAY} else if(nav_mode == 2) {LANE_CHANGE_SPEAK_DELAY*3/2} else {LANE_CHANGE_SPEAK_DELAY/2}
                }
            }else if(action == LaneChangeAction.RIGHT){
                if(!laneChangeSpeak) {
                    ShowPrepareLaneChangeAction(action, edgeAction, targetIdx, visualIdx)

                    appSpeakFile = "audio_pre_lane_right"
                    laneChangeSpeak = true
                    laneSpeakTime = now
                    laneSpeakDelayTime = if(nav_mode == 3){LANE_CHANGE_SPEAK_DELAY} else if(nav_mode == 2) {LANE_CHANGE_SPEAK_DELAY*3/2}  else {LANE_CHANGE_SPEAK_DELAY/2}
                }
            }else if(action == LaneChangeAction.LEFT_TURN) {
                if(!laneChangeSpeak) {
                    ShowPrepareLaneChangeAction(action, edgeAction, targetIdx, visualIdx)

                    appSpeakFile = "audio_turn"
                    laneChangeSpeak = true
                    laneSpeakTime = now
                    laneSpeakDelayTime = if(nav_mode == 3){LANE_TURN_DELAY} else if(nav_mode == 2) {LANE_TURN_DELAY*3/2}  else {LANE_TURN_DELAY/2}
                }
            }else if(action == LaneChangeAction.RIGHT_TURN){
                if(!laneChangeSpeak) {
                    ShowPrepareLaneChangeAction(action, edgeAction, targetIdx, visualIdx)

                    appSpeakFile = "audio_turn"
                    laneChangeSpeak = true
                    laneSpeakTime = now
                    laneSpeakDelayTime = if(nav_mode == 3){LANE_TURN_DELAY} else if(nav_mode == 2) {LANE_TURN_DELAY*3/2}  else {LANE_TURN_DELAY/2}
                }
            }

            //已经发送语音并且延时了1500ms
            if(laneChangeSpeak && ((now - laneSpeakTime >= laneSpeakDelayTime) || !navi_speak_first)) {
                //保存当前变道状态
                atc_state_start = opStateManager.state.atc_state?:LaneChangeOff
                atc_state_curr = atc_state_start

                var msg = "----------------------------------------"
                Log.d("LaneDecision", msg)
                showLaneDebug(msg)

                //首次发送命令
                sendLaneActionCmd(action)

                if(action == LaneChangeAction.LEFT || action == LaneChangeAction.RIGHT) {
                    msg = "${nowTime} 变道命令${action}已发出"
                }else if(action == LaneChangeAction.LEFT_TURN || action == LaneChangeAction.RIGHT_TURN) {
                    msg = "${nowTime} 转弯命令:${action}已发出"
                }
                Log.d("LaneCmd", msg)
                showLaneDebug(msg)

                lastCmdSendTime = now
                laneCmdSent = true
                laneAction = action
                targetLaneIdx = targetIdx
                visualLaneIdx = visualIdx
                laneCmdSentRetryCnt = 0
                lastAtcStateChangeTime = now
            }

            return
        }
        else
        {
            blindDecSpeedEnable = false
            blindDecSpeedPause = false
            blindDecSpeedOffset = 0
            blindSafeTime = 0
        }

        // 命令重发机制
        if ((laneCmdSent && now - lastCmdSendTime >= RESEND_INTERVAL) && !laneCmdExecOk) {
            val msg = "${nowTime} 第{$laneCmdSentRetryCnt}次重发命令: $laneAction"
            Log.d("LaneCmd", msg)
            showLaneDebug(msg)

            if(nav_mode <= 1){
                laneChangeCmdIndex++
            }
            sendLaneActionCmd(laneAction)
            lastCmdSendTime = now
            laneCmdSentRetryCnt++

            //控制转向灯
            SendBlinkerControl(laneAction)
        }
    }

    private fun atcStateName(atc_state : Int):String{
        val stateStr = when (atc_state) {
            LaneChangeOff -> "Off"
            PreLaneChange -> "Prepare"
            LaneChangeStarting -> "Starting"
            LaneChangeFinishing -> "Finishing"
            else -> "UNKNOWN($atc_state)"
        }

        return stateStr
    }

    private fun processLaneChangeState(atcState: Int?) {
        //变道状态为空或变道动作暂停或者未发送变道命令
        if (atcState == null || laneActionPause || !laneCmdSent) return

        val now = System.currentTimeMillis()
        val nowTime = nowTimeStr()

        //控制转向灯
        SendBlinkerControl(laneAction)

        //变道状态转文字
        val stateStr = atcStateName (atcState)

        //正在变道中...
        if (atcState == LaneChangeStarting && !inLaneChangeStarting) {
            inLaneChangeStarting = true

            val msg = "${nowTime} 变道进行中..., 状态：$stateStr"
            Log.d("LaneState", msg)
            showLaneDebug(msg)
        }else if (atcState != LaneChangeStarting){
            inLaneChangeStarting = false
        }

        // 变道完成中
        if (atcState == LaneChangeFinishing && !inLaneChangeFinishing) {
            inLaneChangeFinishing = true
            laneChangeStartTime = now

            val msg = "${nowTime} 变道完成中..., 状态：$stateStr"
            Log.d("LaneState", msg)
            showLaneDebug(msg)
        }

        // finishing → off / pre / starting → 完成
        if (inLaneChangeFinishing && (atcState == LaneChangeOff || atcState == PreLaneChange || atcState == LaneChangeStarting)) {
            appSpeakFile = "audio_lane_change_ok"
            val msg = "${nowTime} 变道已完成, 状态：$stateStr"
            Log.d("LaneState", msg)
            showLaneDebug(msg)

            /*
            val lane_change_end_delay = when (nav_mode) {
                1 -> 0
                3 -> LANE_CHANGE_END_DELAY
                else -> LANE_CHANGE_END_DELAY / 2   // nav_mode == 2
            }
             */
            val lane_change_end_delay = when (vision_lane_mode) {
                1 -> 500L
                3 -> 5000L
                else -> 2500L   // nav_mode == 2
            }
            completeLaneChange(lane_change_end_delay)
        }

        // 如果状态有变化，则重置计时
        if(atc_state_curr != atcState){
            val time_state_change = now - lastAtcStateChangeTime
            val msg = "${nowTime} 变道状态变化: ${atcStateName(atc_state_curr)} -> $stateStr, time=$time_state_change ms"
            Log.w("LaneState", msg)
            showLaneDebug(msg)

            //是否用户干预了变道
            if((atc_state_curr == PreLaneChange || atc_state_curr == LaneChangeStarting) && atcState == LaneChangeOff){
                var user_cancel_delay = USER_CALCEL_LANE_CHANGE_NORMAL_DELAY
                if(nav_mode <= 1){
                    user_cancel_delay = USER_CALCEL_LANE_CHANGE_FAST_DELAY
                }else if(nav_mode >= 3){
                    user_cancel_delay = USER_CALCEL_LANE_CHANGE_LONG_DELAY
                }
                val msg = "${nowTime} 驾驶员干预变道，变道停止，冷却时间${user_cancel_delay} ms"
                Log.d("LaneState", msg)
                showLaneDebug(msg)

                appSpeakFile = "audio_lane_change_cancel"
                completeLaneChange(user_cancel_delay)
            }else {
                //如果状态有变化，说明命令执行了
                if (!laneCmdExecOk) {
                    laneCmdExecOk = true

                    val msg = "${nowTime} 变道命令已执行，等待变道完成..."
                    Log.d("LaneState", msg)
                    showLaneDebug(msg)
                }
            }

            atc_state_curr = atcState
            lastAtcStateChangeTime = now
        }

        if(atcState == LaneChangeStarting || atc_state_curr == LaneChangeStarting){
            //如果在变道进行中或者完成中，说明命令执行了
            if (!laneCmdExecOk) {
                laneCmdExecOk = true

                val msg = "${nowTime} 变道命令已执行，等待变道完成..."
                Log.d("LaneState", msg)
                showLaneDebug(msg)
            }
        }

        // 变道状态未发生改变的时间超过指定时间后重置变道请求
        val atc_timeout = when (atcState) {
            LaneChangeOff -> 2000L
            PreLaneChange -> 2000L
            LaneChangeStarting -> 7000L
            LaneChangeFinishing -> 2000L
            else -> FINISH_TIMEOUT
        }

        val finsh_timeout = when (nav_mode) {
            1 -> atc_timeout
            3 -> atc_timeout+1000L
            else -> atc_timeout   // nav_mode == 2
        }

        if (now - lastAtcStateChangeTime >= finsh_timeout) {
            val msg = "${nowTime} 变道超时，重置变道请求! 状态：$stateStr"
            Log.w("LaneState", msg)
            showLaneDebug(msg)

            appSpeakFile = "audio_lane_change_cancel"

            val lane_change_end_delay = when (nav_mode) {
                1 -> 0
                3 -> LANE_CHANGE_END_DELAY
                else -> LANE_CHANGE_END_DELAY / 2   // nav_mode == 2
            }
            completeLaneChange(lane_change_end_delay)
        }
    }

    private fun doOvertakeAction(action: LaneChangeAction) {
        //暂停变道动作
        if(overtakeActionPause) return

        val now = System.currentTimeMillis()
        val nowTime = nowTimeStr()
        val atc_state = opStateManager.state.atc_state?:LaneChangeOff

        // 检查是否要发送变道命令(未发送过超车命令或未播报超车语音)
        if (!overtakeCmdSent || !overtakeSpeak) {
            //无变道请求，退出函数
            if (action == LaneChangeAction.KEEP){
                overtakeSpeak = false
                return
            }

            //正在变道中或者变道完成中，退出函数
            if(atc_state >= LaneChangeStarting){
                overtakeSpeak = false
                return
            }

            //盲区不安全，播报盲区有车
            val lane_change_safe = LaneChangeSafetyCheck(action)
            if(lane_change_safe < 0){
                if(now - lastOvertakeBlindSpeakTime >= BLIND_SPEAK_INTERVAL){ //定时播报盲区有车
                    lastOvertakeBlindSpeakTime = now
                    appSpeakFile = "audio_car_watchout" //盲区有车

                    var msg = ""
                    if(lane_change_safe < -1){
                        msg = "${nowTime} 侧面(${action})有车阻止超车"
                    }else{
                        msg = "${nowTime} 侧面(${action})实线阻止超车"
                    }
                    Log.d("LaneCmd", msg)
                    showLaneDebug(msg)
                }

                overtakeSpeak = false //清除已经发送准备变道语音的标志
                return
            }

            //已经发送语音并且延时了1500ms
            if(overtakeSpeak && ((now - overtakeSpeakTime >= overtakeSpeakDelayTime) || overtake_mode == 1)) {
                //保存当前变道状态
                overtake_atc_state_start = opStateManager.state.atc_state?:LaneChangeOff
                overtake_atc_state_curr = overtake_atc_state_start

                var msg = "----------------------------------------"
                Log.d("OvertakeCmd", msg)
                showLaneDebug(msg)

                //车道信息显示
                val navPart = if (!vNavLaneText.isNullOrBlank()) {
                    "导航$vNavLaneText, "
                } else {
                    ""
                }
                msg = "${nowTime} 车道: ${navPart}视觉$vLaneText, 超车动作=$action"
                Log.d("OvertakeCmd", msg)
                showLaneDebug(msg)

                if(action == LaneChangeAction.LEFT || action == LaneChangeAction.RIGHT) {
                    msg = "${nowTime} 准备发送超车命令: $action"
                    Log.d("OvertakeCmd", msg)
                    showLaneDebug(msg)
                }

                //首次发送命令
                sendOvertakeActionCmd(action)

                lastOvertakeCmdSendTime = now
                overtakeCmdSent = true
                overtakeAction = action
                overtakeCmdSentRetryCnt = 0
                lastOvertakeAtcStateChangeTime = now
            }

            //准备变道语音提示
            if(action == LaneChangeAction.LEFT) {
                if(!overtakeSpeak) {
                    appSpeakFile = "audio_pre_lane_left"
                    overtakeSpeak = true
                    overtakeSpeakTime = now
                    overtakeSpeakDelayTime = if(overtake_mode == 3){LANE_CHANGE_SPEAK_DELAY} else {LANE_CHANGE_SPEAK_DELAY/2}
                }
            }else if(action == LaneChangeAction.RIGHT){
                if(!overtakeSpeak) {
                    appSpeakFile = "audio_pre_lane_right"
                    overtakeSpeak = true
                    overtakeSpeakTime = now
                    overtakeSpeakDelayTime = if(overtake_mode == 3){LANE_CHANGE_SPEAK_DELAY} else {LANE_CHANGE_SPEAK_DELAY/2}
                }
            }

            return
        }

        // 命令重发机制
        if ((overtakeCmdSent && now - lastOvertakeCmdSendTime >= RESEND_INTERVAL) && !overtakeCmdExecOk) {
            val msg = "${nowTime} 第{$overtakeCmdSentRetryCnt}次重发命令: $overtakeAction"
            Log.d("OvertakeCmd", msg)
            showLaneDebug(msg)

            if(overtake_mode <= 1){
                overtakeCmdIndex++
            }
            sendOvertakeActionCmd(overtakeAction)
            lastOvertakeCmdSendTime = now
            overtakeCmdSentRetryCnt++
        }
    }

    private fun resetOvertakeAction(){
        if(overtakeCmdSent){
            overtakeCmdIndex++
        }

        overtakeCmdSent = false
        overtakeCmdExecOk = false
        overtakeCmdSentRetryCnt = 0
        overtakeSpeak = false
        overtakeAction = LaneChangeAction.KEEP
        overtakeActionPause = false
        overtakeActionPausePrev = false
        inOvertakeStarting = false
        inOvertakeFinishing = false

        val now = System.currentTimeMillis()
        lastOvertakeCmdSendTime = now //上次发送变道命令的时间
        lastOvertakeBlindSpeakTime = now //上次播报盲区有车语音的时间
        overtakeSpeakTime = now //播报准备变道的时间点
        overtakeStartTime = now //变道进行中的时间
        lastOvertakeAtcStateChangeTime = now //最后一次ATC状态变化的时间
        overtakeSpeakDelayTime = if(overtake_mode == 3){LANE_CHANGE_SPEAK_DELAY} else {LANE_CHANGE_SPEAK_DELAY/2}
    }

    //完成状态处理
    private fun completeOvertake(delay_time: Long) {
        if(delay_time > 0) {
            overtakeActionPause = true

            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                //清除状态和标志
                resetOvertakeAction()

                val nowTime = nowTimeStr()
                val msg = "${nowTime} 清除所有超车标志, index++, Action=$overtakeAction"
                Log.d("Overtake", msg)
                showLaneDebug(msg)
            }, delay_time)
        }else{
            //清除状态和标志
            resetOvertakeAction()

            val nowTime = nowTimeStr()
            val msg = "${nowTime} 清除所有超车标志, index++, Action=$overtakeAction"
            Log.d("Overtake", msg)
            showLaneDebug(msg)
        }
    }

    private fun processOvertakeLaneChangeState(atcState: Int?) {
        //变道状态为空或变道动作暂停或者未发送变道命令
        if (atcState == null || overtakeActionPause || !overtakeCmdSent) return

        val now = System.currentTimeMillis()
        val nowTime = nowTimeStr()

        //变道状态转文字
        val stateStr = atcStateName (atcState)

        //正在变道中...
        if (atcState == LaneChangeStarting && !inOvertakeStarting) {
            inOvertakeStarting = true

            val msg = "${nowTime} 超车变道进行中..., 状态：$stateStr"
            Log.d("Overtake", msg)
            showLaneDebug(msg)
        }else if (atcState != LaneChangeStarting){
            inOvertakeStarting = false
        }

        // 变道完成中
        if (atcState == LaneChangeFinishing && !inOvertakeFinishing) {
            inOvertakeFinishing = true
            overtakeStartTime = now

            val msg = "${nowTime} 超车变道完成中..., 状态：$stateStr"
            Log.d("Overtake", msg)
            showLaneDebug(msg)
        }

        // finishing → off / pre / starting → 完成
        if (inOvertakeFinishing && (atcState == LaneChangeOff || atcState == PreLaneChange || atcState == LaneChangeStarting)) {
            appSpeakFile = "audio_lane_change_ok"
            val msg = "${nowTime} 超车变道已完成, 状态：$stateStr"
            Log.d("Overtake", msg)
            showLaneDebug(msg)

            val lane_change_end_delay = when (nav_mode) {
                1 -> 0
                3 -> OVERTAKE_END_DELAY
                else -> OVERTAKE_END_DELAY / 2   // nav_mode == 2
            }
            completeOvertake(lane_change_end_delay)
        }

        // 如果状态有变化，则重置计时
        if(atc_state_curr != atcState){
            val time_state_change = now - lastOvertakeAtcStateChangeTime
            val msg = "${nowTime} 超车变道状态变化: ${atcStateName(overtake_atc_state_curr)} -> $stateStr, time=$time_state_change ms"
            Log.w("Overtake", msg)
            showLaneDebug(msg)

            //是否用户干预了变道
            if((overtake_atc_state_curr == PreLaneChange || overtake_atc_state_curr == LaneChangeStarting) && atcState == LaneChangeOff){
                val msg = "${nowTime} 驾驶员干预超车，变道停止，冷却时间${USER_CALCEL_OVERTAKE_DELAY} ms"
                Log.d("LaneState", msg)
                showLaneDebug(msg)

                appSpeakFile = "audio_lane_change_cancel"
                completeOvertake(USER_CALCEL_OVERTAKE_DELAY)
            }else {
                //如果状态有变化，说明命令执行了
                if (!overtakeCmdExecOk) {
                    overtakeCmdExecOk = true

                    val msg = "${nowTime} 超车变道命令已执行，等待变道完成..."
                    Log.d("Overtake", msg)
                    showLaneDebug(msg)
                }
            }

            overtake_atc_state_curr = atcState
            lastOvertakeAtcStateChangeTime = now
        }

        // 变道状态未发生改变的时间超过指定时间后重置变道请求
        if (now - lastOvertakeAtcStateChangeTime >= OVERTAKE_FINISH_TIMEOUT) {
            val msg = "${nowTime} 超车命令超时，重置变道请求! 状态：$stateStr"
            Log.w("Overtake", msg)
            showLaneDebug(msg)

            appSpeakFile = "audio_lane_change_cancel"

            val lane_change_end_delay = when (overtake_mode) {
                1 -> 0
                3 -> LANE_CHANGE_END_DELAY
                else -> LANE_CHANGE_END_DELAY / 2   // nav_mode == 2
            }
            completeOvertake(lane_change_end_delay)
        }
    }

    //重置变道动作
    private fun resetLaneAction(){
        if(laneCmdSent){
            laneChangeCmdIndex++
        }

        laneCmdSent = false
        laneCmdExecOk = false
        laneCmdSentRetryCnt = 0
        laneChangeSpeak = false
        laneAction = LaneChangeAction.KEEP
        visualLaneIdx = -1
        targetLaneIdx = -1
        lastTargetIdx = -1
        laneActionPause = false
        inLaneChangeStarting = false
        inLaneChangeFinishing = false

        val now = System.currentTimeMillis()
        lastCmdSendTime = now //上次发送变道命令的时间
        lastBlindSpeakTime = now //上次播报盲区有车语音的时间
        lastBlindDecSpeedTime = now
        laneSpeakTime = now //播报准备变道的时间点s
        laneChangeStartTime = now //变道进行中的时间
        lastAtcStateChangeTime = now //最后一次ATC状态变化的时间
        laneSpeakDelayTime = if(nav_mode == 3){LANE_CHANGE_SPEAK_DELAY} else {LANE_CHANGE_SPEAK_DELAY/2}

        //validLaneActionDbTime = now
        //Log.d("LaneDecision", "resetLaneAction, ${validLaneActionDbTime}")
    }

    //完成状态处理
    private fun completeLaneChange(delay_time: Long) {
        //控制转向灯
        SendBlinkerControl(LaneChangeAction.KEEP)

        if(delay_time > 0) {
            laneActionPause = true

            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                //清除状态和标志
                resetLaneAction()

                val nowTime = nowTimeStr()
                val msg = "${nowTime} 清除所有变道标志, index++, laneAction=$laneAction"
                Log.d("LaneState", msg)
                showLaneDebug(msg)
            }, delay_time)
        }else{
            //清除状态和标志
            resetLaneAction()

            val nowTime = nowTimeStr()
            val msg = "${nowTime} 清除所有变道标志, index++, laneAction=$laneAction"
            Log.d("LaneState", msg)
            showLaneDebug(msg)
        }
    }

    // -----------------------------
    // UDP 接收到数据处理函数
    // -----------------------------
    private fun handleNaviUdpJson(json: JSONObject, packetAddress: String, packetPort: Int) {
        val device = json.optString("device", "")
        val resp = json.optString("resp", "")
        if(resp == "") {
            naviSrvIp = json.optString("ip", packetAddress)
            if(device == "navi") {
                naviSrvPort = json.optInt("port", packetPort)
            }
        }

        //Op数据处理
        val hasIsOnroad = json.has("IsOnroad")
        if (hasIsOnroad && device == "op") {
            //更新OP状态
            opStateManager.updateFromJson(json)
            //视觉车道检测
            vLaneResult = opStateManager.visionLaneCalc(vision_lane_mode, navi_lane_cnt, bHighWay || road_median)
        }
    }

    private fun handleLaneUdpJson(json: JSONObject, packetAddress: String, packetPort: Int) {
        val device = json.optString("device", "")
        val resp = json.optString("resp", "")
        if(resp == "") {
            laneSrvIp = json.optString("ip", packetAddress)
            if(device == "lane") {
                laneSrvPort = json.optInt("port", packetPort)
            }
            laneSrvValid = true
        }

        val str_echo_cmd = json.optString("echo_cmd", "")
        if(str_echo_cmd != ""){
            val echo_cmd_result = json.optString("result", "")
            if(echo_cmd_result != ""){
                setRawLogVisible(true)
                updateRawLog(str_echo_cmd, true)
                updateRawLog(echo_cmd_result, true)
            }
        }
    }

    private fun handleCarrotManUdpJson(json: JSONObject, packetAddress: String, packetPort: Int) {
        val device = json.optString("device", "")
        val resp = json.optString("resp", "")
        if (resp == "") {
            carrotManIp = json.optString("ip", packetAddress)
            if(device == "" || device == "navi") {
                carrotManPort = json.optInt("port", packetPort)
            }
            carrotManValid = true
        }

        val auth = json.optBoolean("auth", true)
        val device_id = json.optString("device_id", "")

        // 验证不通过，退出软件
        if (!auth && device_id == Global.deviceId) {
            runOnUiThread {
                finishAffinity() // 安全退出
            }
            return
        }

        val hasIsOnroad = json.has("IsOnroad")

        if (resp == "app_param") {
            lane_detect_enable = json.optInt("lane_detect", 0)
            lane_show_enable = json.optInt("lane_show", 0)
        } else if (hasIsOnroad && device == "") {
            // -----------------------------
            // 解析车辆状态数据
            // -----------------------------
            val isOnroad = json.optBoolean("IsOnroad", false)
            val vCruise = json.optInt("v_cruise_kph", 0)
            val vEgo = json.optInt("v_ego_kph", 0)
            val active = json.optBoolean("active", false)
            xState = json.optInt("xState", -1)
            xTrafficState = json.optInt("trafficState", -1)

            carState.isOnroad = isOnroad
            carState.vCruise = vCruise
            carState.vEgo = vEgo
            carState.active = active
            carState.xState = xState
            carState.xTrafficState = xTrafficState

            // 摄像头编号变化处理
            if (bCameraIdChange) {
                bCameraIdChange = false
                nTrafficSpeedLimit = -1
                nTrafficSpeedLimitChange = true
                xTrafficRedState = false
                xTrafficRedStateTime = System.currentTimeMillis()
            }

            // 红灯停止信号处理
            if (navi_traffic_assist && naviState.roadcate !in 0..1) {
                naviTrafficAssistLast = navi_traffic_assist

                if ((xState == e2eStop && xTrafficState == traffic_red && cp_traffic_assist) ||
                    (nTrafficCnt > 0 && nTrafficLightStatus == red_light && redlight_traffic_assist)
                ) {
                    xTrafficRedState = true
                    xTrafficRedStateTime = System.currentTimeMillis()

                    nTrafficSpeedLimit = if (xState == e2eStop && xTrafficState == traffic_red && cp_traffic_assist) {
                        cp_min_speed_limit
                    } else {
                        red_light_min_speed_limit
                    }
                    nTrafficSpeedLimitChange = true
                }

                // 超时处理
                val now = System.currentTimeMillis()
                val elapsed = now - xTrafficRedStateTime
                xTrafficRedStateTimeElapsed = (navi_traffic_assist_time - (elapsed / 1000))
                if (elapsed > navi_traffic_assist_time * 1000) {
                    nTrafficSpeedLimit = -1
                    xTrafficRedStateTimeElapsed = -1
                    nTrafficSpeedLimitChange = true
                    xTrafficRedState = false
                    xTrafficRedStateTime = System.currentTimeMillis()
                }
            } else {
                nTrafficSpeedLimit = -1
                xTrafficRedStateTimeElapsed = -1
                if (naviTrafficAssistLast) {
                    nTrafficSpeedLimitChange = true
                }
                naviTrafficAssistLast = navi_traffic_assist
            }
        }
    }

    private fun hideLaneView() {
        imageViewLayoutShowEnable = false
        imageViewLayout.visibility = View.GONE
    }

    private fun showLaneView() {
        imageViewLayoutShowEnable = true
    }

    private var naviDataTimeout = true
    private var lastNaviReceiveTime = 0L
    private var lastUdpSendTime = 0L
    private var lastTrafficRecvTime = 0L
    private var timeoutJob: Job? = null
    private var isTimeout = false

    private fun startListening() {
        val filter = IntentFilter().apply {
            addAction("AUTONAVI_STANDARD_BROADCAST_SEND")
            addAction("AUTONAVI_STANDARD_BROADCAST_RECV")
        }

        if (receiver != null) return // 防重复注册

        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent ?: return
                val rawData = mutableMapOf<String, Any?>()
                rawData["action"] = intent.action
                val extras = mutableMapOf<String, Any?>()
                intent.extras?.keySet()?.forEach { key ->
                    extras[key] = intent.extras?.get(key)
                }

                // === 新增：仅当 KEY_TYPE == 10001 时才更新和发送 ===
                val keyType = extras["KEY_TYPE"]?.toString()?.toIntOrNull()

                //红绿灯消息
                if (keyType == 60073){
                    updateTrafficState(extras)
                    lastTrafficRecvTime = System.currentTimeMillis()
                }

                //导航消息
                if (keyType == 10001) {
                    val (changed, jsonStr) = updateNaviState(extras)
                    if (changed && jsonStr != null) {
                        lastJsonToSend = jsonStr
                        //cmUdpService.send(jsonStr)
                        cmUdpServiceSend(jsonStr, port=carrotManOpPort)
                        lastUdpSendTime = System.currentTimeMillis()
                        updateRawLog(jsonStr, bShowRawLog)
                    }

                    lastNaviReceiveTime = System.currentTimeMillis()
                    naviDataTimeout = false

                    //接收到高德导航广播后才启动UDP通讯
                    if(0 == navDataCount){
                        //cmUdpService.start()
                        cmUdpServiceSendEnable = true
                        keepAliveService?.cmUdpService?.sendEnable = true
                    }

                    //广播消息计数
                    navDataCount++
                    if (navDataCount >= 100) {
                        navDataCount = 1
                    }
                }

                //日志显示
                rawData["extras"] = extras
                val text = formatAmapData(rawData)
                updateRawLog(text, bShowRawLog)

                //UI界面显示
                lifecycleScope.launch(Dispatchers.Main) {
                    tvNavData.text = "${getRoadTypeDesc(naviState.roadcate)}," +
                                     "限速:${nRoadLimitSpeed ?: "-"}->${nRoadLimitSpeedRamp}(${naviState.nRoadLimitSpeed ?: "-"})(上次:${nRoadLimitSpeedLast ?: "-"})," +
                                     "${getTurnIconDesc(nTBTTurnTypeMain)}(${nTBTTurnTypeMain}->${naviState.nTBTTurnType})," +
                                     "距离:${naviState.nTBTDist ?: "-"}"

                    tvNavInfo.text = "${getTurnIconDesc(nTBTTurnTypeMain)}"
                    var now = System.currentTimeMillis()
                    if(now - lastUpdateTvDistanceTime > 1000) {
                        tvDistance.text = "${naviState.nTBTDist ?: "-"}m"
                    }
                    var limit_speed = naviState.nRoadLimitSpeed?:0
                    if(limit_speed < 0) limit_speed = 0
                    tvSpeedRight.text = "${limit_speed}"

                    if (keepAliveService?.isCmAvtive() == true) {
                        val navStr = String.format("%02d", navDataCount ?: 0)
                        if (udpStateShow.isEmpty()) {
                            tvUdpStatus.text = "高德:$navStr"
                        } else {
                            tvUdpStatus.text = "$udpStateShow,高德:$navStr"
                        }
                    }
                }
            }
        }

        registerReceiver(receiver, filter)

        // 启动定时检测超时
        startTimeoutChecker()

        // 启动正常循环发送任务
        //startSendJob()

        //startSendingDeviceId()
    }

    private var lastTravelTime = System.currentTimeMillis()
    private var travelDistTimeValid = false

    private fun travelDistProcess(){
        val state = opStateManager.state

        val now = System.currentTimeMillis()
        val elapsed_time = now - lastTravelTime
        lastTravelTime = now

        if(travelDistTimeValid) {
            //计算距离路口的距离
            var v_ego = state.vEgo ?: 0
            if (state.xTurnInfo >= 0 && v_ego > 0 && state.nTurnDist >= 0) {
                val elapsed_dist = elapsed_time * v_ego / 3600.0f //车辆经过的距离, v_ego单位为km/h
                if (state.xTurnDist > -200) {
                    state.xTurnDist -= elapsed_dist
                    Log.d("Navi", String.format("xTurnDist: %.1f m", state.xTurnDist))
                }
            }
        }
        travelDistTimeValid = true
    }

    //平滑道路限速
    private fun smoothSpeedLimit(){
        val now = System.currentTimeMillis()
        //限速爬坡
        if(smooth_speed_limit) {
            if (naviState.nRoadLimitSpeed != null) {
                var nRoadLimitSpeedTmp = naviState.nRoadLimitSpeed ?: -1
                if ((nRoadLimitSpeedRamp != nRoadLimitSpeedTmp) || (nRoadLimitSpeedRampCnt > 0)) { //速度未爬坡完成或者还有发送次数
                    //发送次数递减
                    if((nRoadLimitSpeedRamp == nRoadLimitSpeedTmp) && (nRoadLimitSpeedRampCnt > 0)){
                        nRoadLimitSpeedRampCnt--
                    }

                    //限速爬坡
                    if (nRoadLimitSpeedRamp >= 0) {
                        if (nRoadLimitSpeedRamp > nRoadLimitSpeedTmp) {
                            nRoadLimitSpeedTmp++
                        } else if (nRoadLimitSpeedRamp < nRoadLimitSpeedTmp) {
                            nRoadLimitSpeedTmp--
                        }
                        naviState.nRoadLimitSpeed = nRoadLimitSpeedTmp
                    } else {
                        naviState.nRoadLimitSpeed = nRoadLimitSpeedRamp
                    }

                    //发送json
                    if(cmUdpServiceSendEnable) {
                        val jsonStr = naviState.toJson()
                        lastJsonToSend = jsonStr
                        cmUdpServiceSend(jsonStr, port = carrotManOpPort)

                        lastUdpSendTime = now
                        //updateRawLog(jsonStr, bShowRawLog)
                    }
                }
            }
        }
    }

    private var lastLaneValidTime = System.currentTimeMillis()
    private var prevlaneDetectCnt = -1
    private var lastXTurnInfo = -1
    private var lastNTurnDistMax = -1

    private fun laneLineProcess() : Boolean{
        val now = System.currentTimeMillis()
        if(laneDetectCnt == 0){
            return false
        }
        //首次检测
        if(prevlaneDetectCnt < 0){
            prevlaneDetectCnt = laneDetectCnt
            lastLaneValidTime = now
            return true
        }
        //超时1秒后检测次数没有变化
        if(((now - lastLaneValidTime) > 1000L) && (prevlaneDetectCnt == laneDetectCnt)){
            return false
        }
        //检测次数变化，说明车道线检测正在工作
        if(prevlaneDetectCnt != laneDetectCnt){
            lastLaneValidTime = now
            prevlaneDetectCnt = laneDetectCnt
        }

        return true
    }

    val HIGHWAY_LANE_LINE_TRIG_DIST_OFFSET = 20 //高速虚实线触发进匝道提前距离

    private fun AmapNavi(lane_line_valid : Boolean) : Boolean{
        val now = System.currentTimeMillis()
        val nowTime = nowTimeStr()

        var laneChangeEdge: LaneChangeAction = LaneChangeAction.KEEP
        var action = LaneChangeAction.KEEP
        py_overtake_disable = false

        //检查是否要关闭超车功能
        if((!bHighWay && app_road_navi) || (bHighWay && app_navi)) {
            isStopPyOvertake(navLane)
        }

        if(!navi_blind_dec_speed) {
            blindDecSpeedEnable = false
            blindDecSpeedPause = false
            blindDecSpeedOffset = 0
        }

        //导航处理
        if(opStateManager.state.lidar_l && opStateManager.state.lidar_r && //激光雷达在线
            !naviDataTimeout && opStateManager.state.engaged && ((!bHighWay && app_road_navi) || (bHighWay && app_navi))) { //开启了APP导航和开启了op定速，并且开启了导航
            if (carState.vEgo > (navi_min_speed/3.6)) { //车辆非静止状态
                if(app_navi_valid != 1) {
                    app_navi_valid = 1
                    val msg = "${nowTime} 领航功能正常工作中"
                    Log.d("LaneState", msg)
                    showLaneDebug(msg)

                    atcInfoShow = 0
                }

                var doForkNow = false

                //检查引导类型和引导距离是否有变化(有来设置各种重置标志)
                if((lastXTurnInfo != opStateManager.state.xTurnInfo) || (lastNTurnDistMax != opStateManager.state.nTurnDistMax)){
                    resetSuggestLaneTimer = true
                    enableLaneChange = true
                    enableLaneChangeTimer = now
                    disableLaneChangeTimer = now
                }

                //检查是否要靠边行驶或转弯
                if(opStateManager.state.xTurnInfo >= 0){

                    //引导类型有变化，重置变道操作
                    if(lastXTurnInfo != opStateManager.state.xTurnInfo){
                        val msg = "${nowTime} 导航(${getxTurnInfoDesc(opStateManager.state.xTurnInfo)}->${getxTurnInfoDesc(lastXTurnInfo)}), ${opStateManager.state.xTurnDist}米，重置状态"
                        Log.d("LaneState", msg)
                        showLaneDebug(msg)

                        resetLaneAction()
                    }else if(lastNTurnDistMax != opStateManager.state.nTurnDistMax){
                        val msg = "${nowTime} 导航(${getxTurnInfoDesc(opStateManager.state.xTurnInfo)}),${lastNTurnDistMax}m->${opStateManager.state.nTurnDistMax}m，重置状态"
                        Log.d("LaneState", msg)
                        showLaneDebug(msg)

                        resetLaneAction()
                    }

                    //------------------------------------------------------------------------------------------------------------
                    //左变道或者左转
                    if(opStateManager.state.xTurnInfo == TurnInfo_ForkLeft || opStateManager.state.xTurnInfo == TurnInfo_TurnLeft) {
                        //--------------------高速---------------------
                        if (bHighWay) { //高速
                            //重置禁止变道相关变量
                            enableLaneChange = true
                            disableLaneChangeTimer = now
                            enableLaneChangeTimer = now

                            var atc_dist_h_tmp = atc_dist_h
                            //设置的变道距离比导航引导的最大距离还大(设置自动变道靠边距离为80%)
                            if(opStateManager.state.nTurnDistMax > 0 && atc_dist_h_tmp > opStateManager.state.nTurnDistMax*0.8f){
                                atc_dist_h_tmp = (opStateManager.state.nTurnDistMax*0.8f).toInt()
                                if(atc_dist_h_tmp < fork_dist_h){
                                    atc_dist_h_tmp = fork_dist_h
                                }
                            }

                            //距离路口的距离小于设定的靠左行驶的距离(打方向盘靠边行驶)
                            if (fork_dist_h > 0 && opStateManager.state.xTurnDist > fork_dist_h && opStateManager.state.xTurnDist <= atc_dist_h_tmp) {
                                if(keep_left_en) {
                                    laneChangeEdge = LaneChangeAction.LEFT
                                    py_overtake_disable = true
                                    py_overtake_disable_reason = "准备靠左行驶"

                                    if (1 != atcInfoShow) {
                                        atcInfoShow = 1
                                        atcInfoChange = true
                                        val msg =
                                            "${nowTime} 高速:路口${opStateManager.state.xTurnDist.toInt()}米，小于${atc_dist_h}米，允许靠左行驶"
                                        Log.d("LaneState", msg)
                                        showLaneDebug(msg)
                                    }
                                }
                            }
                            //距离路口的距离小于设定的进左路口的距离(打方向盘进路口)
                            else if(fork_dist_h > 0 && opStateManager.state.xTurnDist < fork_dist_h && opStateManager.state.xTurnInfo == TurnInfo_ForkLeft){
                                laneChangeEdge = LaneChangeAction.LEFT
                                opStateManager.state.doLaneForkNow = LaneChangeAction.LEFT.value //立即左变道请求
                                doForkNow = true
                                py_overtake_disable = true
                                py_overtake_disable_reason = "准备进左岔路"

                                if (2 != atcInfoShow) {
                                    atcInfoShow = 2
                                    atcInfoChange = true
                                    val msg = "${nowTime} 高速:路口${opStateManager.state.xTurnDist.toInt()}米，小于${fork_dist_h}米，向左进岔路"
                                    Log.d("LaneState", msg)
                                    showLaneDebug(msg)
                                }
                            }else{
                                atcInfoShow = 0
                            }
                        }
                        //--------------------低速---------------------
                        else
                        {
                            //检查车辆是否已靠边(开启了车道线检测)
                            if(enableLaneChange)
                            {
                                if(lane_line_valid && left_lane_type >= 1) //左侧为实线
                                {
                                    if((now - disableLaneChangeTimer) > 5000){ //实线检测时间超过5秒
                                        enableLaneChange = false //禁止继续往左变道
                                        enableLaneChangeTimer = now

                                        appSpeakFile = "audio_last_lane" //车辆已靠边
                                        var msg = ""
                                        msg = "${nowTime} 车辆已靠最左边"
                                        Log.d("LaneCmd", msg)
                                        showLaneDebug(msg)
                                    }
                                }
                                else{
                                    disableLaneChangeTimer = now
                                }
                            }
                            else
                            {
                                if(lane_line_valid && left_lane_type < 1) //左侧为虚线或无车道线
                                {
                                    val threshold = when {
                                        nav_mode >= 3 -> 5000
                                        nav_mode == 2 -> 2000
                                        else -> 1000
                                    }

                                    if ((now - enableLaneChangeTimer) > threshold) //虚线检测时间超过指定时间
                                    {
                                        enableLaneChange = true //允许继续往左变道
                                        disableLaneChangeTimer = now

                                        appSpeakFile = "audio_new_lane" //出现新车道
                                        var msg = ""
                                        msg = "${nowTime} 车辆左侧出现新车道"
                                        Log.d("LaneCmd", msg)
                                        showLaneDebug(msg)
                                    }
                                }else{
                                    enableLaneChangeTimer = now
                                }
                            }

                            var atc_dist_l_tmp = atc_dist_l
                            //设置的变道距离比导航引导的最大距离还大(设置自动变道靠边距离为80%)
                            if(opStateManager.state.nTurnDistMax > 0 && atc_dist_l_tmp > opStateManager.state.nTurnDistMax*0.8f){
                                atc_dist_l_tmp = (opStateManager.state.nTurnDistMax*0.8f).toInt()
                                if(atc_dist_l_tmp < fork_dist_l){
                                    atc_dist_l_tmp = fork_dist_l
                                }
                            }

                            //距离路口的距离小于设定的靠左行驶的距离(打方向盘靠边行驶)
                            if (fork_dist_l > 0 && opStateManager.state.xTurnDist >= fork_dist_l && opStateManager.state.xTurnDist <= atc_dist_l_tmp){
                                if(keep_left_en) {
                                    laneChangeEdge = LaneChangeAction.LEFT
                                    py_overtake_disable = true
                                    py_overtake_disable_reason = "准备靠左行驶"

                                    if (1 != atcInfoShow) {
                                        atcInfoShow = 1
                                        atcInfoChange = true
                                        val msg =
                                            "${nowTime} 公路:路口${opStateManager.state.xTurnDist.toInt()}米，小于${atc_dist_l}米，允许靠左行驶"
                                        Log.d("LaneState", msg)
                                        showLaneDebug(msg)
                                    }
                                }
                            }
                            //距离路口的距离小于设定的左转弯的距离(打方向盘进路口)
                            else if(fork_dist_l > 0 && opStateManager.state.xTurnDist < fork_dist_l && opStateManager.state.xTurnInfo == TurnInfo_TurnLeft && nav_turn){
                                laneChangeEdge = LaneChangeAction.LEFT_TURN
                                py_overtake_disable = true
                                py_overtake_disable_reason = "准备向左转弯"

                                if (2 != atcInfoShow) {
                                    atcInfoShow = 2
                                    atcInfoChange = true
                                    val msg = "${nowTime} 公路:路口${opStateManager.state.xTurnDist.toInt()}米，小于${fork_dist_l}米，向左转弯"
                                    Log.d("LaneState", msg)
                                    showLaneDebug(msg)
                                }
                            }else{
                                atcInfoShow = 0
                            }
                        }
                    }
                    //------------------------------------------------------------------------------------------------------------
                    //右变道或者右转
                    else if(opStateManager.state.xTurnInfo == TurnInfo_ForkRight || opStateManager.state.xTurnInfo == TurnInfo_TurnRight) {
                        //---------------------高速--------------------
                        if (bHighWay) //高速
                        {
                            var atc_dist_h_tmp = atc_dist_h
                            //设置的变道距离比导航引导的最大距离还大(设置自动变道靠边距离为80%)
                            if(opStateManager.state.nTurnDistMax > 0 && atc_dist_h_tmp > opStateManager.state.nTurnDistMax*0.8f){
                                atc_dist_h_tmp = (opStateManager.state.nTurnDistMax*0.8f).toInt()
                                if(atc_dist_h_tmp < fork_dist_h){
                                    atc_dist_h_tmp = fork_dist_h
                                }
                            }

                            //进入提前变道靠边范围或存在禁止车道时
                            if ( (fork_dist_h > 0 && opStateManager.state.xTurnDist >= fork_dist_h && opStateManager.state.xTurnDist <= atc_dist_h_tmp)
                                    || (haveNavDisLane(navLane) && dis_lane_en)
                            )
                            {
                                //检查车辆是否已靠边(开启了车道线检测)
                                if (enableLaneChange) {
                                    if (lane_line_valid && right_lane_type >= 1 && vLaneResult.rightLaneCnt <= 1) //右侧为实线并且右侧车道数小于等于1
                                    {
                                        if ((now - disableLaneChangeTimer) > 5000) { //实线检测时间超过5秒
                                            enableLaneChange = false //禁止继续往右变道
                                            enableLaneChangeTimer = now

                                            appSpeakFile = "audio_last_lane" //车辆已靠边
                                            var msg = ""
                                            msg = "${nowTime} 车辆已靠最右边"
                                            Log.d("LaneCmd", msg)
                                            showLaneDebug(msg)
                                        }
                                    } else {
                                        disableLaneChangeTimer = now
                                    }
                                } else {
                                    if (lane_line_valid && right_lane_type < 1) //右侧为虚线或无车道线
                                    {
                                        val threshold = when {
                                            nav_mode >= 3 -> 5000
                                            nav_mode == 2 -> 2000
                                            else -> 1000
                                        }

                                        if ((now - enableLaneChangeTimer) > threshold) //虚线检测时间超过指定时间
                                        {
                                            enableLaneChange = true //允许继续往右变道
                                            disableLaneChangeTimer = now

                                            appSpeakFile = "audio_new_lane" //出现新车道
                                            var msg = ""
                                            msg = "${nowTime} 车辆右侧出现新车道"
                                            Log.d("LaneCmd", msg)
                                            showLaneDebug(msg)
                                        }
                                    } else {
                                        enableLaneChangeTimer = now
                                    }
                                }
                            }
                            else{
                                //重置禁止变道相关变量
                                enableLaneChange = true
                                disableLaneChangeTimer = now
                                enableLaneChangeTimer = now
                            }

                            //距离路口的距离小于设定的靠右行驶的距离(打方向盘靠边行驶)
                            if (fork_dist_h > 0 && opStateManager.state.xTurnDist >= fork_dist_h && opStateManager.state.xTurnDist <= atc_dist_h_tmp){
                                //已在进匝道的检测距离范围内(检测是否出现虚线)
                                //if(lane_line_valid && opStateManager.state.xTurnDist < (fork_dist_h + HIGHWAY_LANE_LINE_TRIG_DIST_OFFSET))
                                if(false)
                                {
                                    //右侧为虚线，变道
                                    if(right_lane_type == 0){
                                        opStateManager.state.doLaneForkNow = LaneChangeAction.RIGHT.value //立即右变道请求
                                        doForkNow = true
                                        py_overtake_disable = true
                                        py_overtake_disable_reason = "准备向右变道"

                                        if (3 != atcInfoShow) {
                                            atcInfoShow = 3
                                            atcInfoChange = true
                                            val msg = "${nowTime} 高速:路口${opStateManager.state.xTurnDist.toInt()}米，检测到虚线，立即向右变道"
                                            Log.d("LaneState", msg)
                                            showLaneDebug(msg)
                                        }
                                    }
                                }
                                else
                                {
                                    if(keep_right_en) {
                                        laneChangeEdge = LaneChangeAction.RIGHT
                                        py_overtake_disable = true
                                        py_overtake_disable_reason = "准备靠右行驶"

                                        if (1 != atcInfoShow) {
                                            atcInfoShow = 1
                                            atcInfoChange = true
                                            val msg =
                                                "${nowTime} 高速:路口${opStateManager.state.xTurnDist.toInt()}米，小于${atc_dist_h}米，允许靠右行驶"
                                            Log.d("LaneState", msg)
                                            showLaneDebug(msg)
                                        }
                                    }
                                }
                            }
                            //检查是否需要变道进岔路
                            else if(fork_dist_h > 0 && opStateManager.state.xTurnDist < fork_dist_h && opStateManager.state.xTurnInfo == TurnInfo_ForkRight){
                                laneChangeEdge = LaneChangeAction.RIGHT
                                opStateManager.state.doLaneForkNow = LaneChangeAction.RIGHT.value //立即右变道请求
                                doForkNow = true
                                py_overtake_disable = true
                                py_overtake_disable_reason = "准备进右岔路"

                                if (2 != atcInfoShow) {
                                    atcInfoShow = 2
                                    atcInfoChange = true
                                    val msg = "${nowTime} 高速:路口${opStateManager.state.xTurnDist.toInt()}米，小于${fork_dist_h}米，向右进岔路"
                                    Log.d("LaneState", msg)
                                    showLaneDebug(msg)
                                }
                            }else{
                                atcInfoShow = 0
                            }
                        }
                        //--------------------低速---------------------
                        else {
                            var atc_dist_l_tmp = atc_dist_l
                            //设置的变道距离比导航引导的最大距离还大
                            if(opStateManager.state.nTurnDistMax > 0 && atc_dist_l_tmp > opStateManager.state.nTurnDistMax*0.8f){
                                atc_dist_l_tmp = (opStateManager.state.nTurnDistMax*0.8f).toInt()
                                if(atc_dist_l_tmp < fork_dist_l){
                                    atc_dist_l_tmp = fork_dist_l
                                }
                            }

                            //检查是否需要变道或者转弯
                            if (fork_dist_l > 0 && opStateManager.state.xTurnDist >= fork_dist_l && opStateManager.state.xTurnDist <= atc_dist_l_tmp){
                                if(keep_right_en) {
                                    laneChangeEdge = LaneChangeAction.RIGHT
                                    py_overtake_disable = true
                                    py_overtake_disable_reason = "准备靠右行驶"

                                    if (1 != atcInfoShow) {
                                        atcInfoShow = 1
                                        atcInfoChange = true
                                        val msg =
                                            "${nowTime} 公路:路口${opStateManager.state.xTurnDist.toInt()}米，小于${atc_dist_l}米，允许靠右行驶"
                                        Log.d("LaneState", msg)
                                        showLaneDebug(msg)
                                    }
                                }
                            }
                            else if(fork_dist_l > 0 && opStateManager.state.xTurnDist < fork_dist_l && opStateManager.state.xTurnInfo == TurnInfo_TurnRight && nav_turn){
                                laneChangeEdge = LaneChangeAction.RIGHT_TURN
                                py_overtake_disable = true
                                py_overtake_disable_reason = "准备向右转弯"

                                if (2 != atcInfoShow) {
                                    atcInfoShow = 2
                                    atcInfoChange = true
                                    val msg = "${nowTime} 公路:路口${opStateManager.state.xTurnDist.toInt()}米，小于${fork_dist_l}米，向右转弯"
                                    Log.d("LaneState", msg)
                                    showLaneDebug(msg)
                                }
                            }else{
                                atcInfoShow = 0
                            }
                        }
                    }else{ //无需控制的引导类型
                        atcInfoShow = 0
                    }
                }
                else{
                    atcInfoShow = 0
                }

                //重置立即变道状态
                if(atcInfoShow == 0){
                    opStateManager.state.doLaneForkNow = LaneChangeAction.KEEP.value
                }
                //更新最后的状态
                lastXTurnInfo = opStateManager.state.xTurnInfo
                lastNTurnDistMax = opStateManager.state.nTurnDistMax

                var targetIdx = -1
                var visualIdx = -1

                //-----------------------------------------
                //是否需要左转弯?
                if(laneChangeEdge == LaneChangeAction.LEFT_TURN){
                    action = LaneChangeAction.LEFT_TURN
                    if(lastValidLaneAction != action || atcInfoChange){ //变道状态有变化
                        val msg = "${nowTime} 引导${lastValidLaneAction}->${action}变化，重置状态"
                        Log.d("LaneState", msg)
                        showLaneDebug(msg)

                        resetLaneAction()
                        atcInfoChange = false
                    }
                    lastValidLaneAction = action
                    prevLaneAction = action
                    validLaneActionDbTime = now
                    Log.d("LaneDecision", "LEFT_TURN, ${validLaneActionDbTime}")
                }
                //-----------------------------------------
                //是否需要右转弯?
                else if(laneChangeEdge == LaneChangeAction.RIGHT_TURN){
                    action = LaneChangeAction.RIGHT_TURN
                    if(lastValidLaneAction != action || atcInfoChange){ //变道状态有变化
                        val msg = "${nowTime} 引导${lastValidLaneAction}->${action}变化，重置状态"
                        Log.d("LaneState", msg)
                        showLaneDebug(msg)

                        resetLaneAction()
                        atcInfoChange = false
                    }
                    lastValidLaneAction = action
                    prevLaneAction = action
                    validLaneActionDbTime = now
                    Log.d("LaneDecision", "RIGHT_TURN, ${validLaneActionDbTime}")
                }
                //-----------------------------------------
                //当前未在变道中状态中(未发送命令/未在变道中)
                else if ((!laneCmdSent || (!inLaneChangeStarting && !inLaneChangeFinishing)) || (nav_mode == 1))
                {
                    //车道决策(targetIdx从0开始，从左往右，visualIdx从0开始，从右往左)
                    var (_targetIdx, _action, _visualIdx) = decideLaneWithAction(vLaneResult, navLane, laneChangeEdge, bHighWay, road_median)
                    targetIdx = _targetIdx
                    action = _action
                    visualIdx = _visualIdx

                    //检查是否要执行变道或转弯
                    var resetLaneActionFlag = false

                    //目标车道无效
                    if (targetIdx < 0 && visualIdx < 0) {
                        action = LaneChangeAction.KEEP
                        if (lastValidLaneAction != action) {
                            resetLaneActionFlag = true

                            val msg = "${nowTime} 无目标车道 index=$targetIdx, visual=$visualIdx, 保持当前车道行驶"
                            Log.w("LaneDecision", msg)
                            showLaneDebug(msg)
                        }
                        lastValidLaneAction = action
                        prevLaneAction = action
                        validLaneActionDbTime = now
                        Log.d("LaneDecision", "targetIdxInvalid, ${validLaneActionDbTime}")
                    }
                    //目标车道有效
                    else
                    {
                        //有禁止变道标志
                        if(!enableLaneChange && action != LaneChangeAction.KEEP)
                        {
                            if(laneChangeEdge == LaneChangeAction.LEFT) //靠左行驶请求
                            {
                                if(action == LaneChangeAction.LEFT && !doForkNow)
                                {
                                    action = LaneChangeAction.KEEP
                                }
                            }
                            else if(laneChangeEdge == LaneChangeAction.RIGHT) //靠右行驶请求
                            {
                                if(action == LaneChangeAction.RIGHT && !doForkNow)
                                {
                                    action = LaneChangeAction.KEEP
                                }
                            }
                        }

                        //是否需要变道?
                        if (action != LaneChangeAction.KEEP) {
                            //当前请求有变化
                            if (action != prevLaneAction) {
                                val msg = "${nowTime} 引导${prevLaneAction}->${action}变化，启动稳定计时"
                                Log.d("LaneState", msg)
                                showLaneDebug(msg)

                                resetLaneAction()
                                validLaneActionDbTime = now //重置计时
                            }
                            prevLaneAction = action

                            //引导类型已稳定（超过1秒）或者有立即变道请求
                            val validLaneStableTime = if(nav_mode == 3) {1000L} else {500L}
                            if (now - validLaneActionDbTime > validLaneStableTime || doForkNow || nav_mode == 1) {
                                if (lastValidLaneAction != action) { //变道状态有变化
                                    var msg = ""
                                    if (doForkNow) {
                                        msg = "${nowTime} 引导${lastValidLaneAction}->${action}变化，重置状态"
                                    } else {
                                        msg = "${nowTime} 引导${lastValidLaneAction}->${action}稳定，重置状态"
                                    }
                                    Log.d("LaneState", msg)
                                    showLaneDebug(msg)

                                    resetLaneAction()
                                }

                                lastValidLaneAction = action
                            } else {
                                action = lastValidLaneAction //保留上次的操作
                            }
                        }
                        //保持当前车道行驶
                        else {
                            if (lastValidLaneAction != action) {
                                resetLaneActionFlag = true
                            }
                            lastValidLaneAction = action
                            prevLaneAction = action
                            validLaneActionDbTime = now
                            Log.d("LaneDecision","LaneChangeAction.KEEP, ${validLaneActionDbTime}")
                        }
                    }
                    lastTargetIdx = targetIdx
                    lastVisualIdx = visualIdx

                    //是否要终止变道
                    if (action == LaneChangeAction.KEEP) {
                        if (resetLaneActionFlag) {
                            var laneText = buildLaneText(vLaneResult, laneAction, visualLaneIdx)
                            vLaneText = laneText

                            val navPart = if (!vNavLaneText.isNullOrBlank()) {
                                "导航$vNavLaneText, "
                            } else {
                                ""
                            }
                            val msg = "${nowTime} ${navPart}视觉$vLaneText, 保持"
                            Log.d("LaneState", msg)
                            showLaneDebug(msg)

                            validLaneActionDbTime = now

                            //重置变道状态
                            resetLaneAction()
                        }
                    }
                }

                //执行动作
                doLaneChangeAction(action, laneChangeEdge, targetIdx, visualIdx)
            }
            else{
                if(app_navi_valid != 0) {
                    app_navi_valid = 0
                    val speedKph = carState.vEgo*3.6
                    val msg = "${nowTime} 车速过低(${speedKph.toInt()}km/h), 小于${navi_min_speed}km/h，暂停领航功能"
                    Log.d("LaneState", msg)
                    showLaneDebug(msg)

                    //重置变道状态
                    resetLaneAction()
                    validLaneActionDbTime = now
                }

                blindDecSpeedEnable = false
                blindDecSpeedPause = false
                blindDecSpeedOffset = 0
                blindSafeTime = 0
                //py_overtake_disable = true
            }

            // 处理变道状态
            processLaneChangeState(opStateManager.state.atc_state)
        }else{
            //领航条件失效
            if(app_navi_valid != -1) {
                app_navi_valid = -1

                var msg = "${nowTime} 条件不符合("
                if(!opStateManager.state.lidar_l){
                    msg += "左侧激光雷达不在线，"
                    py_overtake_disable = true
                    py_overtake_disable_reason = "左侧激光雷达不在线"
                }
                if(!opStateManager.state.lidar_r){
                    msg += "右侧激光雷达不在线，"
                    py_overtake_disable = true
                    py_overtake_disable_reason = "右侧激光雷达不在线"
                }
                if(!opStateManager.state.engaged){
                    msg += "OP定速未开，"
                    //py_overtake_disable = true
                }
                if(!bHighWay && !app_road_navi){
                    msg += "公路车道领航未打开，"
                }
                if(bHighWay && !app_navi){
                    msg += "高速车道领航未打开，"
                }

                Log.d("LaneState", msg)
                showLaneDebug(msg)

                //重置变道状态
                resetLaneAction()
                validLaneActionDbTime = now
            }

            blindDecSpeedEnable = false
            blindDecSpeedPause = false
            blindDecSpeedOffset = 0
            blindSafeTime = 0
        }

        return (app_navi_valid>0) && (laneChangeEdge != LaneChangeAction.KEEP || laneCmdSent)
    }

    private fun autoOvertake(naviFlag : Boolean){
        val now = System.currentTimeMillis()
        val nowTime = nowTimeStr()
        var action = LaneChangeAction.KEEP

        var overtakeMinSpeed = 30
        if(bHighWay){
            overtakeMinSpeed = 60
        }

        //导航处理
        if(opStateManager.state.lidar_l && opStateManager.state.lidar_r //激光雷达在线
            && opStateManager.state.engaged && !naviFlag //开启了op定速并且领航未控制
            && ((!bHighWay && app_road_overtake) || (bHighWay && app_overtake))) { //开启了超车功能

            if (carState.vEgo > (overtakeMinSpeed/3.6)) { //车辆速度允许超车
                if (app_overtake_valid != 1) {
                    app_overtake_valid = 1
                    val msg = "${nowTime} 超车功能正常工作中"
                    Log.d("Overtake", msg)
                    showLaneDebug(msg)
                }

                //超车未暂停
                if(!overtakeActionPause){
                    overtakeActionPausePrev = false

                    //检查左右两侧是否安全
                    val left_safe = LaneChangeSafetyCheck(LaneChangeAction.LEFT)
                    val right_safe = LaneChangeSafetyCheck(LaneChangeAction.RIGHT)

                    //有前车并且左右盲区无车
                    if(opStateManager.state.lead1 && (left_safe >= 0 || right_safe >= 0)){
                        //左右两侧盲区安全
                        if(left_safe >= 0 && right_safe >= 0){
                            //左前方无车
                            if(opStateManager.state.lf_drel == null && !opStateManager.state.l_lead){
                                //车道决策(targetIdx从0开始，从左往右，visualIdx从0开始，从右往左)
                                var (_targetIdx, _action, _visualIdx) = decideOvertakeLane(vLaneResult, navLane, LaneChangeAction.LEFT, bHighWay, road_median)
                                if(_action == LaneChangeAction.LEFT){
                                    doOvertakeAction(_action)
                                }
                            }
                        }
                    }

                }else if(!overtakeActionPausePrev){
                    overtakeActionPausePrev = true

                    val msg = "${nowTime} 超车正在冷却中"
                    Log.d("Overtake", msg)
                    showLaneDebug(msg)
                }

            }else{
                if(app_overtake_valid != 0) {
                    app_overtake_valid = 0
                    val speedKph = carState.vEgo*3.6
                    val msg = "${nowTime} 车速过低(${speedKph.toInt()}km/h), 小于${overtakeMinSpeed}km/h，暂停超车功能"
                    Log.d("Overtake", msg)
                    showLaneDebug(msg)

                    //重置变道状态
                    resetOvertakeAction()
                    validLaneActionDbTime = now
                }
            }
        }else{
            //领航条件失效
            if(app_overtake_valid != -1) {
                app_overtake_valid = -1
                var msg = "${nowTime} 条件不符合("
                if(!opStateManager.state.lidar_l){
                    msg += "左侧激光雷达不在线，"
                }
                if(!opStateManager.state.lidar_r){
                    msg += "右侧激光雷达不在线，"
                }
                if(!opStateManager.state.engaged){
                    msg += "OP未定速，"
                }
                if(!bHighWay && !app_road_overtake){
                    msg += "公路超车未打开，"
                }
                if(bHighWay && !app_overtake){
                    msg += "高速超车未打开，"
                }
                if(naviFlag){
                    msg += "领航控制中，"
                }

                Log.d("Overtake", msg)
                showLaneDebug(msg)

                //重置超车状态
                resetOvertakeAction()
            }
        }
    }

    //导航处理
    private fun naviProcess(){

        //平滑道路限速
        smoothSpeedLimit()
        //行驶路程处理
        travelDistProcess()
        //车道线有效检测
        var lane_line_valid = laneLineProcess()
        laneLineValid = lane_line_valid
        //高德导航
        var naviFlag = AmapNavi(lane_line_valid)
        //超车功能
        autoOvertake(naviFlag)

        //是否关闭超车功能
        //val py = Python.getInstance()
        //val module = py.getModule("auto_overtake_controller")
        //module.callAttr("set_app_navi", py_overtake_disable)

        if(::overtake_controllerModule.isInitialized) {
            if (py_overtake_disable_prev != py_overtake_disable) {
                py_overtake_disable_prev = py_overtake_disable
                overtake_controllerModule.callAttr("set_app_navi", py_overtake_disable)

                val now = System.currentTimeMillis()
                val nowTime = nowTimeStr()
                val msg = if (py_overtake_disable) {
                    "$nowTime ${py_overtake_disable_reason},超车功能关闭"
                } else {
                    "$nowTime 超车功能打开"
                }
                Log.d("LaneState", msg)
                showLaneDebug(msg)
            }
        }
    }

    private var lastTimeoutSendTime = 0L
    private var recvTimeoutCnt = 0

    private fun startTimeoutChecker() {
        timeoutJob?.cancel()
        timeoutJob = lifecycleScope.launch(Dispatchers.IO) {
            delay(5000)
            var imageViewCheckTime = 0L

            while (isActive) {
                delay(100) // 每0.1秒检查一次
                val now = System.currentTimeMillis()

                //导航处理
                naviProcess()

                if(now - imageViewCheckTime > 1000) {
                    //视频画面显示控制的处理
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        withContext(Dispatchers.Main) {
                            if ((video_show && lane_show_enable > 0) /*&& (lane_detect && lane_detect_enable > 0)*/) {
                                if (imageViewLayout.visibility == View.GONE) {
                                    if (imageViewLayoutShowEnable) { //是否允许显示
                                        imageViewLayout.visibility = View.VISIBLE
                                    }
                                }
                            } else {
                                if (imageViewLayout.visibility == View.VISIBLE) {
                                    imageViewLayout.visibility = View.GONE
                                }
                            }
                        }
                        imageViewCheckTime = now
                    }
                }

                var elapsed = now - lastNaviReceiveTime

                // 检查红绿灯数据是否超时（超过5秒）
                if (now - lastTrafficRecvTime > 5_000) {
                    if (nTrafficSpeedLimit != -1) {
                        nTrafficSpeedLimit = -1
                        nTrafficSpeedLimitChange = true
                    }
                }

                if(!cmUdpServiceSendEnable){
                    continue;
                }

                if (elapsed > 10_000) { // 超过10秒没收到数据
                    naviDataTimeout = true
                    if (lastTimeoutSendTime == 0L || now - lastTimeoutSendTime >= 1_000) { // 超时期间每1秒发送一次
                        if(!carrotManIp.isNullOrEmpty()) {
                            if(carState.vCruise > 0 && carState.vCruise <= 140) {
                                var limit_speed = carState.vCruise
                                val limitedJson = JSONObject().apply {
                                    put("nRoadLimitSpeed", limit_speed)
                                }.toString()
                                lastJsonToSend = limitedJson
                                //cmUdpService.send(limitedJson)
                                cmUdpServiceSend(limitedJson, port=carrotManOpPort)
                                lastUdpSendTime = now
                                lastTimeoutSendTime = now

                                recvTimeoutCnt += 1
                                val logShow = "接收广播超时(${recvTimeoutCnt}次)：发送数据包" + limitedJson
                                if(!bShowRawLog){
                                    clearRawLog()
                                }
                                setRawLogVisible(true)
                                updateRawLog(logShow, true)
                            }
                        }else{
                            val limitedJson = JSONObject().apply {
                                put("port", carrotManAppPort)
                            }.toString()
                            lastJsonToSend = limitedJson
                            //cmUdpService.send(limitedJson)
                            cmUdpServiceSend(limitedJson, port=carrotManOpPort)
                            lastUdpSendTime  = now
                            lastTimeoutSendTime = now

                            recvTimeoutCnt += 1
                            val logShow = "未连上设备：发送广播包(${recvTimeoutCnt}次)" + limitedJson
                            if(!bShowRawLog){
                                clearRawLog()
                            }
                            setRawLogVisible(true)
                            updateRawLog(logShow, true)
                        }
                    }
                } else {
                    // 收到新数据，重置计时
                    lastTimeoutSendTime = 0L
                    recvTimeoutCnt = 0
                }
            }
        }
    }

    private fun updateTrafficState(extras: Map<String, Any?>) {
        nTrafficLightStatus = extras["trafficLightStatus"]?.toString()?.toIntOrNull()?:-1
        nTrafficLightSeconds = extras["redLightCountDownSeconds"]?.toString()?.toIntOrNull()?:-1
    }

    private fun updateNaviState(extras: Map<String, Any?>): Pair<Boolean, String?> {
        var updated = false

        // 更新字段（仅当新值不为空且与旧值不同才修改）
        fun <T> updateField(
            key: String,
            newValue: T?,
            setter: (T) -> Unit,
            getter: () -> T?
        ) {
            if (newValue != null && newValue != getter()) {
                setter(newValue)
                updated = true
            }
        }

        fun updateRoadCateField(extras: Map<String, Any?>) {
            val roadcate = extras["ROAD_TYPE"]?.toString()?.toIntOrNull()?:8
            naviState.roadcate = roadcate_conv(roadcate)
            bHighWay = isHighway(naviState.roadcate?:-1)
            gRoadCate = naviState.roadcate?:8
        }

        fun updateTrafficField(extras: Map<String, Any?>) {
            nTrafficCnt = extras["TRAFFIC_LIGHT_NUM"]?.toString()?.toIntOrNull()?:-1
            nTotalTrafficCnt = extras["routeRemainTrafficLightNum"]?.toString()?.toIntOrNull()?:-1
        }

        fun updateTbtField(extras: Map<String, Any?>) {
            if(!isTbtTestEnabled) {
                naviState.nTBTDist = extras["SEG_REMAIN_DIS"]?.toString()?.toIntOrNull() ?: -1
                nTBTTurnTypeMain = extras["ICON"]?.toString()?.toIntOrNull() ?: -1

                //更新变道距离
                val nTurnDistPrev = opStateManager.state.nTurnDist
                opStateManager.state.nTurnDist = naviState.nTBTDist
                opStateManager.state.xTurnDist = opStateManager.state.nTurnDist.toFloat()

                //检测是否切换了引导类型并更新当前引导类型的最大距离
                if(nTBTTurnTypeMain != nTBTTurnTypeMainPrev){ //引导类型不同，更新最大引导距离
                    nTBTTurnTypeMainPrev = nTBTTurnTypeMain
                    nTBTTurnTypeMainChange = true

                    if(nTBTTurnTypeMain >= 0) {
                        opStateManager.state.nTurnDistMax = opStateManager.state.nTurnDist
                    }else{
                        opStateManager.state.nTurnDistMax = -1
                    }
                }else if(opStateManager.state.nTurnDist > nTurnDistPrev) { //本次引导距离大于上次，更新最大引导距离
                    opStateManager.state.nTurnDistMax = opStateManager.state.nTurnDist
                    nTBTTurnTypeMainChange = true
                }
            }
        }

        fun updateCameraField(extras: Map<String, Any?>) {
            val camera_id = extras["cameraID"]?.toString()?.toIntOrNull()?:-1
            if(camera_id != nCameraId){
                nCameraId = camera_id
                bCameraIdChange = true

                //直接清除导航红灯使用摄像头距离辅助减速的状态
                nTrafficSpeedLimit = -1
                nTrafficSpeedLimitChange = true
                xTrafficRedState = false
                xTrafficRedStateTime = System.currentTimeMillis()
            }
        }

        fun updateSpeedField(extras: Map<String, Any?>) {
            val limitedSpeed = extras["LIMITED_SPEED"]?.toString()?.toIntOrNull()
            var icon = extras["ICON"]?.toString()?.toIntOrNull()
            var dist = extras["SEG_REMAIN_DIS"]?.toString()?.toIntOrNull()

            //有限速数据并且大于0，允许用户设定的提高限速功能
            enable_speed_limit_up = limitedSpeed != null && limitedSpeed > 0

            nRoadLimitSpeed = limitedSpeed //当前路段限速
            val lastSpeed = nRoadLimitSpeedLast  // 局部不可变变量，避免 Smart cast 问题

            var computedSpeed = when {
                // 1) 本次限速有效并且大于0
                limitedSpeed != null && limitedSpeed > 0 -> {
                    if ( (icon != null && dist != null && icon == 16 && dist < 300) //前方300米内为隧道
                            && lastSpeed != null && lastSpeed == limitedSpeed //最后一次限速和当前限速相同(说明隧道限速和进隧道前一样)
                            && lastSpeed > 80 && limitedSpeed > 80 //当前限速大于80
                            && tunnel_dec_speed //设置了隧道减速
                    ) {
                        val now = System.currentTimeMillis()

                        if (tunnelEnterTime == null) {
                            tunnelEnterTime = now  // 记录第一次进入隧道的时间
                        }

                        enable_speed_limit_up = false //接近隧道时禁止提速

                        val elapsedSec = (now - tunnelEnterTime!!) / 1000.0  // 已经过的秒数
                        val decDuration = 8.0 // 希望多久内降到目标速度，单位秒
                        val minSpeed = maxOf(80, lastSpeed - 20) //降速后不能小于
                        val targetSpeed = (lastSpeed * 0.8).toInt().coerceAtLeast(minSpeed)

                        // 线性降速
                        val speedDiff = lastSpeed - targetSpeed
                        val decStep = (speedDiff * (elapsedSec / decDuration)).toInt().coerceIn(0, speedDiff)

                        tunnelSpeed = (lastSpeed - decStep).coerceAtLeast(targetSpeed)
                        tunnelSpeed
                    } else {
                        val now = System.currentTimeMillis()
                        if(tunnelEnterTime != null && tunnelSpeed > 0){
                            if(tunnelKeepTime == null){
                                tunnelKeepTime = now  // 记录进入隧道后保持限速的时间
                            }

                            enable_speed_limit_up = false //隧道禁止提速

                            val elapsedSec = (now - tunnelKeepTime!!) / 1000.0  // 已经过的秒数
                            if(elapsedSec > 5.0){ //进隧道后5秒
                                tunnelEnterTime = null
                            }
                            tunnelSpeed //返回进隧道前的速度
                        } else {
                            if ((naviState.szPosRoadName?.contains("隧道") == true) && (tunnelSpeed > 0)) { //在隧道中
                                enable_speed_limit_up = false //隧道禁止提速
                                tunnelSpeed //返回进隧道前的速度
                            } else {
                                tunnelSpeed = -1
                                tunnelEnterTime = null // 离开隧道重置
                                // 正常处理
                                nRoadLimitSpeedLast = limitedSpeed
                                limitedSpeed
                            }
                        }
                    }
                }
                // 2) 本次限速无效并且上一次限速也无效或者上次限速<=0
                lastSpeed == null || lastSpeed <= 0 -> {
                    nRoadLimitSpeedLast = limitedSpeed ?: -1
                    var _limit_speed = 60
                    if(carState.vCruise > 0 && carState.vCruise <= 140){
                        _limit_speed = carState.vCruise
                    }
                    _limit_speed
                }
                // 3)本次限速无效但是上一次限速有效
                else -> {
                    var _limit_speed = lastSpeed
                    if (bHighWay) { //高速或快速
                        //用户调节了定速
                        if((nRoadLimitvCruiseLast != carState.vCruise) && (carState.vCruise > 0 && carState.vCruise <= 140)){
                            nRoadLimitvCruiseLast = carState.vCruise
                            _limit_speed = carState.vCruise
                            nRoadLimitSpeedLast = _limit_speed
                        }
                    }else{ //公路
                        //道路类型从高速变为低速，限速变为60
                        if((nRoadCateLast != naviState.roadcate) && isHighway(nRoadCateLast) && !bHighWay){
                            _limit_speed = 60
                            nRoadLimitSpeedLast = _limit_speed
                            nRoadLimitvCruiseLast = carState.vCruise
                        }
                        //用户调节了定速
                        else if((nRoadLimitvCruiseLast != carState.vCruise) && (carState.vCruise > 0 && carState.vCruise <= 140)){
                            nRoadLimitvCruiseLast = carState.vCruise
                            _limit_speed = carState.vCruise
                            nRoadLimitSpeedLast = _limit_speed
                        }
                        //_limit_speed = (lastSpeed * 0.8).toInt().coerceAtLeast(45)
                    }
                    _limit_speed
                }
            }
            nRoadLimitvCruiseLast = carState.vCruise
            nRoadCateLast = naviState.roadcate?:-1

            //临时提速处理
            if(computedSpeed < 60) {
                if (bHighWay){ //高速或快速，用户设置了提速
                    if(offset_highway > 0){
                        if(computedSpeed == null || computedSpeed <= 0){
                            computedSpeed = min_speed_limit
                        }
                        if(computedSpeed <= 40){ //小于40直接加偏移
                            computedSpeed += offset_highway
                        }else{ //大于40等比例加偏移
                            computedSpeed += (offset_highway/(60-40))*(60-computedSpeed)
                        }
                    }
                }else if (offset_road > 0){ //用户设置了公路提速
                    if(computedSpeed == null || computedSpeed <= 0){
                        computedSpeed = min_speed_limit
                    }
                    if(computedSpeed <= 40){ //小于40直接加偏移
                        computedSpeed += offset_road
                    }else{ //大于40等比例加偏移
                        computedSpeed += (offset_road/(60-40))*(60-computedSpeed)
                    }
                }
            }

            //当没有道路限速时使用测速摄像头限速融合
            val sdi_type_org = convert_sdi_type(nSdiTypeMain)?:-1 //摄像头类型转为CP类型
            var sdi_type = sdi_type_org
            var sdi_speed_limit =  naviState.nSdiSpeedLimit?:-1
            var sdi_dist = naviState.nSdiDist?:-1
            val highway = isHighway(naviState.roadcate?:-1)

            //摄像头限速融合道路限速
            if(sdi_speed_limit > 60 && !bHighWay) { //有摄像头限速并且限速大于60
                if (limitedSpeed == null || limitedSpeed <= 0) { //无道路限速
                    if((computedSpeed < sdi_speed_limit*0.8) //计算后的限速比摄像头小
                            && (nRoadLimitSpeedLast > 0)) {  //上次获取过道路限速
                        computedSpeed = (sdi_speed_limit*0.8).toInt()
                        if(highway){
                            if(computedSpeed > 80){
                                computedSpeed = 80
                            }
                        }else{
                            if(computedSpeed > 60){
                                computedSpeed = 60
                            }
                        }
                    }else if((nRoadLimitSpeedLast <= 0) && (sdi_dist >= 50 && sdi_dist <= 300)){
                        val sdi_speed_limit_up = (sdi_speed_limit*1.1).toInt()
                        if(computedSpeed < sdi_speed_limit_up)
                        computedSpeed = sdi_speed_limit_up //没有道路限速，但是有摄像头限速并且有距离时以摄像头作为限速
                    }
                }
            }

            if(bRoadLimitIncDec){
                computedSpeed = computedSpeed + nRoadLimitOffset
            }
            if(road_speed_limit_percent > 0 && enable_speed_limit_up){
                val speedUp = computedSpeed.toFloat()*(1.0+(road_speed_limit_percent.toFloat()/100.0))
                computedSpeed = speedUp.toInt()
            }
            if(road_speed_limit_kph > 0 && enable_speed_limit_up){
                computedSpeed = computedSpeed + road_speed_limit_kph
            }

            if(blindDecSpeedEnable) {
                if(bHighWay && computedSpeed > 60){
                    if(blindDecSpeedOffset < 0) {
                        val computedSpeedTmp = computedSpeed + blindDecSpeedOffset
                        if (computedSpeedTmp <= 60) {
                            blindDecSpeedPause = true
                            blindDecSpeedOffset = 60 - computedSpeed
                            computedSpeed = 60
                        }else{
                            blindDecSpeedPause = false
                        }
                    }
                }
                else if(!bHighWay && computedSpeed > 30){
                    if(blindDecSpeedOffset < 0) {
                        computedSpeed = computedSpeed + blindDecSpeedOffset
                        if (computedSpeed <= 30) {
                            blindDecSpeedPause = true
                            blindDecSpeedOffset = 30 - computedSpeed
                            computedSpeed = 30
                        }
                        else{
                            blindDecSpeedPause = false
                        }
                    }
                }
            }

            //限制最大限速
            if(computedSpeed > max_speed_limit){
                computedSpeed = max_speed_limit
            }

            if(!smooth_speed_limit) {
                if (naviState.nRoadLimitSpeed != computedSpeed) {
                    naviState.nRoadLimitSpeed = computedSpeed
                    updated = true
                }
            }else {
                if (nRoadLimitSpeedRamp != computedSpeed) {
                    nRoadLimitSpeedRamp = computedSpeed
                    if (naviState.nRoadLimitSpeed == null) {
                        naviState.nRoadLimitSpeed = computedSpeed
                    }
                    nRoadLimitSpeedRampCnt = 6
                    updated = true
                }
            }
        }

        naviState.apply {
            updateField("carrotIndex", extras["carrotIndex"]?.toString()?.toIntOrNull(), { carrotIndex = it }, { carrotIndex })
            updateField("carrotCmd", extras["carrotCmd"]?.toString(), { carrotCmd = it }, { carrotCmd })
            updateField("carrotArg", extras["carrotArg"]?.toString(), { carrotArg = it }, { carrotArg })
            updateField("goalPosX", extras["arrivePOILongitude"]?.toString()?.toDoubleOrNull(), { goalPosX = it }, { goalPosX })
            updateField("goalPosY", extras["arrivePOILatitude"]?.toString()?.toDoubleOrNull(), { goalPosY = it }, { goalPosY })
            updateField("szGoalName", extras["endPOIName"]?.toString(), { szGoalName = it }, { szGoalName })
            updateField("nSdiType", extras["CAMERA_TYPE"]?.toString()?.toIntOrNull()?:-1, { nSdiTypeMain = it }, { nSdiTypeMain })
            updateField("nSdiSpeedLimit", extras["CAMERA_SPEED"]?.toString()?.toIntOrNull(), { nSdiSpeedLimit = it }, { nSdiSpeedLimit })
            updateField("nSdiDist", extras["CAMERA_DIST"]?.toString()?.toIntOrNull(), { nSdiDist = it }, { nSdiDist })
            //updateField("roadcate", extras["ROAD_TYPE"]?.toString()?.toIntOrNull(), { roadcate = it }, { roadcate })
            updateField("szTBTMainText", extras["NEXT_ROAD_NAME"]?.toString(), { szTBTMainText = it }, { szTBTMainText })
            updateField("szNearDirName", extras["NEXT_ROAD_NAME"]?.toString(), { szNearDirName = it }, { szNearDirName })
            updateField("szFarDirName", extras["NEXT_NEXT_ROAD_NAME"]?.toString(), { szFarDirName = it }, { szFarDirName })
            updateField("nTBTDistNext", extras["NEXT_SEG_REMAIN_DIS"]?.toString()?.toIntOrNull(), { nTBTDistNext = it }, { nTBTDistNext })
            updateField("nTBTTurnTypeNext", extras["NEXT_NEXT_TURN_ICON"]?.toString()?.toIntOrNull(), { nTBTTurnTypeNext = it }, { nTBTTurnTypeNext })
            updateField("szTBTMainTextNext", extras["NEXT_NEXT_ROAD_NAME"]?.toString(), { szTBTMainTextNext = it }, { szTBTMainTextNext })
            updateField("nGoPosDist", extras["ROUTE_REMAIN_DIS"]?.toString()?.toIntOrNull(), { nGoPosDist = it }, { nGoPosDist })
            updateField("nGoPosTime", extras["ROUTE_REMAIN_TIME"]?.toString()?.toIntOrNull(), { nGoPosTime = it }, { nGoPosTime })
            updateField("szPosRoadName", extras["CUR_ROAD_NAME"]?.toString(), { szPosRoadName = it }, { szPosRoadName })
            updateField("vpPosPointLat", extras["CAR_LATITUDE"]?.toString()?.toDoubleOrNull(), { vpPosPointLat = it }, { vpPosPointLat })
            updateField("vpPosPointLon", extras["CAR_LONGITUDE"]?.toString()?.toDoubleOrNull(), { vpPosPointLon = it }, { vpPosPointLon })
            updateField("nPosAngle", extras["CAR_DIRECTION"]?.toString()?.toFloatOrNull(), { nPosAngle = it }, { nPosAngle })
            updateField("nPosSpeed", extras["CUR_SPEED"]?.toString()?.toFloatOrNull(), { nPosSpeed = it }, { nPosSpeed })
            updateRoadCateField(extras)
            updateSpeedField(extras)
            updateCameraField(extras)
            updateTrafficField(extras)
            updateTbtField(extras)
        }

        //收费站测试
        if(isTbtTestEnabled) {
            val now = System.currentTimeMillis()
            val time = now - tbtTestTime
            var tbt_dist = naviState.nTBTDist?:0
            tbt_dist -= ((carState.vEgo / 3.6) * time / 1000).toInt()
            naviState.nTBTDist = tbt_dist
            nTBTTurnTypeMain = 14
        }

        // 返回标志 + 最新 JSON
        nTrafficSpeedLimitChange = false

        nTBTTurnTypePrev = naviState.nTBTTurnType
        nTBTTurnType = convert_tbt_type(nTBTTurnTypeMain) //引导类型转为CP类型
        if(bHighWay && nTBTTurnTypeMain == 13){ //高速途经服务区(识别成驶出环岛类型13)引导为靠右变道
            nTBTTurnType = 6
        }
        naviState.nTBTTurnType = nTBTTurnType
        if(nTBTTurnTypePrev != nTBTTurnType){
            nTBTTurnTypeChange = true //引导类型有变
        }

        //摄像头数据
        val sdi_type_org = convert_sdi_type(nSdiTypeMain)?:-1 //摄像头类型转为CP类型
        var sdi_type = sdi_type_org
        var sdi_speed_limit =  naviState.nSdiSpeedLimit?:-1
        var sdi_dist = naviState.nSdiDist?:-1

        //只要有限速的摄像头类型，都转换成限速摄像头
        if(sdi_speed_limit > 0 && sdi_type_org > 0) {
            sdi_type = 1 //类型转为固定式测速摄像头
            if(sdi_speed_limit < 60) sdi_speed_limit = 60 //摄像头限速小于60则强制为60
        }
        //有导航红灯辅助降速，有红灯摄像头并且有距离
        if(nTrafficSpeedLimit > 0 && sdi_type_org == 6 && sdi_dist >= 0){
            sdi_type = 1 //强制为测速摄像头
            sdi_speed_limit = nTrafficSpeedLimit //路口速度
        }

        //收费站特殊处理
        if(nTBTTurnTypeMain == 14 && naviState.nTBTDist != null){
            if(nTBTTurnTypeChange){
            }
            sdi_dist = naviState.nTBTDist   //收费站距离

            //距离收费站小于500米时才进行特殊处理
            if(sdi_dist < 500) {
                if (station_dist_offset > 0 && sdi_dist > 0) { //设置了提前减速距离，并且剩余距离大于0
                    sdi_dist -= station_dist_offset //减去提前减速距离
                    if (sdi_dist <= 1) {
                        sdi_dist = 1 //不让距离变成0，否则
                    }
                }

                sdi_type = 1 //强制为测速摄像头
                sdi_speed_limit = station_speed //收费站限速

                if (isTbtTestEnabled) {
                    clearRawLog()
                }
                val info = "前方${naviState.nTBTDist}米收费站, " +
                        "限速=${station_speed}km/h, " +
                        "提前减速距离=${station_dist_offset}米, " +
                        "通过时间=${station_keep_time}秒"
                setRawLogVisible(true)
                updateRawLog(info, isTbtTestEnabled)

                nTBTTurnTypeStation = true //收费站类型标志
                nTBTTurnStationTime = System.currentTimeMillis() //重置时间
            }
        }else if(nTBTTurnTypeStation == true){
            val now = System.currentTimeMillis()
            val elapsed = now - nTBTTurnStationTime
            if(elapsed < station_keep_time*1000){
                sdi_dist = 1 //不让距离变成0，否则
                sdi_type = 1 //强制为测速摄像头
                sdi_speed_limit = station_speed //收费站限速

                if(isTbtTestEnabled) {
                    clearRawLog()
                }
                val info = "前方${naviState.nTBTDist}米收费站, " +
                        "限速=${station_speed}km/h, " +
                        "提前减速距离=${station_dist_offset}米, " +
                        "通过时间=${station_keep_time}秒, " +
                        "已通过=${(elapsed / 1000).toInt()}秒"
                setRawLogVisible(true)
                updateRawLog(info, isTbtTestEnabled)
            }else{
                clearRawLog()
                setRawLogVisible(false)
                nTBTTurnTypeStation = false
            }
        }

        //修改摄像头数据
        naviState.nSdiDist = sdi_dist
        naviState.nSdiSpeedLimit = sdi_speed_limit
        naviState.nSdiType = sdi_type

        //更新引导类型
        opStateManager.state.nTurnInfo = naviState.nTBTTurnType
        opStateManager.state.xTurnInfo = convert_x_tbt_type(naviState.nTBTTurnType)

        val newJson = naviState.toJson()
        return Pair(true, newJson)
    }

    private val RAW_LOG_MAX_LINES = 500
    private fun clearRawLog() {
        lifecycleScope.launch(Dispatchers.Main) {
            rawLog.text = ""
        }
    }
    private fun setRawLogVisible(Visible: Boolean) {
        if((Visible && webView.visibility != View.VISIBLE) || (!Visible)) {
            lifecycleScope.launch(Dispatchers.Main) {
                scrollRawLog.visibility = if (Visible) View.VISIBLE else View.GONE
                if (Visible){
                    web_container.visibility = View.VISIBLE
                }
            }
        }
    }
    private fun updateRawLog(text: String, bShow: Boolean) {

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

    private fun clearRawLogUI() {
        rawLog.text = ""
    }
    private fun setRawLogUIVisible(Visible: Boolean) {
        if((Visible && webView.visibility != View.VISIBLE) || (!Visible)) {
            scrollRawLog.visibility = if (Visible) View.VISIBLE else View.GONE
            if (Visible){
                web_container.visibility = View.VISIBLE
            }
        }
    }
    private fun updateRawLogUI(text: String, bShow: Boolean) {

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

    private fun updateRawLogNav(text: String) {
        if(app_log) {
            AppLogger.log(this, "MainActivity", text)
        }

        lifecycleScope.launch(Dispatchers.Main) {
            rawLogNav.text = text
            scrollRawLogNav.post { scrollRawLogNav.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    // ICON → 方向集合映射（单字母表示）
    val driveWayIconMap: Map<Int, Set<String>> = mapOf(
            0 to setOf("X"),                 // 直行灰色(不直行) OK
            15 to setOf("S"),             // 直行 OK

            1 to setOf("X"),                 // 左转灰色(不左转) OK
            16 to setOf("L"),             // 左转 OK

            2 to setOf("X"),                 // 直行和左转灰色(不直行和左转) OK
            17 to setOf("S","L"),       // 直行 + 左转

            3 to setOf("X"),                 // 右转灰色(不右转) OK
            18 to setOf("R"),             // 右转 OK

            4 to setOf("X"),                 // 直行和右转灰色(不直行和不右转)
            19 to setOf("S", "R"),      // 直行 + 右转 OK

            5 to setOf("X"),                 // 掉头灰色(不掉头) OK
            20 to setOf("U"),             // 掉头 OK

            9 to setOf("X"),                 // 不直行 + 不掉头
            24 to setOf("S", "U"),      // 直行 + 掉头 OK

            11 to setOf("X"),                // 不左转 + 不掉头 OK
            26 to setOf("L", "U"),      // 左转 + 掉头 OK

            43 to setOf("L"),             // 左转 + 不掉头 OK
            44 to setOf("U"),             // 不左转 + 掉头 OK
            49 to setOf("X"),                // 不直行+ 不左转 + 不掉头 OK
            71 to setOf("S"),             // 直行+ 不左转 + 不掉头 OK
            72 to setOf("L"),             // 不直行+ 左转 + 不掉头 OK

            30 to setOf("S"),             // 直行 + 不左转 OK
            31 to setOf("L"),             // 不直行 + 左转 OK
            32 to setOf("S"),             // 直行 + 不右转 OK
            33 to setOf("R"),             // 不直行 + 右转 OK
            34 to setOf("L"),             // 左转 + 不右转 OK
            35 to setOf("R"),             // 不左转 + 右转 OK
            36 to setOf("S"),             // 直行 + 不左转不右转 OK
            37 to setOf("L"),             // 左转 + 不直行不右转 OK
            38 to setOf("R"),             // 右转 + 不直行不左转 OK
            39 to setOf("S"),             // 直行 + 不掉头 OK
            40 to setOf("U"),             // 不直行 + 掉头 OK
            54 to setOf("X"),                // 公交车道不可通行 OK
            62 to setOf("V"),              // 可变车道 OK
            89 to setOf("X"),               // 未画线，不可通行 OK
    )

    // ICON → 中文方向集合
    val driveWayIconMapCn: Map<Int, Set<String>> = mapOf(
            0 to setOf("XX"),                 // 直行灰色(不直行) OK
            15 to setOf("直行"),             // 直行 OK

            1 to setOf("XX"),                 // 左转灰色(不左转) OK
            16 to setOf("左转"),             // 左转 OK

            2 to setOf("XX"),                 // 直行和左转灰色(不直行和左转) OK
            17 to setOf("直行","左转"),       // 直行 + 左转

            3 to setOf("XX"),                 // 右转灰色(不右转) OK
            18 to setOf("右转"),             // 右转 OK

            4 to setOf("XX"),                 // 直行和右转灰色(不直行和不右转)
            19 to setOf("直行", "右转"),      // 直行 + 右转 OK

            5 to setOf("XX"),                 // 掉头灰色(不掉头) OK
            20 to setOf("掉头"),             // 掉头 OK

            9 to setOf("XX"),                 // 不直行 + 不掉头
            24 to setOf("直行", "掉头"),      // 直行 + 掉头 OK

            11 to setOf("XX"),                // 不左转 + 不掉头 OK
            26 to setOf("左转", "掉头"),      // 左转 + 掉头 OK

            43 to setOf("左转"),             // 左转 + 不掉头 OK
            44 to setOf("掉头"),             // 不左转 + 掉头 OK
            49 to setOf("XX"),                // 不直行+ 不左转 + 不掉头 OK
            71 to setOf("直行"),             // 直行+ 不左转 + 不掉头 OK

            30 to setOf("直行"),             // 直行 + 不左转 OK
            31 to setOf("左转"),             // 不直行 + 左转 OK
            32 to setOf("直行"),             // 直行 + 不右转 OK
            33 to setOf("右转"),             // 不直行 + 右转 OK
            34 to setOf("左转"),             // 左转 + 不右转 OK
            35 to setOf("右转"),             // 不左转 + 右转 OK
            36 to setOf("直行"),             // 直行 + 不左转不右转 OK
            37 to setOf("左转"),             // 左转 + 不直行不右转 OK
            38 to setOf("右转"),             // 右转 + 不直行不左转 OK
            39 to setOf("直行"),             // 直行 + 不掉头 OK
            40 to setOf("直行"),             // 不直行 + 掉头 OK
            54 to setOf("XX"),               // 公交车道不可通行 OK
            62 to setOf("可变"),             // 可变车道 OK
            89 to setOf("XX"),               // 未画线，不可通行 OK
    )

    private fun updateDriveWayTextOnUI(text: String, show: Boolean) {
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
    fun laneLetterToIcon(s: String): String {
        return when (s) {
            "L" -> "←"
            "R" -> "→"
            "S" -> "↑"
            "U" -> "↶"
            "X" -> "⛔"
            else -> s
        }
    }
    /*
    extras={KEY_TYPE=13012, EXTRA_DRIVE_WAY={"drive_way_enabled":true,"drive_way_size":5,"drive_way_info":
           [{"drive_way_number":0,"drive_way_lane_Back_icon":"11","drive_way_lane_Extended":"0","trafficLaneAdvised":false,"trafficLaneExtendedNew":0,"trafficLaneType":0},
           {"drive_way_number":1,"drive_way_lane_Back_icon":"15","drive_way_lane_Extended":"0","trafficLaneAdvised":false,"trafficLaneExtendedNew":0,"trafficLaneType":0},
           {"drive_way_number":2,"drive_way_lane_Back_icon":"15","drive_way_lane_Extended":"0","trafficLaneAdvised":false,"trafficLaneExtendedNew":0,"trafficLaneType":0},
           {"drive_way_number":3,"drive_way_lane_Back_icon":"15","drive_way_lane_Extended":"0","trafficLaneAdvised":false,"trafficLaneExtendedNew":0,"trafficLaneType":0},
           {"drive_way_number":4,"drive_way_lane_Back_icon":"3","drive_way_lane_Extended":"0","trafficLaneAdvised":false,"trafficLaneExtendedNew":0,"trafficLaneType":0}]}}}
    extras={KEY_TYPE=13012, EXTRA_DRIVE_WAY={"drive_way_enabled":false,"drive_way_size":0,"drive_way_info":[]}}}
     */
    private fun parseDriveWay(extras: Map<*, *>?) {
        val driveWayStr = extras?.get("EXTRA_DRIVE_WAY") as? String ?: return
        val driveWay = JSONObject(driveWayStr)

        val enabled = driveWay.optBoolean("drive_way_enabled", false)
        val lane_cnt = driveWay.optInt("drive_way_size", 0)
        if (!enabled || 0 == lane_cnt) {
            navi_lane_cnt = -1
            updateDriveWayTextOnUI("", false)
            val laneStrArray = Array(0) { "" }
            sendLaneToPythonIO (laneStrArray, 0)

            //清空导航车道信息
            navLane = NavLane(
                    laneStrArray = laneStrArray,
                    validCount = 0
            )
            return
        }
        navi_lane_cnt = lane_cnt //车道数量

        val infoArray = driveWay.optJSONArray("drive_way_info") ?: return
        if (infoArray.length() == 0) return

        val sb = StringBuilder()          // 原字符拼接
        val lane_icon = StringBuilder()   // 箭头/图标拼接

        val laneStrArray = Array(infoArray.length()) { "" }
        var validCount = 0

        for (i in 0 until infoArray.length()) {
            val lane = infoArray.optJSONObject(i) ?: continue

            val icon = lane.optString("drive_way_lane_Back_icon", "0").toIntOrNull() ?: 0
            val advised = lane.optBoolean("trafficLaneAdvised", false)

            // 获取语义
            val semantic = driveWayIconMap[icon]

            // -------- 原字符拼接 --------
            var laneStr = if (semantic != null) {
                semantic.joinToString("/")
            } else {
                if (icon < 15) "$icon(X)" else "$icon"
            }

            if (advised) laneStr += "*"  // 推荐车道加 *

            laneStrArray[validCount] = laneStr
            validCount++

            sb.append("| $laneStr ")

            // -------- 箭头/图标拼接 --------
            val semanticForIcon = semantic ?: if (icon < 15) setOf("X") else null

            val laneIconStr = semanticForIcon?.map {
                when (it) {
                    "L" -> "←"
                    "R" -> "→"
                    "S" -> "↑"
                    "U" -> "↶"
                    "X" -> "⛔"
                    else -> it
                }
            }?.joinToString("/") ?: "$icon"

            val laneIconFinal = if (advised) "$laneIconStr*" else laneIconStr

            lane_icon.append("| $laneIconFinal ")
        }

        sb.append("|")
        lane_icon.append("|")
        vNavLaneText = lane_icon.toString()

        //保存导航车道信息
        navLane = NavLane(
            laneStrArray = laneStrArray,
            validCount = validCount
        )

        //updateDriveWayTextOnUI(sb.toString(), true)
        updateDriveWayTextOnUI(lane_icon.toString(), true)

        //车道传递给python
        if(lane_overtake) {
            sendLaneToPythonIO(laneStrArray, validCount)
        }else{
            val laneStrArray = Array(0) { "" }
            sendLaneToPythonIO (laneStrArray, 0)
        }
    }

    // 模拟函数，传入假数据
    private fun simulateDriveWayBroadcast(rawData: Map<String, Any?>) {
        val extras = rawData["extras"] as? Map<*, *> ?: return
        parseDriveWay(extras)
    }

    fun sendLaneToPython(arr: Array<String>, validCount: Int) {
        PythonEnvInit()

        //val py = Python.getInstance()
        //val module = py.getModule("config")

        // 调用 Python 函数：process_lane_array(arr, validCount)
        if(::configModule.isInitialized) {
            val result = configModule.callAttr("process_lane_array", arr, validCount)

            // 如果 Python 有返回值
            val retStr = result?.toString()
            Log.d("PYTHON", "Python返回: $retStr")
        }
    }

    fun sendLaneToPythonIO(arr: Array<String>, validCount: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            //数据通过广播发送
            val validArr = arr.take(validCount).toTypedArray() //取有效部分
            val json = JSONObject() //组JSON
            if(validCount > 0) {
                json.put("laneStrArray", JSONArray(validArr))
            }
            json.put("laneValidCount", validCount)
            val jsonStr = json.toString()
            cmUdpServiceSend(jsonStr, host="255.255.255.255", port = overtakeAppPort)

            //数据传递给python
            PythonEnvInit()
            /*
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(this@MainActivity))

                if (!::py.isInitialized) {
                    py = Python.getInstance()
                    overtake_controllerModule = py.getModule("auto_overtake_controller")
                    overtakeModule = py.getModule("auto_overtake")
                    sound_centerModule = py.getModule("sound_center")
                    configModule = py.getModule("config")
                }
            }
             */

            //val py = Python.getInstance()
            //val module = py.getModule("auto_overtake_controller")

            if(::overtake_controllerModule.isInitialized) {
                val result = overtake_controllerModule.callAttr("process_lane_array", validArr, validCount)

                val retStr = result?.toString()

                // 如果需要回 UI
                withContext(Dispatchers.Main) {
                    Log.d("PYTHON", "Python返回: $retStr")
                }
            }
        }
    }

    // -------- 模拟数据 --------
    // 模拟一条有效数据
    private fun simulateValidData() {
        val driveWayJson = """
        {
          "drive_way_enabled": true,
          "drive_way_size": 3,
          "drive_way_info": [
            {
              "drive_way_lane_Back_icon": "15",
              "trafficLaneAdvised": false
            },
            {
              "drive_way_lane_Back_icon": "18",
              "trafficLaneAdvised": true
            },
            {
              "drive_way_lane_Back_icon": "32",
              "trafficLaneAdvised": false
            }
          ]
        }
    """.trimIndent()

        val rawData = mapOf(
                "extras" to mapOf(
                        "KEY_TYPE" to 13012,
                        //必须是 String
                        "EXTRA_DRIVE_WAY" to driveWayJson
                )
        )

        simulateDriveWayBroadcast(rawData)
    }


    // 模拟一条无效数据
    private fun simulateInvalidData() {
        val driveWayJson = """
        {
          "drive_way_enabled": false
        }
    """.trimIndent()

        val rawData = mapOf(
                "extras" to mapOf(
                        "KEY_TYPE" to 13012,
                        "EXTRA_DRIVE_WAY" to driveWayJson
                )
        )

        simulateDriveWayBroadcast(rawData)
    }

    // --------- 测试调用 ---------
    fun testDriveWaySimulation() {
        // 显示有效数据
        simulateValidData()  // UI 应显示 "|15|18*|32|"

        // 延迟2秒后模拟无效数据
        Handler(Looper.getMainLooper()).postDelayed({
            simulateInvalidData() // UI 隐藏
        }, 2000)
    }

    val naviLog = StringBuilder()
    val trafficLog = StringBuilder()

    private fun formatAmapData(rawData: Map<String, Any?>): String {
        val extras = rawData["extras"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val sb = StringBuilder()
        val route = StringBuilder()
        val road = StringBuilder()
        val turn = StringBuilder()
        val traffic = StringBuilder()
        val keyType = extras["KEY_TYPE"]?.toString()?.toIntOrNull() ?: 0

        sb.append("📡 收到高德导航数据:\n")
        sb.append("🔹 广播类型: ${rawData["action"]}\n")
        sb.append("🔹 关键类型: ${extras["KEY_TYPE"] ?: "未知"}\n\n")

        if(keyType == 10001) {
            naviLog.clear()

            // 导航概览
            extras["ROUTE_REMAIN_DIS"]?.let {
                route.append("🗺️ 导航概览:\n")
                route.append("├─ 距离: ${extras["ROUTE_ALL_DIS"] ?: 0}米")
                route.append(", 剩余: ${extras["ROUTE_REMAIN_DIS_AUTO"] ?: "${extras["ROUTE_REMAIN_DIS"]}米"}")
                route.append(", 时间: ${extras["ROUTE_REMAIN_TIME_AUTO"] ?: "${extras["ROUTE_REMAIN_TIME"]}秒"}")
                route.append(", 到达: ${extras["ETA_TEXT"] ?: "未知"}\n")

                sb.append(route)
                naviLog.append(route)
            }

            // 当前道路
            extras["CUR_ROAD_NAME"]?.let {
                road.append("🛣️ 当前道路:\n")
                road.append("├─ 名称: ${extras["CUR_ROAD_NAME"]}")
                road.append(", 速度: ${extras["CUR_SPEED"] ?: 0} km/h")
                road.append(", 限速: ${extras["LIMITED_SPEED"] ?: 0} km/h")
                road.append(", 方向: ${extras["CAR_DIRECTION"] ?: 0}°\n")
                sb.append(road)
                naviLog.append(road)
            }

            // 下一个转弯
            extras["NEXT_ROAD_NAME"]?.let {
                turn.append("↪️ 下一个转弯:\n")
                turn.append("├─ 道路: ${extras["NEXT_ROAD_NAME"]}")
                turn.append(", 类型: ${getTurnIconDesc(extras["ICON"]?.toString()?.toIntOrNull())}")
                turn.append(", 距离: ${extras["SEG_REMAIN_DIS_AUTO"] ?: "${extras["SEG_REMAIN_DIS"]}米"}")
                turn.append(", 时间: ${extras["SEG_REMAIN_TIME"] ?: 0}秒\n")
                sb.append(turn)
                naviLog.append(turn)
            }

            // 摄像头
            extras["CAMERA_DIST"]?.let {
                val cameraDist = extras["CAMERA_DIST"].toString().toIntOrNull() ?: 0
                val cameraId = extras["cameraID"].toString().toIntOrNull() ?: 0
                val camType = extras["CAMERA_TYPE"].toString().toIntOrNull() ?: 0

                val sbCam = StringBuilder()
                sbCam.append("📸 摄像头信息:\n")
                sbCam.append("├─ ID: ${extras["cameraID"]}")
                sbCam.append(", 距离: ${extras["CAMERA_DIST"]}米")
                sbCam.append(", 类型: ${getCameraTypeDesc(camType)}")
                sbCam.append(", 限速: ${extras["LIMITED_SPEED"]?.let { if (it.toString().toInt() > 0) "$it km/h" else "未知" } ?: "未知"}\n")
                sb.append(sbCam)
                naviLog.append(sbCam)
            }

            extras["TRAFFIC_LIGHT_NUM"]?.let {
                val traffic_light_num = extras["TRAFFIC_LIGHT_NUM"].toString().toIntOrNull() ?: 0
                val remain_traffic_light_num = extras["routeRemainTrafficLightNum"].toString().toIntOrNull() ?: 0

                val sbTraffic = StringBuilder()
                sbTraffic.append("🚦 红绿灯信息:\n")
                sbTraffic.append("├─ 总数: ${remain_traffic_light_num}")
                sbTraffic.append(", 当前剩余: ${traffic_light_num}个")

                sb.append(sbTraffic)
                naviLog.append(sbTraffic)
            }
        }

        if(keyType == 60073) {
            trafficLog.clear()

            // 导航概览
            extras["trafficLightStatus"]?.let {
                val traffic_status = extras["trafficLightStatus"].toString().toIntOrNull() ?: -1
                val traffic_dir = extras["dir"].toString().toIntOrNull() ?: -1

                if(naviLog.isEmpty()){
                    traffic.append("🚦 红绿灯信息:\n")
                    traffic.append("├─ 状态: ${getNavTrafficDesc(traffic_status)}(${traffic_status})")
                }else{
                    traffic.append(", 状态: ${getNavTrafficDesc(traffic_status)}(${traffic_status})")
                }
                traffic.append(", 方向: ${getTrafficDirDesc(traffic_dir)}(${traffic_dir})")
                //traffic.append(", 绿灯剩余: ${extras["greenLightLastSecond"] ?: -1}秒")
                traffic.append(", 剩余: ${extras["redLightCountDownSeconds"] ?: -1}秒\n")

                sb.append(traffic)
                trafficLog.append(traffic)
            }
        }

        // 13012 车道级引导解析
        if (keyType == 13012) {
            parseDriveWay(extras)
        }

        if(!lane_overtake) {
            val laneStrArray = Array(0) { "" }
            sendLaneToPythonIO(laneStrArray, 0)
        }

        val logShow = StringBuilder()
        logShow.append(naviLog)
        logShow.append(trafficLog)

        updateRawLogNav(logShow.toString())

        sb.append("📋 原始数据:\n" + rawData.toString())
        return sb.toString()
    }

    private fun getRoadTypeDesc(type: Int?): String {
        return when (type) {
            //0 -> "高速($type)"
            //1 -> "国道($type)"
            //6 -> "快速($type)"
            //else -> "公路($type)"
            1 -> "高速($type)"
            2 -> "国道($type)"
            0 -> "快速($type)"
            else -> "公路($type)"
        }
    }

    private fun getTurnIconDesc(icon: Int?): String {
        return when (icon) {
            1 -> "直行"
            2 -> "左转"
            3 -> "右转"
            4 -> "左变道"
            5 -> "右变道"
            6 -> "左后方转弯"
            7 -> "右后方转弯"
            8 -> "左掉头"
            9 -> "右掉头"
            10 -> "靠左行驶"
            11 -> "靠右行驶"
            12 -> "进入环岛"
            13 -> "离开环岛"
            14 -> "收费站"
            65 -> "左岔路"
            66 -> "右岔路"
            else -> "其它($icon)"
        }
    }

    private fun getxTurnInfoDesc(turnInfo: Int): String {
        return when (turnInfo) {
            1 -> "左转"
            2 -> "右转"
            3 -> "左变道"
            4 -> "右变道"
            5 -> "直行"
            6 -> "直行"
            7 -> "直行"
            8 -> "直行"
            else -> "$turnInfo"
        }
    }

    private fun getCpTrafficDesc(status: Int?): String {
        return when (status) {
            0 -> "OFF"
            1 -> "RED"
            2 -> "GREEN"
            else -> "其它($status)"
        }
    }

    private fun getCpXStateDesc(status: Int?): String {
        return when (status) {
            0 -> "跟车"
            1 -> "巡航"
            2 -> "e巡航"
            3 -> "e停车"
            4 -> "e准备"
            5 -> "e停止"
            else -> "其它($status)"
        }
    }

    private fun getNavTrafficDesc(status: Int?): String {
        return when (status) {
            2 -> "红灯"
            3 -> "绿灯"
            4 -> "黄灯"
            else -> "其它($status)"
        }
    }

    private fun getTrafficDirDesc(dir: Int?): String {
        return when (dir) {
            1 -> "左转"
            2 -> "右转"
            3 -> "掉头"
            4 -> "直行"
            else -> "其它($dir)"
        }
    }

    private fun getCameraTypeDesc(type: Int): String {
        return when (type) {
            0 -> "测速摄像头"
            1 -> "监控摄像头"
            2 -> "闯红灯摄像头"
            3 -> "违章拍照"
            4 -> "流动测速"
            5 -> "区间测速起点"
            6 -> "区间测速终点"
            7 -> "其他"
            13 -> "行人+闯红灯"
            else -> "其它($type)"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        receiver?.let { unregisterReceiver(it) }
        receiver = null // FIX
        sendJob?.cancel()
        //cmUdpService.stop()

        pollRunnable?.let { sound_handler.removeCallbacks(it) }
        pollRunnable = null

        //executor.shutdown()
    }

    override fun onResume() {
        super.onResume()
        loadUserSettings()
    }

    private fun convert_tbt_type(icon: Int?): Int {
        return when (icon) {
            1    -> 51  // 直行 -> notification straight直行
            2    -> 12   // 左转 -> turn left
            3    -> 13   // 右转 -> turn right
            4    -> 7    // 左前方转弯 -> fork left
            5    -> 6    // 右前方转弯 -> fork right
            6    -> 12   // 左后方转弯 -> turn left
            7    -> 13   // 右后方转弯 -> turn right
            8    -> 12   // 左转掉头 -> turn left
            9    -> 13   // 右转掉头 -> turn right
            10   -> 51  // 靠左行驶 -> notification straight直行
            11   -> 51  // 靠右行驶 -> notification straight直行
            12   -> 51  // 进入环岛 -> notification straight直行
            13   -> 51  // 离开环岛 -> notification straight直行
            14   -> 142 // 收费站 -> rotary straight直行
            16   -> 153 // 隧道 -> straight
            65   -> 7    // 走左侧岔路 -> fork left
            66   -> 6    // 走右侧岔路 -> fork left
            else -> -1   // 未知或未匹配
        }
    }

    private fun convert_x_tbt_type(nTBTTurnType: Int) : Int {

        // xTurnInfo : 1: left turn, 2: right turn, 3: left lane change,
        //             4: right lane change, 5: rotary, 6: tg, 7: arrive or uturn
        val turnTypeMapping: Map<Int, Triple<String, String, Int>> = mapOf(
            12 to Triple("turn", "left", 1),
            16 to Triple("turn", "sharp left", 1),

            // new
            1000 to Triple("turn", "slight left", 1),
            1001 to Triple("turn", "slight right", 2),
            1002 to Triple("fork", "slight left", 3),
            1003 to Triple("fork", "slight right", 4),
            1006 to Triple("off ramp", "left", 3),
            1007 to Triple("off ramp", "right", 4),

            13 to Triple("turn", "right", 2),
            19 to Triple("turn", "sharp right", 2),

            102 to Triple("off ramp", "slight left", 3),
            105 to Triple("off ramp", "slight left", 3),
            112 to Triple("off ramp", "slight left", 3),
            115 to Triple("off ramp", "slight left", 3),

            101 to Triple("off ramp", "slight right", 4),
            104 to Triple("off ramp", "slight right", 4),
            111 to Triple("off ramp", "slight right", 4),
            114 to Triple("off ramp", "slight right", 4),

            7 to Triple("fork", "left", 3),
            44 to Triple("fork", "left", 3),
            17 to Triple("fork", "left", 3),
            75 to Triple("fork", "left", 3),
            76 to Triple("fork", "left", 3),
            118 to Triple("fork", "left", 3),

            6 to Triple("fork", "right", 4),
            43 to Triple("fork", "right", 4),
            73 to Triple("fork", "right", 4),
            74 to Triple("fork", "right", 4),
            123 to Triple("fork", "right", 4),
            124 to Triple("fork", "right", 4),
            117 to Triple("fork", "right", 4),

            131 to Triple("rotary", "slight right", 5),
            132 to Triple("rotary", "slight right", 5),
            140 to Triple("rotary", "slight left", 5),
            141 to Triple("rotary", "slight left", 5),
            133 to Triple("rotary", "right", 5),
            134 to Triple("rotary", "sharp right", 5),
            135 to Triple("rotary", "sharp right", 5),
            136 to Triple("rotary", "sharp left", 5),
            137 to Triple("rotary", "sharp left", 5),
            138 to Triple("rotary", "sharp left", 5),
            139 to Triple("rotary", "left", 5),
            142 to Triple("rotary", "straight", 5),

            14 to Triple("turn", "uturn", 7),
            201 to Triple("arrive", "straight", 8),

            51 to Triple("notification", "straight", 0),
            52 to Triple("notification", "straight", 0),
            53 to Triple("notification", "straight", 0),
            54 to Triple("notification", "straight", 0),
            55 to Triple("notification", "straight", 0),

            153 to Triple("", "", 6), // TG
            154 to Triple("", "", 6),
            249 to Triple("", "", 6)
        )

        val result = turnTypeMapping[nTBTTurnType]

        if (result != null) {
            val navType = result.first
            val navModifier = result.second
            val xTurnInfo = result.third

            return xTurnInfo
        } else {
            val navType = "invalid"
            val navModifier = ""
            val xTurnInfo = -1

            return xTurnInfo
        }
    }

    private fun convert_sdi_type(gaodeType: Int?): Int? {
        if (gaodeType == null) return null
        return when (gaodeType) {
            0 -> 1   // 测速摄像头 -> 超速 (固定式)
            1 -> 14   // 监控摄像头 -> 治安监控 CCTV
            2 -> 6   // 闯红灯摄像头 -> 闯红灯抓拍
            3 -> 5   // 违章拍照 -> 跟车抓拍摄像头
            4 -> 7   // 流动测速 -> 超速 (移动式)
            5 -> 2   // 区间测速起点 -> 区间测速开始
            6 -> 3   // 区间测速终点 -> 区间测速结束
            7 -> 4   // 其他 -> 区间测速中（或可按需求调整）
            13 -> 6  // 行人+闯红灯 -> 闯红灯抓拍
            else -> -1
        }
    }


    //------------------------绑定Service-------------------------------
    var keepAliveService: KeepAliveService? = null
    private var bound = false

    private val serviceConnection = object : ServiceConnection {

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as KeepAliveService.LocalBinder
            keepAliveService = binder.getService()
            bound = true

            // ===== 注册 UDP 回调 =====
            keepAliveService?.setOnCmReceive(::onCmUdpReceive)
            //keepAliveService?.setOnLaneReceive(::onLaneUdpReceive)
            //keepAliveService?.setOnNaviReceive(::onNaviUdpReceive)
            keepAliveService?.setOnStatusUpdate(::onUdpStatusUpdate)

            //keepAliveService?.laneUdpService?.sendEnable = true
            //keepAliveService?.naviUdpService?.sendEnable = true

            // ---------- 新增Lane Detect ----------
            setlaneDetectActivity(imageView, tvDevicID)

            //laneDetectFunc(this, this)
            keepAliveService?.startLaneDetect(this@MainActivity, this@MainActivity)

            //启动高德广播监听
            startListening()

            cmUdpServiceSendEnable = false
            naviUdpServiceSendEnable = true
            laneUdpServiceSendEnable = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            keepAliveService = null
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, KeepAliveService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
    }

    fun cmUdpServiceSend(json: String, host: String? = null, port: Int? = null) {
        keepAliveService?.sendCm(json, host, port)
    }

    fun laneUdpServiceSend(json: String, host: String? = null, port: Int? = null) {
        keepAliveService?.sendLane(json, host, port)
    }

    fun naviUdpServiceSend(json: String, host: String? = null, port: Int? = null) {
        keepAliveService?.sendNavi(json, host, port)
    }

    //------------------------------------------------------------------------------------------
    // ========================== UDP 接收回调 ==========================
    private fun onCmUdpReceive(json: JSONObject, packet: DatagramPacket) {
        handleCarrotManUdpJson(json, packet.address.hostAddress, packet.port)
        handleLaneUdpJson(json, packet.address.hostAddress, packet.port)
        handleNaviUdpJson(json, packet.address.hostAddress, packet.port)
        updateUIAfterUdp(json, source = "CM")
    }

    /*
    private fun onLaneUdpReceive(json: JSONObject, packet: DatagramPacket) {
        handleLaneUdpJson(json, packet.address.hostAddress, packet.port)
        updateUIAfterUdp(json, source = "LANE")
    }
    private fun onNaviUdpReceive(json: JSONObject, packet: DatagramPacket) {
        handleNaviUdpJson(json, packet.address.hostAddress, packet.port)
        updateUIAfterUdp(json, source = "NAVI")
    }
     */

    // ========================== 统一 UI 更新函数 ==========================
    private var lastUiUpdateTime = 0L
    private val UI_UPDATE_INTERVAL_MS = 100L // 100ms
    private var pendingUiUpdateJob: Job? = null

    private fun updateUIAfterUdp(json: JSONObject, source: String) {
        val now = System.currentTimeMillis()
        val elapsed = now - lastUiUpdateTime

        if (elapsed >= UI_UPDATE_INTERVAL_MS) {
            // 超过间隔，直接更新
            doUpdateUI(json, source)
            lastUiUpdateTime = now
            // 取消任何待定的延迟更新
            pendingUiUpdateJob?.cancel()
            pendingUiUpdateJob = null
        } else {
            // 时间没到，安排一次延迟更新
            pendingUiUpdateJob?.cancel() // 取消之前的延迟
            pendingUiUpdateJob = lifecycleScope.launch(Dispatchers.Main) {
                delay(UI_UPDATE_INTERVAL_MS - elapsed) // 等到间隔时间
                doUpdateUI(json, source)
                lastUiUpdateTime = System.currentTimeMillis()
                pendingUiUpdateJob = null
            }
        }
    }

    // ---------- 真正的 UI 更新逻辑，保留你原来的代码 ----------
    private fun doUpdateUI(json: JSONObject, source: String) {
        runOnUiThread {
            // 更新车辆状态 UI（CarrotMan UDP）
            if (source == "CM") {
                tvServerData.text =
                    "车辆:上线-${if (carState.isOnroad) "是" else "否"}, " +
                            "巡航-${if (carState.active) "是" else "否"}, " +
                            "定速:${carState.vCruise} km/h, 速度:${carState.vEgo} km/h"

                tvCpData.text =
                    "状态:${getCpXStateDesc(carState.xState)}(${carState.xState})," +
                            "信号=${getCpTrafficDesc(carState.xTrafficState)}(${carState.xTrafficState})," +
                            "限速:${nTrafficSpeedLimit}km/h," +
                            "时间:${xTrafficRedStateTimeElapsed}秒"

                tvSpeedLeft.text = "${carState.vEgo}"
            }

            // 更新 UDP 通讯状态 UI
            val cmService = keepAliveService?.cmUdpService
            //val laneService = keepAliveService?.laneUdpService
            //val naviService = keepAliveService?.naviUdpService
            val cmRecv = cmService?.recvCount ?: 0
            //val laneRecv = laneService?.recvCount ?: 0
            //val naviRecv = naviService?.recvCount ?: 0
            val cmSend = cmService?.sendCount ?: 0
            //val laneSend = laneService?.sendCount ?: 0
            //val naviSend = naviService?.sendCount ?: 0

            udpStateShow = run {
                val cmPortUsed = cmService?.isPortInUse ?: true
                //val lanePortUsed = laneService?.isPortInUse ?: true
                //val naviPortUsed = naviService?.isPortInUse ?: true

                //if (!cmPortUsed && !lanePortUsed && !naviPortUsed) {
                if (!cmPortUsed) {
                    "OP: ${carrotManIp ?: "-"}," +
                            "接收:${String.format("%02d", cmRecv)}," +
                            //"${String.format("%02d", laneRecv)}," +
                            //"${String.format("%02d", naviRecv)}," +
                            "发送:${String.format("%02d", cmSend)}"
                            //"${String.format("%02d", laneSend)}," +
                            //"${String.format("%02d", naviSend)}"
                } else {
                    val occupiedPorts = mutableListOf<String>()
                    if (cmPortUsed) occupiedPorts.add("CM")
                    //if (lanePortUsed) occupiedPorts.add("Lane")
                    //if (naviPortUsed) occupiedPorts.add("Navi")
                    "通讯: ${occupiedPorts.joinToString("/")}端口被占用"
                }
            }

            tvUdpStatus.text =
                udpStateShow + ",高德:${navDataCount?.let { String.format("%02d", it) } ?: "-"}"

            tvDistance.text = "${opStateManager.state.xTurnDist?.toInt()}m"
            lastUpdateTvDistanceTime = System.currentTimeMillis()

            // 更新 CarrotMan 日志（echo_cmd / result）
            val echoCmd = json.optString("echo_cmd", "")
            if (echoCmd.isNotEmpty()) {
                val result = json.optString("result", "")
                setRawLogVisible(true)
                updateRawLog(echoCmd, true)
                if (result.isNotEmpty()) updateRawLog(result, true)
            }

            // 可扩展：更新 Lane / Navi 特定 UI
            when (source) {
                "LANE" -> updateLaneUIIfNeeded(json)
                "NAVI" -> updateNaviUIIfNeeded(json)
            }

            //显示当前车辆所在车道
            var laneText = buildLaneText(vLaneResult, laneAction, visualLaneIdx)
            vLaneText = laneText
            if(laneText != ""){
                vLaneTextView.text = "视觉: ${laneText}"
                vLaneTextView.visibility = View.VISIBLE
                vLaneTextViewLayout.visibility = View.VISIBLE
            }else{
                vLaneTextView.visibility = View.GONE
                if(driveWayTextView.visibility == View.GONE && vLaneTextView.visibility == View.GONE){
                    vLaneTextViewLayout.visibility = View.GONE
                }
            }
        }
    }

    fun buildLaneText(result: LaneCalcResult, _laneAction: LaneChangeAction, visualIdx:Int): String {
        val total = result.laneCnt
        val current = result.leftLaneId  // 从左数第几条车道（1-based）

        if (total <= 0) return ""

        // 把视觉索引(右->左) 转成 左->右索引
        val targetFromLeft = total - visualIdx

        val sb = StringBuilder()
        for (i in 1..total) {

            val isCurrent = (i == current)
            val isTarget = (i == targetFromLeft)

            val ch = if (isCurrent) {
                val car = when (_laneAction) {
                    LaneChangeAction.LEFT  -> "←\uD83D\uDE98"
                    LaneChangeAction.LEFT_TURN  -> "←\uD83D\uDE98"
                    LaneChangeAction.RIGHT -> "\uD83D\uDE98→"
                    LaneChangeAction.RIGHT_TURN -> "\uD83D\uDE98→"
                    LaneChangeAction.KEEP  -> "\uD83D\uDE98"
                }
                if (isTarget) "$car*" else car
            } else {
                if (isTarget) "*" else "x"
            }

            sb.append("| ").append(ch).append(" ")
        }
        sb.append("|")

        return sb.toString()
    }

    // ========================== Lane / Navi 特定 UI 更新 ==========================
    private fun updateLaneUIIfNeeded(json: JSONObject) {
        // 如果你有 lane 的显示控件，这里更新
    }

    private fun updateNaviUIIfNeeded(json: JSONObject) {
        // 如果你有 Navi 的显示控件，这里更新
    }


    private fun onUdpStatusUpdate() {

    }
}
