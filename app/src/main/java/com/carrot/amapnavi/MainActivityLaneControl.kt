package com.carrot.amapnavi

import android.util.Log
import org.json.JSONObject

/* =========================================================
 * 变道控制模块（导航变道 / 转向）
 * 说明：本文件中的函数均为 MainActivity 的扩展函数，
 *       变道相关的状态与定时常量定义在 MainActivity.kt 中。
 * ========================================================= */

// -----------------------------
// 侧向安全去抖(迟滞)
// -----------------------------
// 传感器存在抖动：毫米波盲区、激光雷达盲区、CP 车道实线信号、视觉线型，
// 都会在"有车/无车""实线/虚线"之间跳变。原来安全门每个控制周期独立判定，
// 抖动时约有一半的帧是"安全"的，只要二次确认恰好落在安全帧就下发变道命令，
// 表现为"实线有时也变道""雷达/激光雷达不稳定也会变道"。
// 这里改为：判"不安全"立即生效；判"安全"需连续 safeConfirmFrames 帧才恢复。
// 侧向安全去抖的确认帧数(约 = 帧数 × 100ms)。做成变量，便于车上实时调：
//   调大 = 更保守(更少误放行，但变道反应变慢)；调小 = 更灵敏(抖动时可能误放行)。
//   调试后门可热改：--es cmd set --es key safe_confirm_frames --es val 3
var safeConfirmFrames = 5          // 默认约 0.5s(控制周期 100ms)
private const val SAFETY_TICK_MS = 100L
private val sideSafeStreak = HashMap<LaneChangeAction, Int>()
private val sideBlockCode = HashMap<LaneChangeAction, Int>()
private var lastSafetyTickTime = 0L

// 复位侧向安全去抖状态(场景切换 / 调试复位时调用)
fun resetSideSafetyDebounce() {
    sideSafeStreak.clear()
    sideBlockCode.clear()
    lastSafetyTickTime = 0L
}

// 带去抖的安全门入口：同一控制周期内多次调用不会重复推进计数
fun MainActivity.LaneChangeSafetyCheck(action: LaneChangeAction): Int {
    val now = System.currentTimeMillis()
    if (now - lastSafetyTickTime >= SAFETY_TICK_MS) {
        lastSafetyTickTime = now
        for (dir in arrayOf(LaneChangeAction.LEFT, LaneChangeAction.RIGHT)) {
            val raw = LaneChangeSafetyCheckRaw(dir)
            if (raw < 0) {                    // 不安全：立即生效
                sideSafeStreak[dir] = 0
                sideBlockCode[dir] = raw
            } else {                          // 安全：连续若干帧才恢复
                val n = (sideSafeStreak[dir] ?: 0) + 1
                sideSafeStreak[dir] = n
                if (n >= safeConfirmFrames) sideBlockCode[dir] = 0
            }
        }
    }
    // 转弯动作只查侧前盲区，不做去抖
    return sideBlockCode[action] ?: LaneChangeSafetyCheckRaw(action)
}

// 车道线判据的三态结果
//   SOLID   = 确定实线(CP 判实线 或 App 视觉判实线)
//   CLEAR   = 确定非实线(至少一路明确给出"不是实线")
//   UNKNOWN = 两路都没有给出有效判断(视觉检测停摆 且 CP 未下发车道线判据)
enum class LaneLineJudgement { SOLID, CLEAR, UNKNOWN }

// 变道安全门返回码补充：
//   -128 车道线判据不可用(看不到线 ≠ 可以变道)，按 fail-safe 禁止变道
const val LANE_BLOCK_UNKNOWN = -128

// CP 车道实线信号的有效期：超过该时长没收到，视为 CP 没有在提供线型判据
private const val BLIND_LANE_FRESH_MS = 1500L

// Prepare 期间保持变道请求的重发间隔(ms)：CP 侧只有 atc 请求持续存在才会推进到 Starting，
//   请求一消失就立刻退回 Off(见 Overtake 模块同名常量说明)
private const val LANE_PRE_KEEPALIVE_MS = 250L

// 该侧车道线判据：CP 车道实线信号 与 App 视觉线型 取或(保守)，并识别"判据不可用"
//
//   许可权划分(重要)：CP 侧信号只能"否决"(判实线时阻止变道)，不能"许可"。
//   原因：无法区分"CP 确认该侧不是实线"和"CP 没有车道线判据、字段恰好为 false"。
//         若把 CP 的 false 当成许可，一旦本机视觉检测停摆(相机断流/推理掉帧)，
//         实线拦截会整体失效并静默放行 —— 这正是"实线有时也变道"的隐患之一。
fun laneLineJudgement(left: Boolean): LaneLineJudgement {
    val s = opStateManager.state
    val cpSolid = if (left) s.blind_lane_l else s.blind_lane_r
    val cpFresh = (System.currentTimeMillis() - s.blindLaneTs) < BLIND_LANE_FRESH_MS
    val appType = if (left) left_lane_type else right_lane_type
    val appValid = laneTypeDetectValid() && appType >= 0

    // 任一来源判实线 → 实线
    if ((cpFresh && cpSolid) || (appValid && appType == 1)) return LaneLineJudgement.SOLID
    // 只有本机视觉判据能给出"非实线"的许可
    if (appValid) return LaneLineJudgement.CLEAR
    // 视觉判据不可用 → 未知(不采信 CP 的 false)
    //   lane_unknown_block=true(默认)：fail-safe 禁止变道
    //   lane_unknown_block=false：按"未知=不拦"处理(只在明确实线时拦)，用于视觉线型
    //     长期识别不出(-1)导致变道功能整体不可用的场合，可车上热调
    if (!lane_unknown_block) return LaneLineJudgement.CLEAR
    return LaneLineJudgement.UNKNOWN
}

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
    // 运行时自检：同一条命令被重复下发(并发/误重发)时告警
    HealthMonitor.onCmdSent(index, cmd, arg)
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

/* =========================================================
 * 激光雷达侧向安全判定（App 侧自算，与 CP 侧同一口径）
 *
 * CP 下发的四角距离/相对速度之外，App 自己也做一遍判定：
 *
 *   危险 = 外推距离 < 危险距离  或  当前距离 < 危险距离  或  TTC < 阈值
 *   外推距离 = |d| - 接近速度 × 安全时距（相对速度时距）
 *   危险距离 = 绝对速度时距：正值 = 基准速度 × 时距；负值 = 绝对值(m)
 *   基准速度 = 前方 本车速度 / 后方 对方速度(vEgo + vrel)
 *
 * 只在对应侧激光雷达在线时启用（离线时无法确认侧后方，仍只依赖原车标志）。
 * ========================================================= */
object LidarSideSafety {
    const val TTC_LIMIT_S = 2.5f        // 碰撞时间下限
    const val MIN_CLEARANCE_M = 4.0f    // 最小净空兜底
    const val FRONT_DIST_TIME_S = -5f   // 前方：负值=绝对 5m（兜底）
    const val REAR_DIST_TIME_S = -10f   // 后方：负值=绝对 10m（兜底）
    const val FRONT_VREL_TIME_S = 4.0f  // 前方安全时距（相对速度 × 4s）
    const val REAR_VREL_TIME_S = 4.0f   // 后方安全时距
    const val RELEASE_MARGIN = 1.4f     // 迟滞：已判危险时要退到 危险距离×该值 才清除
    const val LATERAL_MAX_M = 5.5f      // 横向口径：超过该值的目标视为路外，不参与判定
}

// 迟滞锁存：key = "L-front"/"L-rear"/"R-front"/"R-rear"（与 CP 侧的 latch 同口径，
// 避免距离在阈值附近抖动时卡片来回闪）
private val lidarHazardLatch = mutableMapOf<String, Boolean>()

// 横向超出判定区域(路外)的目标不参与判定（CP 侧按侧面车道宽屏蔽，App 侧用固定上限兜底）
private fun lateralOutOfRange(xrelMm: Int?): Boolean {
    if (xrelMm == null) return false
    return kotlin.math.abs(xrelMm) / 1000f > LidarSideSafety.LATERAL_MAX_M
}

// 单个侧向目标是否危险：dM>0 前方 / dM<0 后方；vRelKmh = 目标速度 - 本车速度
fun lidarTargetRisky(dM: Float, vRelKmh: Int?, vEgoMs: Float,
                     distTimeS: Float, vRelTimeS: Float,
                     latch: Boolean = false): Boolean {
    if (dM == 0f) return false
    val vRel = (vRelKmh ?: 0).toFloat() / 3.6f
    val basis = if (dM > 0f) vEgoMs else maxOf(0f, vEgoMs + vRel)
    val dangerRaw = if (distTimeS > 0f) {
        maxOf(basis * distTimeS, LidarSideSafety.MIN_CLEARANCE_M)
    } else {
        maxOf(kotlin.math.abs(distTimeS), LidarSideSafety.MIN_CLEARANCE_M)
    }
    // 迟滞：已判危险时放宽清除条件（与 CP 侧一致）
    val dangerDist = if (latch) dangerRaw * LidarSideSafety.RELEASE_MARGIN else dangerRaw
    val closing = if (dM > 0f) maxOf(-vRel, 0f) else maxOf(vRel, 0f)
    val dAbs = kotlin.math.abs(dM)
    val future = maxOf(0f, dAbs - closing * vRelTimeS)
    val ttcRisky = closing > 0.05f && dAbs / closing < LidarSideSafety.TTC_LIMIT_S
    return future < dangerDist || dAbs < dangerDist || ttcRisky
}

// 激光雷达自算的该侧危险（激光不在线 / 无目标 / 安全 → null）
fun MainActivity.lidarSideHazard(action: LaneChangeAction): BlockCause? {
    val s = opStateManager.state
    val left = action == LaneChangeAction.LEFT
    if (!app_lidar_self_check) return null                   // 设置里关掉了自算判据
    if (!(if (left) s.lidar_l else s.lidar_r)) return null   // 激光离线：交给原车标志
    val vEgoMs = (s.vEgo ?: 0).toFloat() / 3.6f

    val key = if (left) "L" else "R"

    // 前方：激光前角（横向超出路外范围的目标不参与判定）
    val fd = (if (left) s.lf_drel else s.rf_drel)?.takeIf { it > 0 }?.let { it / 1000f }
    val fWide = lateralOutOfRange(if (left) s.lf_xrel else s.rf_xrel)
    if (fd != null && !fWide) {
        val risky = lidarTargetRisky(fd, if (left) s.lf_vrel else s.rf_vrel, vEgoMs,
            frontDistTime(), frontVrelTime(), lidarHazardLatch[key + "-front"] == true)
        lidarHazardLatch[key + "-front"] = risky
        if (risky) return BlockCause(-32, "侧前危险·激光${"%.1f".format(fd)}m")
    } else {
        lidarHazardLatch[key + "-front"] = false
    }

    // 后方：激光后角（drel<0 表示在后方）
    val rd = (if (left) s.lb_drel else s.rb_drel)?.takeIf { it < 0 }?.let { -it / 1000f }
    val rWide = lateralOutOfRange(if (left) s.lb_xrel else s.rb_xrel)
    if (rd != null && !rWide) {
        val risky = lidarTargetRisky(-rd, if (left) s.lb_vrel else s.rb_vrel, vEgoMs,
            rearDistTime(), rearVrelTime(), lidarHazardLatch[key + "-rear"] == true)
        lidarHazardLatch[key + "-rear"] = risky
        if (risky) return BlockCause(-64, "侧后逼近·激光${"%.1f".format(rd)}m")
    } else {
        lidarHazardLatch[key + "-rear"] = false
    }
    return null
}

// 阈值：优先用 CP 下发的(与 CP 同源，只在一处调参)，关掉"阈值跟随CP"则用超车参数页里
// 用户自己选的口径：固定距离(m，用负值表示绝对距离) 或 绝对速度时距(s，正值)
private fun MainActivity.frontDistTime(): Float =
    if (!app_thresh_follow_cp) localFrontDistTime()
    else opStateManager.state.lidar_front_vdist_time?.let { it / 10f } ?: localFrontDistTime()

private fun MainActivity.rearDistTime(): Float =
    if (!app_thresh_follow_cp) localRearDistTime()
    else opStateManager.state.lidar_behind_vdist_time?.let { it / 10f } ?: localRearDistTime()

// 本地口径：时距模式返回正秒数(速度×秒)，固定距离模式返回负米数(绝对值=距离)
private fun MainActivity.localFrontDistTime(): Float =
    if (lidar_front_dist_mode == 1) lidar_front_time_s else -lidar_front_dist_m

private fun MainActivity.localRearDistTime(): Float =
    if (lidar_rear_dist_mode == 1) lidar_rear_time_s else -lidar_rear_dist_m

// 相对速度时距(s)：外推用(危险距离 = |d| - 接近速度 × 时距)；关掉"阈值跟随CP"用自己设的值
private fun MainActivity.frontVrelTime(): Float =
    if (!app_thresh_follow_cp) lidar_front_vrel_time_s
    else opStateManager.state.lidar_front_vrel_time?.let { it / 10f } ?: lidar_front_vrel_time_s

private fun MainActivity.rearVrelTime(): Float =
    if (!app_thresh_follow_cp) lidar_rear_vrel_time_s
    else opStateManager.state.lidar_behind_vrel_time?.let { it / 10f } ?: lidar_rear_vrel_time_s

// 某一个激光角是否危险（仅供卡片着色查询，不改迟滞状态：
//  迟滞锁存由 lidarSideHazard() 在算"该侧禁止原因"时统一更新）
fun MainActivity.lidarCornerRisky(left: Boolean, front: Boolean): Boolean {
    val s = opStateManager.state
    if (!app_lidar_self_check) return false                     // 设置里关掉了自算判据
    if (!(if (left) s.lidar_l else s.lidar_r)) return false     // 激光离线：不自算
    val vEgoMs = (s.vEgo ?: 0).toFloat() / 3.6f
    val drel = if (left) (if (front) s.lf_drel else s.lb_drel)
               else (if (front) s.rf_drel else s.rb_drel)
    val xrel = if (left) (if (front) s.lf_xrel else s.lb_xrel)
               else (if (front) s.rf_xrel else s.rb_xrel)
    val vrel = if (left) (if (front) s.lf_vrel else s.lb_vrel)
               else (if (front) s.rf_vrel else s.rb_vrel)
    if (drel == null || lateralOutOfRange(xrel)) return false
    val key = (if (left) "L" else "R") + (if (front) "-front" else "-rear")
    return if (front) {
        val d = drel.takeIf { it > 0 }?.let { it / 1000f } ?: return false
        lidarTargetRisky(d, vrel, vEgoMs, frontDistTime(), frontVrelTime(),
            lidarHazardLatch[key] == true)
    } else {
        val d = drel.takeIf { it < 0 }?.let { -it / 1000f } ?: return false
        lidarTargetRisky(-d, vrel, vEgoMs, rearDistTime(), rearVrelTime(),
            lidarHazardLatch[key] == true)
    }
}

//检查盲区是否安全
//  返回码(0=允许变道；<0=禁止，供卡片显示原因)：
//    0    允许
//   -1    本侧车道线为实线(CP 判据 或 App 视觉判据，取或)
//   -2    原车前雷达的侧前盲区(l_front_blind/r_front_blind)
//   -4    原车后盲区(BSD, left_blindspot/right_blindspot)
//   -8    外挂激光雷达盲区(纯激光: 侧方/前角/后角)
//  -16    综合盲区里的其它来源(摄像头等, blind_l/blind_r)
//  -32    激光自算：侧前方不安全(时距/TTC)
//  -64    激光自算：侧后方不安全(时距/TTC)
// -128    车道线判据不可用(视觉检测停摆 且 CP 未下发车道线判据) → fail-safe 禁止
private fun MainActivity.LaneChangeSafetyCheckRaw(action: LaneChangeAction): Int {
    val s = opStateManager.state
    if (action == LaneChangeAction.LEFT) {
        //左侧盲区有车
        if (s.l_front_blind) { //原车左侧前方
            return -2
        } else if (s.left_blindspot) { //原车左侧后方
            return -4
        } else if (s.lidar_lblind || s.lidar_lfblind || s.lidar_lbblind) { //激光雷达(纯激光)
            return -8
        } else if (laneLineJudgement(true) == LaneLineJudgement.SOLID) { //左侧实线(取或)
            return -1
        } else if (laneLineJudgement(true) == LaneLineJudgement.UNKNOWN) { //左侧线型判据不可用
            return LANE_BLOCK_UNKNOWN
        } else if (s.blind_l) { //综合盲区里的其它来源(摄像头等)
            return -16
        }
        // 激光在线时，App 自算的时距/TTC 判据（侧前/侧后逼近）
        lidarSideHazard(LaneChangeAction.LEFT)?.let { return it.code }
    } else if (action == LaneChangeAction.RIGHT) {
        //右侧盲区有车
        if (s.r_front_blind) {
            return -2
        } else if (s.right_blindspot) {
            return -4
        } else if (s.lidar_rblind || s.lidar_rfblind || s.lidar_rbblind) {
            return -8
        } else if (laneLineJudgement(false) == LaneLineJudgement.SOLID) { //实线(取或)
            return -1
        } else if (laneLineJudgement(false) == LaneLineJudgement.UNKNOWN) { //线型判据不可用
            return LANE_BLOCK_UNKNOWN
        } else if (s.blind_r) {
            return -16
        }
        lidarSideHazard(LaneChangeAction.RIGHT)?.let { return it.code }
    }

    if (action == LaneChangeAction.LEFT_TURN) {
        //左侧盲区有车
        if (s.l_front_blind) { //原车左侧前方
            return -2
        }
    } else if (action == LaneChangeAction.RIGHT_TURN) {
        //右侧盲区有车
        if (s.r_front_blind) {
            return -2
        }
    }

    return 0
}

//该侧"禁止变道"的原因(供第4行的变道卡片显示)：原因文字 + 判据来源
data class BlockCause(val code: Int, val text: String)

//  判据集合与 LaneChangeSafetyCheck 完全一致（任何一条成立就一定返回 <0），
//  显示顺序按方位：前(前雷达/激光前角) -> 后(BSD/激光后角) -> 侧方激光 -> 实线 -> 摄像头
fun MainActivity.sideBlockCause(action: LaneChangeAction): BlockCause? {
    val s = opStateManager.state
    val left = action == LaneChangeAction.LEFT

    val causes = mutableListOf(
        (if (left) s.l_front_blind else s.r_front_blind) to BlockCause(-2, "前侧盲区·前雷达"),
        (if (left) s.left_blindspot else s.right_blindspot) to BlockCause(-4, "后方盲区·BSD"),
        (if (left) s.lidar_lfblind else s.lidar_rfblind) to BlockCause(-8, "激光盲区·前角"),
        (if (left) s.lidar_lbblind else s.lidar_rbblind) to BlockCause(-8, "激光盲区·后角"),
        (if (left) s.lidar_lblind else s.lidar_rblind) to BlockCause(-8, "激光盲区·侧方"),
        // 车道实线：与安全门同源(见 laneLineJudgement)，CP 判的实线 或 App 自己的车道线类型
        (laneLineJudgement(left) == LaneLineJudgement.SOLID) to BlockCause(-1, "车道实线"),
        // 车道线判据不可用：视觉检测停摆 且 CP 也没给线型判据(与安全门一致，禁止变道)
        (laneLineJudgement(left) == LaneLineJudgement.UNKNOWN) to BlockCause(LANE_BLOCK_UNKNOWN, "车道线未知"),
        // 综合盲区按分解来源分别显示，避免一律标成"摄像头盲区"
        (if (left) s.blind_camera_l else s.blind_camera_r) to BlockCause(-16, "摄像头盲区"),
        (if (left) s.blind_car_l else s.blind_car_r) to BlockCause(-16, "车身盲区"),
        (if (left) s.blind_l else s.blind_r) to BlockCause(-16, "综合盲区"),
        // 侧向信号抖动：原始判据当前是"安全"，但去抖窗口未走完，仍按不安全处理
        (LaneChangeSafetyCheck(action) < 0) to BlockCause(-256, "侧向信号抖动确认中"),
    )
    // 激光自算的时距/TTC 判据（激光在线时才有，与 LaneChangeSafetyCheck 的判据集合一致）
    lidarSideHazard(action)?.let { causes += (true to it) }
    return causes.firstOrNull { it.first }?.second
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
                if (lane_change_safe == -1 || lane_change_safe == LANE_BLOCK_UNKNOWN) {
                    appSpeakFile = "audio_solid_blocked" //实线/线型未知阻止变道
                    val why = if (lane_change_safe == LANE_BLOCK_UNKNOWN) "车道线判据不可用" else "实线"
                    msg = "${nowTime} 侧面(${action})${why}阻止变道"
                } else {
                    appSpeakFile = "audio_car_watchout" //盲区有车
                    msg = "${nowTime} 侧面(${action})有车阻止变道"
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
            // ---- 命令下发前二次确认 ----
            // 播报后还要等 0.75~2.25s 才真正下发，这段时间车辆已行进数十米，
            // 实线/盲区状态可能已经变化(典型：播报时是虚线、执行时已进实线区)。
            // 这里必须重新过一次安全门，不通过就取消本次下发并重新计时等待。
            val recheck = LaneChangeSafetyCheck(action)
            if (recheck < 0) {
                laneSpeakTime = now
                if (now - lastRecheckLogTime >= BLIND_SPEAK_INTERVAL) {
                    lastRecheckLogTime = now
                    val why = when {
                        recheck == -1 -> "实线"
                        recheck == LANE_BLOCK_UNKNOWN -> "车道线判据不可用"
                        else -> "盲区有车"
                    }
                    val msg = "${nowTime} 发送前二次检查不通过($why,$recheck)，暂不下发变道命令 $action"
                    Log.w("LaneCmd", msg)
                    showLaneDebug(msg)
                }
                return
            }

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

    // Prepare 期间持续保持变道请求(CP 侧 atc 请求消失会立刻退回 Off)
    if (atcState == PreLaneChange && !inLaneChangeStarting) {
        if (now - lastCmdSendTime >= LANE_PRE_KEEPALIVE_MS) {
            laneChangeCmdIndex++                        // 序号递增，避免被 CP 去重
            sendLaneActionCmd(laneAction)               // 方向保持不变
            lastCmdSendTime = now
            laneCmdSentRetryCnt++

            if (laneCmdSentRetryCnt % 4 == 1) {
                val msg = "${nowTime} Prepare 保持中，持续重发变道命令" +
                        "(已发${laneCmdSentRetryCnt}次) index=$laneChangeCmdIndex 方向=$laneAction"
                Log.d("LaneCmd", msg)
                showLaneDebug(msg)
            }
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
