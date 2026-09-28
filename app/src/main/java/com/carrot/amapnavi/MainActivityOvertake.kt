package com.carrot.amapnavi

import android.util.Log
import org.json.JSONObject

/* =========================================================
 * 超车变道控制模块
 * 说明：本文件中的函数均为 MainActivity 的扩展函数，
 *       超车相关的状态与定时常量定义在 MainActivity.kt 中。
 * ========================================================= */

// 激进模式的最短"决定→下发"确认窗口(ms)：不保留窗口就等于没有任何复核机会
private const val OVERTAKE_MIN_CONFIRM_MS = 300L

// CP 进入 Prepare 后的"保持请求"重发间隔(ms)
//   CP 侧(desire_helper.py)只有 atc 变道请求持续为真时才会从 Prepare 推进到 Starting：
//     desire_enabled = driver_enabled or atc_enabled，一旦 atc 请求消失就立刻
//     lane_change_state = off(实车实测命令只发一次时 220ms 就被退回 Off)。
//   所以 Prepare 期间必须持续重发同一方向的命令(序号递增以免被去重丢弃)。
private const val OVERTAKE_PRE_KEEPALIVE_MS = 250L

fun MainActivity.sendOvertakeActionCmd(action: LaneChangeAction) {
    val dir = if (action == LaneChangeAction.LEFT || action == LaneChangeAction.LEFT_TURN) "LEFT" else "RIGHT"

    // 序号统一从全局递增源取，避免与导航链路撞号(见 nextLaneCmdIndex 注释)
    overtakeCmdIndex = nextLaneCmdIndex()

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

                var msg = ""
                if (lane_change_safe == -1 || lane_change_safe == LANE_BLOCK_UNKNOWN) {
                    appSpeakFile = "audio_solid_blocked" //实线/线型未知阻止超车
                    val why = if (lane_change_safe == LANE_BLOCK_UNKNOWN) "车道线判据不可用" else "实线"
                    msg = "${nowTime} 侧面(${action})${why}阻止超车"
                } else {
                    appSpeakFile = "audio_car_watchout" //盲区有车
                    msg = "${nowTime} 侧面(${action})有车阻止超车"
                }
                Log.d("LaneCmd", msg)
                showLaneDebug(msg)
            }

            overtakeSpeak = false //清除已经发送准备变道语音的标志
            return
        }

        //已经发送语音并且延时了1500ms
        // 激进模式原来直接跳过延时立即下发；现在仍允许缩短，但至少保留一个最小确认窗口，
        // 给二次安全确认留出时间(否则"决定超车"和"下发命令"在同一周期完成，没有任何复核机会)
        val speakElapsed = now - overtakeSpeakTime
        val delayOk = speakElapsed >= overtakeSpeakDelayTime ||
                (overtake_mode == 1 && speakElapsed >= OVERTAKE_MIN_CONFIRM_MS)
        if (overtakeSpeak && delayOk) {
            // ---- 命令下发前二次确认 ----
            // 与导航变道同理：从决定超车到真正下发之间车已行进一段距离，
            // 侧向盲区/车道线状态可能已变化，下发前必须重新过一次安全门。
            val recheck = LaneChangeSafetyCheck(action)
            if (recheck < 0) {
                overtakeSpeakTime = now
                if (now - lastOvertakeRecheckLogTime >= BLIND_SPEAK_INTERVAL) {
                    lastOvertakeRecheckLogTime = now
                    val why = when {
                        recheck == -1 -> "实线"
                        recheck == LANE_BLOCK_UNKNOWN -> "车道线判据不可用"
                        else -> "盲区有车"
                    }
                    val msg = "${nowTime} 发送前二次检查不通过($why,$recheck)，暂不下发超车命令 $action"
                    Log.w("OvertakeCmd", msg)
                    showLaneDebug(msg)
                }
                return
            }

            // ---- 导航接管硬互斥 ----
            // 导航正在靠边/进匝道/变道中(含粘滞窗口)时，超车不得下发命令，
            // 否则会出现"同一方向连续两条变道命令"或与导航变道互相抢车道。
            if (naviTakeoverActive()) {
                overtakeSpeak = false
                if (now - lastOvertakeRecheckLogTime >= BLIND_SPEAK_INTERVAL) {
                    lastOvertakeRecheckLogTime = now
                    val msg = "${nowTime} 导航接管中，取消本次超车下发 $action"
                    Log.w("OvertakeCmd", msg)
                    showLaneDebug(msg)
                }
                return
            }

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
            // 记录本次超车方向，用于反向变道冷却(见 MainActivityLaneDecision)
            if (action == LaneChangeAction.LEFT || action == LaneChangeAction.RIGHT) {
                overtakeLastDirection = action
                overtakeLastDirectionTime = now
            }
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

        // 同变道命令：重发必须递增序号，否则会被 CP 当成重复命令丢弃
        overtakeCmdIndex++
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

    //控制转向灯
    SendBlinkerControl(overtakeAction)

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

        finishOvertake(true)
        val lane_change_end_delay = when (overtake_mode) {
            1 -> 0
            3 -> OVERTAKE_END_DELAY
            else -> OVERTAKE_END_DELAY / 2   // overtake_mode == 2
        }
        completeOvertake(lane_change_end_delay)
    }

    // 如果状态有变化，则重置计时
    // 注意：这里必须比较超车自己的 overtake_atc_state_curr。
    //   原代码误用了导航变道的 atc_state_curr(它一直是 0)，于是只要 atc != Off 就
    //   每帧都判成"状态变化"：日志被刷屏、lastOvertakeAtcStateChangeTime 被不断刷新，
    //   导致"变道状态长时间未变"的超时保护在 Prepare/Starting/Finishing 期间完全失效
    //   (CP 停在 Prepare 不动时 App 会一直傻等)。
    if (overtake_atc_state_curr != atcState) {
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
            finishOvertake(false)
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

    // Prepare 期间持续保持变道请求(否则 CP 侧 desire 消失会立刻退回 Off，见常量注释)
    if (atcState == PreLaneChange && !inOvertakeStarting) {
        if (now - lastOvertakeCmdSendTime >= OVERTAKE_PRE_KEEPALIVE_MS) {
            // 序号由 sendOvertakeActionCmd 内部统一取全局递增号(见 nextLaneCmdIndex)
            sendOvertakeActionCmd(overtakeAction)       // 方向必须保持不变
            lastOvertakeCmdSendTime = now
            overtakeCmdSentRetryCnt++

            if (overtakeCmdSentRetryCnt % 4 == 1) {     // 每约 1s 记一条，避免刷屏
                val msg = "${nowTime} Prepare 保持中，持续重发超车命令" +
                        "(已发${overtakeCmdSentRetryCnt}次) index=$overtakeCmdIndex 方向=$overtakeAction"
                Log.d("OvertakeCmd", msg)
                showLaneDebug(msg)
            }
        }
    }

    // 变道状态未发生改变的时间超过指定时间后重置变道请求
    if (now - lastOvertakeAtcStateChangeTime >= OVERTAKE_FINISH_TIMEOUT) {
        val msg = "${nowTime} 超车命令超时，重置变道请求! 状态：$stateStr"
        Log.w("Overtake", msg)
        showLaneDebug(msg)

        appSpeakFile = "audio_lane_change_cancel"

        finishOvertake(false)
        val lane_change_end_delay = when (overtake_mode) {
            1 -> 0
            3 -> LANE_CHANGE_END_DELAY
            else -> LANE_CHANGE_END_DELAY / 2   // nav_mode == 2
        }
        completeOvertake(lane_change_end_delay)
    }
}

// 超车结束时的收尾处理：记录方向、按当前超车模式更新冷却时间
fun MainActivity.finishOvertake(success: Boolean) {
    val now = System.currentTimeMillis()
    val p = currentOvertakeParams()

    if (success) {
        //超车变道完成
        overtakeLastDirection = overtakeAction
        overtakeCooldownDuration = p.cooldownSuccess
    } else {
        //超车失败或被驾驶员取消
        overtakeLastDirection = LaneChangeAction.KEEP
        overtakeCooldownDuration = p.cooldownFailed
    }

    overtakeCooldownUntil = now + overtakeCooldownDuration
    overtakeTriggerStableTime = 0L
    overtakeInProgress = false
}

// 清空超车运行态(基础条件失效时调用)
fun MainActivity.clearOvertakeRuntime() {
    overtakeTriggerStableTime = 0L
    overtakeInProgress = false
}

// 超车决策原因日志去重与限频
fun MainActivity.logOvertakeReason(reason: String) {
    val now = System.currentTimeMillis()
    val changed = reason != overtakeLastReason
    overtakeLastReason = reason
    if (!changed) return
    if (now - overtakeLastLogTime < 1000L) return    //最多每秒记录一次
    overtakeLastLogTime = now
    Log.d("Overtake", reason)
    showLaneDebug(reason)
}
