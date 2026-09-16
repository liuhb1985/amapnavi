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
        // -------- 单侧安全评估 --------
        val minLaneWidth: Float = 2.3f,         // 目标车道最小宽度(m)
        val sideMinScore: Int = 60,             // 单侧可用最低评分
        val sideVrelThreshold: Int = 10,        // 侧前方车辆更慢阈值(km/h)
        val sideSafetyMargin: Float = 5f,       // 侧前车与本车道前车距离安全裕度(m)
        val rearMinDist: Float = 12f,           // 目标车道后方车辆最小安全距离(m)，实际还会叠加约0.8s时距
        val sideFrontMinDist: Float = 20f,      // 目标车道前方车辆最小距离(m)，需小于激光雷达约50m量程
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

// 后台结构体，用于收集高德导航字段
data class NaviState(
        var carrotIndex: Int? = null,
        var carrotCmd: String? = null,
        var carrotArg: String? = null,
        var goalPosX: Double? = null,
        var goalPosY: Double? = null,
        var szGoalName: String? = null,
        var nRoadLimitSpeed: Int? = -1,
        var nSdiType: Int? = -1,
        var nSdiSpeedLimit: Int? = 0,
        var nSdiSection: Int? = -1,
        var nSdiDist: Int? = -1,
        var nSdiBlockType: Int? = -1,
        var nSdiBlockSpeed: Int? = 0,
        var nSdiBlockDist: Int? = 0,
        var nSdiPlusType: Int? = -1,
        var nSdiPlusSpeedLimit: Int? = 0,
        var nSdiPlusDist: Int? = 0,
        var nSdiPlusBlockType: Int? = -1,
        var nSdiPlusBlockSpeed: Int? = 0,
        var nSdiPlusBlockDist: Int? = 0,
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
        var nPosSpeed: Float? = null
) {
    fun toJson(): String = Gson().toJson(this)
}