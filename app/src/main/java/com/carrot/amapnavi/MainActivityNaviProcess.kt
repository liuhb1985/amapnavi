package com.carrot.amapnavi

import android.util.Log
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/* =========================================================
 * 导航主处理模块
 *   - 行驶距离推算 / 限速平滑 / 车道线有效性
 *   - 高德导航领航（AmapNavi）/ 自动超车（autoOvertake）
 *   - 定时超时检测
 * 说明：本文件中的函数均为 MainActivity 的扩展函数。
 * ========================================================= */

fun MainActivity.travelDistProcess() {
    val state = opStateManager.state

    val now = System.currentTimeMillis()
    val elapsed_time = now - lastTravelTime
    lastTravelTime = now

    if (travelDistTimeValid) {
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
fun MainActivity.smoothSpeedLimit() {
    val now = System.currentTimeMillis()
    //限速爬坡
    if (smooth_speed_limit) {
        if (naviState.nRoadLimitSpeed != null) {
            var nRoadLimitSpeedTmp = naviState.nRoadLimitSpeed ?: -1
            if ((nRoadLimitSpeedRamp != nRoadLimitSpeedTmp) || (nRoadLimitSpeedRampCnt > 0)) { //速度未爬坡完成或者还有发送次数
                //发送次数递减
                if ((nRoadLimitSpeedRamp == nRoadLimitSpeedTmp) && (nRoadLimitSpeedRampCnt > 0)) {
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
                if (cmUdpServiceSendEnable) {
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

fun MainActivity.laneLineProcess(): Boolean {
    val now = System.currentTimeMillis()
    if (laneDetectCnt == 0) {
        return false
    }
    //首次检测
    if (prevlaneDetectCnt < 0) {
        prevlaneDetectCnt = laneDetectCnt
        lastLaneValidTime = now
        return true
    }
    //超时1秒后检测次数没有变化
    if (((now - lastLaneValidTime) > 1000L) && (prevlaneDetectCnt == laneDetectCnt)) {
        return false
    }
    //检测次数变化，说明车道线检测正在工作
    if (prevlaneDetectCnt != laneDetectCnt) {
        lastLaneValidTime = now
        prevlaneDetectCnt = laneDetectCnt
    }

    return true
}

fun MainActivity.AmapNavi(lane_line_valid: Boolean): Boolean {
    val now = System.currentTimeMillis()
    val nowTime = nowTimeStr()

    var laneChangeEdge: LaneChangeAction = LaneChangeAction.KEEP
    var action = LaneChangeAction.KEEP
    py_overtake_disable = false

    //检查是否要关闭超车功能
    if ((!bHighWay && app_road_navi) || (bHighWay && app_navi)) {
        isStopPyOvertake(navLane)
    }

    if (!navi_blind_dec_speed) {
        blindDecSpeedEnable = false
        blindDecSpeedPause = false
        blindDecSpeedOffset = 0
    }

    //导航处理
    if (opStateManager.state.lidar_l && opStateManager.state.lidar_r && //激光雷达在线
        !naviDataTimeout && opStateManager.state.engaged && ((!bHighWay && app_road_navi) || (bHighWay && app_navi))
    ) { //开启了APP导航和开启了op定速，并且开启了导航
        if (carState.vEgo > (navi_min_speed / 3.6)) { //车辆非静止状态
            if (app_navi_valid != 1) {
                app_navi_valid = 1
                val msg = "${nowTime} 领航功能正常工作中"
                Log.d("LaneState", msg)
                showLaneDebug(msg)

                atcInfoShow = 0
            }

            var doForkNow = false

            //检查引导类型和引导距离是否有变化(有来设置各种重置标志)
            if ((lastXTurnInfo != opStateManager.state.xTurnInfo) || (lastNTurnDistMax != opStateManager.state.nTurnDistMax)) {
                resetSuggestLaneTimer = true
                enableLaneChange = true
                enableLaneChangeTimer = now
                disableLaneChangeTimer = now
            }

            //检查是否要靠边行驶或转弯
            if (opStateManager.state.xTurnInfo >= 0) {

                //引导类型有变化，重置变道操作
                if (lastXTurnInfo != opStateManager.state.xTurnInfo) {
                    val msg = "${nowTime} 导航(${getxTurnInfoDesc(opStateManager.state.xTurnInfo)}->${getxTurnInfoDesc(lastXTurnInfo)}), ${opStateManager.state.xTurnDist}米，重置状态"
                    Log.d("LaneState", msg)
                    showLaneDebug(msg)

                    resetLaneAction()
                } else if (lastNTurnDistMax != opStateManager.state.nTurnDistMax) {
                    val msg = "${nowTime} 导航(${getxTurnInfoDesc(opStateManager.state.xTurnInfo)}),${lastNTurnDistMax}m->${opStateManager.state.nTurnDistMax}m，重置状态"
                    Log.d("LaneState", msg)
                    showLaneDebug(msg)

                    resetLaneAction()
                }

                //------------------------------------------------------------------------------------------------------------
                //左变道或者左转
                if (opStateManager.state.xTurnInfo == TurnInfo_ForkLeft || opStateManager.state.xTurnInfo == TurnInfo_TurnLeft) {
                    //--------------------高速---------------------
                    if (bHighWay) { //高速
                        //重置禁止变道相关变量
                        enableLaneChange = true
                        disableLaneChangeTimer = now
                        enableLaneChangeTimer = now

                        var atc_dist_h_tmp = atc_dist_h
                        //设置的变道距离比导航引导的最大距离还大(设置自动变道靠边距离为80%)
                        if (opStateManager.state.nTurnDistMax > 0 && atc_dist_h_tmp > opStateManager.state.nTurnDistMax * 0.8f) {
                            atc_dist_h_tmp = (opStateManager.state.nTurnDistMax * 0.8f).toInt()
                            if (atc_dist_h_tmp < fork_dist_h) {
                                atc_dist_h_tmp = fork_dist_h
                            }
                        }

                        //距离路口的距离小于设定的靠左行驶的距离(打方向盘靠边行驶)
                        if (fork_dist_h > 0 && opStateManager.state.xTurnDist > fork_dist_h && opStateManager.state.xTurnDist <= atc_dist_h_tmp) {
                            if (keep_left_en) {
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
                        else if (fork_dist_h > 0 && opStateManager.state.xTurnDist < fork_dist_h && opStateManager.state.xTurnInfo == TurnInfo_ForkLeft) {
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
                        } else {
                            atcInfoShow = 0
                        }
                    }
                    //--------------------低速---------------------
                    else {
                        //检查车辆是否已靠边(开启了车道线检测)
                        if (enableLaneChange) {
                            if (lane_line_valid && left_lane_type >= 1) //左侧为实线
                            {
                                if ((now - disableLaneChangeTimer) > 5000) { //实线检测时间超过5秒
                                    enableLaneChange = false //禁止继续往左变道
                                    enableLaneChangeTimer = now

                                    appSpeakFile = "audio_last_lane" //车辆已靠边
                                    var msg = ""
                                    msg = "${nowTime} 车辆已靠最左边"
                                    Log.d("LaneCmd", msg)
                                    showLaneDebug(msg)
                                }
                            } else {
                                disableLaneChangeTimer = now
                            }
                        } else {
                            if (lane_line_valid && left_lane_type < 1) //左侧为虚线或无车道线
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
                            } else {
                                enableLaneChangeTimer = now
                            }
                        }

                        var atc_dist_l_tmp = atc_dist_l
                        //设置的变道距离比导航引导的最大距离还大(设置自动变道靠边距离为80%)
                        if (opStateManager.state.nTurnDistMax > 0 && atc_dist_l_tmp > opStateManager.state.nTurnDistMax * 0.8f) {
                            atc_dist_l_tmp = (opStateManager.state.nTurnDistMax * 0.8f).toInt()
                            if (atc_dist_l_tmp < fork_dist_l) {
                                atc_dist_l_tmp = fork_dist_l
                            }
                        }

                        //距离路口的距离小于设定的靠左行驶的距离(打方向盘靠边行驶)
                        if (fork_dist_l > 0 && opStateManager.state.xTurnDist >= fork_dist_l && opStateManager.state.xTurnDist <= atc_dist_l_tmp) {
                            if (keep_left_en) {
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
                        else if (fork_dist_l > 0 && opStateManager.state.xTurnDist < fork_dist_l && opStateManager.state.xTurnInfo == TurnInfo_TurnLeft && nav_turn) {
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
                        } else {
                            atcInfoShow = 0
                        }
                    }
                }
                //------------------------------------------------------------------------------------------------------------
                //右变道或者右转
                else if (opStateManager.state.xTurnInfo == TurnInfo_ForkRight || opStateManager.state.xTurnInfo == TurnInfo_TurnRight) {
                    //---------------------高速--------------------
                    if (bHighWay) //高速
                    {
                        var atc_dist_h_tmp = atc_dist_h
                        //设置的变道距离比导航引导的最大距离还大(设置自动变道靠边距离为80%)
                        if (opStateManager.state.nTurnDistMax > 0 && atc_dist_h_tmp > opStateManager.state.nTurnDistMax * 0.8f) {
                            atc_dist_h_tmp = (opStateManager.state.nTurnDistMax * 0.8f).toInt()
                            if (atc_dist_h_tmp < fork_dist_h) {
                                atc_dist_h_tmp = fork_dist_h
                            }
                        }

                        //进入提前变道靠边范围或存在禁止车道时
                        if ((fork_dist_h > 0 && opStateManager.state.xTurnDist >= fork_dist_h && opStateManager.state.xTurnDist <= atc_dist_h_tmp)
                            || (haveNavDisLane(navLane) && dis_lane_en)
                        ) {
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
                        } else {
                            //重置禁止变道相关变量
                            enableLaneChange = true
                            disableLaneChangeTimer = now
                            enableLaneChangeTimer = now
                        }

                        //距离路口的距离小于设定的靠右行驶的距离(打方向盘靠边行驶)
                        if (fork_dist_h > 0 && opStateManager.state.xTurnDist >= fork_dist_h && opStateManager.state.xTurnDist <= atc_dist_h_tmp) {
                            //已在进匝道的检测距离范围内(检测是否出现虚线)
                            //if(lane_line_valid && opStateManager.state.xTurnDist < (fork_dist_h + HIGHWAY_LANE_LINE_TRIG_DIST_OFFSET))
                            if (false) {
                                //右侧为虚线，变道
                                if (right_lane_type == 0) {
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
                            } else {
                                if (keep_right_en) {
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
                        else if (fork_dist_h > 0 && opStateManager.state.xTurnDist < fork_dist_h && opStateManager.state.xTurnInfo == TurnInfo_ForkRight) {
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
                        } else {
                            atcInfoShow = 0
                        }
                    }
                    //--------------------低速---------------------
                    else {
                        var atc_dist_l_tmp = atc_dist_l
                        //设置的变道距离比导航引导的最大距离还大
                        if (opStateManager.state.nTurnDistMax > 0 && atc_dist_l_tmp > opStateManager.state.nTurnDistMax * 0.8f) {
                            atc_dist_l_tmp = (opStateManager.state.nTurnDistMax * 0.8f).toInt()
                            if (atc_dist_l_tmp < fork_dist_l) {
                                atc_dist_l_tmp = fork_dist_l
                            }
                        }

                        //检查是否需要变道或者转弯
                        if (fork_dist_l > 0 && opStateManager.state.xTurnDist >= fork_dist_l && opStateManager.state.xTurnDist <= atc_dist_l_tmp) {
                            if (keep_right_en) {
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
                        } else if (fork_dist_l > 0 && opStateManager.state.xTurnDist < fork_dist_l && opStateManager.state.xTurnInfo == TurnInfo_TurnRight && nav_turn) {
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
                        } else {
                            atcInfoShow = 0
                        }
                    }
                } else { //无需控制的引导类型
                    atcInfoShow = 0
                }
            } else {
                atcInfoShow = 0
            }

            //重置立即变道状态
            if (atcInfoShow == 0) {
                opStateManager.state.doLaneForkNow = LaneChangeAction.KEEP.value
            }
            //更新最后的状态
            lastXTurnInfo = opStateManager.state.xTurnInfo
            lastNTurnDistMax = opStateManager.state.nTurnDistMax

            var targetIdx = -1
            var visualIdx = -1

            //-----------------------------------------
            //是否需要左转弯?
            if (laneChangeEdge == LaneChangeAction.LEFT_TURN) {
                action = LaneChangeAction.LEFT_TURN
                if (lastValidLaneAction != action || atcInfoChange) { //变道状态有变化
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
            else if (laneChangeEdge == LaneChangeAction.RIGHT_TURN) {
                action = LaneChangeAction.RIGHT_TURN
                if (lastValidLaneAction != action || atcInfoChange) { //变道状态有变化
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
            else if ((!laneCmdSent || (!inLaneChangeStarting && !inLaneChangeFinishing)) || (nav_mode == 1)) {
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
                else {
                    //有禁止变道标志
                    if (!enableLaneChange && action != LaneChangeAction.KEEP) {
                        if (laneChangeEdge == LaneChangeAction.LEFT) //靠左行驶请求
                        {
                            if (action == LaneChangeAction.LEFT && !doForkNow) {
                                action = LaneChangeAction.KEEP
                            }
                        } else if (laneChangeEdge == LaneChangeAction.RIGHT) //靠右行驶请求
                        {
                            if (action == LaneChangeAction.RIGHT && !doForkNow) {
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
                        val validLaneStableTime = if (nav_mode == 3) { 1000L } else { 500L }
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
                        Log.d("LaneDecision", "LaneChangeAction.KEEP, ${validLaneActionDbTime}")
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
        } else {
            if (app_navi_valid != 0) {
                app_navi_valid = 0
                val speedKph = carState.vEgo * 3.6
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
    } else {
        //领航条件失效
        if (app_navi_valid != -1) {
            app_navi_valid = -1

            var msg = "${nowTime} 条件不符合("
            if (!opStateManager.state.lidar_l) {
                msg += "左侧激光雷达不在线，"
                py_overtake_disable = true
                py_overtake_disable_reason = "左侧激光雷达不在线"
            }
            if (!opStateManager.state.lidar_r) {
                msg += "右侧激光雷达不在线，"
                py_overtake_disable = true
                py_overtake_disable_reason = "右侧激光雷达不在线"
            }
            if (!opStateManager.state.engaged) {
                msg += "OP定速未开，"
                //py_overtake_disable = true
            }
            if (!bHighWay && !app_road_navi) {
                msg += "公路车道领航未打开，"
            }
            if (bHighWay && !app_navi) {
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

    return (app_navi_valid > 0) && (laneChangeEdge != LaneChangeAction.KEEP || laneCmdSent)
}

fun MainActivity.autoOvertake(naviFlag: Boolean) {
    val now = System.currentTimeMillis()
    val nowTime = nowTimeStr()
    val s = opStateManager.state
    val p = currentOvertakeParams()

    overtakeDebugNaviFlag = naviFlag        //供诊断面板显示"领航"状态

    var overtakeMinSpeed = 30
    if (bHighWay) {
        overtakeMinSpeed = 60
    }

    // KT超车开关已打开时，接管并关闭Python侧超车控制器，避免两套逻辑同时下发变道命令
    if ((!bHighWay && app_road_overtake) || (bHighWay && app_overtake)) {
        if (!py_overtake_disable) {
            py_overtake_disable = true
            py_overtake_disable_reason = "KT超车已接管"
        }
    }

    // ============ 1. 基础条件判断(激光雷达在线/OP定速/超车开关/领航未接管) ============
    if (!(s.lidar_l && s.lidar_r                       //激光雷达在线
                && s.engaged && !naviFlag              //开启了op定速并且领航未控制
                && ((!bHighWay && app_road_overtake) || (bHighWay && app_overtake)))) {

        //条件原因(同时用于日志与诊断面板)
        var condReason = ""
        if (!s.lidar_l) condReason += "左侧激光雷达不在线，"
        if (!s.lidar_r) condReason += "右侧激光雷达不在线，"
        if (!s.engaged) condReason += "OP未定速，"
        if (!bHighWay && !app_road_overtake) condReason += "公路超车未打开，"
        if (bHighWay && !app_overtake) condReason += "高速超车未打开，"
        if (naviFlag) condReason += "领航控制中，"

        if (app_overtake_valid != -1) {
            app_overtake_valid = -1
            val msg = "${nowTime} 条件不符合(${condReason})"

            Log.d("Overtake", msg)
            showLaneDebug(msg)

            //重置超车状态
            resetOvertakeAction()
        }
        clearOvertakeRuntime()
        logOvertakeStage("1-基础条件", "不超车", condReason)
        return
    }

    // ============ 2. 处理超车变道状态机(每周期调用：检测变道完成/驾驶员干预/超时) ============
    processOvertakeLaneChangeState(s.atc_state)

    // ============ 3. 车速门限 ============
    val vEgo = s.vEgo ?: 0
    if (vEgo < overtakeMinSpeed) { //车辆速度允许超车
        if (app_overtake_valid != 0) {
            app_overtake_valid = 0
            val msg = "${nowTime} 车速过低(${vEgo}km/h), 小于${overtakeMinSpeed}km/h，暂停超车功能"
            Log.d("Overtake", msg)
            showLaneDebug(msg)

            //重置变道状态
            resetOvertakeAction()
            validLaneActionDbTime = now
        }
        clearOvertakeRuntime()
        logOvertakeStage("3-车速门限", "暂停", "车速${vEgo}km/h<${overtakeMinSpeed}km/h")
        return
    }

    if (app_overtake_valid != 1) {
        app_overtake_valid = 1
        val msg = "${nowTime} 超车功能正常工作中"
        Log.d("Overtake", msg)
        showLaneDebug(msg)
    }

    // ============ 4. 变道命令已发送或正在收尾，等待状态机完成 ============
    if (overtakeCmdSent || overtakeActionPause) {
        overtakeInProgress = true
        logOvertakeStage("4-等待变道完成", "进行中",
            "cmdSent=${overtakeCmdSent} pause=${overtakeActionPause} atc=${s.atc_state}")
        return
    }
    overtakeInProgress = false

    // ============ 5. 超车冷却 ============
    if (now < overtakeCooldownUntil) {
        overtakeTriggerStableTime = 0L
        logOvertakeReason("${nowTime} 超车冷却中(${p.modeName})")
        logOvertakeStage("5-冷却", "不超车", "剩余${overtakeCooldownUntil - now}ms")
        return
    }

    // ============ 6. 超车触发条件评估 ============
    val (trigger, triggerReason) = evaluateOvertakeTrigger()
    if (!trigger) {
        overtakeTriggerStableTime = 0L
        logOvertakeReason("${nowTime} 不超车：$triggerReason")
        logOvertakeStage("6-触发评估", "不超车", triggerReason)
        return
    }
    //要求触发条件稳定一段时间，避免雷达数据抖动导致误触发
    if (overtakeTriggerStableTime == 0L) {
        overtakeTriggerStableTime = now
        logOvertakeStage("6-触发稳定", "计时开始", "需稳定${p.stableTime}ms | $triggerReason")
        return
    }
    if (now - overtakeTriggerStableTime < p.stableTime) {
        logOvertakeStage("6-触发稳定", "等待中",
            "已${now - overtakeTriggerStableTime}ms/${p.stableTime}ms | $triggerReason")
        return
    }

    // ============ 7. 左右两侧安全性评估(毫米波雷达 + 激光雷达侧向数据) ============
    val leftEval = evaluateOvertakeSide(LaneChangeAction.LEFT)
    val rightEval = evaluateOvertakeSide(LaneChangeAction.RIGHT)

    //收集可用方向：保守模式仅允许左侧超车，常规/激进允许左右两侧
    val candidates = ArrayList<OvertakeSideEval>(2)
    if (leftEval.safe) candidates.add(leftEval)
    if (rightEval.safe && p.allowRightOvertake) candidates.add(rightEval)
    if (candidates.isEmpty()) {
        logOvertakeReason("${nowTime} 不超车：两侧均不安全(左:${leftEval.reason}; 右:${rightEval.reason})")
        logOvertakeStage("7-两侧评估", "不超车",
            "两侧均不安全(左:${leftEval.reason}; 右:${rightEval.reason})")
        return
    }

    //激进模式取评分更高的方向；常规/保守优先左侧超车
    val ordered = if (p.preferLeftFirst) {
        candidates.sortedBy { if (it.side == LaneChangeAction.LEFT) 0 else 1 }
    } else {
        candidates.sortedByDescending { it.score }
    }

    // ============ 8. 导航车道校验并执行 ============
    for (c in ordered) {
        if (decideOvertakeLane(vLaneResult, navLane, c.side, bHighWay, road_median).second == c.side) {
            logOvertakeReason("${nowTime} 超车(${c.side}/${p.modeName}): ${c.reason} | $triggerReason")
            logOvertakeStage("8-执行超车", "超车${c.side}", "${c.reason} | $triggerReason")
            doOvertakeAction(c.side)
            return
        }
    }

    logOvertakeReason("${nowTime} 不超车：导航车道不允许(${ordered[0].reason})")
    logOvertakeStage("8-导航校验", "不超车", "导航车道不允许(${ordered[0].reason})")
}


//导航处理
fun MainActivity.naviProcess() {

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

    if (isOvertakeControllerReady()) {
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

fun MainActivity.startTimeoutChecker() {
    timeoutJob?.cancel()
    timeoutJob = lifecycleScope.launch(Dispatchers.IO) {
        delay(5000)
        var imageViewCheckTime = 0L

        while (isActive) {
            delay(100) // 每0.1秒检查一次
            val now = System.currentTimeMillis()

            //导航处理
            naviProcess()

            if (now - imageViewCheckTime > 1000) {
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

            if (!cmUdpServiceSendEnable) {
                continue;
            }

            if (elapsed > 10_000) { // 超过10秒没收到数据
                naviDataTimeout = true
                if (lastTimeoutSendTime == 0L || now - lastTimeoutSendTime >= 1_000) { // 超时期间每1秒发送一次
                    if (!carrotManIp.isNullOrEmpty()) {
                        if (carState.vCruise > 0 && carState.vCruise <= 140) {
                            var limit_speed = carState.vCruise
                            val limitedJson = JSONObject().apply {
                                put("nRoadLimitSpeed", limit_speed)
                            }.toString()
                            lastJsonToSend = limitedJson
                            //cmUdpService.send(limitedJson)
                            cmUdpServiceSend(limitedJson, port = carrotManOpPort)
                            lastUdpSendTime = now
                            lastTimeoutSendTime = now

                            recvTimeoutCnt += 1
                            val logShow = "接收广播超时(${recvTimeoutCnt}次)：发送数据包" + limitedJson
                            if (!bShowRawLog) {
                                clearRawLog()
                            }
                            setRawLogVisible(true)
                            updateRawLog(logShow, true)
                        }
                    } else {
                        val limitedJson = JSONObject().apply {
                            put("port", carrotManAppPort)
                        }.toString()
                        lastJsonToSend = limitedJson
                        //cmUdpService.send(limitedJson)
                        cmUdpServiceSend(limitedJson, port = carrotManOpPort)
                        lastUdpSendTime = now
                        lastTimeoutSendTime = now

                        recvTimeoutCnt += 1
                        val logShow = "未连上设备：发送广播包(${recvTimeoutCnt}次)" + limitedJson
                        if (!bShowRawLog) {
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
