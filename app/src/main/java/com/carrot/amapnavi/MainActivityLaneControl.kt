package com.carrot.amapnavi

import android.util.Log
import org.json.JSONObject

/* =========================================================
 * 变道控制模块（导航变道 / 转向）
 * 说明：本文件中的函数均为 MainActivity 的扩展函数，
 *       变道相关的状态与定时常量定义在 MainActivity.kt 中。
 * ========================================================= */

fun MainActivity.showLaneDebug(msg: String) {
    // 显示在 UI 上的日志
    setRawLogVisible(true)
    updateRawLog(msg, true)
}

fun MainActivity.LaneChangeActionStr(action: LaneChangeAction): String {
    if (action == LaneChangeAction.KEEP) {
        return "KEEP"
    } else if (action == LaneChangeAction.LEFT) {
        return "LEFT"
    } else if (action == LaneChangeAction.RIGHT) {
        return "RIGHT"
    } else if (action == LaneChangeAction.LEFT_TURN) {
        return "LEFT_TURN"
    } else if (action == LaneChangeAction.RIGHT_TURN) {
        return "RIGHT_TURN"
    } else {
        return "UNKNOW"
    }
}

/* =========================================================
 * CP 变道命令协议映射
 *
 * CP 侧真正生效的遥控变道接口是 carrot_man 的 UDP 7706：
 *   carrot_man_thread 收到报文 → CarrotServ.update()
 *     → carrotCmd / carrotArg → 发布 carrotMan 消息
 *       → desire_helper._update_atc_blinker() → LaneChangeState.preLaneChange
 * 其中只识别 carrotCmd == "LANECHANGE"，carrotArg = "LEFT"/"RIGHT"。
 *
 * 旧的 {"index","cmd","arg"} 是发到 amapnavi(4211) 的，amapnavi 只把它存进
 * shared_data.remote_cmd/remote_arg，CP 侧没有任何消费者（等于没接线），
 * 因此这里统一改发 7706，直接用 carrot_serv 的现成协议。
 * ========================================================= */
private fun laneCmdToCarrotCmd(cmd: String): String = when (cmd) {
    // 超车变道在 CP 侧同样是走变道控制
    "OVERTAKE" -> "LANECHANGE"
    // 路口转弯在 CP 侧也是走变道控制（转弯意图由 CP 自己按导航 TBT 推算 atcType）
    "TURN" -> "LANECHANGE"
    else -> cmd
}

//发送变道json命令（发往 carrot_serv / carrotMan，UDP 7706）
fun MainActivity.sendLaneCmd(index: Int, cmd: String, arg: String) {
    val json = JSONObject() //组JSON
    // CP 用 carrotIndex 与上一条命令比较判断是否为新命令，重发时会递增，值相同时会被去重
    json.put("carrotIndex", index)
    json.put("carrotCmd", laneCmdToCarrotCmd(cmd)) //LANECHANGE
    json.put("carrotArg", arg) //LEFT/RIGHT
    val jsonStr = json.toString()
    cmUdpServiceSend(jsonStr, port = carrotManOpPort)
}

fun MainActivity.sendBlinkerCtrlComm(index: Int, ctrl: String, state: String) {
    val json = JSONObject() //组JSON
    json.put("ctrlid", index)
    json.put("ctrl", ctrl) //blinker
    json.put("state", state) //LEFT/RIGHT
    val jsonStr = json.toString()
    cmUdpServiceSend(jsonStr, port = naviOpPort)
}

fun MainActivity.sendLaneActionCmd(action: LaneChangeAction) {
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

//检查盲区是否安全
fun MainActivity.LaneChangeSafetyCheck(action: LaneChangeAction): Int {
    if (action == LaneChangeAction.LEFT) {
        //左侧盲区有车
        if (opStateManager.state.l_front_blind) { //原车左侧前方
            return -2
        } else if (opStateManager.state.left_blindspot) { //原车左侧后方
            return -4
        } else if (opStateManager.state.lidar_lblind) { //激光雷达或摄像头左侧
            return -8
        } else if (left_lane_type == 1) { //左侧实线
            return -1
        }
    } else if (action == LaneChangeAction.RIGHT) {
        //右侧盲区有车
        if (opStateManager.state.r_front_blind) {
            return -2
        } else if (opStateManager.state.right_blindspot) {
            return -4
        } else if (opStateManager.state.lidar_rblind) {
            return -8
        } else if (right_lane_type == 1) { //实线
            return -1
        }
    }

    if (action == LaneChangeAction.LEFT_TURN) {
        //左侧盲区有车
        if (opStateManager.state.l_front_blind) { //原车左侧前方
            return -2
        }
    } else if (action == LaneChangeAction.RIGHT_TURN) {
        //右侧盲区有车
        if (opStateManager.state.r_front_blind) {
            return -2
        }
    }

    return 0
}

fun MainActivity.SendBlinkerControl(action: LaneChangeAction) {
    if (action == LaneChangeAction.LEFT) {
        sendBlinkerCtrlComm(0, "blinker", "left")
    } else if (action == LaneChangeAction.RIGHT) {
        sendBlinkerCtrlComm(0, "blinker", "right")
    } else if (action == LaneChangeAction.KEEP) {
        sendBlinkerCtrlComm(0, "blinker", "none")
    }
}

fun MainActivity.ShowPrepareLaneChangeAction(action: LaneChangeAction, edgeAction: LaneChangeAction, targetIdx: Int, visualIdx: Int) {
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
            if (targetIdx in navLane.laneStrArray.indices) {
                "${laneLetterToIcon(laneArray[targetIdx])}"
            } else {
                ""
            }
    val targetIdxStr = if (targetIdx > -1) { "${targetIdx + 1}" } else { "${targetIdx}" }
    val visualIdxStr = if (visualIdx > -1) { "${visualIdx + 1}" } else { "${visualIdx}" }
    if (edgeAction == LaneChangeAction.KEEP) {
        msg = "${nowTime} 导航(左->${targetIdxStr}$targetLaneStr), 视觉(${visualIdxStr}<-右), 动作=$action"
    } else {
        msg = "${nowTime} 导航(左->${targetIdxStr}$targetLaneStr), 视觉(${visualIdxStr}<-右), 动作=$action，靠边=$edgeAction"
    }
    Log.d("LaneDecision", msg)
    showLaneDebug(msg)

    if (action == LaneChangeAction.LEFT || action == LaneChangeAction.RIGHT) {
        msg = "${nowTime} 准备发送变道命令: $action"
    } else if (action == LaneChangeAction.LEFT_TURN || action == LaneChangeAction.RIGHT_TURN) {
        msg = "${nowTime} 准备发送转弯命令: $action"
    }
    Log.d("LaneCmd", msg)
    showLaneDebug(msg)
}

//变道执行
fun MainActivity.doLaneChangeAction(action: LaneChangeAction, edgeAction: LaneChangeAction, targetIdx: Int, visualIdx: Int) {
    //暂停变道动作
    if (laneActionPause) return

    val now = System.currentTimeMillis()
    val nowTime = nowTimeStr()
    val atc_state = opStateManager.state.atc_state ?: LaneChangeOff

    // 检查是否要发送变道命令
    if (!laneCmdSent || !laneChangeSpeak) {
        //无变道请求，退出函数
        if (action == LaneChangeAction.KEEP) {
            laneChangeSpeak = false
            blindDecSpeedEnable = false
            blindDecSpeedPause = false
            blindDecSpeedOffset = 0
            blindSafeTime = 0
            return
        }

        //正在变道中或者变道完成中，退出函数
        if (atc_state >= LaneChangeStarting) {
            laneChangeSpeak = false
            blindDecSpeedEnable = false
            blindDecSpeedPause = false
            blindDecSpeedOffset = 0
            blindSafeTime = 0
            return
        }

        //盲区不安全，播报盲区有车
        val lane_change_safe = LaneChangeSafetyCheck(action)
        if (lane_change_safe < 0) {
            blindSafeTime = now
            lastBlindDbTime = now

            //定时播报盲区有车
            if (now - lastBlindSpeakTime >= BLIND_SPEAK_INTERVAL) {
                lastBlindSpeakTime = now

                var msg = ""
                if (lane_change_safe < -1) {
                    appSpeakFile = "audio_car_watchout" //盲区有车
                    msg = "${nowTime} 侧面(${action})有车阻止变道"
                } else {
                    appSpeakFile = "audio_solid_blocked" //实线阻止变道
                    msg = "${nowTime} 侧面(${action})实线阻止变道"
                }
                Log.d("LaneCmd", msg)
                showLaneDebug(msg)
            }

            //盲区有车时减速
            if (navi_blind_dec_speed) {

                //发送转向灯控制命令
                if (now - lastBlindBlinkerTime >= BLIND_BLINKER_INTERVAL) {
                    lastBlindBlinkerTime = now

                    SendBlinkerControl(action)
                }

                val blindDecSpeedInterval = when (nav_mode) {
                    1 -> 200L
                    2 -> 400L
                    3 -> 600L
                    else -> 400L
                }

                if (now - lastBlindDecSpeedTime >= blindDecSpeedInterval) { //每0.5秒降低1km/h的速度
                    lastBlindDecSpeedTime = now
                    blindDecSpeedEnable = true

                    //前雷达盲区和后雷达盲区
                    if ((lane_change_safe == -2) || (lane_change_safe == -4)) {
                        if (!blindDecSpeedPause) {
                            if (blindDecSpeedOffset > -100) {
                                blindDecSpeedOffset-- //速度减1
                            }

                            var msg = when (action) {
                                LaneChangeAction.LEFT -> "${nowTime} 原车左盲区有车减速${blindDecSpeedOffset}km/h"
                                LaneChangeAction.RIGHT -> "${nowTime} 原车右盲区有车减速${blindDecSpeedOffset}km/h"
                                else -> "${nowTime} 原车盲区有车减速${blindDecSpeedOffset}km/h"
                            }
                            Log.d("LaneCmd", msg)
                            showLaneDebug(msg)
                        }
                    }
                    //激光雷达盲区
                    else if (lane_change_safe == -8) {
                        if (action == LaneChangeAction.LEFT) {
                            if (laneLineValid && enableLaneChange && vLaneResult.leftLaneCnt >= 1) {
                                if (!blindDecSpeedPause) {
                                    if (blindDecSpeedOffset > -100) {
                                        blindDecSpeedOffset-- //速度减1
                                    }

                                    var msg = ""
                                    msg = "${nowTime} 激光左盲区有车减速${blindDecSpeedOffset}km/h"
                                    Log.d("LaneCmd", msg)
                                    showLaneDebug(msg)
                                }
                            }
                        } else if (action == LaneChangeAction.RIGHT) {
                            if (laneLineValid && enableLaneChange && ((vLaneResult.rightLaneCnt >= 1 && !bHighWay) || (vLaneResult.rightLaneCnt >= 2 && bHighWay))) {
                                if (!blindDecSpeedPause) {
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
        } else {
            //盲区减速取消消抖1秒
            if (now - lastBlindDbTime >= 1000) {
                blindDecSpeedEnable = false
                blindDecSpeedPause = false
            }

            if (0L == blindSafeTime) {
                blindSafeTime = now
                return
            }

            if (nav_mode == 2) {
                if (now - lastBlindDbTime < 300) {
                    return
                }
            } else if (nav_mode == 3) {
                if (now - lastBlindDbTime < 600) {
                    return
                }
            }
        }

        //准备变道语音提示
        if (action == LaneChangeAction.LEFT) {
            if (!laneChangeSpeak) {
                ShowPrepareLaneChangeAction(action, edgeAction, targetIdx, visualIdx)

                appSpeakFile = "audio_pre_lane_left"
                laneChangeSpeak = true
                laneSpeakTime = now
                laneSpeakDelayTime = if (nav_mode == 3) { LANE_CHANGE_SPEAK_DELAY } else if (nav_mode == 2) { LANE_CHANGE_SPEAK_DELAY * 3 / 2 } else { LANE_CHANGE_SPEAK_DELAY / 2 }
            }
        } else if (action == LaneChangeAction.RIGHT) {
            if (!laneChangeSpeak) {
                ShowPrepareLaneChangeAction(action, edgeAction, targetIdx, visualIdx)

                appSpeakFile = "audio_pre_lane_right"
                laneChangeSpeak = true
                laneSpeakTime = now
                laneSpeakDelayTime = if (nav_mode == 3) { LANE_CHANGE_SPEAK_DELAY } else if (nav_mode == 2) { LANE_CHANGE_SPEAK_DELAY * 3 / 2 } else { LANE_CHANGE_SPEAK_DELAY / 2 }
            }
        } else if (action == LaneChangeAction.LEFT_TURN) {
            if (!laneChangeSpeak) {
                ShowPrepareLaneChangeAction(action, edgeAction, targetIdx, visualIdx)

                appSpeakFile = "audio_turn"
                laneChangeSpeak = true
                laneSpeakTime = now
                laneSpeakDelayTime = if (nav_mode == 3) { LANE_TURN_DELAY } else if (nav_mode == 2) { LANE_TURN_DELAY * 3 / 2 } else { LANE_TURN_DELAY / 2 }
            }
        } else if (action == LaneChangeAction.RIGHT_TURN) {
            if (!laneChangeSpeak) {
                ShowPrepareLaneChangeAction(action, edgeAction, targetIdx, visualIdx)

                appSpeakFile = "audio_turn"
                laneChangeSpeak = true
                laneSpeakTime = now
                laneSpeakDelayTime = if (nav_mode == 3) { LANE_TURN_DELAY } else if (nav_mode == 2) { LANE_TURN_DELAY * 3 / 2 } else { LANE_TURN_DELAY / 2 }
            }
        }

        //已经发送语音并且延时了1500ms
        if (laneChangeSpeak && ((now - laneSpeakTime >= laneSpeakDelayTime) || !navi_speak_first)) {
            //保存当前变道状态
            atc_state_start = opStateManager.state.atc_state ?: LaneChangeOff
            atc_state_curr = atc_state_start

            var msg = "----------------------------------------"
            Log.d("LaneDecision", msg)
            showLaneDebug(msg)

            //首次发送命令
            sendLaneActionCmd(action)

            if (action == LaneChangeAction.LEFT || action == LaneChangeAction.RIGHT) {
                msg = "${nowTime} 变道命令${action}已发出"
            } else if (action == LaneChangeAction.LEFT_TURN || action == LaneChangeAction.RIGHT_TURN) {
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
    } else {
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

        // CP 侧用 carrotIndex 是否变化来判断"这是新命令"，重发必须递增序号，
        // 否则会被 CP 当成重复命令丢弃（旧协议发到 amapnavi 时序号没人消费，所以原来只在 nav_mode<=1 递增）
        laneChangeCmdIndex++
        sendLaneActionCmd(laneAction)
        lastCmdSendTime = now
        laneCmdSentRetryCnt++

        //控制转向灯
        SendBlinkerControl(laneAction)
    }
}

fun MainActivity.atcStateName(atc_state: Int): String {
    val stateStr = when (atc_state) {
        LaneChangeOff -> "Off"
        PreLaneChange -> "Prepare"
        LaneChangeStarting -> "Starting"
        LaneChangeFinishing -> "Finishing"
        else -> "UNKNOWN($atc_state)"
    }

    return stateStr
}

fun MainActivity.processLaneChangeState(atcState: Int?) {
    //变道状态为空或变道动作暂停或者未发送变道命令
    if (atcState == null || laneActionPause || !laneCmdSent) return

    val now = System.currentTimeMillis()
    val nowTime = nowTimeStr()

    //控制转向灯
    SendBlinkerControl(laneAction)

    //变道状态转文字
    val stateStr = atcStateName(atcState)

    //正在变道中...
    if (atcState == LaneChangeStarting && !inLaneChangeStarting) {
        inLaneChangeStarting = true

        val msg = "${nowTime} 变道进行中..., 状态：$stateStr"
        Log.d("LaneState", msg)
        showLaneDebug(msg)
    } else if (atcState != LaneChangeStarting) {
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
    if (atc_state_curr != atcState) {
        val time_state_change = now - lastAtcStateChangeTime
        val msg = "${nowTime} 变道状态变化: ${atcStateName(atc_state_curr)} -> $stateStr, time=$time_state_change ms"
        Log.w("LaneState", msg)
        showLaneDebug(msg)

        //是否用户干预了变道
        if ((atc_state_curr == PreLaneChange || atc_state_curr == LaneChangeStarting) && atcState == LaneChangeOff) {
            var user_cancel_delay = USER_CALCEL_LANE_CHANGE_NORMAL_DELAY
            if (nav_mode <= 1) {
                user_cancel_delay = USER_CALCEL_LANE_CHANGE_FAST_DELAY
            } else if (nav_mode >= 3) {
                user_cancel_delay = USER_CALCEL_LANE_CHANGE_LONG_DELAY
            }
            val msg = "${nowTime} 驾驶员干预变道，变道停止，冷却时间${user_cancel_delay} ms"
            Log.d("LaneState", msg)
            showLaneDebug(msg)

            appSpeakFile = "audio_lane_change_cancel"
            completeLaneChange(user_cancel_delay)
        } else {
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

    if (atcState == LaneChangeStarting || atc_state_curr == LaneChangeStarting) {
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
        3 -> atc_timeout + 1000L
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

//重置变道动作
fun MainActivity.resetLaneAction() {
    if (laneCmdSent) {
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
    laneSpeakDelayTime = if (nav_mode == 3) { LANE_CHANGE_SPEAK_DELAY } else { LANE_CHANGE_SPEAK_DELAY / 2 }

    //validLaneActionDbTime = now
    //Log.d("LaneDecision", "resetLaneAction, ${validLaneActionDbTime}")
}

//完成状态处理
fun MainActivity.completeLaneChange(delay_time: Long) {
    //控制转向灯
    SendBlinkerControl(LaneChangeAction.KEEP)

    if (delay_time > 0) {
        laneActionPause = true

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            //清除状态和标志
            resetLaneAction()

            val nowTime = nowTimeStr()
            val msg = "${nowTime} 清除所有变道标志, index++, laneAction=$laneAction"
            Log.d("LaneState", msg)
            showLaneDebug(msg)
        }, delay_time)
    } else {
        //清除状态和标志
        resetLaneAction()

        val nowTime = nowTimeStr()
        val msg = "${nowTime} 清除所有变道标志, index++, laneAction=$laneAction"
        Log.d("LaneState", msg)
        showLaneDebug(msg)
    }
}
