package com.carrot.amapnavi

import android.util.Log

/* =========================================================
 * 运行时自检 / 实时告警
 *
 * 目的：把"实时调试"要盯的判据做进程序本身，不依赖外部工具。
 *   决策循环每 100ms 调用一次 tick()，内部做轻量不变量检查，
 *   发现异常统一用 tag = AmapAlert 打印(W 级)，logcat 一条命令即可过滤：
 *       adb logcat -s AmapAlert
 *   每个类别的告警都做限频，避免刷屏；问题持续存在时会周期性重复提醒。
 *
 * 检查项：
 *   1) 决策循环停顿(正常周期 100ms，超过 1.5s 视为停顿)
 *   2) 同一条命令短时间重复发送(并发/重复下发)
 *   3) CP 停在 Prepare 不进入变道
 *   4) 命令已发但 CP 状态仍为 Off(命令未被接受)
 *   5) 车道线判据不可用持续(会整体禁止变道/超车)
 *   6) 变道/超车命令发出后长时间没有终态(卡住)
 * ========================================================= */
object HealthMonitor {

    const val TAG = "AmapAlert"

    private const val STALL_MS = 1500L              // 决策循环停顿判定
    private const val PREPARE_STUCK_MS = 3000L      // CP 停在 Prepare 判定
    private const val CMD_NO_RESPONSE_MS = 3000L    // 命令未被执行判定
    private const val UNKNOWN_HOLD_MS = 5000L       // 判据不可用持续判定
    private const val REPEAT_MS = 10_000L           // 同类告警重复提醒间隔

    private var lastTick = 0L
    private var lastStallAlert = 0L
    private var lastPrepareAlert = 0L
    private var lastNoRespAlert = 0L
    private var lastUnknownAlert = 0L
    private var lastStuckAlert = 0L

    // 命令发出后是否见过 CP 的响应(避免"变道完成后的收尾期"被误判成命令未被接受)
    private var cmdSentEdge = false
    private var sawAtcResponse = false

    // 判据不可用：记录开始时间，用于判断"持续"
    private var unknownSinceL = 0L
    private var unknownSinceR = 0L

    // 命令重复：记录上次 index 与重复次数
    private var lastCmdIndex = Int.MIN_VALUE
    private var lastCmdTime = 0L
    private var dupCount = 0

    /** 命令下发时调用（sendLaneCmd 里统一挂钩） */
    fun onCmdSent(index: Int, cmd: String, arg: String) {
        val now = System.currentTimeMillis()
        if (index == lastCmdIndex && now - lastCmdTime < 2000L) {
            dupCount++
            if (dupCount >= 2) {
                Log.w(TAG, "同一条命令重复下发: index=$index cmd=$cmd arg=$arg 已重复 ${dupCount + 1} 次" +
                        " → 检查是否有多个决策循环并发(或重发机制误判)")
            }
        } else {
            dupCount = 0
        }
        lastCmdIndex = index
        lastCmdTime = now
    }

    /** 决策循环每周期调用 */
    fun tick(a: MainActivity) {
        val now = System.currentTimeMillis()

        // ---------- 1) 决策循环停顿 ----------
        if (lastTick != 0L) {
            val gap = now - lastTick
            if (gap > STALL_MS && now - lastStallAlert > REPEAT_MS) {
                lastStallAlert = now
                Log.w(TAG, "决策循环停顿 ${gap}ms(正常 100ms)：App 可能被系统冻结/阻塞，期间所有自动变道都会失效")
            }
        }
        lastTick = now

        val s = opStateManager.state
        val atc = s.atc_state ?: LaneChangeOff

        // ---------- 2) 超车：命令已发但 CP 无响应 ----------
        if (a.overtakeCmdSent) {
            val waited = now - a.lastOvertakeCmdSendTime
            // 命令周期内只要见过任何非 Off 状态，就不再报"未被接受"
            //   (变道完成后 overtakeCmdSent 还要保留一段收尾期，之前会在这里误报)
            if (!cmdSentEdge) { cmdSentEdge = true; sawAtcResponse = false }
            if (atc != LaneChangeOff) sawAtcResponse = true
            if (atc == LaneChangeOff && !sawAtcResponse &&
                    waited > CMD_NO_RESPONSE_MS && now - lastNoRespAlert > REPEAT_MS) {
                lastNoRespAlert = now
                Log.w(TAG, "超车命令发出 ${waited}ms，CP 从未响应(一直 Off)：命令未被接受" +
                        "(检查 UDP 7706 连通性/carrotMan 是否收到)")
            }
            if (atc == PreLaneChange) {
                val stuck = now - a.lastOvertakeAtcStateChangeTime
                if (stuck > PREPARE_STUCK_MS && now - lastPrepareAlert > REPEAT_MS) {
                    lastPrepareAlert = now
                    Log.w(TAG, "CP 停在 Prepare ${stuck}ms 不进入变道(超车 ${a.overtakeAction})：" +
                            "命令已被接收但 CP 侧未推进，App 正在重发推动")
                }
            }
            // 命令发出后长时间既没完成也没超时(状态卡住)
            if (atc != LaneChangeOff && atc != PreLaneChange &&
                    now - a.lastOvertakeAtcStateChangeTime > 8000L &&
                    now - lastStuckAlert > REPEAT_MS) {
                lastStuckAlert = now
                Log.w(TAG, "超车变道状态 ${atcStateNameStatic(atc)} 已持续 " +
                        "${now - a.lastOvertakeAtcStateChangeTime}ms 未推进：可能卡在 CP 侧执行环节")
            }
        }

        // ---------- 3) 车道线判据不可用持续 ----------
        checkLaneUnknown(true, now)
        checkLaneUnknown(false, now)
    }

    private fun checkLaneUnknown(left: Boolean, now: Long) {
        val unknown = laneLineJudgement(left) == LaneLineJudgement.UNKNOWN
        if (unknown) {
            val since = if (left) unknownSinceL else unknownSinceR
            if (since == 0L) {
                if (left) unknownSinceL = now else unknownSinceR = now
            } else if (now - since > UNKNOWN_HOLD_MS && now - lastUnknownAlert > REPEAT_MS) {
                lastUnknownAlert = now
                val side = if (left) "左侧" else "右侧"
                val t = if (left) left_lane_type else right_lane_type
                Log.w(TAG, "${side}车道线判据不可用已持续 ${(now - since) / 1000}s(视觉线型=$t，CP 也无实线判据)：" +
                        "该侧变道/超车会被禁止(设置里可关\"车道线未知拦截\")")
            }
        } else {
            if (left) unknownSinceL = 0L else unknownSinceR = 0L
        }
    }
}

// 供日志使用的状态名(避免依赖 MainActivity 实例)
fun atcStateNameStatic(atc: Int): String = when (atc) {
    LaneChangeOff -> "Off"
    PreLaneChange -> "Prepare"
    LaneChangeStarting -> "Starting"
    LaneChangeFinishing -> "Finishing"
    else -> "UNKNOWN($atc)"
}
