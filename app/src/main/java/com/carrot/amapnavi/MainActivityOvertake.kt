package com.carrot.amapnavi

import android.util.Log
import org.json.JSONObject

/* =========================================================
 * 超车变道控制模块
 * 说明：本文件中的函数均为 MainActivity 的扩展函数，
 *       超车相关的状态与定时常量定义在 MainActivity.kt 中。
 * ========================================================= */

fun MainActivity.sendOvertakeActionCmd(action: LaneChangeAction) {
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

fun MainActivity.doOvertakeAction(action: LaneChangeAction) {
    //暂停变道动作
    if (overtakeActionPause) return

    val now = System.currentTimeMillis()
    val nowTime = nowTimeStr()
    val atc_state = opStateManager.state.atc_state ?: LaneChangeOff

    // 检查是否要发送变道命令(未发送过超车命令或未播报超车语音)
    if (!overtakeCmdSent || !overtakeSpeak) {
        //无变道请求，退出函数
        if (action == LaneChangeAction.KEEP) {
            overtakeSpeak = false
            return
        }

        //正在变道中或者变道完成中，退出函数
        if (atc_state >= LaneChangeStarting) {
            overtakeSpeak = false
            return
        }

        //盲区不安全，播报盲区有车
        val lane_change_safe = LaneChangeSafetyCheck(action)
        if (lane_change_safe < 0) {
            if (now - lastOvertakeBlindSpeakTime >= BLIND_SPEAK_INTERVAL) { //定时播报盲区有车
                lastOvertakeBlindSpeakTime = now
                appSpeakFile = "audio_car_watchout" //盲区有车

                var msg = ""
                if (lane_change_safe < -1) {
                    msg = "${nowTime} 侧面(${action})有车阻止超车"
                } else {
                    msg = "${nowTime} 侧面(${action})实线阻止超车"
                }
                Log.d("LaneCmd", msg)
                showLaneDebug(msg)
            }

            overtakeSpeak = false //清除已经发送准备变道语音的标志
            return
        }

        //已经发送语音并且延时了1500ms
        if (overtakeSpeak && ((now - overtakeSpeakTime >= overtakeSpeakDelayTime) || overtake_mode == 1)) {
            //保存当前变道状态
            overtake_atc_state_start = opStateManager.state.atc_state ?: LaneChangeOff
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

            if (action == LaneChangeAction.LEFT || action == LaneChangeAction.RIGHT) {
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
        if (action == LaneChangeAction.LEFT) {
            if (!overtakeSpeak) {
                appSpeakFile = "audio_pre_lane_left"
                overtakeSpeak = true
                overtakeSpeakTime = now
                overtakeSpeakDelayTime = if (overtake_mode == 3) { LANE_CHANGE_SPEAK_DELAY } else { LANE_CHANGE_SPEAK_DELAY / 2 }
            }
        } else if (action == LaneChangeAction.RIGHT) {
            if (!overtakeSpeak) {
                appSpeakFile = "audio_pre_lane_right"
                overtakeSpeak = true
                overtakeSpeakTime = now
                overtakeSpeakDelayTime = if (overtake_mode == 3) { LANE_CHANGE_SPEAK_DELAY } else { LANE_CHANGE_SPEAK_DELAY / 2 }
            }
        }

        return
    }

    // 命令重发机制
    if ((overtakeCmdSent && now - lastOvertakeCmdSendTime >= RESEND_INTERVAL) && !overtakeCmdExecOk) {
        val msg = "${nowTime} 第{$overtakeCmdSentRetryCnt}次重发命令: $overtakeAction"
        Log.d("OvertakeCmd", msg)
        showLaneDebug(msg)

        if (overtake_mode <= 1) {
            overtakeCmdIndex++
        }
        sendOvertakeActionCmd(overtakeAction)
        lastOvertakeCmdSendTime = now
        overtakeCmdSentRetryCnt++
    }
}

fun MainActivity.resetOvertakeAction() {
    if (overtakeCmdSent) {
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
    overtakeSpeakDelayTime = if (overtake_mode == 3) { LANE_CHANGE_SPEAK_DELAY } else { LANE_CHANGE_SPEAK_DELAY / 2 }
}

//完成状态处理
fun MainActivity.completeOvertake(delay_time: Long) {
    if (delay_time > 0) {
        overtakeActionPause = true

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            //清除状态和标志
            resetOvertakeAction()

            val nowTime = nowTimeStr()
            val msg = "${nowTime} 清除所有超车标志, index++, Action=$overtakeAction"
            Log.d("Overtake", msg)
            showLaneDebug(msg)
        }, delay_time)
    } else {
        //清除状态和标志
        resetOvertakeAction()

        val nowTime = nowTimeStr()
        val msg = "${nowTime} 清除所有超车标志, index++, Action=$overtakeAction"
        Log.d("Overtake", msg)
        showLaneDebug(msg)
    }
}

fun MainActivity.processOvertakeLaneChangeState(atcState: Int?) {
    //变道状态为空或变道动作暂停或者未发送变道命令
    if (atcState == null || overtakeActionPause || !overtakeCmdSent) return

    val now = System.currentTimeMillis()
    val nowTime = nowTimeStr()

    //变道状态转文字
    val stateStr = atcStateName(atcState)

    //正在变道中...
    if (atcState == LaneChangeStarting && !inOvertakeStarting) {
        inOvertakeStarting = true

        val msg = "${nowTime} 超车变道进行中..., 状态：$stateStr"
        Log.d("Overtake", msg)
        showLaneDebug(msg)
    } else if (atcState != LaneChangeStarting) {
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
    if (atc_state_curr != atcState) {
        val time_state_change = now - lastOvertakeAtcStateChangeTime
        val msg = "${nowTime} 超车变道状态变化: ${atcStateName(overtake_atc_state_curr)} -> $stateStr, time=$time_state_change ms"
        Log.w("Overtake", msg)
        showLaneDebug(msg)

        //是否用户干预了变道
        if ((overtake_atc_state_curr == PreLaneChange || overtake_atc_state_curr == LaneChangeStarting) && atcState == LaneChangeOff) {
            val msg = "${nowTime} 驾驶员干预超车，变道停止，冷却时间${USER_CALCEL_OVERTAKE_DELAY} ms"
            Log.d("LaneState", msg)
            showLaneDebug(msg)

            appSpeakFile = "audio_lane_change_cancel"
            completeOvertake(USER_CALCEL_OVERTAKE_DELAY)
        } else {
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
