package com.carrot.amapnavi

import com.google.gson.Gson

data class LaneCalcResult(
        val laneCnt: Int = 0,
        val leftLaneCnt: Int = 0,
        val rightLaneCnt: Int = 0,
        val leftLaneId: Int = 0,
        val rightLaneId: Int = 0
)

// 超车单侧车道评估结果(基于毫米波雷达 + 激光雷达侧向数据)
data class OvertakeSideEval(
        val side: LaneChangeAction = LaneChangeAction.KEEP,
        val safe: Boolean = false,     // 该方向是否安全可超车
        val score: Int = 0,            // 0~100，越大越适合超车
        val reason: String = ""        // 评估说明
)

// 超车模式参数集(1=激进 2=常规 3=保守)
data class OvertakeParams(
        val mode: Int = 2,
        val modeName: String = "常规",
        // -------- 触发条件 --------
        val triggerMaxDist: Float = 80f,        // 触发超车的前车最大距离(m)，受毫米波雷达约100m量程限制
        val triggerMinDist: Float = 12f,        // 触发超车的前车最小距离(m)，过近先拉开
        val vrelThreshold: Int = -3,            // 前车相对速度阈值(km/h)，负值表示前车更慢
        val timeGapMax: Float = 3.0f,           // 跟车时距阈值(s)
        val cruiseRatio: Float = 0.90f,         // 巡航速度比例阈值
        val leadMinSpeedHighWay: Int = 35,      // 高速前车最低速度(km/h)，低于视为拥堵不超车
        val leadMinSpeedRoad: Int = 20,         // 普通道路前车最低速度(km/h)
        val stableTime: Long = 800L,            // 触发条件需要连续满足的时间(ms)
        val cutInSuppressMs: Long = 2500L,      // 检测到前车插入(cut-in)后的超车抑制时长(ms)
        // -------- 单侧安全评估 --------
        val minLaneWidth: Float = 2.3f,         // 目标车道最小宽度(m)
        val sideMinScore: Int = 60,             // 单侧可用最低评分
        val sideVrelThreshold: Int = 10,        // 侧前方车辆更慢阈值(km/h)
        val sideSafetyMargin: Float = 5f,       // 侧前车与本车道前车距离安全裕度(m)
        val rearMinDist: Float = 12f,           // 目标车道后方车辆最小安全距离(m)，实际还会叠加约0.8s时距
        val sideFrontMinDist: Float = 20f,      // 目标车道前方车辆最小距离(m)，需小于激光雷达约50m量程
        val sideUnknownPenalty: Int = 25,       // 侧前方"无数据"扣分：量程外未知 ≠ 确定无车
        val sideSpeedMargin: Int = 8,           // 目标车道前车比本车道前车慢超过该值(km/h)则否决该方向超车
        //   —— 防"变过去那条车道更慢"：只看距离不够，必须比速度
        val allowRightOvertake: Boolean = true, // 是否允许右侧超车
        val preferLeftFirst: Boolean = true,    // 是否优先选择左侧超车
        // -------- 冷却 --------
        val cooldownSuccess: Long = 10000L,     // 成功超车后冷却时间(ms)
        val cooldownFailed: Long = 4000L        // 超车失败/取消后冷却时间(ms)
)

data class CarState(
        var isOnroad: Boolean = false,
        var vCruise: Int = 0,
        var vEgo: Int = 0,
        var active: Boolean = false,
        var xState: Int = 0,
        var xTrafficState: Int = 0
)

data class NavLane(
        val laneStrArray: Array<String>,  // 存储车道字符串
        val validCount: Int               // 有效车道数量
)

data class OpState(
        var isOnroad: Boolean = false,
        var vCruise: Int? = null,
        var vEgo: Int? = null,
        var active: Boolean = false,
        var engaged: Boolean = false,
        var steer_angle: Float? = null,
        var gas_press: Boolean = false,
        var break_press: Boolean = false,
        var left_blindspot: Boolean = false,
        var right_blindspot: Boolean = false,

        var lead1: Boolean = false,
        var drel: Int? = null,
        var vlead: Int? = null,
        var vrel: Int? = null,
        var lead_accel: Float? = null,

        var l_lead: Boolean = false,
        var l_drel: Int? = null,
        var l_vlead: Int? = null,
        var l_vrel: Int? = null,

        var r_lead: Boolean = false,
        var r_drel: Int? = null,
        var r_vlead: Int? = null,
        var r_vrel: Int? = null,

        //speed, km/h
        var lf_vrel: Int? = null,
        var rf_vrel: Int? = null,
        var lb_vrel: Int? = null,
        var rb_vrel: Int? = null,

        var l_front_blind: Boolean = false,
        var r_front_blind: Boolean = false,

        var lidar_lblind: Boolean = false,
        var lidar_rblind: Boolean = false,

        //激光雷达四角盲区(纯激光雷达自己的信号)
        var lidar_lfblind: Boolean = false,
        var lidar_lbblind: Boolean = false,
        var lidar_rfblind: Boolean = false,
        var lidar_rbblind: Boolean = false,

        //综合盲区(车道线/车身/激光/摄像头合并，CP 侧 *_blindspot_combined)
        var blind_l: Boolean = false,
        var blind_r: Boolean = false,

        //综合盲区的分解来源(CP 下发)，用于精确显示到底是哪一种
        var blind_camera_l: Boolean = false,
        var blind_camera_r: Boolean = false,
        var blind_car_l: Boolean = false,
        var blind_car_r: Boolean = false,
        var blind_lane_l: Boolean = false,
        var blind_lane_r: Boolean = false,
        // CP 车道实线信号的最近接收时间(ms)：用于区分"CP 说不是实线"和"CP 根本没给判据"
        var blindLaneTs: Long = 0L,

        //侧向判定阈值(CP 下发, x0.1: 正=时距s / 负=绝对距离m)，App 自算判据与 CP 同源
        var lidar_front_vdist_time: Int? = null,
        var lidar_front_vrel_time: Int? = null,
        var lidar_behind_vdist_time: Int? = null,
        var lidar_behind_vrel_time: Int? = null,

        //distance, mm
        var lf_drel: Int? = null,
        var lb_drel: Int? = null,
        var rf_drel: Int? = null,
        var rb_drel: Int? = null,
        var lf_xrel: Int? = null,
        var lb_xrel: Int? = null,
        var rf_xrel: Int? = null,
        var rb_xrel: Int? = null,

        var lidar_l: Boolean = false,
        var lidar_r: Boolean = false,
        var camera_l: Boolean = false,
        var camera_r: Boolean = false,

        var roadcate: Int? = null,
        var lat_a: Float? = null,
        var max_curve: Float? = null,

        var desire_speed: Int? = null,
        var blind_enable: Boolean = false,
        var op_blocked: Boolean = false,
        var road_blocked: Boolean = false,

        var sound: Int? = null,
        var blinker: Int? = null,

        var lane_width: Float? = null,
        var l_lane_width: Float? = null,
        var r_lane_width: Float? = null,
        var l_edge_dist: Float? = null,
        var r_edge_dist: Float? = null,

        var prob: Boolean = false,
        var l_lane_prob: Float? = null,
        var l_line_prob: Float? = null,
        var r_line_prob: Float? = null,
        var r_lane_prob: Float? = null,
        var l_edge_prob: Float? = null,
        var r_edge_prob: Float? = null,

        var atc_state: Int? = null,

        var data_time : Long = 0L,
        var nTurnInfo : Int = -1,
        var xTurnInfo : Int = -1,
        var nTurnDist : Int = 0,
        var xTurnDist : Float = 0.0f,
        var nTurnDistMax : Int = 0,
        var doLaneForkNow: Int = 0,
)

/* =========================================================
 * 后台结构体，用于收集高德导航字段（JSON 直发 CP 的 UDP 7706）
 *
 * 与 CP(carrot_serv.update) 对齐后的两点说明：
 * 1. 已移除 CP 侧「只存不消费」的死字段：goalPosX/goalPosY/szGoalName、
 *    nSdiSection/nSdiBlockSpeed/nSdiPlusSpeedLimit/nSdiPlusBlockType/
 *    nSdiPlusBlockSpeed/nSdiPlusBlockDist/nPosSpeed。
 *    这些字段发了 CP 也不用，留着只会在排障时误导（以为发了就该生效）。
 * 2. 新增 CP 支持但此前没用的字段：epochTime/timezone（校时）、
 *    latitude/longitude/heading/accuracy（手机 GPS 冗余）。
 *    它们默认 null，Gson 会整字段跳过，不开开关时完全不改变报文。
 * ========================================================= */
data class NaviState(
        var carrotIndex: Int? = null,
        var carrotCmd: String? = null,
        var carrotArg: String? = null,
        var nRoadLimitSpeed: Int? = -1,
        var nSdiType: Int? = -1,
        var nSdiSpeedLimit: Int? = 0,
        var nSdiDist: Int? = -1,
        var nSdiBlockType: Int? = -1,
        var nSdiBlockDist: Int? = 0,
        var nSdiPlusType: Int? = -1,
        var nSdiPlusDist: Int? = 0,
        var roadcate: Int? = 0,
        var nTBTDist: Int = 0,
        var nTBTTurnType: Int = -1,
        var szTBTMainText: String? = null,
        var szNearDirName: String? = null,
        var szFarDirName: String? = null,
        var nTBTNextRoadWidth: Int? = 0,
        var nTBTDistNext: Int? = 0,
        var nTBTTurnTypeNext: Int? = -1,
        var szTBTMainTextNext: String? = null,
        var nGoPosDist: Int? = null,
        var nGoPosTime: Int? = null,
        var szPosRoadName: String? = null,
        var vpPosPointLat: Double? = null,
        var vpPosPointLon: Double? = null,
        var nPosAngle: Float? = null,

        // ---- CP 已支持、此前未使用的字段（null = 不发送） ----
        /** Unix 秒。CP 在 carrotIndex % 60 == 0 时用它校时 */
        var epochTime: Long? = null,
        /** 时区，默认 Asia/Seoul */
        var timezone: String? = null,
        /** 手机 GPS：CP 在 3 秒无导航数据时用；accuracy < 15 才采信 */
        var latitude: Double? = null,
        var longitude: Double? = null,
        var heading: Float? = null,
        var accuracy: Float? = null
) {
    /**
     * 序列化前把道路限速规范化：
     * CP 侧 nRoadLimitSpeed <= 0 会被当成 30 km/h、> 200 会被当成
     * "(n-20)/10" 反解（历史放大编码），两类都要在 App 侧先夹住，
     * 否则一个非法值会让车莫名其妙被限速。
     */
    fun toJson(): String {
        val limit = nRoadLimitSpeed
        val safeLimit = when {
            limit == null || limit <= 0 -> NAVI_LIMIT_FALLBACK
            limit > NAVI_LIMIT_MAX -> NAVI_LIMIT_MAX
            else -> limit
        }
        val backup = nRoadLimitSpeed
        nRoadLimitSpeed = safeLimit
        return try {
            Gson().toJson(this)
        } finally {
            nRoadLimitSpeed = backup
        }
    }

    private companion object {
        /** 无有效限速时的兜底值（与 CP 的 30 一致） */
        const val NAVI_LIMIT_FALLBACK = 30
        /** CP 反解码的临界值：超过会被 (n-20)/10 解析成离谱的低速 */
        const val NAVI_LIMIT_MAX = 200
    }
}