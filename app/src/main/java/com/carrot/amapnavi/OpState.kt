package com.carrot.amapnavi

import android.util.Log
import org.json.JSONObject

sealed class FieldSpec(
        val key: String,
        private val resetOnTimeout: Boolean = true
) {
    abstract fun update(
            json: JSONObject,
            state: OpState,
            lastUpdate: MutableMap<String, Long>,
            now: Long,
            debounceMs: Long
    )

    protected fun shouldReset(lastUpdate: MutableMap<String, Long>, now: Long, debounceMs: Long): Boolean {
        return resetOnTimeout && ((lastUpdate[key] ?: 0L) + debounceMs < now)
    }
}

class BoolField(
        key: String,
        private val setter: (OpState, Boolean) -> Unit,
        resetOnTimeout: Boolean = true
) : FieldSpec(key, resetOnTimeout) {

    override fun update(
            json: JSONObject,
            state: OpState,
            lastUpdate: MutableMap<String, Long>,
            now: Long,
            debounceMs: Long
    ) {
        if (json.has(key) && !json.isNull(key)) {
            setter(state, json.optBoolean(key))
            lastUpdate[key] = now
        } else if (shouldReset(lastUpdate, now, debounceMs)) {
            setter(state, false)
        }
    }
}

class IntField(
        key: String,
        private val setter: (OpState, Int?) -> Unit,
        resetOnTimeout: Boolean = true
) : FieldSpec(key, resetOnTimeout) {

    override fun update(
            json: JSONObject,
            state: OpState,
            lastUpdate: MutableMap<String, Long>,
            now: Long,
            debounceMs: Long
    ) {
        if (json.has(key) && !json.isNull(key)) {
            setter(state, json.optInt(key))
            lastUpdate[key] = now
        } else if (shouldReset(lastUpdate, now, debounceMs)) {
            setter(state, null)
        }
    }
}

class FloatField(
        key: String,
        private val setter: (OpState, Float?) -> Unit,
        resetOnTimeout: Boolean = true
) : FieldSpec(key, resetOnTimeout) {

    override fun update(
            json: JSONObject,
            state: OpState,
            lastUpdate: MutableMap<String, Long>,
            now: Long,
            debounceMs: Long
    ) {
        if (json.has(key) && !json.isNull(key)) {
            setter(state, json.optDouble(key).toFloat())
            lastUpdate[key] = now
        } else if (shouldReset(lastUpdate, now, debounceMs)) {
            setter(state, null)
        }
    }
}

private val OP_FIELD_TABLE = listOf(
        //常规车辆状态
        BoolField("IsOnroad", { s, v -> s.isOnroad = v }, resetOnTimeout = false),
        IntField("v_cruise_kph", { s, v -> s.vCruise = v }, resetOnTimeout = false),
        IntField("v_ego_kph", { s, v -> s.vEgo = v }, resetOnTimeout = false),
        BoolField("active", { s, v -> s.active = v }, resetOnTimeout = false),
        BoolField("engaged", { s, v -> s.engaged = v }, resetOnTimeout = false),
        FloatField("steer_angle", { s, v -> s.steer_angle = v }, resetOnTimeout = false),
        IntField("desire_speed", { s, v -> s.desire_speed = v }, resetOnTimeout = false),

        BoolField("gas_press", { s, v -> s.gas_press = v }),
        BoolField("brake_press", { s, v -> s.break_press = v }),

        //原车后盲区
        BoolField("left_blindspot", { s, v -> s.left_blindspot = v }),
        BoolField("right_blindspot", { s, v -> s.right_blindspot = v }),

        //前车状态
        BoolField("lead1", { s, v -> s.lead1 = v }),
        IntField("drel", { s, v -> s.drel = v }),
        IntField("vlead", { s, v -> s.vlead = v }),
        IntField("vrel", { s, v -> s.vrel = v }),
        FloatField("lead_accel", { s, v -> s.lead_accel = v }),

        //左侧前车
        BoolField("l_lead", { s, v -> s.l_lead = v }),
        IntField("l_drel", { s, v -> s.l_drel = v }),
        IntField("l_vlead", { s, v -> s.l_vlead = v }),
        IntField("l_vrel", { s, v -> s.l_vrel = v }),

        //右侧前车
        BoolField("r_lead", { s, v -> s.r_lead = v }),
        IntField("r_drel", { s, v -> s.r_drel = v }),
        IntField("r_vlead", { s, v -> s.r_vlead = v }),
        IntField("r_vrel", { s, v -> s.r_vrel = v }),

        //激光雷达目标
        IntField("lf_vrel", { s, v -> s.lf_vrel = v }),
        IntField("rf_vrel", { s, v -> s.rf_vrel = v }),
        IntField("lb_vrel", { s, v -> s.lb_vrel = v }),
        IntField("rb_vrel", { s, v -> s.rb_vrel = v }),

        BoolField("l_front_blind", { s, v -> s.l_front_blind = v }),
        BoolField("r_front_blind", { s, v -> s.r_front_blind = v }),

        //激光雷达自身盲区(纯激光)
        BoolField("lidar_lblind", { s, v -> s.lidar_lblind = v }),
        BoolField("lidar_rblind", { s, v -> s.lidar_rblind = v }),
        BoolField("lidar_lfblind", { s, v -> s.lidar_lfblind = v }),
        BoolField("lidar_lbblind", { s, v -> s.lidar_lbblind = v }),
        BoolField("lidar_rfblind", { s, v -> s.lidar_rfblind = v }),
        BoolField("lidar_rbblind", { s, v -> s.lidar_rbblind = v }),

        //综合盲区(车道线/车身/激光/摄像头)
        BoolField("blind_l", { s, v -> s.blind_l = v }),
        BoolField("blind_r", { s, v -> s.blind_r = v }),

        //综合盲区的分解来源：摄像头 / 车身 / 车道实线
        BoolField("blind_camera_l", { s, v -> s.blind_camera_l = v }),
        BoolField("blind_camera_r", { s, v -> s.blind_camera_r = v }),
        BoolField("blind_car_l", { s, v -> s.blind_car_l = v }),
        BoolField("blind_car_r", { s, v -> s.blind_car_r = v }),
        BoolField("blind_lane_l", { s, v -> s.blind_lane_l = v }),
        BoolField("blind_lane_r", { s, v -> s.blind_lane_r = v }),

        //侧向判定阈值(配置类: 断流后沿用上次的值)
        IntField("lidar_front_vdist_time", { s, v -> s.lidar_front_vdist_time = v }, resetOnTimeout = false),
        IntField("lidar_front_vrel_time", { s, v -> s.lidar_front_vrel_time = v }, resetOnTimeout = false),
        IntField("lidar_behind_vdist_time", { s, v -> s.lidar_behind_vdist_time = v }, resetOnTimeout = false),
        IntField("lidar_behind_vrel_time", { s, v -> s.lidar_behind_vrel_time = v }, resetOnTimeout = false),

        IntField("lf_drel", { s, v -> s.lf_drel = v }),
        IntField("lb_drel", { s, v -> s.lb_drel = v }),
        IntField("rf_drel", { s, v -> s.rf_drel = v }),
        IntField("rb_drel", { s, v -> s.rb_drel = v }),

        IntField("lf_xrel", { s, v -> s.lf_xrel = v }),
        IntField("lb_xrel", { s, v -> s.lb_xrel = v }),
        IntField("rf_xrel", { s, v -> s.rf_xrel = v }),
        IntField("rb_xrel", { s, v -> s.rb_xrel = v }),

        BoolField("lidar_l", { s, v -> s.lidar_l = v }),
        BoolField("lidar_r", { s, v -> s.lidar_r = v }),

        BoolField("camera_l", { s, v -> s.camera_l = v }),
        BoolField("camera_r", { s, v -> s.camera_r = v }),

        //道路类型
        IntField("roadcate", { s, v -> s.roadcate = v }, resetOnTimeout = false),
        FloatField("lat_a", { s, v -> s.lat_a = v }),
        FloatField("max_curve", { s, v -> s.max_curve = v }),

        //op控制状态
        BoolField("blind_enable", { s, v -> s.blind_enable = v }),
        BoolField("op_blocked", { s, v -> s.op_blocked = v }),
        BoolField("road_blocked", { s, v -> s.road_blocked = v }),

        IntField("sound", { s, v -> s.sound = v }),
        IntField("blinker", { s, v -> s.blinker = v }),

        //车道宽度
        FloatField("lane_width", { s, v -> s.lane_width = v }, resetOnTimeout = false),
        FloatField("l_lane_width", { s, v -> s.l_lane_width = v }, resetOnTimeout = false),
        FloatField("r_lane_width", { s, v -> s.r_lane_width = v }, resetOnTimeout = false),
        FloatField("l_edge_dist", { s, v -> s.l_edge_dist = v }, resetOnTimeout = false),
        FloatField("r_edge_dist", { s, v -> s.r_edge_dist = v }, resetOnTimeout = false),

        //车道置信度
        BoolField("prob", { s, v -> s.prob = v }),
        FloatField("l_lane_prob", { s, v -> s.l_lane_prob = v }),
        FloatField("l_line_prob", { s, v -> s.l_line_prob = v }),
        FloatField("r_line_prob", { s, v -> s.r_line_prob = v }),
        FloatField("r_lane_prob", { s, v -> s.r_lane_prob = v }),
        FloatField("l_edge_prob", { s, v -> s.l_edge_prob = v }),
        FloatField("r_edge_prob", { s, v -> s.r_edge_prob = v }),

        //自动转弯状态
        IntField("atc_state", { s, v -> s.atc_state = v }, resetOnTimeout = false)
)

class OpStateManager(
        private val debounceMs: Long = 500L
) {
    val state = OpState()
    private val lastUpdate = mutableMapOf<String, Long>()
    var prevTime = System.currentTimeMillis()

    //json数据更新
    fun updateFromJson(json: JSONObject) {
        val now = System.currentTimeMillis()
        val elapsed_time = now-prevTime
        prevTime = now
        Log.d("Navi", "DataTime: ${elapsed_time} ms")

        OP_FIELD_TABLE.forEach {
            it.update(json, state, lastUpdate, now, debounceMs)
        }
    }

    private var lastStableResult: LaneCalcResult? = null
    private var pendingResult: LaneCalcResult? = null
    private var pendingCount = 0

    private val STABLE_THRESHOLD = 3  // 连续2次才生效

    //高速公路计算视觉车道
    private fun visionLaneCalcRawHighWay(navi_lane_cnt: Int): LaneCalcResult {
        var lane_cnt = 1
        var left_lane_cnt = 0
        var right_lane_cnt = 0
        var left_lane_id = 1
        var right_lane_id = 1

        val lLaneProb: Float
        val lLineProb: Float
        if(state.prob) {
            //lLaneProb = state.l_lane_prob ?: 0f
            //lLineProb = state.l_line_prob ?: 0f
            lLaneProb = 1.0f
            lLineProb = 1.0f
        }else{
            lLaneProb = 1.0f
            lLineProb = 1.0f
        }
        val l_lane_width = if (lLaneProb < 0.5f || lLineProb < 0.5f) 0.0f else state.l_lane_width ?: 0.0f

        val lEdgeProb: Float
        if(state.prob) {
            //lEdgeProb = state.l_edge_prob ?: 0f
            lEdgeProb = 1.0f
        }else{
            lEdgeProb = 1.0f
        }
        val l_edge_dist = if (lEdgeProb < 0.5f) 0.0f else state.l_edge_dist ?: 0.0f

        //左侧路宽大于2.0m
        if (l_lane_width >= 2.0f) {
            lane_cnt++
            left_lane_id++
            left_lane_cnt++

            //左侧路沿宽度大于1.5m
            if ((l_edge_dist > l_lane_width) && (l_edge_dist - l_lane_width >= 1.5f)) {
                lane_cnt++
                left_lane_id++
                left_lane_cnt++
            }
        }

        val rLaneProb: Float
        val rLineProb: Float
        if(state.prob) {
            //rLaneProb = state.r_lane_prob ?: 0f
            //rLineProb = state.r_line_prob ?: 0f
            rLaneProb = 1.0f
            rLineProb = 1.0f
        }else{
            rLaneProb = 1.0f
            rLineProb = 1.0f
        }
        val r_lane_width = if (rLaneProb < 0.5f || rLineProb < 0.5f) 0.0f else state.r_lane_width ?: 0.0f

        val rEdgeProb: Float
        if(state.prob) {
            //rEdgeProb = state.r_edge_prob ?: 0f
            rEdgeProb = 1.0f
        }else{
            rEdgeProb = 1.0f
        }
        val r_edge_dist = if (rEdgeProb < 0.5f) 0.0f else state.r_edge_dist ?: 0.0f

        //右侧路宽大于2.0m
        if (r_lane_width >= 2.0f) {
            lane_cnt++
            right_lane_id++
            right_lane_cnt++

            //右侧路沿大于1.2米
            if ((r_edge_dist > r_lane_width) && (r_edge_dist - r_lane_width >= 1.2f)) {
                lane_cnt++
                right_lane_id++
                right_lane_cnt++
            }
        }

        return LaneCalcResult(
            laneCnt = lane_cnt,
            leftLaneCnt = left_lane_cnt,
            rightLaneCnt = right_lane_cnt,
            leftLaneId = left_lane_id,
            rightLaneId = right_lane_id
        )
    }

    private fun visionLaneCalcRawRoad(navi_lane_cnt: Int): LaneCalcResult {
        var lane_cnt = 1
        var left_lane_cnt = 0
        var right_lane_cnt = 0
        var left_lane_id = 1
        var right_lane_id = 1

        //val r_lane_width = state.r_lane_width ?: 0.0f
        //val r_edge_dist = state.r_edge_dist ?: 0.0f

        val rLaneProb: Float
        val rLineProb: Float
        if(state.prob) {
            rLaneProb = state.r_lane_prob ?: 0f
            rLineProb = state.r_line_prob ?: 0f
        }else{
            rLaneProb = 1.0f
            rLineProb = 1.0f
        }
        val r_lane_width = if (rLaneProb < 0.5f || rLineProb < 0.5f) 0.0f else state.r_lane_width ?: 0.0f

        val rEdgeProb: Float
        if(state.prob) {
            //rEdgeProb = state.r_edge_prob ?: 0f
            rEdgeProb = 1.0f
        }else{
            rEdgeProb = 1.0f
        }
        val r_edge_dist = if (rEdgeProb < 0.5f) 0.0f else state.r_edge_dist ?: 0.0f

        if (r_lane_width >= 2.0f) {
            lane_cnt++
            right_lane_id++
            right_lane_cnt++

            if ((r_edge_dist > r_lane_width) && (r_edge_dist - r_lane_width >= 1.5f)) {
                lane_cnt++
                right_lane_id++
                right_lane_cnt++
            }
        }

        //val l_lane_width = state.l_lane_width ?: 0.0f
        //val l_edge_dist = state.l_edge_dist ?: 0.0f

        val lLaneProb: Float
        val lLineProb: Float
        if(state.prob) {
            lLaneProb = state.l_lane_prob ?: 0f
            lLineProb = state.l_line_prob ?: 0f
        }else{
            lLaneProb = 1.0f
            lLineProb = 1.0f
        }
        val l_lane_width = if (lLaneProb < 0.5f || lLineProb < 0.5f) 0.0f else state.l_lane_width ?: 0.0f

        val lEdgeProb: Float
        if(state.prob) {
            //lEdgeProb = state.l_edge_prob ?: 0f
            lEdgeProb = 1.0f
        }else{
            lEdgeProb = 1.0f
        }
        val l_edge_dist = if (lEdgeProb < 0.5f) 0.0f else state.l_edge_dist ?: 0.0f

        if (l_lane_width >= 2.0f) {
            if (navi_lane_cnt < 0 || lane_cnt < navi_lane_cnt) {
                lane_cnt++
                left_lane_id++
                left_lane_cnt++

                if ((l_edge_dist > l_lane_width) && (l_edge_dist - l_lane_width >= 1.5f)) {
                    if (navi_lane_cnt < 0 || lane_cnt < navi_lane_cnt) {
                        lane_cnt++
                        left_lane_id++
                        left_lane_cnt++
                    }
                }
            }
        }

        //视觉的车道数大于导航的
        if (navi_lane_cnt > 0 && lane_cnt > navi_lane_cnt) {
            when (right_lane_cnt) { //通过右侧车道数量判断
                0 -> { //右侧没有车道
                    left_lane_cnt = navi_lane_cnt - 1
                    if(left_lane_cnt < 0) left_lane_cnt = 0
                    left_lane_id = left_lane_cnt + 1
                }
                1 -> { //右侧有1条车道
                    left_lane_cnt = navi_lane_cnt - 2
                    if(left_lane_cnt < 0) left_lane_cnt = 0
                    left_lane_id = left_lane_cnt + 1
                }
                2 -> { //右侧有2条车道
                    left_lane_cnt = navi_lane_cnt - 3
                    if(left_lane_cnt < 0) left_lane_cnt = 0
                    left_lane_id = left_lane_cnt + 1
                }
            }
            lane_cnt = left_lane_cnt + right_lane_cnt + 1
        }

        return LaneCalcResult(
            laneCnt = lane_cnt,
            leftLaneCnt = left_lane_cnt,
            rightLaneCnt = right_lane_cnt,
            leftLaneId = left_lane_id,
            rightLaneId = right_lane_id
        )
    }

    fun visionLaneCalc(mode: Int, navi_lane_cnt: Int, highWay: Boolean): LaneCalcResult {
        var lane_result : LaneCalcResult? = null

        var stable_time = STABLE_THRESHOLD;
        if(mode == 2){
            stable_time = 20; //2秒
        }else if(mode == 3){
            stable_time = 40; //4秒
        }
        if(highWay) {
            lane_result = visionLaneCalcRawHighWay(navi_lane_cnt)
        }else{
            lane_result = visionLaneCalcRawRoad(navi_lane_cnt)
        }

        // 第一次
        if (lastStableResult == null) {
            lastStableResult = lane_result
            pendingResult = lane_result
            pendingCount = 0
            return lane_result
        }

        // 如果和当前稳定值相同 → 直接返回稳定值
        if (lane_result == lastStableResult) {
            pendingResult = lane_result
            pendingCount = 0
            return lastStableResult!!
        }

        // 和上次待确认值一样 → 计数 +1
        if (lane_result == pendingResult) {
            pendingCount++
            if (pendingCount >= stable_time) {
                lastStableResult = lane_result
                pendingResult = null
                pendingCount = 0
            }
        } else {
            // 新变化，重新开始计数
            pendingResult = lane_result
            pendingCount = 1
        }

        return lastStableResult!!
    }

}

