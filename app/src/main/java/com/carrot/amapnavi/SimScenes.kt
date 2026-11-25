package com.carrot.amapnavi

import org.json.JSONObject

/* =========================================================
 * 端到端模拟场景库(仅调试用，由 DebugReceiver cmd=sim 启动)
 *
 * 目的：在没有实车、没有 CP 的环境下，按帧把传感器状态灌进决策链路，
 *       完整走一遍"触发 → 侧评估 → 安全门 → 下发"，用来验证超车/变道与
 *       匝道提前减速在各种正常与恶劣条件下的行为。
 *
 * 设计原则(避免"条件太理想")：
 *   - 传感器按真实频率(100ms/帧)逐帧注入，不是一次性给好数据
 *   - 显式覆盖抖动、丢帧、离线、幽灵目标、判据缺失等劣化情况
 *   - 场景里的距离/车速按帧推进，能走完整条决策链而不是只看单点
 *
 * 每帧注入三类输入：
 *   ① CP 侧 opState 字段：前车/雷达/激光/盲区/车道线(blind_lane_*)
 *   ② App 侧视觉线型原始值：走与非实车完全相同的 LaneLine 时间滤波
 *   ③ 运行态直注入：xTurnInfo/xTurnDist(高德引导)、navLane(导航车道)、bHighWay
 * ========================================================= */
object SimScenes {

    // 场景名 → 说明
    val scenes: LinkedHashMap<String, String> = linkedMapOf(
            // ---------- 超车：触发条件 ----------
            "ot_base" to "基线：前车70(慢)、两侧净空、虚线 → 应超车",
            "ot_lead_far" to "前车120m(超触发距离) → 不超车",
            "ot_lead_near" to "前车6m(过近) → 不超车",
            "ot_lead_fast" to "前车115(不比我慢) → 不超车",
            "ot_lead_crawl" to "前车20km/h(疑似拥堵) → 不超车",
            "ot_no_cruise" to "未开启定速(engaged=false，vCruise=0) → 不超车",
            "ot_slow_speed" to "车速30(低于高速门限60) → 不超车",
            // ---------- 超车：cut-in ----------
            "ot_cutin" to "第10帧前车 60m→15m 插入 → 抑制窗口内不超车",
            "ot_cutin_light" to "第10帧前车 60m→40m 插入(降幅33%) → 应抑制",
            "ot_cutin_small" to "前车缓慢接近 60m→55m(降幅8%) → 不应误判为插入",
            "ot_cutin_repeat" to "每30帧插入一次 → 持续抑制，不超车",
            // ---------- 超车：侧向安全 ----------
            "ot_side_clear" to "侧后45m、侧前80m 净空 → 应超车",
            "ot_side_front_close" to "侧前车10m → 不超车",
            "ot_side_rear_close" to "侧后车8m(激光) → 不超车",
            "ot_side_front_closer" to "侧前30m 比本车道前车45m更近 → 评分下降",
            "ot_side_unknown" to "侧向无任何数据(量程外未知) → 不应视为安全",
            "ot_side_late" to "前3s侧向无数据、之后正常 → 无数据期间不超车",
            // ---------- 超车：传感器劣化 ----------
            "ot_lidar_left_off" to "左侧激光离线 → 无法确认后方，不超车",
            "ot_lidar_intermittent" to "左激光在线/离线交替 → 不应在离线帧放行",
            "ot_lidar_flaky" to "激光侧方盲区每3帧翻转 → 去抖应拦住",
            "ot_radar_flaky" to "前雷达侧前盲区每3帧翻转 → 去抖应拦住",
            "ot_radar_ghost" to "雷达幽灵目标(侧前20m，激光无) → 不应盲变",
            // ---------- 超车：车道线 ----------
            "ot_left_solid" to "CP 判左侧实线 → 不可左超",
            "ot_both_solid" to "两侧实线 → 不可超车",
            "ot_solid_flaky" to "视觉线型实/虚抖动 → 迟滞应持续判实线",
            "ot_lane_unknown" to "视觉检测停摆 + CP 无车道线信号 → 判据不可用，禁止变道",
            // ---------- 超车：导航车道 ----------
            "ot_nav_left_blocked" to "导航左车道禁行(x) → 不可左超",
            // ---------- 匝道提前减速 ----------
            "ramp_hw120" to "高速120km/h 匝道(右) 距离700m→0 → 减速曲线到60",
            "ramp_hw90" to "高速90km/h 匝道(右) → 减速曲线到60",
            "ramp_rd60" to "公路60km/h 匝道(右) → 减速曲线到45",
            "ramp_fork_left" to "高速 左分岔 → 同样减速",
            "ramp_turn_right" to "高速 右转弯(非匝道) → 不减速",
            "ramp_guide_switch" to "匝道引导在150m处被切成左转 → 维持期内应保持减速",
            "ramp_dist_flaky" to "匝道距离抖动(±40m) → 不应反复撤销/激活",
            "ramp_two" to "连续两个匝道 → 第二个应重新触发减速"
    )

    fun isKnown(name: String): Boolean = scenes.containsKey(name)

    // 非匝道类场景(用于分析脚本区分)
    fun isRampScene(name: String): Boolean = name.startsWith("ramp_")

    // 场景时长(秒)：匝道场景要跑完整条曲线，时间更长
    fun durationSec(name: String): Int = if (isRampScene(name)) 18 else 12

    // -----------------------------
    // 场景参数
    // -----------------------------
    fun highWayOf(name: String): Boolean = when (name) {
        "ramp_rd60" -> false
        else -> true
    }

    fun vEgoOf(name: String): Int = when (name) {
        "ot_slow_speed" -> 30
        "ramp_hw90" -> 90
        "ramp_rd60" -> 60
        else -> 100
    }

    fun vCruiseOf(name: String): Int = when (name) {
        "ot_no_cruise" -> 0
        else -> 110
    }

    // 本车道前车
    private fun leadDrelOf(name: String, tick: Long): Int = when (name) {
        "ot_lead_far" -> 120
        "ot_lead_near" -> 6
        "ot_cutin" -> if (tick < 10) 60 else 15
        "ot_cutin_light" -> if (tick < 10) 60 else 40
        "ot_cutin_small" -> (60L - tick).coerceAtLeast(55L).toInt()   // 缓慢接近
        "ot_cutin_repeat" -> if (tick % 30 < 8) 60 else 18          // 周期性插入
        else -> 45
    }

    private fun leadVleadOf(name: String): Int = when (name) {
        "ot_lead_fast" -> 115
        "ot_lead_crawl" -> 20
        "ot_cutin" -> 60
        "ot_cutin_light" -> 60
        "ot_cutin_repeat" -> 60
        else -> 70
    }

    private fun leadVrelOf(name: String, tick: Long): Int = when (name) {
        "ot_lead_fast" -> 15
        "ot_lead_crawl" -> -50
        "ot_cutin", "ot_cutin_light" -> if (tick < 10) -30 else -45
        "ot_cutin_repeat" -> if (tick % 30 < 8) -30 else -50
        else -> -30
    }

    // 侧向(激光前角，mm；不下发表示"量程内无目标")
    private fun sideFrontLidarOf(name: String): Int? = when (name) {
        "ot_side_front_close" -> 10_000       // 10m
        "ot_side_front_closer" -> 30_000      // 30m < 本车道前车45m
        "ot_side_unknown" -> null             // 量程内无目标
        "ot_radar_ghost" -> null              // 只有雷达有目标
        else -> 80_000
    }

    private fun sideRearLidarOf(name: String): Int? = when (name) {
        "ot_side_rear_close" -> -8_000        // 后方 8m
        "ot_side_clear" -> -45_000            // 后方 45m
        "ot_side_unknown" -> null
        else -> -60_000
    }

    // -----------------------------
    // 生成该场景在某一帧的 CP 状态报文
    // -----------------------------
    fun frame(name: String, tick: Long): JSONObject = JSONObject().apply {
        put("v_ego_kph", vEgoOf(name))
        put("v_cruise_kph", vCruiseOf(name))
        put("engaged", name != "ot_no_cruise")   // 该场景模拟"未开启定速"
        put("active", true)
        put("IsOnroad", true)
        put("atc_state", 0)

        // 侧向传感器在线
        put("lidar_l", lidarLeftOnline(name, tick))
        put("lidar_r", true)

        // 车道宽度：决定视觉车道数(>=2m 才算存在车道)
        put("lane_width", 3.5f)
        put("l_lane_width", 3.5f)
        put("r_lane_width", 3.5f)

        // 侧向目标(激光四角)。ot_side_late：前 3s 完全没有侧向数据，之后恢复正常。
        val sideLateBlank = (name == "ot_side_late" && tick < 30)
        putNullable("lf_drel", if (sideLateBlank) null else sideFrontLidarOf(name))
        putNullable("rf_drel", 80_000)
        putNullable("lb_drel", if (sideLateBlank) null else sideRearLidarOf(name))
        putNullable("rb_drel", -60_000)

        // 本车道前车
        put("lead1", true)
        put("drel", leadDrelOf(name, tick))
        put("vlead", leadVleadOf(name))
        put("vrel", leadVrelOf(name, tick))

        // 默认：各类盲区通过、车道线非实线
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

        when (name) {
            // ---- 车道线 ----
            "ot_left_solid" -> { put("blind_lane_l", true); put("blind_lane_r", false) }
            "ot_both_solid" -> { put("blind_lane_l", true); put("blind_lane_r", true) }
            "ot_solid_flaky" -> { put("blind_lane_l", false); put("blind_lane_r", false) }
            // ---- 传感器抖动 ----
            "ot_lidar_flaky" -> put("lidar_lblind", (tick / 3) % 2 == 0L)
            "ot_radar_flaky" -> put("l_front_blind", (tick / 3) % 2 == 0L)
            "ot_radar_ghost" -> {
                // 雷达侧前角有目标(20m)，激光没有 → 检查是否会被当成真实障碍
                put("l_lead", true)
                put("l_drel", 20)
                put("l_vrel", -20)
            }
            // 判据不可用：刻意"不下发" blind_lane_*，配合视觉检测停摆
            "ot_lane_unknown" -> Unit
            else -> { put("blind_lane_l", false); put("blind_lane_r", false) }
        }
    }

    // 左激光在线状态(部分场景离线/间歇)
    private fun lidarLeftOnline(name: String, tick: Long): Boolean = when (name) {
        "ot_lidar_left_off" -> false
        "ot_lidar_intermittent" -> (tick / 10) % 2 == 0L
        else -> true
    }

    private fun JSONObject.putNullable(key: String, value: Int?) {
        if (value == null) remove(key) else put(key, value)
    }

    // -----------------------------
    // App 侧视觉线型注入(0=虚线 1=实线 -1=无线)；null 表示视觉检测停摆
    // -----------------------------
    fun laneRaw(name: String, tick: Long): Pair<Int, Int>? = when (name) {
        "ot_solid_flaky" -> (if ((tick / 3) % 2 == 0L) 1 else 0) to 0
        "ot_lane_unknown" -> null       // 视觉检测停摆
        else -> 0 to 0
    }

    // 该场景是否关闭视觉车道线检测(模拟检测停摆)
    fun laneDetectOff(name: String): Boolean = name == "ot_lane_unknown"

    // -----------------------------
    // 高德引导注入(xTurnInfo / xTurnDist)
    // -----------------------------
    fun turnInfoOf(name: String, tick: Long): Int = when (name) {
        "ramp_hw120", "ramp_hw90", "ramp_rd60", "ramp_dist_flaky" -> TurnInfo_ForkRight
        "ramp_fork_left" -> TurnInfo_ForkLeft
        "ramp_turn_right" -> TurnInfo_TurnRight
        "ramp_guide_switch" -> if (tick < 60) TurnInfo_ForkRight else TurnInfo_TurnLeft
        // 连续两个匝道：第 41~141 帧引导结束(第一个匝道的维持期)，之后第二个匝道
        "ramp_two" -> if (tick in 41L..141L) -1 else TurnInfo_ForkRight
        else -> -1
    }

    // 到路口/岔口的距离(m)：按帧推进，模拟车辆接近
    fun turnDistOf(name: String, tick: Long): Float {
        val jitter = if (name == "ramp_dist_flaky") {
            // 抖动 ±40m：传感器/导航距离本身不稳
            if ((tick / 3) % 2 == 0L) 40f else -40f
        } else 0f

        return when (name) {
            "ramp_hw120" -> (700f - tick * 4f).coerceAtLeast(0f)
            "ramp_hw90" -> (500f - tick * 3f).coerceAtLeast(0f)
            "ramp_rd60" -> (260f - tick * 2f).coerceAtLeast(0f)
            "ramp_fork_left" -> (600f - tick * 4f).coerceAtLeast(0f)
            "ramp_turn_right" -> (200f - tick * 2f).coerceAtLeast(0f)
            "ramp_guide_switch" -> (500f - tick * 4f).coerceAtLeast(0f)
            "ramp_dist_flaky" -> (500f - tick * 4f).coerceAtLeast(0f) + jitter
            // 连续两个匝道：第 0~40 帧走完第一个，41~141 帧引导结束(维持期)，
            // 142 帧之后第二个匝道重新开始 → 应产生第二次"减速开始"
            "ramp_two" -> when {
                tick < 41 -> (300f - tick * 7.5f).coerceAtLeast(0f)
                tick < 142 -> -1f
                else -> (300f - (tick - 142) * 7.5f).coerceAtLeast(0f)
            }
            else -> -1f
        }
    }

    // -----------------------------
    // 导航车道注入(仅个别场景需要)
    // -----------------------------
    fun navLaneOf(name: String): NavLane? = when (name) {
        // 3 条车道，索引0(最左)禁行。禁行标记是高位 X(见 isBlocked/isStopPyOvertake)
        "ot_nav_left_blocked" -> NavLane(arrayOf("X", "0", "0"), 3)
        else -> null
    }

    // 是否需要打开推荐/禁行车道功能(仅导航场景)
    fun needNavCheck(name: String): Boolean = navLaneOf(name) != null

    // 该场景是否为"领航(靠边/匝道/转弯)"场景：
    //   这类场景需要额外的决策入口条件(开领航 + naviDataTimeout=false)，见 applySimScene
    fun isNaviScene(name: String): Boolean = name.startsWith("ramp_")
}

// 把一帧模拟状态灌进运行状态(调用点：naviProcess)
fun MainActivity.applySimScene(name: String, tick: Long) {
    // ① CP 状态
    opStateManager.updateFromJson(SimScenes.frame(name, tick))
    vLaneResult = opStateManager.visionLaneCalc(vision_lane_mode, navi_lane_cnt, bHighWay || road_median)

    // ② App 视觉线型(走真实滤波链路)；模拟"视觉检测在跑"
    val detectOn = !SimScenes.laneDetectOff(name)
    lane_detect = detectOn
    laneLineValid = detectOn
    SimScenes.laneRaw(name, tick)?.let { (l, r) -> simInjectLaneTypeRaw(l, r) }

    // ③ 运行态直注入
    bHighWay = SimScenes.highWayOf(name)
    opStateManager.state.xTurnInfo = SimScenes.turnInfoOf(name, tick)
    opStateManager.state.xTurnDist = SimScenes.turnDistOf(name, tick)
    opStateManager.state.nTurnDist = opStateManager.state.xTurnDist.toInt()

    // ④ 领航入口条件(见 AmapNavi 开头的 gate)：
    //   模拟环境没有真实高德数据流，naviDataTimeout 会一直为 true，
    //   整个靠边/匝道决策会被跳过(表现为 laneChangeEdge 恒为 KEEP)。
    //   领航开关已由 DebugReceiver 的 sim 命令按场景类型设置。
    if (SimScenes.isNaviScene(name)) naviDataTimeout = false

    // ⑤ 车辆状态(carState)：领航的车速门限用的是 carState(来自 CP 车辆状态报文)，
    //   模拟环境没有该报文 → vEgo 恒为 0，会在"车速过低"处直接退出。
    //   carState.vEgo 单位 m/s，车辆状态报文里的 vEgo 换算即可。
    carState.vEgo = (SimScenes.vEgoOf(name) / 3.6f).toInt()
    carState.vCruise = SimScenes.vCruiseOf(name)
    carState.isOnroad = true
    carState.active = true

    val nav = SimScenes.navLaneOf(name)
    if (nav != null) {
        navLane = nav
        suggest_lane_en = true
        dis_lane_en = true
    }
}
