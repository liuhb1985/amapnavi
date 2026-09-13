package com.carrot.amapnavi

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/* =========================================================
 * 高德导航数据解析 / 车道级引导模块
 *   - 导航状态解析（updateNaviState / updateTrafficState）
 *   - 车道级引导解析（parseDriveWay）
 *   - 车道数据 UDP 广播（sendLaneToPythonIO）
 *   - 日志格式化（formatAmapData）与模拟数据
 * 说明：本文件中的函数均为 MainActivity 的扩展函数。
 * ========================================================= */

fun MainActivity.updateTrafficState(extras: Map<String, Any?>) {
    nTrafficLightStatus = extras["trafficLightStatus"]?.toString()?.toIntOrNull()?:-1
    nTrafficLightSeconds = extras["redLightCountDownSeconds"]?.toString()?.toIntOrNull()?:-1
}

fun MainActivity.updateNaviState(extras: Map<String, Any?>): Pair<Boolean, String?> {
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

        //本次"限速"是否来自驾驶员定速(没有可用道路限速时的兜底)
        //CP 侧是"道路限速一变就把定速改成这个值"，所以定速来源的值一旦再叠加偏移,
        //就会变成 限速=定速+偏移 -> CP把定速改成限速 -> 限速再+偏移 的自激增长,必须禁止后续叠加
        var bLimitFromVCruise = false

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
                bLimitFromVCruise = true //没有道路限速，以定速为限速
                var _limit_speed = 60
                if(carState.vCruise > 0 && carState.vCruise <= 140){
                    _limit_speed = carState.vCruise
                }
                _limit_speed
            }
            // 3)本次限速无效但是上一次限速有效
            else -> {
                //本次没有道路限速,一律以定速为准(否则 CP 会把定速改成上一次的限速值/60,导致怎么调定速速度都上不去)
                if(carState.vCruise > 0 && carState.vCruise <= 140){
                    bLimitFromVCruise = true //以定速为限速
                    nRoadLimitSpeedLast = carState.vCruise
                    carState.vCruise
                } else {
                    //定速未设定或不合法,退回原逻辑:保持上次限速
                    var _limit_speed = lastSpeed
                    //道路类型从高速变为低速，限速变为60
                    if((!bHighWay) && (nRoadCateLast != naviState.roadcate) && isHighway(nRoadCateLast)){
                        _limit_speed = 60
                        nRoadLimitSpeedLast = _limit_speed
                    }
                    _limit_speed
                }
            }
        }
        nRoadLimitvCruiseLast = carState.vCruise
        nRoadCateLast = naviState.roadcate?:-1

        //临时提速处理(限速来自定速时不叠加偏移，否则每发一次就会被 CP 累加一次偏移)
        if(computedSpeed < 60 && !bLimitFromVCruise) {
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
        if(sdi_speed_limit > 60 && !bHighWay && !bLimitFromVCruise) { //有摄像头限速并且限速大于60(限速来自定速时不用摄像头值改写定速)
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

        if(bRoadLimitIncDec && !bLimitFromVCruise){ //限速±5 按钮：限速来自定速时不能叠加，否则每次发送都会 +5 自激
            computedSpeed = computedSpeed + nRoadLimitOffset
        }
        if(road_speed_limit_percent > 0 && enable_speed_limit_up && !bLimitFromVCruise){
            val speedUp = computedSpeed.toFloat()*(1.0+(road_speed_limit_percent.toFloat()/100.0))
            computedSpeed = speedUp.toInt()
        }
        if(road_speed_limit_kph > 0 && enable_speed_limit_up && !bLimitFromVCruise){
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

fun MainActivity.parseDriveWay(extras: Map<*, *>?) {
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
fun MainActivity.simulateDriveWayBroadcast(rawData: Map<String, Any?>) {
    val extras = rawData["extras"] as? Map<*, *> ?: return
    parseDriveWay(extras)
}

fun MainActivity.sendLaneToPythonIO(arr: Array<String>, validCount: Int) {
    CoroutineScope(Dispatchers.IO).launch {
        //数据通过广播发送（原 Python auto_overtake_controller 监听 4210 接收，Python 版已删除）
        val validArr = arr.take(validCount).toTypedArray() //取有效部分
        val json = JSONObject() //组JSON
        if(validCount > 0) {
            json.put("laneStrArray", JSONArray(validArr))
        }
        json.put("laneValidCount", validCount)
        val jsonStr = json.toString()
        cmUdpServiceSend(jsonStr, host="255.255.255.255", port = overtakeAppPort)
    }
}

// -------- 模拟数据 --------
// 模拟一条有效数据
fun MainActivity.simulateValidData() {
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
fun MainActivity.simulateInvalidData() {
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
fun MainActivity.testDriveWaySimulation() {
    // 显示有效数据
    simulateValidData()  // UI 应显示 "|15|18*|32|"

    // 延迟2秒后模拟无效数据
    Handler(Looper.getMainLooper()).postDelayed({
        simulateInvalidData() // UI 隐藏
    }, 2000)
}

fun MainActivity.formatAmapData(rawData: Map<String, Any?>): String {
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
