package com.carrot.amapnavi

import org.json.JSONObject

/* =========================================================
 * 端到端模拟场景(仅调试用，由 adb 广播启动，见 DebugReceiver cmd=sim)
 *
 * 目的：在没有实车、没有 CP 的环境下，把一帧一帧的传感器状态灌进
 *       opStateManager，完整走一遍"触发 → 侧评估 → 安全门 → 下发"链路，
 *       验证实线/雷达/激光雷达在各种不稳定情况下都不会误放行变道。
 *
 * 每帧注入两类判据：
 *   ① CP 侧：blind_lane_l/r(车道实线)、l_front_blind(前雷达)、lidar_lblind(激光)
 *   ② App 侧：视觉线型原始值(走与非实车完全相同的 LaneLine 时间滤波)
 * ========================================================= */
object SimScenes {

    // 场景名 → 说明
    val scenes: LinkedHashMap<String, String> = linkedMapOf(
            "baseline" to "基线：前车慢、两侧安全、虚线 → 应该超车",
            "solid_cp" to "CP 判左侧实线(blind_lane_l) → 左侧应被拦",
            "solid_app" to "App 视觉判左侧实线 → 左侧应被拦(实线2帧确认)",
            "solid_flaky" to "视觉线型实/虚抖动 → 迟滞应持续判实线并拦住",
            "cp_flaky" to "CP 实线信号抖动 → 任一帧判实线即拦住",
            "radar_flaky" to "原车前雷达侧前盲区抖动 → 应拦住",
            "lidar_flaky" to "激光雷达侧方盲区抖动 → 应拦住",
            "lane_unknown" to "视觉检测关闭 + CP 无车道线信号 → 判据不可用，禁止变道",
            "cutin" to "第10帧前车 60m→15m 插入 → 抑制窗口内不超车"
    )

    fun isKnown(name: String): Boolean = scenes.containsKey(name)

    // 生成该场景在某一帧的 CP 状态报文
    fun frame(name: String, tick: Long): JSONObject = JSONObject().apply {
        // ---- 车辆状态(高速工况) ----
        put("v_ego_kph", 100)
        put("v_cruise_kph", 110)
        put("engaged", true)
        put("active", true)
        put("IsOnroad", true)
        put("atc_state", 0)

        // ---- 侧向传感器在线 ----
        put("lidar_l", true)
        put("lidar_r", true)

        // ---- 车道宽度：决定视觉车道数(>=2.0m 才认为左侧存在车道) ----
        put("lane_width", 3.5f)
        put("l_lane_width", 3.5f)
        put("r_lane_width", 3.5f)

        // ---- 侧向目标：侧前 80m、侧后 60m(量程内且足够远) ----
        put("lf_drel", 80000)
        put("rf_drel", 80000)
        put("lb_drel", -60000)
        put("rb_drel", -60000)

        // ---- 本车道前车 ----
        val cutIn = (name == "cutin" && tick >= 10)
        put("lead1", true)
        put("drel", if (cutIn) 15 else 60)
        put("vlead", if (cutIn) 60 else 70)
        put("vrel", if (cutIn) -40 else -30)

        // ---- 默认：各类盲区均通、车道线均非实线 ----
        put("l_front_blind", false)
        put("r_front_blind", false)
        put("lidar_lblind", false)
        put("lidar_rblind", false)
        put("lidar_lfblind", false)
        put("lidar_rfblind", false)
        put("lidar_lbblind", false)
        put("lidar_rbblind", false)
        put("blind_l", false)
        put("blind_r", false)
        // 注意：lane_unknown 场景刻意"不下发" blind_lane_*，
        // 用于验证"CP 也没提供车道线判据"时 App 是否 fail-safe

        when (name) {
            "solid_cp" -> {
                put("blind_lane_l", true); put("blind_lane_r", false)
            }
            "cp_flaky" -> {
                put("blind_lane_l", (tick / 3) % 2 == 0L)             // 每3帧翻转：CP 判据不稳定
                put("blind_lane_r", false)
            }
            "radar_flaky" -> {
                put("l_front_blind", (tick / 3) % 2 == 0L)
                put("blind_lane_l", false); put("blind_lane_r", false)
            }
            "lidar_flaky" -> {
                put("lidar_lblind", (tick / 3) % 2 == 0L)
                put("blind_lane_l", false); put("blind_lane_r", false)
            }
            "lane_unknown" -> Unit   // 不下发 blind_lane_*：模拟 CP 也没有判据
            else -> {
                put("blind_lane_l", false); put("blind_lane_r", false)
            }
        }
    }

    // 该帧注入的 App 视觉线型原始值(左, 右)：0=虚线 1=实线 -1=无线
    // 返回 null 表示不注入(模拟视觉检测停摆)
    fun laneRaw(name: String, tick: Long): Pair<Int, Int>? = when (name) {
        "solid_app" -> 1 to 0
        "solid_flaky" -> (if ((tick / 3) % 2 == 0L) 1 else 0) to 0  // 虚实线抖动
        "lane_unknown" -> null                                      // 视觉检测停摆
        else -> 0 to 0
    }

    // 该场景是否关闭视觉车道线检测
    fun laneDetectOff(name: String): Boolean = name == "lane_unknown"
}

// 把一帧模拟状态灌进运行状态(调用点：naviProcess)
fun MainActivity.applySimScene(name: String, tick: Long) {
    opStateManager.updateFromJson(SimScenes.frame(name, tick))
    vLaneResult = opStateManager.visionLaneCalc(vision_lane_mode, navi_lane_cnt, bHighWay || road_median)

    // 模拟"视觉检测在跑"，否则 laneTypeDetectValid() 会认为判据不可用
    val detectOn = !SimScenes.laneDetectOff(name)
    lane_detect = detectOn
    laneLineValid = detectOn

    // App 视觉线型：走与实车相同的滤波链路
    SimScenes.laneRaw(name, tick)?.let { (l, r) -> simInjectLaneTypeRaw(l, r) }
}
