package com.carrot.amapnavi

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.*

val carrotManAppPort = 7705
val carrotManOpPort = 7706
val laneAppPort = 4212
val laneOpPort = 4213
val naviAppPort = 5210
val overtakeAppPort = 4210
val naviOpPort = 4211

class KeepAliveService : Service() {

    companion object {
        const val CHANNEL_ID = "keep_alive_channel"
        const val NOTIFY_ID = 1
    }

    // ===================== Binder =====================
    inner class LocalBinder : Binder() {
        fun getService(): KeepAliveService = this@KeepAliveService
    }

    private val binder = LocalBinder()

    // lane 报文心跳：距上次发送超过该时长即无条件补发一帧
    //   目的：让"激光屏蔽标志"与 lane 通道不再依赖视觉帧
    //        (检测关闭 / 相机断流 / 取图失败时仍保持在线)
    //   注意：心跳会让 CP 的 lane_online 长期为 true，因此"线型是否被采信"
    //        改由 lane_valid 字段决定(见 sendLaneType 与 amap_navi.xiaogeLineValid)
    private val laneHeartbeatMs = 1000L

    @Volatile
    private var lastLaneSendMs = 0L

    override fun onBind(intent: Intent?): IBinder = binder

    // ===================== 全局 scope（唯一） =====================
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ===================== UDP 实例 =====================
    lateinit var cmUdpService: UdpService
    /** 4210：CP 的 overtake 通道，只做兼容握手（回响应 + 记录 CP 地址），业务数据走 cmUdpService(7705) */
    lateinit var opUdpService: UdpService
    //lateinit var laneUdpService: UdpService
    //lateinit var naviUdpService: UdpService

    /** 本机 IP（广播循环每 60 秒刷新，回包时用） */
    @Volatile
    var localIp: String = ""
        private set

    private fun currentLocalIp(): String =
        if (localIp.isNotEmpty()) localIp else (getAllLocalIpAddresses().firstOrNull() ?: "")

    // ===================== Job =====================
    private var broadcastJob: Job? = null
    private var appSrvSendJob: Job? = null
    private var sendJob: Job? = null

    private var lastJsonToSend: String? = null
    //var cmUdpServiceSendEnable = true

    // ===================== 生命周期 =====================
    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        startForeground(NOTIFY_ID, createNotification())
        requestIgnoreBatteryOptimizations()

        // --- UDP ---
        cmUdpService = UdpService(carrotManAppPort, carrotManOpPort, serviceScope)
        // 4210：CP 的 overtake 通道（原 Python auto_overtake_controller 的网络部分）
        opUdpService = UdpService(overtakeAppPort, naviOpPort, serviceScope)
        //laneUdpService = UdpService(laneAppPort, laneOpPort, serviceScope)
        //naviUdpService = UdpService(naviAppPort, naviOpPort, serviceScope)

        cmUdpService.start()
        // CP 的上报包带 ip/port，回一条 resp=overtake（节流 1 秒，CP 是 10Hz 单播）
        opUdpService.onReceiveData = { json, _ ->
            OvertakeCompat.responseFor(json, currentLocalIp())?.let { resp ->
                opUdpService.send(resp, OvertakeCompat.opIp, OvertakeCompat.opPort)
            }
        }
        opUdpService.start()
        //laneUdpService.start()
        //naviUdpService.start()

        startBroadcast()
        //startSendingDeviceId()
        //startSendJob()

        Log.i("KeepAliveService", "Service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    // ===================== Lane Detect =====================
    fun startLaneDetect(context: Context, activity: Activity) {
        startLaneDetectInternal(context, activity, serviceScope)
    }

    // ===================== UDP 状态检测 =====================
    fun isCmAvtive(): Boolean {
        if(!::cmUdpService.isInitialized){
            return false
        }
        return cmUdpService.udpActive
    }
    fun isLaneAvtive(): Boolean {
        if(!::cmUdpService.isInitialized){
            return false
        }
        return cmUdpService.udpActive
    }
    fun isNaviAvtive(): Boolean {
        if(!::cmUdpService.isInitialized){
            return false
        }
        return cmUdpService.udpActive
    }

    fun isCmValid(): Boolean {
        return ::cmUdpService.isInitialized
    }
    fun cmSendEnable(enable: Boolean) {
        if(!::cmUdpService.isInitialized){
            return
        }
        cmUdpService.sendEnable = enable
    }

    fun isLaneValid(): Boolean {
        //return ::laneUdpService.isInitialized
        return ::cmUdpService.isInitialized
    }
    fun isNaviValid(): Boolean {
        //return ::naviUdpService.isInitialized
        return ::cmUdpService.isInitialized
    }
    fun sendLane(enable: Boolean) {
        //laneUdpService.sendEnable = enable
    }
    fun sendNavi(enable: Boolean) {
        //naviUdpService.sendEnable = enable
    }

    // ===================== Activity → UDP（发送） =====================
    fun sendCm(json: String, host: String? = null, port: Int? = null) {
        if(!::cmUdpService.isInitialized){
            return
        }
        cmUdpService.send(json, host, port)
    }
    fun sendLane(json: String, host: String? = null, port: Int? = null) {
        if(!::cmUdpService.isInitialized){
            return
        }
        //laneUdpService.send(json, host, port)
        cmUdpService.send(json, host, port)
    }
    fun sendNavi(json: String, host: String? = null, port: Int? = null) {
        if(!::cmUdpService.isInitialized){
            return
        }
        //naviUdpService.send(json, host, port)
        cmUdpService.send(json, host, port)
    }

    fun sendLaneType(left_lane:Int, right_lane:Int){
        if(!::cmUdpService.isInitialized) return  // 防护

        // ★ 导航要求该侧靠边/进匝道时，向 CP 发布"虚线(0)"，打开 CP 侧的实线闸门：
        //   CP 侧 amap_navi.py 的 solid_line_blind() 直接由这里发布的 left_lane/right_lane
        //   计算(>=1 视为实线) → 置 lane_blind → desire_helper 拒绝变道。
        //   实车(2026-09-28 18:37 高速出口)：App 侧已放行并连续下发 8 次命令，
        //   但 CP 因 lane_blind 始终停在 Prepare 不执行 → 全部超时，没进匝道。
        //   放宽条件复用 App 安全门同款判定 solidLineRelaxed(临匝道窗口/连续岔路)，范围可控。
        //   计算收敛到 MainActivity.calcPublishLaneType()(单一计算点)，
        //   并把实际发布值记到 lastPubLaneL/R —— publishLaneTypeIfChanged() 靠它
        //   在放宽状态变化时立即补发，不必等下一个视觉帧。
        val (l, r) = MainActivity.instance?.calcPublishLaneType() ?: (left_lane to right_lane)
        val (supL, supR) = MainActivity.instance?.calcLidarSuppress() ?: (false to false)
        // 线型判据是否有效(检测开关开着 + 检测在跑 + 结果新鲜)：
        //   心跳会让通道长期在线，CP 靠这个字段区分"在线且有判据"与"在线但检测已关闭/停摆"
        val laneValid = laneTypeDetectValid()
        lastPubLaneL = l
        lastPubLaneR = r
        lastPubSupL = supL
        lastPubSupR = supR
        lastLaneSendMs = System.currentTimeMillis()

        val laneJson = JSONObject().apply {
            put("resp", "lane")
            put("port", laneAppPort)
            put("left_lane", l)
            put("right_lane", r)
            put("lane_valid", if (laneValid) 1 else 0)
            // 临匝道临时屏蔽激光盲区：CP 侧归一化盲区只保留"摄像头"与"原车前雷达"
            //   条件与实线放宽同源(solidLineRelaxed)；不发(=0)即恢复
            put("sup_lidar_l", if (supL) 1 else 0)
            put("sup_lidar_r", if (supR) 1 else 0)
        }.toString()

        when {
            !laneSrvIp.isNullOrEmpty() -> cmUdpService.send(laneJson, host = laneSrvIp, port = laneOpPort)
            !carrotManIp.isNullOrEmpty() -> cmUdpService.send(laneJson, host = carrotManIp, port = laneOpPort)
            else -> Log.w("KeepAliveService", "No IP to send lane result")
        }
    }

    // ===================== UDP → Activity（回调注册） =====================
    fun setOnCmReceive(cb: (JSONObject, DatagramPacket) -> Unit) {
        cmUdpService.onReceiveData = cb
    }

    //fun setOnLaneReceive(cb: (JSONObject, DatagramPacket) -> Unit) {
        //laneUdpService.onReceiveData = cb
    //}

    //fun setOnNaviReceive(cb: (JSONObject, DatagramPacket) -> Unit) {
        //naviUdpService.onReceiveData = cb
    //}

    fun setOnStatusUpdate(cb: () -> Unit) {
        cmUdpService.onUpdateStatus = cb
        //laneUdpService.onUpdateStatus = cb
        //naviUdpService.onUpdateStatus = cb
    }

    // ===================== Notification =====================
    private fun createNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AmapNavi Service Running")
            .setContentText("后台稳定通信中")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Keep Alive Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    // ===================== 电池优化 =====================
    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        if (packageManager.hasSystemFeature("android.hardware.type.automotive")) return

        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        intent.data = Uri.parse("package:$packageName")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(packageManager) != null) startActivity(intent)
    }

    // ===================== 广播 =====================
    private fun startBroadcast() {
        if (broadcastJob?.isActive == true) return

        broadcastJob = serviceScope.launch {

            var lastGetLocalIpAddrTime = 0L
            var lastLaneSendTime = 0L
            var lastNaviSendTime = 0L
            var lastSendIdTime = 0L
            var lastUdpSendTime = 0L

            // 本机 IP -> 广播地址 列表
            var ipBroadcastPairs: List<Pair<String, InetAddress>> = emptyList()

            while (isActive) {
                val now = System.currentTimeMillis()

                // ================= IP & Broadcast 地址，每 60 秒刷新 =================
                if (lastGetLocalIpAddrTime == 0L || now - lastGetLocalIpAddrTime >= 60_000L) {
                    ipBroadcastPairs = getAllLocalIpAddresses().mapNotNull { ipStr ->
                        val broadcast = try {
                            getBroadcastAddress(InetAddress.getByName(ipStr))
                        } catch (_: Exception) {
                            null
                        }
                        broadcast?.let { ipStr to it }
                    }
                    localIp = ipBroadcastPairs.firstOrNull()?.first ?: ""
                    lastGetLocalIpAddrTime = now
                }

                try {
                    // ================= Navi UDP：2 秒 =================
                    // CP 侧对心跳型客户端的超时是 5 秒；原来 3 秒一次余量偏小，
                    // 丢一个广播包就可能被判离线（客户端数量跳变），改为 2 秒。
                    if (now - lastNaviSendTime >= 2000L) {
                        for ((ip, broadcast) in ipBroadcastPairs) {
                            cmUdpService.send(
                                    JSONObject().apply {
                                        put("resp", "heartbeat")
                                        put("device", "overtake")
                                        put("ip", ip)
                                        put("port", overtakeAppPort)
                                    }.toString(),
                                    broadcast.hostAddress,
                                    naviOpPort
                            )
                        }
                        lastNaviSendTime = now
                    }

                    // ================= Lane UDP：3 秒（你以后可以改 30 秒） =================
                    if (now - lastLaneSendTime >= 3000L) {
                        for ((ip, broadcast) in ipBroadcastPairs) {
                            cmUdpService.send(
                                    JSONObject().apply {
                                        put("resp", "heartbeat")
                                        put("device", "app")
                                        put("ip", ip)
                                        put("port", laneAppPort)
                                    }.toString(),
                                    broadcast.hostAddress,
                                    laneOpPort
                            )
                        }
                        lastLaneSendTime = now
                    }

                    // ================= Server ID：2 秒 =================
                    if (now - lastSendIdTime >= 2000L) {
                        // 先判断lateinit变量有没有初始化
                        if (Global.deviceId.isNotBlank()) {
                            cmUdpService.send(
                                    JSONObject().apply {
                                        put("device_id", Global.deviceId)
                                        put("timestamp", now)
                                    }.toString(),
                                    appServerHost,
                                    appServerPort
                            )
                            lastSendIdTime = now
                        }
                    }

                    // ================= 最后一帧导航 =================
                    if (cmUdpService.sendEnable && now - lastUdpSendTime >= 3000L) {
                        lastJsonToSend?.let {
                            cmUdpService.send(it)
                            lastUdpSendTime = now
                        }
                    }

                } catch (e: Exception) {
                    Log.e("KeepAliveService", "broadcast error", e)
                }

                if(sendLaneResultFlag){
                    sendLaneResultFlag = false
                    sendLaneType(left_lane_type, right_lane_type)
                    laneSendCnt++
                    if(laneSendCnt >= 100) laneSendCnt = 1
                } else if (System.currentTimeMillis() - lastLaneSendMs >= laneHeartbeatMs) {
                    // 心跳：视觉帧停摆(检测关闭 / 相机断流 / 取图失败)时，
                    //   仍保持 lane 通道与激光屏蔽标志在线(否则 CP 3s 超时会复位屏蔽标志)
                    //   线型是否被 CP 采信由 lane_valid 字段决定，不受心跳影响
                    sendLaneType(left_lane_type, right_lane_type)
                }

                delay(50L)
            }
        }
    }

    // ===================== IP 工具 =====================
    private fun getBroadcastAddress(ip: InetAddress): InetAddress? =
        try {
            val p = ip.hostAddress.split(".")
            InetAddress.getByName("${p[0]}.${p[1]}.${p[2]}.255")
        } catch (e: Exception) {
            null
        }

    private fun getAllLocalIpAddresses(): List<String> {
        val ips = mutableListOf<String>()
        NetworkInterface.getNetworkInterfaces().toList().forEach { intf ->
            intf.inetAddresses.toList().forEach { addr ->
                if (!addr.isLoopbackAddress && addr is Inet4Address) {
                    ips.add(addr.hostAddress)
                }
            }
        }
        return ips
    }
}
