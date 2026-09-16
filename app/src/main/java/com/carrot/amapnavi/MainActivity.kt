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
import com.carrot.amapnavi.UdpDeviceActivity
import java.net.BindException
import androidx.appcompat.app.AlertDialog
import android.media.MediaPlayer
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.app.Activity
import android.Manifest
import android.content.*
import android.content.pm.PackageManager
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
import org.json.JSONArray
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date

/* =========================================================
 * 主页面类（仅保留：控件/状态字段、生命周期、Service 绑定）
 *
 * 其它功能模块已按功能拆分到以下文件（均为 MainActivity 的扩展函数）：
 *   - MainActivitySettings.kt     参数设置 / 语音轮询
 *   - MainActivityUi.kt           UI 显示 / 控件交互 / 原始日志
 *   - MainActivityLaneDecision.kt 车道决策 / 超车决策
 *   - MainActivityLaneControl.kt  变道控制
 *   - MainActivityOvertake.kt     超车变道控制
 *   - MainActivityNaviProcess.kt  导航主处理 / 超时检测
 *   - MainActivityUdp.kt          UDP / 广播数据 / UI 更新
 *   - MainActivityAmap.kt         高德数据解析 / 车道级引导
 *   - NavConstants.kt             常量与枚举
 *   - AppGlobalState.kt           全局状态与 UDP 通讯变量
 *   - NavRuntimeState.kt          车道/车辆运行态全局变量
 *   - AmapUtils.kt                高德转换/描述工具函数
 *   - TimeUtils.kt                时间工具
 *
 * 注意：为了支持跨文件访问，原 private 成员已放宽为 internal。
 * ========================================================= */
//主页面类
class MainActivity : AppCompatActivity() {

    internal lateinit var layoutSystemButtons: androidx.constraintlayout.widget.ConstraintLayout
    internal lateinit var web_container: LinearLayout
    internal lateinit var tvSpeedLeft: TextView
    internal lateinit var tvNavInfo: TextView
    internal lateinit var tvDistance: TextView
    internal lateinit var tvSpeedRight: TextView

    internal lateinit var imageView: ImageView
    internal lateinit var tvDevicID: TextView
    internal lateinit var tvUdpStatus: TextView
    internal lateinit var tvVersion: TextView
    internal lateinit var tvServerData: TextView
    internal lateinit var tvNavData: TextView
    internal lateinit var tvCpData: TextView
    internal lateinit var rawLog: TextView
    internal lateinit var rawLogNav: TextView
    internal lateinit var webView: WebView
    internal lateinit var imageViewLayout: LinearLayout
    internal lateinit var driveWayTextView: TextView
    internal lateinit var vLaneTextViewLayout: LinearLayout
    internal lateinit var vLaneTextView: TextView
    internal lateinit var tvOvertakeDebug: TextView
    internal lateinit var tvOvertakeDebugHead: TextView
    internal lateinit var tvRadarFront: TextView
    internal lateinit var tvRadarMmwLeft: TextView
    internal lateinit var tvRadarMmwRight: TextView
    internal lateinit var tvRadarLidarLeftFront: TextView
    internal lateinit var tvRadarLidarRightFront: TextView
    internal lateinit var tvRadarLidarLeftRear: TextView
    internal lateinit var tvRadarLidarRightRear: TextView
    internal lateinit var tvRadarLeftBlock: TextView
    internal lateinit var tvRadarRightBlock: TextView
    internal lateinit var tvRadarEgo: TextView
    internal lateinit var layoutOvertakeDebug: LinearLayout
    internal lateinit var btnOvertakeDebug: Button
    internal lateinit var btnOvertakeDebugClose: Button
    internal var overtakeDebugRefreshJob: Job? = null
    // 雷达卡片文本的上一次输出(内容没变就不重复写日志，避免刷屏)
    internal var radarCardsLogLast: String = ""
    internal lateinit var btnAppOverTake: Button
    //private lateinit var btnLaneDetect: Button
    internal lateinit var btnNavSetting: Button
    //private lateinit var btnImportSetting: Button
    internal lateinit var btnNavState: Button
    internal lateinit var btnShowLog: Button
    internal lateinit var btnMoreButton: Button
    internal lateinit var btnIncRoadLimit: Button
    internal lateinit var btnDecRoadLimit: Button
    internal lateinit var btnResetRoadLimit: Button
    internal lateinit var btnTbtTest: Button
    internal lateinit var btnLaneTest: Button
    internal lateinit var btnInstallFlask: Button
    internal lateinit var btnInstallOpencv: Button

    //限速偏移按钮是否已完成绑定：refreshRoadLimitBtnUi() 可能在 onCreate 早期或被广播触发
    internal fun roadLimitBtnsBound(): Boolean =
        ::btnIncRoadLimit.isInitialized &&
                ::btnResetRoadLimit.isInitialized &&
                ::btnDecRoadLimit.isInitialized
    //private lateinit var rawLogCam: TextView
    internal lateinit var scrollRawLog: ScrollView
    internal lateinit var scrollRawLogNav: ScrollView
    //private lateinit var scrollRawLogCam: ScrollView
    internal lateinit var btnClearLog: Button
    internal lateinit var btnCopyLog: Button

    internal var webview_owner = 0
    internal var imageViewLayoutShowEnable = true
    internal var imageViewLayoutShow = false
    internal var imageViewLayoutHide = false

    internal var ipList: List<String> = emptyList()
    internal var currentIpIndex = 0

    internal var receiver: BroadcastReceiver? = null

    internal var isTbtTestEnabled = false
    internal var tbtTestTime = 0L

    var xState : Int =  -1
    var xTrafficState : Int =  -1
    //var xStateCnt =  0
    var xTrafficRedState = false
    var xTrafficRedStateTime = 0L
    var xTrafficRedStateTimeElapsed = 0L

    var lastUpdateTvDistanceTime = 0L

    // 新增：导航状态结构体
    internal var naviState = NaviState()
    internal var nTBTTurnStationTime = 0L
    internal var nTBTTurnTypeStation = false
    internal var nTBTTurnTypeChange = false
    internal var nTBTTurnTypeMainChange = false
    internal var nTBTTurnTypePrev: Int = -1
    internal var nTBTTurnTypeMainPrev: Int = -1
    internal var nTBTTurnTypeMain: Int = -1
    internal var nTBTTurnType: Int = -1
    internal var nSdiTypeMain: Int = -1
    internal var nRoadLimitSpeedLast: Int = -1
    internal var nRoadLimitSpeed: Int? = -1
    internal var nRoadLimitSpeedRamp: Int = -1
    internal var nRoadLimitSpeedRampCnt: Int = 0
    internal var nTrafficCnt = -1
    internal var nTotalTrafficCnt = -1
    internal var nTrafficLightStatus = -1
    internal var nTrafficLightSeconds = -1
    internal var nTrafficSpeedLimit = -1
    internal var nTrafficSpeedLimitChange = false
    internal var navi_traffic_assist = false
    internal var cp_traffic_assist = false
    internal var redlight_traffic_assist = false
    internal var tunnel_dec_speed = true
    internal var app_speak = false
    internal var app_log = false
    internal var app_navi = false
    internal var app_road_navi = false
    internal var navi_speak = true
    internal var navi_speak_first = true
    internal var navi_blind_dec_speed = false
    internal var enable_speed_limit_up = false
    internal var py_overtake_disable = false
    internal var py_overtake_disable_prev = false
    internal var py_overtake_disable_reason = ""

    internal var suggest_lane_en = false
    internal var dis_lane_en = false
    internal var keep_left_en = false
    internal var keep_right_en = false
    internal var navi_min_speed = 20

    internal var road_median = true
    internal var road_right_first = true
    internal var lane_prob_enable = false
    internal var vision_keep_right = true
    internal var nav_turn = true
    internal var nav_mode = 2
    internal var smooth_speed_limit = false
    internal var naviTrafficAssistLast = false
    internal var red_light_min_speed_limit = 1
    internal var cp_min_speed_limit = 20
    internal var road_speed_limit_percent = 0
    internal var road_speed_limit_kph = 0
    internal var offset_highway = 20
    internal var offset_road = 15
    internal var min_speed_limit = 30
    internal var max_speed_limit = 130
    internal var station_dist_offset = 50
    internal var station_speed = 10
    internal var station_keep_time = 10
    internal var navi_traffic_assist_time = 10
    internal var nCameraId: Int? = -1
    internal var bCameraIdChange = false
    internal var bRoadLimitTest = false
    internal var bRoadLimitIncDec = false
    internal var nRoadLimitOffset = 0
    //上一次收到的高德有效道路限速(>0)：用于判断「高德限速变化」并自动重置手动偏移
    internal var nAmapLimitSpeedLast: Int = -1
    internal var nRoadLimitvCruiseLast: Int = -1
    internal var nRoadCateLast: Int = -1
    internal var bHighWay = false
    internal var gRoadCate: Int = 8
    internal var atc_dist_h = 2000
    internal var atc_dist_l = 500
    internal var fork_dist_h = 80
    internal var fork_dist_l = 30
    internal var top_pad_dp = 5

    internal var overtake_mode = 2
    internal var vision_lane_mode = 1
    internal var app_overtake = false
    internal var app_road_overtake = false
    // 侧向变道安全：App 自算判据(默认开) / 阈值跟随 CP(默认开, 关则用 App 内置默认值)
    internal var app_lidar_self_check = true
    internal var app_thresh_follow_cp = true
    // 侧向危险阈值(不跟随 CP 时使用)：mode 0=固定距离(m) 1=绝对速度时距(s)
    internal var lidar_front_dist_mode = 0
    internal var lidar_front_dist_m = 5f
    internal var lidar_front_time_s = 1.2f
    internal var lidar_rear_dist_mode = 0
    internal var lidar_rear_dist_m = 10f
    internal var lidar_rear_time_s = 1.5f
    // 超车反向变道冷却(ms)：刚往一侧超完车后，这段时间内不允许往反方向再超车变道
    internal var overtake_reverse_cooldown_ms = 20_000L

    var tunnelEnterTime: Long? = null  // 毫秒时间戳
    var tunnelKeepTime: Long? = null  // 毫秒时间戳
    var tunnelSpeed = -1 //隧道速度

    internal val sound_handler = Handler(Looper.getMainLooper())
    internal val pollIntervalMs = 100L   // 每 100ms 检查一次
    internal var pollRunnable: Runnable? = null

    @Volatile
    internal var lastJsonToSend: String? = null
    internal var sendJob: Job? = null
    internal var appSrvSendJob: Job? = null

    //Lane Detect
    //private lateinit var laneDetector: LaneDetector
    //private lateinit var imageView: ImageView
    //private val executor = Executors.newSingleThreadExecutor() // 使用单线程池处理推理

    // 保存上一次的车道类型文本
    internal var lastDriveWayText: String = ""

    internal val uiHandler = Handler(Looper.getMainLooper())
    internal val hideRawLogRunnable = Runnable {
        setRawLogVisible(false)
    }

    internal var inSuggestLaneTime = 0L
    internal var inOvertakeSuggestLaneTime = 0L

    // ------------------- 变道控制相关 -------------------
    internal val RESEND_INTERVAL = 1000L
    internal val LANE_CHANGE_END_DELAY = 2000L
    internal val USER_CALCEL_LANE_CHANGE_LONG_DELAY = 5000L
    internal val USER_CALCEL_LANE_CHANGE_NORMAL_DELAY = 2000L
    internal val USER_CALCEL_LANE_CHANGE_FAST_DELAY = 1000L
    internal val BLIND_SPEAK_INTERVAL = 3000L
    internal val BLIND_BLINKER_INTERVAL = 500L
    internal val LANE_CHANGE_SPEAK_DELAY = 1500L
    internal val LANE_TURN_DELAY = 800L
    internal val FINISH_TIMEOUT = 10000L   // 10秒超时保护，可调整

    internal val OVERTAKE_END_DELAY = 30000L
    internal val USER_CALCEL_OVERTAKE_DELAY = 5000L
    internal val OVERTAKE_FINISH_TIMEOUT = 10000L   // 10秒超时保护，可调整

    // 超车决策参数已按“超车模式(激进/常规/保守)”分级，见 OvertakeParams 与 currentOvertakeParams()

    var laneAction: LaneChangeAction = LaneChangeAction.KEEP
    var curLaneAction: LaneChangeAction = LaneChangeAction.KEEP
    var lastValidLaneAction: LaneChangeAction = LaneChangeAction.KEEP
    var prevLaneAction: LaneChangeAction = LaneChangeAction.KEEP
    var lastTargetIdx = -1 //最后一次导航车道编号
    var lastVisualIdx = -1
    var targetLaneIdx = -1 //车辆所在的导航车道编号(从0开始，从左往右)
    var visualLaneIdx = -1 //车辆所在视觉车道编号(从0开始，从右往左)
    var laneChangeCmdIndex = 0 //变道命令id
    internal var laneCmdSent = false //变道命令已发送标志
    internal var laneCmdExecOk = false //变道命令已执行标志
    internal var laneCmdSentRetryCnt = 0 //变道命令重发次数
    internal var laneChangeSpeak = false //准备变道命令已发送标志
    internal var inLaneChangeFinishing = false //变道正在完成中的标志
    internal var inLaneChangeStarting = false //变道正在进行中的标志

    internal var lastCmdSendTime = 0L //上次发送变道命令的时间
    internal var lastBlindSpeakTime = 0L //上次播报盲区有车语音的时间
    internal var lastBlindDecSpeedTime = 0L //上次减速时间
    internal var laneSpeakTime = 0L //播报准备变道的时间点s
    internal var laneChangeStartTime = 0L //变道进行中的时间
    internal var lastAtcStateChangeTime = 0L //最后一次ATC状态变化的时间
    internal var laneSpeakDelayTime = LANE_CHANGE_SPEAK_DELAY
    internal var validLaneActionDbTime = 0L
    internal var blindDecSpeedEnable = false
    internal var blindDecSpeedPause = false
    internal var blindDecSpeedOffset = 0
    internal var lastBlindDbTime = 0L
    internal var blindSafeTime = 0L
    internal var lastBlindBlinkerTime = 0L

    internal var app_navi_valid = -1 //APP导航条件成立
    internal var atc_state_start = 0 //首次发送变道命令时的atc状态
    internal var atc_state_curr = 0 //当前的atc状态
    internal var laneActionPause = false //暂停变道动作
    internal var atcInfoShow = 0
    internal var atcInfoChange = false
    internal var app_overtake_valid = -1

    var overtakeAction: LaneChangeAction = LaneChangeAction.KEEP
    var overtakeCmdIndex = 0 //变道命令id
    internal var overtake_atc_state_start = 0 //首次发送变道命令时的atc状态
    internal var overtake_atc_state_curr = 0 //当前的atc状态    var overtakeChangeCmdIndex = 0 //变道命令id
    internal var overtakeActionPause = false //暂停变道动作
    internal var overtakeActionPausePrev = false //暂停变道动作
    internal var overtakeCmdSent = false //变道命令已发送标志
    internal var overtakeCmdExecOk = false //变道命令已执行标志
    internal var overtakeCmdSentRetryCnt = 0 //变道命令重发次数
    internal var overtakeSpeak = false //准备变道命令已发送标志
    internal var inOvertakeFinishing = false //变道正在完成中的标志
    internal var inOvertakeStarting = false //变道正在进行中的标志

    internal var lastOvertakeCmdSendTime = 0L //上次发送变道命令的时间
    internal var lastOvertakeBlindSpeakTime = 0L //上次播报盲区有车语音的时间
    internal var overtakeSpeakTime = 0L //播报准备变道的时间点s
    internal var overtakeStartTime = 0L //变道进行中的时间
    internal var lastOvertakeAtcStateChangeTime = 0L //最后一次ATC状态变化的时间
    internal var overtakeSpeakDelayTime = LANE_CHANGE_SPEAK_DELAY
    //private var validLaneActionDbTime = 0L

    internal var naviDataTimeout = true
    internal var lastNaviReceiveTime = 0L
    internal var lastUdpSendTime = 0L
    internal var lastTrafficRecvTime = 0L
    internal var timeoutJob: Job? = null
    internal var isTimeout = false

    internal var lastTravelTime = System.currentTimeMillis()
    internal var travelDistTimeValid = false

    internal var lastLaneValidTime = System.currentTimeMillis()
    internal var prevlaneDetectCnt = -1
    internal var lastXTurnInfo = -1
    internal var lastNTurnDistMax = -1

    internal val HIGHWAY_LANE_LINE_TRIG_DIST_OFFSET = 20 //高速虚实线触发进匝道提前距离

    internal var lastTimeoutSendTime = 0L
    internal var recvTimeoutCnt = 0

    internal val RAW_LOG_MAX_LINES = 500

    // ICON → 方向集合映射（单字母表示）
    internal val driveWayIconMap: Map<Int, Set<String>> = mapOf(
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
    internal val driveWayIconMapCn: Map<Int, Set<String>> = mapOf(
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

    internal val naviLog = StringBuilder()
    internal val trafficLog = StringBuilder()

    // ========================== 统一 UI 更新函数 ==========================
    internal var lastUiUpdateTime = 0L
    internal val UI_UPDATE_INTERVAL_MS = 100L // 100ms
    internal var pendingUiUpdateJob: Job? = null

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

        layoutOvertakeDebug = findViewById(R.id.layoutOvertakeDebug)
        tvOvertakeDebug = findViewById(R.id.tvOvertakeDebug)
        tvOvertakeDebugHead = findViewById(R.id.tvOvertakeDebugHead)
        tvRadarFront = findViewById(R.id.tvRadarFront)
        tvRadarMmwLeft = findViewById(R.id.tvRadarMmwLeft)
        tvRadarMmwRight = findViewById(R.id.tvRadarMmwRight)
        tvRadarLidarLeftFront = findViewById(R.id.tvRadarLidarLeftFront)
        tvRadarLidarRightFront = findViewById(R.id.tvRadarLidarRightFront)
        tvRadarLidarLeftRear = findViewById(R.id.tvRadarLidarLeftRear)
        tvRadarLidarRightRear = findViewById(R.id.tvRadarLidarRightRear)
        tvRadarLeftBlock = findViewById(R.id.tvRadarLeftBlock)
        tvRadarRightBlock = findViewById(R.id.tvRadarRightBlock)
        tvRadarEgo = findViewById(R.id.tvRadarEgo)
        btnOvertakeDebug = findViewById(R.id.btnOvertakeDebug)
        btnOvertakeDebugClose = findViewById(R.id.btnOvertakeDebugClose)

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
        btnLaneTest = findViewById(R.id.btnLaneTest)

        //限速偏移按钮：初始化「重置」按钮上显示的当前偏移量
        refreshRoadLimitBtnUi()

        val container = findViewById<LinearLayout>(R.id.containerLinearLayout)
        web_container = findViewById<LinearLayout>(R.id.webLinearLayout)

        //显示版本号
        val versionName = this.packageManager
            .getPackageInfo(this.packageName, 0)
            .versionName
        tvVersion.text = "V" + versionName

        startSoundPolling() // 启动声音轮询

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

        // 车道线推理测试页（真机验证 ONNX 推理是否正常）
        btnLaneTest.setOnClickListener {
            showLaneTestPage()
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
            requestStorageThen { AppLogger.shareLogs(this) }
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
            refreshRoadLimitBtnUi()
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
            refreshRoadLimitBtnUi()
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
            refreshRoadLimitBtnUi()
            if(!bShowRawLog) {
                // 取消上一次再重新计时
                uiHandler.removeCallbacks(hideRawLogRunnable)
                uiHandler.postDelayed(hideRawLogRunnable, 5000)
            }
        }

        btnOvertakeDebug.setOnClickListener {
            val show = !overtakeDebugEnable
            setOvertakeDebugPanelVisible(show)
            btnOvertakeDebug.text = if (show) "隐藏" else "超车"
        }

        //诊断页内的关闭按钮
        btnOvertakeDebugClose.setOnClickListener {
            setOvertakeDebugPanelVisible(false)
            btnOvertakeDebug.text = "超车"
        }

        //诊断页内的"超车设置"入口：跳到超车专用设置页
        findViewById<Button>(R.id.btnOvertakeSetting).setOnClickListener {
            startActivity(android.content.Intent(this, OvertakeSettingActivity::class.java))
        }

        // 自动化测试入口：直接打开雷达诊断页
        //   adb shell am start -n com.carrot.amapnavi/.MainActivity --ez radar true
        // 原因：uiautomator 在本界面永远拿不到 idle(数据 200ms 刷新)，靠 dump 找按钮点击不可靠
        // 只在"本次显式启动"时生效：系统重建 Activity 会保留 intent，
        // 否则每次开机/恢复都会自动跳到超车诊断页
        if (savedInstanceState == null && intent?.getBooleanExtra("radar", false) == true) {
            setOvertakeDebugPanelVisible(true)
            btnOvertakeDebug.text = "隐藏"
        }
        intent?.removeExtra("radar")

        //超车专用日志：独立文件夹(AmapNavi/overtake/日期_0001.log)，与主日志(AmapNavi/app/)无关
        OvertakeLogger.init(this)
        val btnOvertakeLogToggle = findViewById<Button>(R.id.btnOvertakeLogToggle)
        val btnOvertakeLogShare = findViewById<Button>(R.id.btnOvertakeLogShare)
        val tvOvertakeLogState = findViewById<TextView>(R.id.tvOvertakeLogState)

        fun refreshOvertakeLogUi() {
            if (OvertakeLogger.enabled) {
                btnOvertakeLogToggle.text = "停止记录"
                tvOvertakeLogState.text = "超车日志:记录中 " + OvertakeLogger.todayFileName(this)
            } else {
                btnOvertakeLogToggle.text = "记录"
                tvOvertakeLogState.text = "超车日志:未记录"
            }
        }
        refreshOvertakeLogUi()

        fun doToggleOvertakeLog() {
            val on = OvertakeLogger.toggle(this)
            refreshOvertakeLogUi()
            Toast.makeText(
                this,
                if (on) "开始记录 → ${OvertakeLogger.logDirPath(this)}" +
                    "\n日志存这里，文件管理器可直接查看/发送"
                else "已停止记录，可点「发日志」导出",
                Toast.LENGTH_LONG
            ).show()
        }

        btnOvertakeLogToggle.setOnClickListener {
            //开始记录前先确保有存储权限（日志要写手机存储公共目录）；停止不需要
            if (OvertakeLogger.enabled) doToggleOvertakeLog()
            else requestStorageThen { doToggleOvertakeLog() }
        }

        btnOvertakeLogShare.setOnClickListener {
            requestStorageThen { OvertakeLogger.shareLogs(this) }
        }

        //点击模型可复制全部诊断内容，便于测试后反馈
        tvOvertakeDebug.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("OvertakeDebug", tvOvertakeDebug.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "超车诊断已复制", Toast.LENGTH_SHORT).show()
        }

        //点击顶栏结论同样可复制
        tvOvertakeDebugHead.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("OvertakeDebug", tvOvertakeDebug.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "超车诊断已复制", Toast.LENGTH_SHORT).show()
        }

        btnMoreButton.setOnClickListener {
            if(layoutSystemButtons.visibility == View.VISIBLE){
                layoutSystemButtons.visibility = View.GONE
            }else{
                layoutSystemButtons.visibility = View.VISIBLE
                refreshRoadLimitBtnUi()     //展开前先同步一次「重置」按钮上的当前偏移
                applyRoadLimitPanelHeight() //三个限速大按钮铺满剩余空间，底部留约20%
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

    //------------------------ 日志存储权限 ----------------------------
    // 日志优先写到手机存储 AmapNavi/ 下（主日志 app/、超车日志 overtake/，文件管理器可见、
    // 可自己挑文件发送），这需要 WRITE_EXTERNAL_STORAGE；没授权时先申请，授权/拒绝后都继续原动作
    // （拒绝也照样能用，只是回退到 app 私有目录 Android/data/<包名>/files/logs/）
    private var pendingAfterStoragePermission: (() -> Unit)? = null
    private val REQ_STORAGE_PERMISSION = 1001

    private fun requestStorageThen(action: () -> Unit) {
        if (LogStorage.hasWritePermission(this) || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            LogStorage.reset()   // 可能刚在系统设置里授权，重新探测一次用哪个目录
            action()
            return
        }
        pendingAfterStoragePermission = action
        requestPermissions(LogStorage.STORAGE_PERMISSIONS, REQ_STORAGE_PERMISSION)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_STORAGE_PERMISSION) return

        val idx = permissions.indexOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val granted = idx >= 0 && idx < grantResults.size &&
            grantResults[idx] == PackageManager.PERMISSION_GRANTED
        LogStorage.reset()   // 授权结果决定用公共目录还是私有目录，重新探测

        if (!granted) {
            Toast.makeText(
                this,
                "未授予存储权限：日志改存应用私有目录，文件管理器里可能看不到",
                Toast.LENGTH_LONG
            ).show()
        }

        val action = pendingAfterStoragePermission
        pendingAfterStoragePermission = null
        action?.invoke()
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

    //------------------------绑定Service-------------------------------
    var keepAliveService: KeepAliveService? = null
    private var bound = false

    private val serviceConnection = object : ServiceConnection {

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as KeepAliveService.LocalBinder
            keepAliveService = binder.getService()
            bound = true

            // ===== 注册 UDP 回调 =====
            keepAliveService?.setOnCmReceive { json, packet -> this@MainActivity.onCmUdpReceive(json, packet) }
            //keepAliveService?.setOnLaneReceive(::onLaneUdpReceive)
            //keepAliveService?.setOnNaviReceive(::onNaviUdpReceive)
            keepAliveService?.setOnStatusUpdate { this@MainActivity.onUdpStatusUpdate() }

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
}
