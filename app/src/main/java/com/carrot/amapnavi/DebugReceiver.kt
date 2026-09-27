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
            "navi_speak" to Spec("navi_speak", 'b'),
            "navi_speak_first" to Spec("navi_speak_first", 'b'),
            "navi_blind_dec_speed" to Spec("navi_blind_dec_speed", 'b'),
            "app_lidar_self_check" to Spec("app_lidar_self_check", 'b'),
            "app_thresh_follow_cp" to Spec("app_thresh_follow_cp", 'b'),
            // ---- 数值 ----
            "nav_mode" to Spec("nav_mode", 'i'),
            "overtake_mode" to Spec("overtake_mode", 'i'),
            "vision_lane_mode" to Spec("vision_lane_mode", 'i'),
            "navi_min_speed" to Spec("navi_min_speed", 'i'),
            "atc_dist_h" to Spec("atc_dist_h", 'i'),
            "atc_dist_l" to Spec("atc_dist_l", 'i'),
            "fork_dist_h" to Spec("fork_dist_h", 'i'),
            "fork_dist_l" to Spec("fork_dist_l", 'i'),
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
                        val parts = (value ?: "baseline").split(",")
                        val scene = parts[0].trim()
                        val secs = parts.getOrNull(1)?.trim()?.toLongOrNull() ?: 30L
                        if (!SimScenes.isKnown(scene)) {
                            Log.w(TAG, "未知场景 $scene，可用 --es cmd scenes 查看")
                        } else {
                            // 模拟高速工况：打开超车、关闭领航(领航会接管变道请求)，并复位状态机
                            a.bHighWay = true
                            a.app_overtake = true
                            a.app_road_overtake = true
                            a.app_navi = false
                            a.app_road_navi = false
                            a.resetOvertakeAction()
                            a.resetLaneAction()
                            overtakeCooldownUntil = 0L
                            overtakeLastDirection = LaneChangeAction.KEEP
                            overtakeTriggerStableTime = 0L
                            cutInSuppressUntil = 0L
                            prevLeadDrel = null
                            prevLeadPresent = false
                            resetLaneTypeFilter()
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
                    Log.d(TAG, "模拟已停止")
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
                }
                ed.putBoolean(spec.prefKey, v)
            }
            'i' -> {
                val v = raw.toIntOrNull() ?: return false
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
                }
                ed.putInt(spec.prefKey, v)
            }
            'f' -> {
                val v = raw.toFloatOrNull() ?: return false
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
                "navi_speak" -> a.navi_speak
                "navi_speak_first" -> a.navi_speak_first
                "navi_blind_dec_speed" -> a.navi_blind_dec_speed
                "app_lidar_self_check" -> a.app_lidar_self_check
                "app_thresh_follow_cp" -> a.app_thresh_follow_cp
                else -> false
            }).toString()
            'i' -> (if (a == null) prefs.getInt(spec.prefKey, 0) else when (key) {
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
                else -> 0
            }).toString()
            'f' -> (if (a == null) prefs.getFloat(spec.prefKey, 0f) else when (key) {
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
