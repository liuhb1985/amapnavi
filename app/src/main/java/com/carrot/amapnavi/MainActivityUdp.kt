package com.carrot.amapnavi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.View
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket

/* =========================================================
 * UDP / 广播数据处理模块
 * 说明：本文件中的函数均为 MainActivity 的扩展函数。
 * ========================================================= */

fun MainActivity.handleNaviUdpJson(json: JSONObject, packetAddress: String, packetPort: Int) {
    val device = json.optString("device", "")
    val resp = json.optString("resp", "")
    if (resp == "") {
        naviSrvIp = json.optString("ip", packetAddress)
        if (device == "navi") {
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
        //声音请求（原 Python sound_center：报文里的 sound 索引 → 播报）
        if (json.has("sound")) {
            SoundCenter.requestIndex(json.optInt("sound", 0))
        }
    }
}

fun MainActivity.handleLaneUdpJson(json: JSONObject, packetAddress: String, packetPort: Int) {
    val device = json.optString("device", "")
    val resp = json.optString("resp", "")
    if (resp == "") {
        laneSrvIp = json.optString("ip", packetAddress)
        if (device == "lane") {
            laneSrvPort = json.optInt("port", packetPort)
        }
        laneSrvValid = true
    }

    val str_echo_cmd = json.optString("echo_cmd", "")
    if (str_echo_cmd != "") {
        val echo_cmd_result = json.optString("result", "")
        if (echo_cmd_result != "") {
            setRawLogVisible(true)
            updateRawLog(str_echo_cmd, true)
            updateRawLog(echo_cmd_result, true)
        }
    }
}

fun MainActivity.handleCarrotManUdpJson(json: JSONObject, packetAddress: String, packetPort: Int) {
    val device = json.optString("device", "")
    val resp = json.optString("resp", "")
    if (resp == "") {
        carrotManIp = json.optString("ip", packetAddress)
        if (device == "" || device == "navi") {
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
        lane_detect_enable = json.optInt("lane_detect", 1)
        lane_show_enable = json.optInt("lane_show", 1)
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

/* =========================================================
 * 红绿灯检测注入 CP：carrotCmd = DETECT
 *
 * CP 侧(carrot_man.py) 的命令白名单包含 DETECT，carrot_serv._update_cmd 解析
 *   carrotArg = "<state>,<v1>,<v2>,<v3>"
 *   state ∈ Red Light / Yellow Light / Green Light / Left turn
 *   v1,v2 = 坐标；v3 = 置信度
 * 用于给 CP 的 trafficState 提供一个独立于摄像头的信源。
 *
 * 为什么值得做：CP 自己的红绿灯靠视觉模型，逆光 / 大雨 / 前方大车遮挡时会失效；
 * 高德的灯态来自路口信号灯数据，是完全独立的另一个信源，两者互补。
 *
 * 注意：与变道命令共用 nextLaneCmdIndex() 递增源——CP 靠 carrotIndex 变化
 * 判断"这是新命令"，序号不变会被当成重复命令丢弃。
 * ========================================================= */
fun MainActivity.sendTrafficDetectIfChanged() {
    if (!traffic_detect_cmd) return
    val status = nTrafficLightStatus
    if (status == lastTrafficDetectSent) return          // 只在状态变化时下发

    val state = when (status) {
        red_light -> "Red Light"
        green_light -> "Green Light"
        yellow_light -> "Yellow Light"
        else -> null
    }
    if (state == null) return

    // 倒计时可用时作为置信度参考；不可用给固定值
    val conf = if (nTrafficLightSeconds > 0) 100 else 80
    val index = nextLaneCmdIndex()
    val json = JSONObject().apply {
        put("carrotIndex", index)
        put("carrotCmd", "DETECT")
        put("carrotArg", "$state,0,0,$conf")
    }
    cmUdpServiceSend(json.toString(), port = carrotManOpPort)
    lastTrafficDetectSent = status
    AppLogger.log(this, "TRAFFIC", "红灯注入CP: DETECT index=$index arg=${json.optString("carrotArg")}(高德status=$status)")
}

// -----------------------------
// 高德广播监听（导航 / 红绿灯数据）
// -----------------------------
fun MainActivity.startListening() {
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
                // 把高德红绿灯注入 CP（与 CP 自己的视觉红绿灯互补）
                sendTrafficDetectIfChanged()
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

// ========================== UDP 接收回调 ==========================
fun MainActivity.onCmUdpReceive(json: JSONObject, packet: DatagramPacket) {
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
fun MainActivity.updateUIAfterUdp(json: JSONObject, source: String) {
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
fun MainActivity.doUpdateUI(json: JSONObject, source: String) {
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

fun MainActivity.buildLaneText(result: LaneCalcResult, _laneAction: LaneChangeAction, visualIdx:Int): String {
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
fun MainActivity.updateLaneUIIfNeeded(json: JSONObject) {
    // 如果你有 lane 的显示控件，这里更新
}

fun MainActivity.updateNaviUIIfNeeded(json: JSONObject) {
    // 如果你有 Navi 的显示控件，这里更新
}


fun MainActivity.onUdpStatusUpdate() {

}
