package com.carrot.amapnavi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/* =========================================================
 * 调试后门（只响应 adb 广播，正常运行不触发、零开销）
 *
 *   adb shell am broadcast -a com.carrot.amapnavi.DEBUG --es cmd dump
 *   adb shell am broadcast -a com.carrot.amapnavi.DEBUG --es cmd get  --es key overtake_mode
 *   adb shell am broadcast -a com.carrot.amapnavi.DEBUG --es cmd set  --es key overtake_mode --es val 3
 *   adb shell am broadcast -a com.carrot.amapnavi.DEBUG --es cmd frame --es val 200
 *   adb shell am broadcast -a com.carrot.amapnavi.DEBUG --es cmd clearcd
 *   adb shell am broadcast -a com.carrot.amapnavi.DEBUG --es cmd reset
 *   adb shell am broadcast -a com.carrot.amapnavi.DEBUG --es cmd lane
 *   adb shell am broadcast -a com.carrot.amapnavi.DEBUG --es cmd scenes
 *   adb shell am broadcast -a com.carrot.amapnavi.DEBUG --es cmd sim   --es val baseline      (场景[,持续秒数])
 *   adb shell am broadcast -a com.carrot.amapnavi.DEBUG --es cmd simstop
 *   adb shell am broadcast -a com.carrot.amapnavi.DEBUG --es cmd lanecheck
 *   adb shell am broadcast -a com.carrot.amapnavi.DEBUG --es cmd ramptest
 *
 * 端到端模拟：不需要实车和 CP，sim 会按帧把传感器状态灌进决策链路，
 * 覆盖实线/虚实线抖动、前雷达与激光雷达抖动、判据不可用、前车插入等场景。
 *
 * 说明：
 *   dump / lane / get 只读不写；set 会同时改内存里的变量并写入 user_settings，
 *   App 重启后依然生效（等于"热调参"，不需要重新编译安装）。
 *   全部输出走 logcat，tag = AmapDbg。
 * ========================================================= */
class DebugReceiver : BroadcastReceiver() {

    private data class Spec(val prefKey: String, val kind: Char) // b=布尔 i=整数 f=浮点 s=秒(存秒,内存ms)

    private val PARAMS = mapOf(
            // ---- 功能开关 ----
            "app_navi" to Spec("app_navi", 'b'),
            "app_road_navi" to Spec("app_road_navi", 'b'),
            "app_overtake" to Spec("app_overtake", 'b'),
            "app_road_overtake" to Spec("app_road_overtake", 'b'),
            "suggest_lane_en" to Spec("suggest_lane_en", 'b'),
            "dis_lane_en" to Spec("dis_lane_en", 'b'),
            "keep_left_en" to Spec("keep_left_en", 'b'),
            "keep_right_en" to Spec("keep_right_en", 'b'),
            "nav_turn" to Spec("nav_turn", 'b'),
            "road_median" to Spec("road_median", 'b'),
            "road_right_first" to Spec("road_right_first", 'b'),
            "vision_keep_right" to Spec("vision_keep_right", 'b'),
            "lane_prob_enable" to Spec("lane_prob_enable", 'b'),
            // 车道线判据不可用时是否拦截变道(默认拦截)：视觉线型长期=-1 时可关掉
            "lane_unknown_block" to Spec("lane_unknown_block", 'b'),
            // 变道安全门"手写版兼容"总开关(默认开)：打开=回到 9-13 手写版的判据集合+零延时
            "safety_legacy_mode" to Spec("safety_legacy_mode", 'b'),
            // 高速是否预留最右一条车道(当应急车道)：默认 false=靠边可到最右
            "highway_reserve_rightmost_lane" to Spec("highway_reserve_rightmost_lane", 'b'),
            // 行车日志落盘总开关(默认开)：关掉后不再写主日志(仍打 logcat)
            "drive_log_en" to Spec("drive_log_en", 'b'),
            // 实线判据"临匝道放宽"(默认开)：仅导航变道生效，超车仍严格
            "solid_relax_en" to Spec("solid_relax_en", 'b'),
            // 在"进匝道距离"之前多少米开始解除实线判定(默认 15m)
            "solid_relax_margin_m" to Spec("solid_relax_margin_m", 'f'),
            // 连续岔路判定：下一步岔路距离上限(m，默认 500)
            "fork_chain_dist_m" to Spec("fork_chain_dist_m", 'i'),
            // 临匝道放宽窗口偏移量(m)：窗口 = 进匝道距离 + 本值
            //   窗口内同时解除：实线判据 / 激光盲区 / "车辆已靠边"抑制
            "solid_relax_offset_h" to Spec("solid_relax_offset_h", 'i'),
            "solid_relax_offset_l" to Spec("solid_relax_offset_l", 'i'),
            // "车辆已靠边"抑制变道(默认开)：该侧实线(+高速该侧车道数<=1)持续5秒 → 禁止继续变道
            "last_lane_suppress_en" to Spec("last_lane_suppress_en", 'b'),
            // 有推荐/禁行车道时禁止超车(默认开)：导航车道含 "*"/"X" → 本帧不允许超车
            "overtake_block_on_nav_lane_hint" to Spec("overtake_block_on_nav_lane_hint", 'b'),
            // 导航靠边/进匝道时"语音后等待"毫秒数(默认 0=不等，立即下发；普通变道仍用 1500)
            "navi_edge_speak_delay_ms" to Spec("navi_edge_speak_delay_ms", 'i'),
            // 导航靠边/进匝道时 Prepare 状态超时毫秒数(默认 700，普通变道为 2000)
            "navi_edge_atc_timeout_ms" to Spec("navi_edge_atc_timeout_ms", 'i'),
            "navi_speak" to Spec("navi_speak", 'b'),
            "navi_speak_first" to Spec("navi_speak_first", 'b'),
            "navi_blind_dec_speed" to Spec("navi_blind_dec_speed", 'b'),
            "app_lidar_self_check" to Spec("app_lidar_self_check", 'b'),
            "app_thresh_follow_cp" to Spec("app_thresh_follow_cp", 'b'),
            // ---- 数值 ----
            "nav_mode" to Spec("nav_mode", 'i'),
            // 侧向安全去抖确认帧数(约 ×100ms)：调大更保守、调小更灵敏
            "safe_confirm_frames" to Spec("safe_confirm_frames", 'i'),
            // 导航变道(靠边/进匝道)专用的去抖确认帧数：默认比超车小，避免错过路口
            "safe_confirm_frames_navi" to Spec("safe_confirm_frames_navi", 'i'),
            "overtake_mode" to Spec("overtake_mode", 'i'),
            "vision_lane_mode" to Spec("vision_lane_mode", 'i'),
            "navi_min_speed" to Spec("navi_min_speed", 'i'),
            "atc_dist_h" to Spec("atc_dist_h", 'i'),
            "atc_dist_l" to Spec("atc_dist_l", 'i'),
            "fork_dist_h" to Spec("fork_dist_h", 'i'),
            "fork_dist_l" to Spec("fork_dist_l", 'i'),
            // ---- 匝道/分岔提前减速 ----
            "ramp_slowdown_en" to Spec("ramp_slowdown_en", 'b'),
            "ramp_slowdown_dist_h" to Spec("ramp_slowdown_dist_h", 'i'),
            "ramp_slowdown_dist_l" to Spec("ramp_slowdown_dist_l", 'i'),
            "ramp_slowdown_speed_h" to Spec("ramp_slowdown_speed_h", 'i'),
            "ramp_slowdown_speed_l" to Spec("ramp_slowdown_speed_l", 'i'),
            "ramp_hold_time" to Spec("ramp_hold_time", 'i'),
            "overtake_reverse_cooldown_ms" to Spec("overtake_reverse_cooldown_s", 's'),
            // ---- 侧向安全阈值(不跟随CP时生效) ----
            "lidar_front_dist_mode" to Spec("lidar_front_dist_mode", 'i'),
            "lidar_front_dist_m" to Spec("lidar_front_dist_m", 'f'),
            "lidar_front_time_s" to Spec("lidar_front_time_s", 'f'),
            "lidar_rear_dist_mode" to Spec("lidar_rear_dist_mode", 'i'),
            "lidar_rear_dist_m" to Spec("lidar_rear_dist_m", 'f'),
            "lidar_rear_time_s" to Spec("lidar_rear_time_s", 'f'),
            "lidar_front_vrel_time_s" to Spec("lidar_front_vrel_time_s", 'f'),
            "lidar_rear_vrel_time_s" to Spec("lidar_rear_vrel_time_s", 'f'),
    )

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val a = MainActivity.instance
        val cmd = intent.getStringExtra("cmd") ?: "dump"
        val key = intent.getStringExtra("key")
        val value = intent.getStringExtra("val")

        try {
            when (cmd) {
                "dump" -> if (a != null) dump(a) else Log.w(TAG, "MainActivity 未运行，无法 dump")
                "lane" -> if (a != null) dumpLane(a) else Log.w(TAG, "MainActivity 未运行，无法 dump lane")
                "get" -> {
                    if (key == null) Log.w(TAG, "缺少 --es key")
                    else Log.d(TAG, "$key = ${readValue(context, a, key)}")
                }
                "set" -> {
                    if (key == null || value == null) Log.w(TAG, "缺少 --es key / --es val")
                    else {
                        val ok = applyValue(context, a, key, value)
                        Log.d(TAG, "set $key = $value → ${if (ok) "OK(已持久化)" else "失败/未知参数"}")
                        if (a != null) Log.d(TAG, "当前值: $key = ${readValue(context, a, key)}")
                    }
                }
                "frame" -> {
                    when {
                        value == null || value == "on" -> {
                            overtakeFullFrameLog = true
                            overtakeFullFrameIntervalMs = 200L
                        }
                        value == "off" -> overtakeFullFrameLog = false
                        else -> {
                            val ms = value.toLongOrNull()
                            if (ms == null || ms <= 0) {
                                Log.w(TAG, "frame 参数应为 on/off/毫秒数")
                            } else {
                                overtakeFullFrameLog = true
                                overtakeFullFrameIntervalMs = ms.coerceIn(50L, 5000L)
                            }
                        }
                    }
                    Log.d(TAG, "逐帧日志: ${if (overtakeFullFrameLog) "开(${overtakeFullFrameIntervalMs}ms)" else "关"}" +
                            " (落盘需先开启超车记录开关)")
                }
                "clearcd" -> {
                    overtakeCooldownUntil = 0L
                    overtakeLastDirection = LaneChangeAction.KEEP
                    overtakeLastDirectionTime = 0L
                    overtakeTriggerStableTime = 0L
                    Log.d(TAG, "已清除超车冷却/反向冷却")
                }
                "reset" -> {
                    if (a != null) {
                        a.resetLaneAction()
                        a.resetOvertakeAction()
                        Log.d(TAG, "已复位变道/超车状态机")
                    } else Log.w(TAG, "MainActivity 未运行")
                }
                "scenes" -> {
                    Log.d(TAG, "可用模拟场景(cmd=sim --es val <场景>[,秒数]):")
                    SimScenes.scenes.forEach { (k, v) -> Log.d(TAG, "  $k : $v") }
                }
                "sim" -> {
                    if (a == null) {
                        Log.w(TAG, "MainActivity 未运行，无法启动模拟")
                    } else {
                        val parts = (value ?: "ot_base").split(",")
                        val scene = parts[0].trim()
                        // 未指定时长时用场景自带的推荐时长(匝道场景更长)
                        val secs = parts.getOrNull(1)?.trim()?.toLongOrNull()
                                ?: SimScenes.durationSec(scene).toLong()
                        if (!SimScenes.isKnown(scene)) {
                            Log.w(TAG, "未知场景 $scene，可用 --es cmd scenes 查看")
                        } else {
                            // 先保存模拟前的领航开关(下面会改，模拟结束/停止时恢复)
                            simSavedAppNavi = a.app_navi
                            simSavedAppRoadNavi = a.app_road_navi
                            // 模拟工况：超车场景关领航(领航会接管变道请求)；
                            //   领航场景(ramp_*)反之 —— 否则靠边/匝道决策会被入口门控整体跳过
                            val naviScene = SimScenes.isNaviScene(scene)
                            a.bHighWay = true
                            a.app_overtake = !naviScene
                            a.app_road_overtake = !naviScene
                            a.app_navi = naviScene
                            a.app_road_navi = naviScene
                            a.resetOvertakeAction()
                            a.resetLaneAction()
                            overtakeCooldownUntil = 0L
                            overtakeLastDirection = LaneChangeAction.KEEP
                            overtakeTriggerStableTime = 0L
                            cutInSuppressUntil = 0L
                            prevLeadDrel = null
                            prevLeadPresent = false
                            resetLaneTypeFilter()
                            resetSideSafetyDebounce()
                            rampSlowdownActive = false
                            rampSlowdownSpeed = -1
                            rampSlowdownUntil = 0L
                            rampSlowdownLockedDist = 0f
                            // 保存导航车道/相关开关：部分场景会临时替换
                            simSavedNavLane = navLane
                            simSavedSuggestLaneEn = a.suggest_lane_en
                            simSavedDisLaneEn = a.dis_lane_en
                            simScene = scene
                            simTick = 0L
                            simHoldUntil = System.currentTimeMillis() + secs.coerceIn(5L, 600L) * 1000L
                            Log.d(TAG, "模拟启动: $scene (${SimScenes.scenes[scene]})，持续${secs}s")
                            Log.d(TAG, "提示: 用 logcat -s AmapDbg OvertakeDbg OvertakeCmd LaneCmd 观察决策过程")
                        }
                    }
                }
                "simstop" -> {
                    simScene = null
                    simHoldUntil = 0L
                    resetLaneTypeFilter()
                    resetSideSafetyDebounce()
                    rampSlowdownActive = false
                    rampSlowdownSpeed = -1
                    rampSlowdownUntil = 0L
                    rampSlowdownLockedDist = 0f
                    // 恢复导航车道与开关
                    simSavedNavLane?.let { navLane = it }
                    simSavedNavLane = null
                    if (a != null) {
                        a.suggest_lane_en = simSavedSuggestLaneEn
                        a.dis_lane_en = simSavedDisLaneEn
                        // 恢复领航开关(模拟启动时按场景类型改过)
                        a.app_navi = simSavedAppNavi
                        a.app_road_navi = simSavedAppRoadNavi
                    }
                    Log.d(TAG, "模拟已停止(导航车道与开关已恢复)")
                }
                "lanecheck" -> {
                    if (a == null) {
                        Log.w(TAG, "MainActivity 未运行，无法执行 lanecheck")
                    } else {
                        // 构造导航车道直接验证"高速预留最右侧应急车道"的决策结果
                        //   注：必须打开 suggest_lane_en，否则 decideOvertakeLane 会走
                        //       "仅凭视觉车道允许变道"的早退分支，验不到导航车道层面
                        val savedNav = navLane
                        val savedLast = lastNavLane
                        val savedV = vLaneResult
                        val savedHighway = a.bHighWay
                        val savedSuggest = a.suggest_lane_en
                        val savedDis = a.dis_lane_en
                        try {
                            a.bHighWay = true
                            a.suggest_lane_en = true
                            // 4 条导航车道：索引0最左 .. 索引3最右(应急车道)
                            val nav = NavLane(arrayOf("0", "0", "0", "0"), 4)
                            navLane = nav
                            lastNavLane = nav
                            Log.d(TAG, "[lanecheck] navCnt=4(索引3=应急车道) suggest_lane_en=true")

                            // 例1：车辆在索引2(最右正常车道)，两侧视觉各2条 → currentIdx=2
                            vLaneResult = LaneCalcResult(laneCnt = 4, leftLaneCnt = 2, rightLaneCnt = 2,
                                    leftLaneId = 3, rightLaneId = 3)
                            Log.d(TAG, "[lanecheck] 1 高速 车@索引2 右超 → ${a.decideOvertakeLane(vLaneResult, nav, LaneChangeAction.RIGHT, true, false)}  期望 (-1,KEEP,-1)")
                            Log.d(TAG, "[lanecheck] 2 高速 车@索引2 靠右 → ${a.decideLaneWithAction(vLaneResult, nav, LaneChangeAction.RIGHT, true, false)}  期望 (2,KEEP,1)")
                            Log.d(TAG, "[lanecheck] 3 公路 车同条件 右超 → ${a.decideOvertakeLane(vLaneResult, nav, LaneChangeAction.RIGHT, false, false)}  期望 (2,RIGHT,1)")
                            Log.d(TAG, "[lanecheck] 4 高速 车@索引2 左超 → ${a.decideOvertakeLane(vLaneResult, nav, LaneChangeAction.LEFT, true, false)}  期望 (1,LEFT,2)")

                            // 例2：车辆在索引1，左侧视觉1条 → currentIdx=1
                            vLaneResult = LaneCalcResult(laneCnt = 4, leftLaneCnt = 1, rightLaneCnt = 3,
                                    leftLaneId = 2, rightLaneId = 3)
                            Log.d(TAG, "[lanecheck] 5 高速 车@索引1 右超 → ${a.decideOvertakeLane(vLaneResult, nav, LaneChangeAction.RIGHT, true, false)}  期望 (2,RIGHT,1)")
                        } catch (e: Exception) {
                            Log.e(TAG, "lanecheck 异常: ${e.message}")
                        } finally {
                            navLane = savedNav
                            lastNavLane = savedLast
                            vLaneResult = savedV
                            a.bHighWay = savedHighway
                            a.suggest_lane_en = savedSuggest
                            a.dis_lane_en = savedDis
                        }
                        Log.d(TAG, "[lanecheck] 已恢复运行状态")
                    }
                }
                "ramptest" -> {
                    if (a == null) {
                        Log.w(TAG, "MainActivity 未运行，无法执行 ramptest")
                    } else {
                        // 匝道提前减速的确定性验证：构造引导类型/距离，检查状态机与限速下压
                        val st = opStateManager.state
                        val saveTurn = st.xTurnInfo
                        val saveDist = st.xTurnDist
                        val saveLimit = a.naviState.nRoadLimitSpeed
                        val saveRamp = a.nRoadLimitSpeedRamp
                        val saveRampCnt = a.nRoadLimitSpeedRampCnt
                        val saveActive = rampSlowdownActive
                        val saveSpeed = rampSlowdownSpeed
                        val saveUntil = rampSlowdownUntil
                        val saveHighway = a.bHighWay
                        val saveEn = a.ramp_slowdown_en
                        val saveSmooth = a.smooth_speed_limit
                        val saveVEgo = st.vEgo
                        val saveLocked = rampSlowdownLockedDist
                        val saveDistH = a.ramp_slowdown_dist_h
                        val saveDistL = a.ramp_slowdown_dist_l
                        val saveSpeedH = a.ramp_slowdown_speed_h
                        val saveSpeedL = a.ramp_slowdown_speed_l
                        val saveForkH = a.fork_dist_h
                        val saveForkL = a.fork_dist_l
                        val saveV0 = rampSlowdownV0
                        try {
                            a.bHighWay = true
                            a.ramp_slowdown_en = true
                            a.ramp_slowdown_dist_h = 400
                            a.ramp_slowdown_dist_l = 200
                            a.ramp_slowdown_speed_h = 60
                            a.ramp_slowdown_speed_l = 45
                            a.fork_dist_h = 80
                            a.fork_dist_l = 30
                            a.smooth_speed_limit = false
                            rampSlowdownActive = false
                            rampSlowdownSpeed = -1
                            rampSlowdownUntil = 0L
                            rampSlowdownLockedDist = 0f
                            rampSlowdownV0 = 0f

                            // 高速 120km/h 设定400m：120→60 需约463m，400m 不够 → 起点自动扩到 80+463≈543m
                            st.vEgo = 120
                            st.xTurnInfo = TurnInfo_ForkRight
                            st.xTurnDist = 1600f
                            a.rampSlowdownProcess()
                            Log.d(TAG, "[ramptest] 1 高速120km/h 匝道前1600m → active=$rampSlowdownActive 期望 false")

                            st.xTurnDist = 600f
                            a.rampSlowdownProcess()
                            Log.d(TAG, "[ramptest] 2 高速120km/h 匝道前600m(起点≈543m) → active=$rampSlowdownActive 期望 false")

                            st.xTurnDist = 500f
                            a.rampSlowdownProcess()
                            Log.d(TAG, "[ramptest] 3 高速120km/h 匝道前500m → active=$rampSlowdownActive v0=${rampSlowdownV0.toInt()} 起点=${rampSlowdownLockedDist.toInt()}m 期望 true/120/~543")

                            // 4) 曲线中间点：dist=280m → 约91km/h
                            a.naviState.nRoadLimitSpeed = 120
                            a.nRoadLimitSpeedRamp = 120
                            a.nRoadLimitSpeedRampCnt = 0
                            st.xTurnDist = 280f
                            a.rampSlowdownProcess()
                            Log.d(TAG, "[ramptest] 4 高速120km/h dist=280m → 限速=${a.naviState.nRoadLimitSpeed} 期望 ≈91")

                            // 5) 到分岔距离处应为目标速度
                            st.xTurnDist = 80f
                            a.rampSlowdownProcess()
                            Log.d(TAG, "[ramptest] 5 高速120km/h dist=80m(分岔) → 限速=${a.naviState.nRoadLimitSpeed} 期望 60")

                            // 6) 已进匝道(引导结束)：维持期内保持目标限速
                            st.xTurnInfo = -1
                            a.rampSlowdownProcess()
                            Log.d(TAG, "[ramptest] 6 匝道引导结束(维持期内) → active=$rampSlowdownActive 期望 true")

                            // 7) 维持期到期后撤销
                            rampSlowdownUntil = System.currentTimeMillis() - 1
                            a.rampSlowdownProcess()
                            Log.d(TAG, "[ramptest] 7 维持期到期 → active=$rampSlowdownActive 期望 false")

                            // 8) 公路 60km/h 设定200m：60→45 只需约68m，200m 够用 → 起点=200m
                            a.bHighWay = false
                            st.vEgo = 60
                            st.xTurnInfo = TurnInfo_ForkLeft
                            st.xTurnDist = 180f
                            a.rampSlowdownProcess()
                            Log.d(TAG, "[ramptest] 8 公路60km/h 匝道前180m → active=$rampSlowdownActive 起点=${rampSlowdownLockedDist.toInt()}m 期望 true/200")

                            st.xTurnDist = 30f
                            a.rampSlowdownProcess()
                            Log.d(TAG, "[ramptest] 9 公路60km/h dist=30m(分岔) → 限速=${a.naviState.nRoadLimitSpeed} 期望 45")

                            // 10) 转弯引导：不减速
                            rampSlowdownActive = false
                            rampSlowdownLockedDist = 0f
                            st.xTurnInfo = TurnInfo_TurnRight
                            st.xTurnDist = 100f
                            a.rampSlowdownProcess()
                            Log.d(TAG, "[ramptest] 10 右转弯100m → active=$rampSlowdownActive 期望 false")
                        } catch (e: Exception) {
                            Log.e(TAG, "ramptest 异常: ${e.message}")
                        } finally {
                            st.xTurnInfo = saveTurn
                            st.xTurnDist = saveDist
                            st.vEgo = saveVEgo
                            a.naviState.nRoadLimitSpeed = saveLimit
                            a.nRoadLimitSpeedRamp = saveRamp
                            a.nRoadLimitSpeedRampCnt = saveRampCnt
                            rampSlowdownActive = saveActive
                            rampSlowdownSpeed = saveSpeed
                            rampSlowdownUntil = saveUntil
                            rampSlowdownLockedDist = saveLocked
                            rampSlowdownV0 = saveV0
                            a.bHighWay = saveHighway
                            a.ramp_slowdown_en = saveEn
                            a.smooth_speed_limit = saveSmooth
                            a.ramp_slowdown_dist_h = saveDistH
                            a.ramp_slowdown_dist_l = saveDistL
                            a.ramp_slowdown_speed_h = saveSpeedH
                            a.ramp_slowdown_speed_l = saveSpeedL
                            a.fork_dist_h = saveForkH
                            a.fork_dist_l = saveForkL
                        }
                        Log.d(TAG, "[ramptest] 已恢复运行状态")
                    }
                }
                "params" -> Log.d(TAG, "可设置参数: " + PARAMS.keys.joinToString(","))
                else -> Log.w(TAG, "未知命令 $cmd (支持: dump/lane/get/set/frame/clearcd/reset/params)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "debug 命令异常: ${e.message}")
        }
    }

    // -----------------------------
    // 全量状态快照
    // -----------------------------
    private fun dump(a: MainActivity) {
        val s = opStateManager.state
        val now = System.currentTimeMillis()
        Log.d(TAG, "==== AmapNavi 状态快照 ====")
        Log.d(TAG, "车: vEgo=${s.vEgo} vCruise=${s.vCruise} engaged=${s.engaged} active=${s.active} atc=${s.atc_state} onroad=${s.isOnroad}")
        Log.d(TAG, "前车: lead=${s.lead1} drel=${s.drel} vlead=${s.vlead} vrel=${s.vrel}")
        Log.d(TAG, "雷达: 前角L=${if (s.l_lead) s.l_drel else "-"} 前角R=${if (s.r_lead) s.r_drel else "-"}")
        Log.d(TAG, "激光: online L/R=${s.lidar_l}/${s.lidar_r} lf=${s.lf_drel}(${s.lf_xrel}) rf=${s.rf_drel}(${s.rf_xrel}) lb=${s.lb_drel} rb=${s.rb_drel}")
        Log.d(TAG, "盲区: 前 ${s.l_front_blind}/${s.r_front_blind} BSD ${s.left_blindspot}/${s.right_blindspot} 激光 ${s.lidar_lblind}/${s.lidar_rblind} 综合 ${s.blind_l}/${s.blind_r}")
        Log.d(TAG, "车道: 宽=${s.lane_width} 左=${s.l_lane_width} 右=${s.r_lane_width} 路沿=${s.l_edge_dist}/${s.r_edge_dist} 实线=${left_lane_type}/${right_lane_type}")
        Log.d(TAG, "导航: xTurnInfo=${s.xTurnInfo} xTurnDist=${"%.1f".format(s.xTurnDist)} nTurnDist=${s.nTurnDist} max=${s.nTurnDistMax} forkNow=${s.doLaneForkNow} 高速=${a.bHighWay}")
        Log.d(TAG, "配置: nav_mode=${a.nav_mode} overtake_mode=${a.overtake_mode} vision_lane=${a.vision_lane_mode} median=${a.road_median} rightFirst=${a.road_right_first}")
        Log.d(TAG, "开关: 领航 高速/公路=${a.app_navi}/${a.app_road_navi} 超车 高速/公路=${a.app_overtake}/${a.app_road_overtake} 推荐车道=${a.suggest_lane_en} 禁行=${a.dis_lane_en} 靠左/右=${a.keep_left_en}/${a.keep_right_en}")
        Log.d(TAG, "距离: atc_h=${a.atc_dist_h} fork_h=${a.fork_dist_h} atc_l=${a.atc_dist_l} fork_l=${a.fork_dist_l} minSpeed=${a.navi_min_speed}")
        Log.d(TAG, "匝道减速: 开关=${a.ramp_slowdown_en} 高速${a.ramp_slowdown_dist_h}m/${a.ramp_slowdown_speed_h}km 公路${a.ramp_slowdown_dist_l}m/${a.ramp_slowdown_speed_l}km 维持${a.ramp_hold_time}s" +
                " | active=$rampSlowdownActive 起点=${rampSlowdownLockedDist.toInt()}m v0=${rampSlowdownV0.toInt()} 目标=$rampSlowdownSpeed 曲线压制=${rampActiveLimit}km/h 剩余=${(rampSlowdownUntil - now).coerceAtLeast(0L)}ms")
        Log.d(TAG, "限速下发: nRoadLimitSpeed=${a.naviState.nRoadLimitSpeed} ramp=${a.nRoadLimitSpeedRamp} cnt=${a.nRoadLimitSpeedRampCnt} 末次高德=${a.nAmapLimitSpeedLast}")
        Log.d(TAG, "阈值: 自算=${a.app_lidar_self_check} 跟随CP=${a.app_thresh_follow_cp} 前=${a.lidar_front_dist_m}m/${a.lidar_front_time_s}s 后=${a.lidar_rear_dist_m}m/${a.lidar_rear_time_s}s vrel=${a.lidar_front_vrel_time_s}/${a.lidar_rear_vrel_time_s}")
        Log.d(TAG, "变道状态机: cmdSent=${a.laneCmdSent} execOk=${a.laneCmdExecOk} pause=${a.laneActionPause} atc_curr=${a.atc_state_curr} target=${a.lastTargetIdx} visual=${a.lastVisualIdx} action=${a.laneAction}")
        Log.d(TAG, "超车状态机: cmdSent=${a.overtakeCmdSent} pause=${a.overtakeActionPause} action=${a.overtakeAction}" +
                " prog=$overtakeInProgress 冷却剩余=${(overtakeCooldownUntil - now).coerceAtLeast(0)}ms 上次方向=$overtakeLastDirection" +
                " 触发稳定=${if (overtakeTriggerStableTime == 0L) 0 else now - overtakeTriggerStableTime}ms")
        Log.d(TAG, "阶段: $overtakeDebugStage → $overtakeDebugDecision : $overtakeDebugReason")
        Log.d(TAG, "SNAP " + a.overtakeSnapshotLine())
        dumpLane(a)
    }

    private fun dumpLane(a: MainActivity) {
        val lanes = (0 until navLane.validCount).map { navLane.laneStrArray.getOrNull(it) ?: "" }
        Log.d(TAG, "导航车道(${navLane.validCount}): " + lanes.joinToString("|") +
                "  | 视觉: cnt=${vLaneResult.laneCnt} 左=${vLaneResult.leftLaneCnt}(id${vLaneResult.leftLaneId}) 右=${vLaneResult.rightLaneCnt}(id${vLaneResult.rightLaneId})")
        Log.d(TAG, "视觉文本=$vLaneText 导航文本=$vNavLaneText | 推荐车道禁用=$disableSuggestLane 允许变道=$enableLaneChange 车道线有效=$laneLineValid")
    }

    // -----------------------------
    // 参数读写(内存 + SharedPreferences)
    // -----------------------------
    private fun applyValue(context: Context, a: MainActivity?, key: String, raw: String): Boolean {
        val spec = PARAMS[key] ?: return false
        val prefs = context.getSharedPreferences("user_settings", Context.MODE_PRIVATE)
        val ed = prefs.edit()

        when (spec.kind) {
            'b' -> {
                val v = raw == "1" || raw.equals("true", true) || raw.equals("on", true)
                // 全局开关，不依赖 MainActivity 实例
                if (key == "lane_unknown_block") lane_unknown_block = v
                if (key == "safety_legacy_mode") safety_legacy_mode = v
                if (key == "highway_reserve_rightmost_lane") highway_reserve_rightmost_lane = v
                if (key == "drive_log_en") drive_log_en = v
                if (key == "solid_relax_en") solid_relax_en = v
                if (key == "last_lane_suppress_en") last_lane_suppress_en = v
                if (key == "overtake_block_on_nav_lane_hint") overtake_block_on_nav_lane_hint = v
                if (a != null) when (key) {
                    "app_navi" -> a.app_navi = v
                    "app_road_navi" -> a.app_road_navi = v
                    "app_overtake" -> a.app_overtake = v
                    "app_road_overtake" -> a.app_road_overtake = v
                    "suggest_lane_en" -> a.suggest_lane_en = v
                    "dis_lane_en" -> a.dis_lane_en = v
                    "keep_left_en" -> a.keep_left_en = v
                    "keep_right_en" -> a.keep_right_en = v
                    "nav_turn" -> a.nav_turn = v
                    "road_median" -> a.road_median = v
                    "road_right_first" -> a.road_right_first = v
                    "vision_keep_right" -> a.vision_keep_right = v
                    "lane_prob_enable" -> a.lane_prob_enable = v
                    "navi_speak" -> a.navi_speak = v
                    "navi_speak_first" -> a.navi_speak_first = v
                    "navi_blind_dec_speed" -> a.navi_blind_dec_speed = v
                    "app_lidar_self_check" -> a.app_lidar_self_check = v
                    "app_thresh_follow_cp" -> a.app_thresh_follow_cp = v
                    "ramp_slowdown_en" -> a.ramp_slowdown_en = v
                }
                ed.putBoolean(spec.prefKey, v)
            }
            'i' -> {
                val v = raw.toIntOrNull() ?: return false
                // 侧向安全去抖帧数(全局变量，不依赖 MainActivity 实例)
                if (key == "safe_confirm_frames") { safeConfirmFrames = v.coerceIn(1, 30) }
                if (key == "safe_confirm_frames_navi") { safeConfirmFramesNavi = v.coerceIn(1, 30) }
                if (key == "fork_chain_dist_m") { fork_chain_dist_m = v.coerceIn(0, 5000) }
                if (key == "solid_relax_offset_h") { solid_relax_offset_h = v.coerceIn(0, 5000) }
                if (key == "solid_relax_offset_l") { solid_relax_offset_l = v.coerceIn(0, 5000) }
                if (key == "navi_edge_speak_delay_ms" && a != null) { a.navi_edge_speak_delay_ms = v.coerceIn(0, 5000).toLong() }
                if (key == "navi_edge_atc_timeout_ms" && a != null) { a.navi_edge_atc_timeout_ms = v.coerceIn(100, 5000).toLong() }
                if (a != null) when (key) {
                    "nav_mode" -> a.nav_mode = v
                    "overtake_mode" -> a.overtake_mode = v
                    "vision_lane_mode" -> a.vision_lane_mode = v
                    "navi_min_speed" -> a.navi_min_speed = v
                    "atc_dist_h" -> a.atc_dist_h = v
                    "atc_dist_l" -> a.atc_dist_l = v
                    "fork_dist_h" -> a.fork_dist_h = v
                    "fork_dist_l" -> a.fork_dist_l = v
                    "lidar_front_dist_mode" -> a.lidar_front_dist_mode = v
                    "lidar_rear_dist_mode" -> a.lidar_rear_dist_mode = v
                    "ramp_slowdown_dist_h" -> a.ramp_slowdown_dist_h = v
                    "ramp_slowdown_dist_l" -> a.ramp_slowdown_dist_l = v
                    "ramp_slowdown_speed_h" -> a.ramp_slowdown_speed_h = v
                    "ramp_slowdown_speed_l" -> a.ramp_slowdown_speed_l = v
                    "ramp_hold_time" -> a.ramp_hold_time = v
                }
                ed.putInt(spec.prefKey, v)
            }
            'f' -> {
                val v = raw.toFloatOrNull() ?: return false
                if (key == "solid_relax_margin_m") solid_relax_margin_m = v.coerceIn(0f, 200f)
                if (a != null) when (key) {
                    "lidar_front_dist_m" -> a.lidar_front_dist_m = v
                    "lidar_front_time_s" -> a.lidar_front_time_s = v
                    "lidar_rear_dist_m" -> a.lidar_rear_dist_m = v
                    "lidar_rear_time_s" -> a.lidar_rear_time_s = v
                    "lidar_front_vrel_time_s" -> a.lidar_front_vrel_time_s = v
                    "lidar_rear_vrel_time_s" -> a.lidar_rear_vrel_time_s = v
                }
                ed.putFloat(spec.prefKey, v)
            }
            's' -> { // 秒 → 毫秒
                val sec = raw.toLongOrNull() ?: return false
                val ms = (sec * 1000L).coerceIn(0L, 120_000L)
                if (a != null) a.overtake_reverse_cooldown_ms = ms
                ed.putInt(spec.prefKey, (ms / 1000L).toInt())
            }
        }
        ed.apply()
        return true
    }

    private fun readValue(context: Context, a: MainActivity?, key: String): String {
        val spec = PARAMS[key] ?: return "未知参数($key)，可用 --es cmd params 查看"
        val prefs = context.getSharedPreferences("user_settings", Context.MODE_PRIVATE)
        return when (spec.kind) {
            'b' -> (if (a == null) prefs.getBoolean(spec.prefKey, false) else when (key) {
                "app_navi" -> a.app_navi
                "app_road_navi" -> a.app_road_navi
                "app_overtake" -> a.app_overtake
                "app_road_overtake" -> a.app_road_overtake
                "suggest_lane_en" -> a.suggest_lane_en
                "dis_lane_en" -> a.dis_lane_en
                "keep_left_en" -> a.keep_left_en
                "keep_right_en" -> a.keep_right_en
                "nav_turn" -> a.nav_turn
                "road_median" -> a.road_median
                "road_right_first" -> a.road_right_first
                "vision_keep_right" -> a.vision_keep_right
                "lane_prob_enable" -> a.lane_prob_enable
                "lane_unknown_block" -> lane_unknown_block
                "safety_legacy_mode" -> safety_legacy_mode
                "highway_reserve_rightmost_lane" -> highway_reserve_rightmost_lane
                "drive_log_en" -> drive_log_en
                "solid_relax_en" -> solid_relax_en
                "last_lane_suppress_en" -> last_lane_suppress_en
                "overtake_block_on_nav_lane_hint" -> overtake_block_on_nav_lane_hint
                "navi_speak" -> a.navi_speak
                "navi_speak_first" -> a.navi_speak_first
                "navi_blind_dec_speed" -> a.navi_blind_dec_speed
                "app_lidar_self_check" -> a.app_lidar_self_check
                "app_thresh_follow_cp" -> a.app_thresh_follow_cp
                "ramp_slowdown_en" -> a.ramp_slowdown_en
                else -> false
            }).toString()
            'i' -> run {
                if (key == "safe_confirm_frames") return@run safeConfirmFrames.toString()
                if (key == "safe_confirm_frames_navi") return@run safeConfirmFramesNavi.toString()
                if (key == "fork_chain_dist_m") return@run fork_chain_dist_m.toString()
                if (key == "solid_relax_offset_h") return@run solid_relax_offset_h.toString()
                if (key == "solid_relax_offset_l") return@run solid_relax_offset_l.toString()
                if (key == "navi_edge_speak_delay_ms") return@run (a?.navi_edge_speak_delay_ms ?: 0L).toString()
                if (key == "navi_edge_atc_timeout_ms") return@run (a?.navi_edge_atc_timeout_ms ?: 0L).toString()
                (if (a == null) prefs.getInt(spec.prefKey, 0) else when (key) {
                "nav_mode" -> a.nav_mode
                "overtake_mode" -> a.overtake_mode
                "vision_lane_mode" -> a.vision_lane_mode
                "navi_min_speed" -> a.navi_min_speed
                "atc_dist_h" -> a.atc_dist_h
                "atc_dist_l" -> a.atc_dist_l
                "fork_dist_h" -> a.fork_dist_h
                "fork_dist_l" -> a.fork_dist_l
                "lidar_front_dist_mode" -> a.lidar_front_dist_mode
                "lidar_rear_dist_mode" -> a.lidar_rear_dist_mode
                "ramp_slowdown_dist_h" -> a.ramp_slowdown_dist_h
                "ramp_slowdown_dist_l" -> a.ramp_slowdown_dist_l
                "ramp_slowdown_speed_h" -> a.ramp_slowdown_speed_h
                "ramp_slowdown_speed_l" -> a.ramp_slowdown_speed_l
                "ramp_hold_time" -> a.ramp_hold_time
                else -> 0
            }).toString()
                }   // end run (safe_confirm_frames 提前返回)
            'f' -> (if (a == null) prefs.getFloat(spec.prefKey, 0f) else when (key) {
                "solid_relax_margin_m" -> solid_relax_margin_m
                "lidar_front_dist_m" -> a.lidar_front_dist_m
                "lidar_front_time_s" -> a.lidar_front_time_s
                "lidar_rear_dist_m" -> a.lidar_rear_dist_m
                "lidar_rear_time_s" -> a.lidar_rear_time_s
                "lidar_front_vrel_time_s" -> a.lidar_front_vrel_time_s
                "lidar_rear_vrel_time_s" -> a.lidar_rear_vrel_time_s
                else -> 0f
            }).toString()
            's' -> ((if (a == null) prefs.getInt(spec.prefKey, 20) * 1000L else a.overtake_reverse_cooldown_ms) / 1000L).toString() + "s"
            else -> "?"
        }
    }

    companion object {
        const val ACTION = "com.carrot.amapnavi.DEBUG"
        private const val TAG = "AmapDbg"
    }
}
