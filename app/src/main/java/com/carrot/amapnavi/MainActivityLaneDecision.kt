package com.carrot.amapnavi

import android.util.Log

/* =========================================================
 * 车道决策模块
 *   - 导航推荐车道 / 禁行车道决策
 *   - 超车变道决策
 * 说明：本文件中的函数均为 MainActivity 的扩展函数，
 *       依赖的相关状态定义在 NavRuntimeState.kt / MainActivity.kt 中。
 * ========================================================= */

fun MainActivity.isSameNavLane(a: NavLane, b: NavLane): Boolean {
    if (a.validCount != b.validCount) return false

    for (i in 0 until a.validCount) {
        if (a.laneStrArray[i] != b.laneStrArray[i]) {
            return false
        }
    }
    return true
}

fun MainActivity.isStopPyOvertake(
        _navLane: NavLane
){
    fun isBlocked(s: String): Boolean = s.contains("X")
    fun isSuggested(s: String): Boolean = s.contains("*")

    val lanes = _navLane.laneStrArray
    val count = _navLane.validCount
    var blocked_flag = false
    var suggested_flag = false

    for (i in 0 until count) {
        if (isBlocked(lanes[i])) {
            blocked_flag = true
            break
        }
    }

    for (i in 0 until count) {
        if (isSuggested(lanes[i])) {
            suggested_flag = true
            break
        }
    }

    if(blocked_flag || suggested_flag) {
        py_overtake_disable = true
        if(blocked_flag){
            py_overtake_disable_reason = "有禁止车道"
        }else if(suggested_flag){
            py_overtake_disable_reason = "有推荐车道"
        }

    }
}

fun MainActivity.haveNavDisLane(_navLane: NavLane): Boolean{
    fun isBlocked(s: String): Boolean = s.contains("X")

    val lanes = _navLane.laneStrArray
    val count = _navLane.validCount
    var blocked_flag = false

    for (i in 0 until count) {
        if (isBlocked(lanes[i])) {
            blocked_flag = true
            break
        }
    }

    return blocked_flag
}


// -----------------------------
// 车道决策函数
// -----------------------------
fun MainActivity.decideLaneWithAction(
        vLaneResult: LaneCalcResult,
        _navLane: NavLane,
        edgeType: LaneChangeAction,   // LEFT / RIGHT / KEEP
        highWay: Boolean,             // 高速公路
        roadMedian: Boolean           // 道路中间有隔离带
): Triple<Int, LaneChangeAction, Int> {

    fun isBlocked(s: String): Boolean = s.contains("X")
    fun isSuggested(s: String): Boolean = s.contains("*")

    val nowTime = nowTimeStr()
    val nav_lanes = _navLane.laneStrArray
    val nav_cnt = _navLane.validCount

    // 高速时最右侧一条车道是应急车道：预留，不作为变道/超车目标
    //   导航车道数组索引越大越靠右，故可用上界 = nav_cnt-1-预留数
    val reservedRightLaneCnt = if (highWay) 1 else 0
    val rightmostUsableIdx = nav_cnt - 1 - reservedRightLaneCnt

    //导航引导车道是否变化? 导航引导有变化?
    if(!isSameNavLane(_navLane, lastNavLane) || resetSuggestLaneTimer){
        //重置推荐车道计时和禁止推荐车道的标志
        inSuggestLaneTime = System.currentTimeMillis() //重置在推荐车道的计时
        if (disableSuggestLane) {
            disableSuggestLane = false

            if(suggest_lane_en) {
                val msg = "${nowTime} 导航车道或引导发行变化，恢复推荐车道变道功能"
                Log.w("LaneDecision", msg)
                showLaneDebug(msg)
            }
        }

        if (!navLaneChange) {
            //...
        }
        navLaneChange = true
        resetSuggestLaneTimer = false
    }else{
        navLaneChange = false
    }
    lastNavLane = _navLane

    // 导航未给车道引导数据 或者 推荐车道/禁行车道功能关闭+导航要求靠右行驶+设置了纯视觉靠右
    if (nav_cnt <= 0 || nav_lanes.isEmpty() || (!suggest_lane_en && !dis_lane_en && vision_keep_right && edgeType == LaneChangeAction.RIGHT)) {
        inSuggestLaneTime = System.currentTimeMillis()

        //导航要求靠右行驶+设置了纯视觉靠右
        if(vision_keep_right && edgeType == LaneChangeAction.RIGHT && vLaneResult.leftLaneId >= 1){
            val rightLaneId = vLaneResult.rightLaneId
            if(highWay){
                if(rightLaneId > 2){ //车辆不在最右车道(应急车道除外)
                    return Triple(-1, LaneChangeAction.RIGHT, 1)
                }
            }else{
                if(rightLaneId > 1){ //车辆不在最右车道
                    return Triple(-1, LaneChangeAction.RIGHT, 0)
                }
            }
        }

        //保持当前车道行驶
        return Triple(-1, LaneChangeAction.KEEP, -1)
    }

    //两侧车道线均不可靠，不进行车道领航
    if(opStateManager.state.prob && lane_prob_enable) {
        val lLineProb = opStateManager.state.l_line_prob ?: 0f
        val rLineProb = opStateManager.state.r_line_prob ?: 0f
        if (lLineProb < 0.5 && rLineProb < 0.5) {
            return Triple(-1, LaneChangeAction.KEEP, -1)
        }
    }

    //检查视觉车道数据是否有效
    if(vLaneResult.laneCnt == 0 || vLaneResult.leftLaneId == 0 || vLaneResult.rightLaneId == 0){
        return Triple(-1, LaneChangeAction.KEEP, -1)
    }

    //限制视觉车道编号不大于导航车道数量
    // ---------- leftLaneId（从1开始，从左往右数的车辆所在视觉车道）视觉车道整合导航车道 ----------
    val rawLeftLaneId = vLaneResult.leftLaneId
    val safeLeftLaneId = when {
        rawLeftLaneId < 1 -> 1
        rawLeftLaneId > nav_cnt -> nav_cnt
        else -> rawLeftLaneId
    }

    // ---------- rightLaneId（从1开始，从右往左数的车辆所在视觉车道）视觉车道整合导航车道 ----------
    val rawRightLaneId = vLaneResult.rightLaneId
    val safeRightLaneId = when {
        rawRightLaneId < 1 -> 1
        rawRightLaneId > nav_cnt -> nav_cnt
        else -> rawRightLaneId
    }

    // ---------- 计算车辆所在的导航车道currentIdx，即计算navLane.laneStrArray的数组索引号(索引0为最后导航车道) ----------
    var currentIdx = 0
    val leftVisibleLaneCnt = rawLeftLaneId - 1   // 实际看到的左侧车道数
    val rightVisibleLaneCnt = rawRightLaneId - 1 // 实际看到的右侧车道数

    if (roadMedian || highWay) { //路中有隔离带或者高速

        //-----------------特殊情况处理------------------
        if(dis_lane_en) { //启用了禁行车道
            //左侧无车道+右侧有一条车道
            if (leftVisibleLaneCnt == 0 && rightVisibleLaneCnt == 1) {
                //导航车道数量大于2
                if (nav_cnt > 2) {
                    //当前车道禁行+最右侧导航车道禁行，即导航|x|0|0|x|,|x|0|x|，视觉|c|x|
                    if (isBlocked(nav_lanes[0]) && isBlocked(nav_lanes[nav_cnt - 1])) {
                        //往右变道
                        return Triple(1, LaneChangeAction.RIGHT, 0)
                    }
                }
            }
            //右侧无车道+左侧有一条车道
            else if (leftVisibleLaneCnt == 1 && rightVisibleLaneCnt == 0) {
                //导航车道数量大于2
                if (nav_cnt > 2) {
                    //当前车道禁行+最左侧导航车道禁行，即导航|x|0|0|x|,|x|0|x|，视觉|x|c|
                    if (isBlocked(nav_lanes[0]) && isBlocked(nav_lanes[nav_cnt - 1])) {
                        //没有靠左行驶的请求，则保持当前车道行驶
                        if (edgeType != LaneChangeAction.LEFT) {
                            return Triple(-1, LaneChangeAction.KEEP, -1)
                        }
                    }
                }
            }
        }

        if(highWay || !road_right_first){ //高速 或者 非右侧边沿计算原则
            //-----------------特殊情况处理------------------
            //高速已在最右侧车道(不含应急车道)+导航指示该车道为禁行车道(为防止变道进应急车道，进行特殊处理)
            if(highWay && dis_lane_en){
                //左侧有大于等于1条视觉车道和右侧有1条视觉车道 + 导航车道大于等于2
                if (leftVisibleLaneCnt >= 1 && rightVisibleLaneCnt == 1 && nav_cnt >= 2) {
                    //当前车道禁行+右侧导航车道可以通行，即导航|...|x|0|，视觉|0|c|0|
                    if (isBlocked(nav_lanes[nav_cnt - 2]) && !isBlocked(nav_lanes[nav_cnt - 1])) {
                        //是否当前车道左侧全为禁行车道?
                        var all_left_blocked = true
                        for (i in 0 until (nav_cnt-2)) {
                            if (!isBlocked(nav_lanes[i])) {
                                all_left_blocked = false
                            }
                        }

                        //当前车道往左全是禁行车道+没有靠左行驶请求+没有立即变道请求
                        if (all_left_blocked && edgeType != LaneChangeAction.LEFT && opStateManager.state.doLaneForkNow != LaneChangeAction.RIGHT.value) {
                            return Triple(-1, LaneChangeAction.KEEP, -1)
                        }
                    }
                }
            }

            currentIdx =
                    if (leftVisibleLaneCnt < 2) {              // 左侧没到视觉上限 → 左可信
                        safeLeftLaneId - 1
                    } else if (rightVisibleLaneCnt < 2) {      // 右侧没到视觉上限 → 右可信
                        nav_cnt - safeRightLaneId
                    } else {                            // 两侧都可能被截断 → 固定用左
                        safeLeftLaneId - 1
                    }
        }else { //右侧边沿计算模式
            currentIdx =
                    if (rightVisibleLaneCnt < 2) {             // 右侧没到视觉上限 → 右可信
                        nav_cnt - safeRightLaneId
                    }else if (leftVisibleLaneCnt < 2) {        // 左侧没到视觉上限 → 左可信
                        safeLeftLaneId - 1
                    } else {                            // 两侧都可能被截断 → 右可信
                        nav_cnt - safeRightLaneId
                    }
        }
    } else { //公路
        currentIdx = nav_cnt - safeRightLaneId
    }
    currentIdx = currentIdx.coerceIn(0, nav_cnt - 1)

    //车辆是否已在推荐车道上？
    if (isSuggested(nav_lanes[currentIdx])) {
        //在推荐车道上的时间超过5秒后则临时禁用推荐车道功能（直到导航车道信息有变或者导航引导信息有变）
        if((System.currentTimeMillis() - inSuggestLaneTime) > 5000L){
            if(!disableSuggestLane) {
                disableSuggestLane = true

                if(suggest_lane_en) {
                    val msg = "${nowTime} 行驶在推荐车道已超过5秒，暂停推荐车道变道功能"
                    Log.w("LaneDecision", msg)
                    showLaneDebug(msg)
                }
            }
        }
        return Triple(-1, LaneChangeAction.KEEP, -1)
    }else{
        if(!disableSuggestLane) {
            inSuggestLaneTime = System.currentTimeMillis()
        }
    }

    //currentIdx为车辆所在的导航车道数组的索引
    var targetIdx = currentIdx

    // =====================================================
    // ① 靠边策略优先（LEFT / RIGHT）
    // =====================================================
    if (edgeType == LaneChangeAction.LEFT) {
        // 找最左侧可通行车道
        for (i in 0 until nav_cnt) {
            if (!isBlocked(nav_lanes[i])) {
                targetIdx = i
                break
            }
        }
    } else if (edgeType == LaneChangeAction.RIGHT) {
        // 找最右侧可通行车道(高速时最右侧应急车道不参与)
        for (i in rightmostUsableIdx downTo 0) {
            if (!isBlocked(nav_lanes[i])) {
                targetIdx = i
                break
            }
        }
    } else {
        // 优先找推荐车道（带有*号的）
        var starIdx = -1
        if(suggest_lane_en && !disableSuggestLane) { //是否允许走推荐车道
            val idx = nav_lanes.indexOfFirst { it.contains("*") && !isBlocked(it) }
            // 高速：推荐车道若落在预留的应急车道上则忽略
            if (idx in 0..rightmostUsableIdx) starIdx = idx
        }
        if (starIdx != -1) {
            targetIdx = starIdx
        } else {
            // 当前车道禁止 → 找最近可通行
            if(dis_lane_en) { //禁行车道功能开启
                if (isBlocked(nav_lanes[currentIdx])) {
                    for (offset in 1 until nav_cnt) {
                        val left = currentIdx - offset
                        val right = currentIdx + offset

                        if (left >= 0 && !isBlocked(nav_lanes[left])) {
                            targetIdx = left
                            break
                        }
                        if (right <= rightmostUsableIdx && !isBlocked(nav_lanes[right])) {
                            targetIdx = right
                            break
                        }
                    }
                }
            }else{//禁行车道功能未开启
                return Triple(-1, LaneChangeAction.KEEP, -1)
            }
        }
    }

    // 兜底保护
    if (isBlocked(nav_lanes[targetIdx])) {
        targetIdx = currentIdx
    }

    //计算目标车道在视觉车道的第几车道
    fun targetIdxToRightBased(targetIdx: Int, nav_cnt: Int): Int {
        if (nav_cnt <= 0) return 0
        return (nav_cnt - 1 - targetIdx).coerceIn(0, nav_cnt - 1)
    }
    val visualLaneId = targetIdxToRightBased(targetIdx, _navLane.validCount)

    // ---------- 计算变道方向 ----------
    val action = when {
        targetIdx < currentIdx -> LaneChangeAction.LEFT
        targetIdx > currentIdx -> LaneChangeAction.RIGHT
        else -> LaneChangeAction.KEEP
    }

    return Triple(targetIdx, action, visualLaneId)
}

// -----------------------------
// 超车变道决策函数
//   edgeType: 期望的超车方向(LEFT / RIGHT)
//   返回: Triple<目标导航车道索引, 实际动作(LEFT/RIGHT/KEEP), 目标视觉车道号>
//         返回动作与 edgeType 一致时表示该方向允许超车，否则为 KEEP(不允许)
// -----------------------------
fun MainActivity.decideOvertakeLane(
    vLaneResult: LaneCalcResult,
    _navLane: NavLane,
    edgeType: LaneChangeAction,   // 期望的超车方向 LEFT / RIGHT
    highWay: Boolean,             // 高速公路
    roadMedian: Boolean           // 道路中间有隔离带
): Triple<Int, LaneChangeAction, Int> {

    //计算目标车道在视觉车道的第几车道
    fun targetIdxToRightBased(targetIdx: Int, count: Int): Int {
        if (count <= 0) return 0
        return (count - 1 - targetIdx).coerceIn(0, count - 1)
    }

    if (edgeType != LaneChangeAction.LEFT && edgeType != LaneChangeAction.RIGHT) {
        return Triple(-1, LaneChangeAction.KEEP, -1)
    }

    //该方向在视觉车道上是否确实存在可行驶车道
    val sideLaneOk = if (edgeType == LaneChangeAction.LEFT) {
        //车辆不在最左侧
        vLaneResult.leftLaneId > 1
    } else {
        //高速最右侧为应急车道，rightLaneId > 2 才表示右侧存在可用的正常车道
        val needLaneId = if (highWay) 2 else 1
        vLaneResult.rightLaneId > needLaneId
    }
    if (!sideLaneOk) {
        return Triple(-1, LaneChangeAction.KEEP, -1)
    }

    val lanes = _navLane.laneStrArray
    val count = _navLane.validCount

    //导航未给车道引导数据 或者 推荐车道/禁行车道功能关闭 → 仅凭视觉车道允许变道
    if (count <= 0 || lanes.isEmpty() || (!suggest_lane_en && !dis_lane_en)) {
        return if (edgeType == LaneChangeAction.LEFT) {
            Triple(-1, LaneChangeAction.LEFT, (vLaneResult.leftLaneId - 1).coerceAtLeast(0))
        } else {
            Triple(-1, LaneChangeAction.RIGHT, 0)
        }
    }

    // ---------- leftLaneId（从1开始，从左往右数的车辆所在视觉车道）视觉车道整合导航车道 ----------
    val rawLeftLaneId = vLaneResult.leftLaneId
    val safeLeftLaneId = when {
        rawLeftLaneId < 1 -> 1
        rawLeftLaneId > count -> count
        else -> rawLeftLaneId
    }

    // ---------- rightLaneId（从1开始，从右往左数的车辆所在视觉车道）视觉车道整合导航车道 ----------
    val rawRightLaneId = vLaneResult.rightLaneId
    val safeRightLaneId = when {
        rawRightLaneId < 1 -> 1
        rawRightLaneId > count -> count
        else -> rawRightLaneId
    }

    // ---------- 计算车辆所在的导航车道currentIdx，即计算navLane.laneStrArray的数组索引号(索引0为最后导航车道) ----------
    var currentIdx = 0
    val leftVisibleLaneCnt = rawLeftLaneId - 1   // 实际看到的左侧车道数
    val rightVisibleLaneCnt = rawRightLaneId - 1 // 实际看到的右侧车道数

    if (roadMedian || highWay) { //路中有隔离带或者高速
        if(highWay || !road_right_first){ //高速 或者 非右侧边沿计算原则
            currentIdx =
                if (leftVisibleLaneCnt < 2) {              // 左侧没到视觉上限 → 左可信
                    safeLeftLaneId - 1
                } else if (rightVisibleLaneCnt < 2) {      // 右侧没到视觉上限 → 右可信
                    count - safeRightLaneId
                } else {                            // 两侧都可能被截断 → 固定用左
                    safeLeftLaneId - 1
                }
        }else { //右侧边沿计算模式
            currentIdx =
                if (rightVisibleLaneCnt < 2) {             // 右侧没到视觉上限 → 右可信
                    count - safeRightLaneId
                }else if (leftVisibleLaneCnt < 2) {        // 左侧没到视觉上限 → 左可信
                    safeLeftLaneId - 1
                } else {                            // 两侧都可能被截断 → 右可信
                    count - safeRightLaneId
                }
        }
    } else { //公路
        currentIdx = count - safeRightLaneId
    }
    currentIdx = currentIdx.coerceIn(0, count - 1)

    fun isBlocked(s: String): Boolean = s.contains("X")

    // ---------- 校验目标导航车道 ----------
    // 高速时最右侧一条车道是应急车道：不允许超车变道过去
    val rightmostUsableIdx = count - 1 - if (highWay) 1 else 0

    val targetIdx: Int
    if (edgeType == LaneChangeAction.LEFT) {
        if (currentIdx <= 0) {
            return Triple(-1, LaneChangeAction.KEEP, -1)  // 已在最左侧导航车道
        }
        targetIdx = currentIdx - 1
    } else {
        if (currentIdx >= rightmostUsableIdx) {
            // 已在最右侧可用车道(高速时最右一条为应急车道，不再往右超车)
            return Triple(-1, LaneChangeAction.KEEP, -1)
        }
        targetIdx = currentIdx + 1
    }

    //目标车道为禁行车道，不允许超车
    if (dis_lane_en && isBlocked(lanes[targetIdx])) {
        return Triple(-1, LaneChangeAction.KEEP, -1)
    }

    val visualLaneId = targetIdxToRightBased(targetIdx, count)
    return Triple(targetIdx, edgeType, visualLaneId)
}


// -----------------------------
// 传感器探测能力限制(重要)
//   毫米波雷达：前向 + 左右前角，最大探测约100m
//   激光雷达：左右前后角，最大探测约40~50m(50m左右时有时可探测到) —— 侧后方只有激光雷达可用
//   传感器数据不做噪声/可信度二次过滤，只要给出数据即视为有效；
//   "未探测到目标"只代表量程内无车，量程之外属于未知区域。
// -----------------------------
private const val OVERTAKE_RADAR_MAX_RANGE = 100f     // 毫米波雷达最大探测距离(m)
private const val OVERTAKE_LIDAR_MAX_RANGE = 50f      // 激光雷达最大探测距离(m)，40m稳定/50m左右有时可达

// 反向变道冷却：刚往一侧超完车后，这段时间内不允许往反方向再超车变道。
// 目的：侧向"没有数据"被当成"没有车"时，会出现"变过去发现前车慢 → 又变回来"的来回变道；
//       有了这个冷却，回程方向在该窗口内直接判不安全，必须等冷却结束才可能再变。
private const val OVERTAKE_REVERSE_COOLDOWN_MS = 20_000L

// -----------------------------
// 超车模式参数集：1=激进 2=常规 3=保守
//   overtake_mode 由设置界面写入(SettingActivity: rbOvertakeFast/Normal/SlowMode)
//   triggerMaxDist 不得超过毫米波雷达量程 OVERTAKE_RADAR_MAX_RANGE
//   sideFrontMinDist / rearMinDist 应小于激光雷达量程 OVERTAKE_LIDAR_MAX_RANGE
// -----------------------------
private val OVERTAKE_PARAMS_AGGRESSIVE = OvertakeParams(
    mode = 1, modeName = "激进",
    triggerMaxDist = 100f, triggerMinDist = 8f,
    vrelThreshold = -2, timeGapMax = 2.5f, cruiseRatio = 0.95f,
    leadMinSpeedHighWay = 25, leadMinSpeedRoad = 15,
    // 原 400ms：前车一插入(cut-in)的瞬态才刚产生就被当成"前车慢"执行变道
    stableTime = 1200L,
    cutInSuppressMs = 1500L,
    minLaneWidth = 2.0f, sideMinScore = 45, sideVrelThreshold = 5,
    sideSafetyMargin = 3f, rearMinDist = 8f, sideFrontMinDist = 15f,
    sideUnknownPenalty = 15,
    allowRightOvertake = true, preferLeftFirst = false,
    cooldownSuccess = 6000L, cooldownFailed = 3000L
)

private val OVERTAKE_PARAMS_NORMAL = OvertakeParams(
    mode = 2, modeName = "常规",
    triggerMaxDist = 80f, triggerMinDist = 12f,
    vrelThreshold = -3, timeGapMax = 2.5f, cruiseRatio = 0.90f,
    leadMinSpeedHighWay = 35, leadMinSpeedRoad = 20,
    stableTime = 2000L,
    cutInSuppressMs = 2500L,
    minLaneWidth = 2.3f, sideMinScore = 60, sideVrelThreshold = 10,
    sideSafetyMargin = 5f, rearMinDist = 12f, sideFrontMinDist = 20f,
    sideUnknownPenalty = 25,
    allowRightOvertake = true, preferLeftFirst = true,
    cooldownSuccess = 10000L, cooldownFailed = 4000L
)

private val OVERTAKE_PARAMS_CONSERVATIVE = OvertakeParams(
    mode = 3, modeName = "保守",
    triggerMaxDist = 60f, triggerMinDist = 20f,
    vrelThreshold = -8, timeGapMax = 2.0f, cruiseRatio = 0.80f,
    leadMinSpeedHighWay = 45, leadMinSpeedRoad = 30,
    stableTime = 3000L,
    cutInSuppressMs = 3500L,
    minLaneWidth = 2.6f, sideMinScore = 75, sideVrelThreshold = 15,
    sideSafetyMargin = 8f, rearMinDist = 20f, sideFrontMinDist = 25f,
    sideUnknownPenalty = 35,
    allowRightOvertake = false, preferLeftFirst = true,
    cooldownSuccess = 15000L, cooldownFailed = 6000L
)

// 当前超车模式对应的参数
fun MainActivity.currentOvertakeParams(): OvertakeParams = when (overtake_mode) {
    1 -> OVERTAKE_PARAMS_AGGRESSIVE      // 激进
    3 -> OVERTAKE_PARAMS_CONSERVATIVE    // 保守
    else -> OVERTAKE_PARAMS_NORMAL       // 常规
}


// -----------------------------
// 超车触发条件评估(前车慢/跟车近/未达巡航 → 有超车需求)
//   返回: Pair<是否满足触发条件, 原因说明>
// -----------------------------
fun MainActivity.evaluateOvertakeTrigger(): Pair<Boolean, String> {
    val s = opStateManager.state
    val p = currentOvertakeParams()
    val vEgo = s.vEgo ?: 0
    val vCruise = s.vCruise ?: 0

    //前方必须有车且距离有效
    if (!s.lead1) return false to "前方无车"
    val drel = s.drel
    if (drel == null || drel <= 0) return false to "无有效前车距离"

    //前车过慢(疑似拥堵)不超车
    val leadMinSpeed = if (bHighWay) p.leadMinSpeedHighWay else p.leadMinSpeedRoad
    val vlead = s.vlead
    if (vlead != null && vlead < leadMinSpeed) {
        return false to "前车速度${vlead}km/h低于${leadMinSpeed}km/h疑似拥堵"
    }

    //距离过远暂不超车，过近先拉开距离
    if (drel.toFloat() > p.triggerMaxDist) {
        return false to "前车距离${drel}m过远(>${p.triggerMaxDist.toInt()}m)"
    }
    if (drel.toFloat() < p.triggerMinDist) {
        return false to "前车距离${drel}m过近等待拉开(<${p.triggerMinDist.toInt()}m)"
    }

    // 是否被前车压速：必须有至少一条"前车确实比我慢"的证据。
    //   注意"跟车时距"只作为辅助说明，不能单独构成超车理由：
    //   正常跟车时时距本来就可能很小，单独用它触发会变成"只要跟得近就超车"
    //   (实车表现为前车稍近一点就不停地试图变道)。
    val slowReasons = mutableListOf<String>()
    val vrel = s.vrel
    if (vrel != null && vrel < p.vrelThreshold) {
        slowReasons.add("前车较慢${vrel}km/h")
    }
    // 以"前车绝对速度低于巡航"为主要判据(vlead 在上面已取出)：
    //   cut-in 刚发生时本车还没开始减速，vEgo 仍接近巡航，只看 vEgo 会漏判；
    //   看 vlead 才能反映"前车是不是真的比我慢"。
    if (vCruise > 0 && vlead != null && vlead < vCruise * p.cruiseRatio) {
        slowReasons.add("前车${vlead}km/h低于巡航${(vCruise * p.cruiseRatio).toInt()}km/h")
    } else if (vCruise > 0 && vEgo < vCruise * p.cruiseRatio) {
        slowReasons.add("速度比${vEgo * 100 / vCruise}%") // vlead 缺失时退回用本车速度
    }
    if (slowReasons.isEmpty()) {
        return false to "前车不慢(跟车时距不作为独立触发条件)"
    }

    val reasons = slowReasons.toMutableList()
    val timeGap = if (vEgo > 0) drel / (vEgo / 3.6f) else 0f
    if (timeGap > 0f && timeGap <= p.timeGapMax) {
        reasons.add("跟车时距${"%.1f".format(timeGap)}s")
    }

    return true to reasons.joinToString(",")
}


// -----------------------------
// 前车 cut-in(插队)检测
// -----------------------------
// 现象：有车并入本车道时 drel 会突然大幅变小、vrel 瞬间变得很负。
//       原逻辑把这种瞬态直接当成"前车慢"，稳定时间一到就发变道命令，
//       用户反馈的"前车一插入就立即变道"就是这个原因(激进模式 stableTime 仅 400ms)。
// 处理：识别到插队后进入抑制窗口，窗口内不触发超车，并清零触发稳定计时。
private const val CUT_IN_DROP_MIN_M = 8       // 单周期内前车距离突降阈值(m)
private const val CUT_IN_DROP_RATIO = 0.25f   // 且降幅不低于原距离的该比例

fun MainActivity.updateCutInDetection() {
    val s = opStateManager.state
    val now = System.currentTimeMillis()
    val drel = if (s.lead1) s.drel?.takeIf { it > 0 } else null
    val prev = prevLeadDrel

    var reason = ""
    var strong = true
    if (drel != null) {
        if (!prevLeadPresent) {
            // 前车从无到有：多数是插入，但也可能是雷达刚重新捕获，抑制窗口取一半
            reason = "前车突然出现${drel}m"
            strong = false
        } else if (prev != null) {
            val drop = prev - drel
            if (drop >= CUT_IN_DROP_MIN_M && drop >= prev * CUT_IN_DROP_RATIO) {
                reason = "前车距离突降${prev}m→${drel}m"
            }
        }
    }
    prevLeadDrel = drel
    prevLeadPresent = drel != null

    if (reason.isEmpty()) return

    val p = currentOvertakeParams()
    val window = if (strong) p.cutInSuppressMs else p.cutInSuppressMs / 2
    cutInSuppressUntil = now + window
    lastCutInTime = now
    cutInReason = reason
    overtakeTriggerStableTime = 0L   // 触发计时重新开始，避免沿用插队前的累计时间

    val msg = "${nowTimeStr()} 检测到前车插入($reason)，${window / 1000}s 内不触发超车"
    Log.w("Overtake", msg)
    showLaneDebug(msg)
}

// 是否处于 cut-in 抑制窗口内
fun MainActivity.isCutInSuppressing(): Boolean =
        System.currentTimeMillis() < cutInSuppressUntil

// -----------------------------
// 单侧车道超车评估(综合毫米波雷达 + 激光雷达侧向数据)
// -----------------------------
fun MainActivity.evaluateOvertakeSide(side: LaneChangeAction): OvertakeSideEval {
    val s = opStateManager.state
    val p = currentOvertakeParams()
    val sideStr = if (side == LaneChangeAction.LEFT) "左" else "右"

    //0) 反向变道冷却：刚往一侧超完车，窗口内不允许往反方向再超车变道
    //   （避免"侧向无数据=无车"导致的"变过去又变回来"来回变道）
    if (overtakeLastDirection != LaneChangeAction.KEEP && overtakeLastDirection != side) {
        val passed = System.currentTimeMillis() - overtakeLastDirectionTime
        if (passed < overtake_reverse_cooldown_ms) {
            return OvertakeSideEval(side, false, 0,
                "${sideStr}侧反向冷却中(剩${(overtake_reverse_cooldown_ms - passed) / 1000}s)")
        }
    }

    //1) 目标方向是否存在可行驶车道
    if (side == LaneChangeAction.LEFT) {
        if (vLaneResult.leftLaneId <= 1 && vLaneResult.leftLaneCnt <= 0) {
            return OvertakeSideEval(side, false, 0, "${sideStr}侧无车道")
        }
    } else {
        val needLaneId = if (bHighWay) 2 else 1
        if (vLaneResult.rightLaneId <= needLaneId && vLaneResult.rightLaneCnt <= 0) {
            return OvertakeSideEval(side, false, 0, "${sideStr}侧无可用车道")
        }
    }

    //2) 盲区/实线安全门(原车毫米波盲区 + 激光雷达盲区 + 车道线类型)
    val safeRet = LaneChangeSafetyCheck(side)
    if (safeRet < 0) {
        val r = when (safeRet) {
            -1 -> "${sideStr}侧实线阻挡"
            LANE_BLOCK_UNKNOWN -> "${sideStr}侧车道线判据不可用"
            -2 -> "${sideStr}前盲区有车"
            -4 -> "${sideStr}后盲区有车"
            -8 -> "${sideStr}激光雷达盲区有车"
            else -> "${sideStr}侧盲区不安全"
        }
        return OvertakeSideEval(side, false, 0, r)
    }

    //3) 目标车道宽度
    val laneWidth = if (side == LaneChangeAction.LEFT) s.l_lane_width else s.r_lane_width
    if (laneWidth != null && laneWidth > 0f && laneWidth < p.minLaneWidth) {
        return OvertakeSideEval(side, false, 0, "${sideStr}侧车道过窄${"%.1f".format(laneWidth)}m")
    }

    //4) 侧向车辆距离/速度评估
    //   注意：毫米波雷达(左右前角)约100m量程，激光雷达(左右前后角)约40~50m量程；
    //        侧后方只有激光雷达可用，"未探测到"仅代表量程内无车。
    var score = 100
    val reasons = mutableListOf<String>()

    val frontDist = currentLeadDistance()          // 本车道前车距离(m)
    val sideFrontDist = sideFrontDistance(side)    // 目标车道前方最近车辆距离(m)
    val sideRearDist = sideRearDistance(side)      // 目标车道后方最近车辆距离(m)
    val sideVrel = sideFrontVrel(side)             // 目标车道前方车辆相对速度(km/h)

    //4.1 该侧激光雷达必须在线，否则侧后方没有数据来源，无法确认安全
    if (!sideSensorOk(side)) {
        return OvertakeSideEval(side, false, 0, "${sideStr}侧激光雷达不在线，后方无法确认")
    }

    //4.2 侧后方净空：所需距离随车速增大；若所需净空超出激光雷达量程，则量程内已无法确认安全
    val needRearDist = overtakeRequiredRearDist()
    if (needRearDist > OVERTAKE_LIDAR_MAX_RANGE) {
        return OvertakeSideEval(side, false, 0,
            "${sideStr}侧车速${s.vEgo ?: 0}km/h所需后方净空${"%.1f".format(needRearDist)}m超出激光雷达量程")
    }
    if (sideRearDist != null && sideRearDist < needRearDist) {
        return OvertakeSideEval(side, false, 0,
            "${sideStr}侧后方车辆过近${"%.1f".format(sideRearDist)}m(<${"%.1f".format(needRearDist)}m)")
    }
    if (sideRearDist == null) {
        reasons.add("侧后方无车(激光${OVERTAKE_LIDAR_MAX_RANGE.toInt()}m内)")
    } else {
        reasons.add("侧后车${"%.1f".format(sideRearDist)}m")
    }

    //4.3 侧前方车辆：毫米波雷达约100m量程内是否有车、是否比本车道前车更近
    if (sideFrontDist != null) {
        if (frontDist != null && sideFrontDist < frontDist - p.sideSafetyMargin) {
            //目标车道前车比本车道前车更近，变过去没有意义且风险更高
            score -= 50
            reasons.add("侧前车${sideFrontDist.toInt()}m比前车${frontDist.toInt()}m更近")
        } else {
            reasons.add("侧前车${sideFrontDist.toInt()}m")
        }
        if (sideFrontDist < p.sideFrontMinDist) {
            score -= ((p.sideFrontMinDist - sideFrontDist) * 2f).toInt()
            reasons.add("侧前车过近(${sideFrontDist.toInt()}m<${p.sideFrontMinDist.toInt()}m)")
        }
    } else {
        // 注意：没有数据只代表"量程内没有"，量程之外属于未知，不能当成"确定无车"。
        // 原来这里只记原因不扣分(等于按"畅通"处理)，现在按模式扣分，
        // 两侧都无数据时评分会低于门限，不再因为"没看到车"就变过去。
        score -= p.sideUnknownPenalty
        reasons.add("侧前方无数据(超量程未知)-${p.sideUnknownPenalty}")
    }

    if (sideVrel != null && sideVrel < -p.sideVrelThreshold) {
        score -= 20
        reasons.add("侧前车较慢${sideVrel}km/h")
    }

    score = score.coerceIn(0, 100)
    val safe = score >= p.sideMinScore
    val reason = "${sideStr}侧" + (if (reasons.isEmpty()) "畅通" else reasons.joinToString(",")) + "评分$score"
    return OvertakeSideEval(side, safe, score, reason)
}


// 本车道前车距离(m)，无有效前车返回 null
fun MainActivity.currentLeadDistance(): Float? {
    val s = opStateManager.state
    val d = s.drel
    return if (s.lead1 && d != null && d > 0) d.toFloat() else null
}

// 该侧激光雷达是否在线(侧后方只有激光雷达可用，离线则后方不可信)
fun MainActivity.sideSensorOk(side: LaneChangeAction): Boolean {
    val s = opStateManager.state
    return if (side == LaneChangeAction.LEFT) s.lidar_l else s.lidar_r
}

// 当前车速下侧后方需要的最小净空(m)：取模式参数与约0.8s时距的较大值
//   车速越高所需净空越大，一旦超过激光雷达量程就不允许超车
fun MainActivity.overtakeRequiredRearDist(): Float {
    val s = opStateManager.state
    val p = currentOvertakeParams()
    val vEgo = (s.vEgo ?: 0).toFloat()
    return maxOf(p.rearMinDist, vEgo / 3.6f * 0.8f)
}

// 目标车道前方最近车辆距离(m) —— 毫米波雷达(前角) + 激光雷达(前角)取更近者
//   传感器只要给出数据即视为有效，不做额外噪声/量程过滤
fun MainActivity.sideFrontDistance(side: LaneChangeAction): Float? {
    val s = opStateManager.state
    return if (side == LaneChangeAction.LEFT) {
        val radar = if (s.l_lead) s.l_drel?.takeIf { it > 0 }?.toFloat() else null   // m
        val lidar = s.lf_drel?.takeIf { it > 0 }?.let { it / 1000f }                 // mm → m
        listOfNotNull(radar, lidar).minOrNull()
    } else {
        val radar = if (s.r_lead) s.r_drel?.takeIf { it > 0 }?.toFloat() else null   // m
        val lidar = s.rf_drel?.takeIf { it > 0 }?.let { it / 1000f }                 // mm → m
        listOfNotNull(radar, lidar).minOrNull()
    }
}

// 目标车道后方最近车辆距离(m) —— 仅激光雷达左右后角(约40~50m量程，drel<0 表示在后方)
fun MainActivity.sideRearDistance(side: LaneChangeAction): Float? {
    val s = opStateManager.state
    return if (side == LaneChangeAction.LEFT) {
        s.lb_drel?.takeIf { it < 0 }?.let { -it / 1000f }
    } else {
        s.rb_drel?.takeIf { it < 0 }?.let { -it / 1000f }
    }
}

// 目标车道前方车辆相对速度(km/h)
fun MainActivity.sideFrontVrel(side: LaneChangeAction): Int? {
    val s = opStateManager.state
    return if (side == LaneChangeAction.LEFT) {
        s.l_vrel ?: s.lf_vrel
    } else {
        s.r_vrel ?: s.rf_vrel
    }
}


// -----------------------------
// 超车诊断面板(测试用)
//   把一次决策的输入、所处阶段、两侧评估、最终结论拼成一段文本，
//   显示在主界面面板上，方便测试时直接查看/复制反馈。
//   仅读取状态并重复调用纯评估函数，不产生任何副作用。
// -----------------------------
private fun boolStr(b: Boolean): String = if (b) "✓" else "✗"

fun MainActivity.buildOvertakeDebugText(stage: String, decision: String, reason: String): String {
    val s = opStateManager.state
    val p = currentOvertakeParams()
    val now = System.currentTimeMillis()
    val vEgo = s.vEgo ?: 0
    val vCruise = s.vCruise ?: 0
    val minSpeed = if (bHighWay) 60 else 30
    val cdLeft = (overtakeCooldownUntil - now).coerceAtLeast(0L)
    val stable = if (overtakeTriggerStableTime == 0L) 0L else now - overtakeTriggerStableTime
    val drel = s.drel
    val timeGap = if (vEgo > 0 && drel != null) drel.toFloat() / (vEgo / 3.6f) else 0f

    val sb = StringBuilder()
    sb.append("【超车诊断】").append(stage).append(" → ").append(decision).append('\n')

    // 第1行：模式 / 道路 / 开关 / 领航 / 传感器 / 定速
    sb.append("模式:").append(p.modeName).append('(').append(p.mode).append(')')
        .append(" 道路:").append(if (bHighWay) "高速" else "公路")
        .append(" 开关:").append(boolStr(if (bHighWay) app_overtake else app_road_overtake))
        .append(" 领航:").append(boolStr(overtakeDebugNaviFlag))
        .append(" 雷达L:").append(boolStr(s.lidar_l)).append(" 雷达R:").append(boolStr(s.lidar_r))
        .append(" 定速:").append(boolStr(s.engaged))
        .append('\n')

    // 第2行：车速 / 巡航 / 冷却 / 变道状态机
    sb.append("车速:").append(vEgo).append('/').append(minSpeed)
        .append(" 巡航:").append(vCruise)
        .append(" 冷却:").append(cdLeft).append("ms")
        .append(" 状态:cmd=").append(boolStr(overtakeCmdSent))
        .append(" pause=").append(boolStr(overtakeActionPause))
        .append(" prog=").append(boolStr(overtakeInProgress))
        .append(" atc=").append(s.atc_state ?: -1)
        .append(" 稳定:").append(stable).append('/').append(p.stableTime).append("ms")
        .append('\n')

    // 第3行：触发条件(前车距离/相对速度/时距)
    val (trigger, triggerReason) = evaluateOvertakeTrigger()
    sb.append("触发:").append(boolStr(trigger))
        .append(" 前车:").append(boolStr(s.lead1))
        .append(" drel=").append(drel ?: "-")
        .append(" vrel=").append(s.vrel ?: "-")
        .append(" vlead=").append(s.vlead ?: "-")
        .append(" 时距=").append(if (timeGap > 0f) "%.1fs".format(timeGap) else "-")
        .append(" → ").append(triggerReason)
        .append('\n')

    // 第4/5行：左右两侧原始数据 + 评估结论
    sb.append(sideDebugLine(LaneChangeAction.LEFT)).append('\n')
    sb.append(sideDebugLine(LaneChangeAction.RIGHT)).append('\n')

    // 第6行：最终结论
    sb.append("结论:").append(decision).append(" —— ").append(reason)
    return sb.toString()
}

// 单侧诊断行：即使评估提前返回，也把该侧原始传感器数据一并列出
private fun MainActivity.sideDebugLine(side: LaneChangeAction): String {
    val s = opStateManager.state
    val sideStr = if (side == LaneChangeAction.LEFT) "左" else "右"
    val eval = evaluateOvertakeSide(side)
    val laneWidth = if (side == LaneChangeAction.LEFT) s.l_lane_width else s.r_lane_width
    val front = sideFrontDistance(side)
    val rear = sideRearDistance(side)
    val vrel = sideFrontVrel(side)
    val laneCnt = if (side == LaneChangeAction.LEFT) vLaneResult.leftLaneCnt else vLaneResult.rightLaneCnt
    val laneId = if (side == LaneChangeAction.LEFT) vLaneResult.leftLaneId else vLaneResult.rightLaneId

    return sideStr + ": 车道" + laneCnt + "(id" + laneId + ")" +
        " 雷达" + boolStr(sideSensorOk(side)) +
        " 宽" + (laneWidth?.let { "%.1f".format(it) } ?: "-") +
        " 盲区" + LaneChangeSafetyCheck(side) +
        " 侧前" + (front?.let { "%.0f".format(it) } ?: "-") +
        " 侧后" + (rear?.let { "%.0f".format(it) } ?: "-") +
        " 侧前vr" + (vrel ?: "-") +
        " 需净空" + "%.0f".format(overtakeRequiredRearDist()) +
        " 评分" + eval.score + (if (eval.safe) " 安全" else " 不安全") +
        " | " + eval.reason
}

// 决策阶段上报：记录最近一次阶段/结论/原因，并同步一条Logcat(tag: OvertakeDbg)
//   诊断页内容由定时刷新统一渲染(见 MainActivityUi.renderOvertakeDebugPage)
fun MainActivity.logOvertakeStage(stage: String, decision: String, reason: String) {
    overtakeDebugStage = stage
    overtakeDebugDecision = decision
    overtakeDebugReason = reason

    // 超车专用日志(独立文件，不受诊断页显示/主日志开关影响)
    OvertakeLogger.onDecision(stage, decision, reason) { overtakeSnapshotLine() }

    // 全量逐帧记录(调试后门开启时)：按设定间隔把每帧现场打到 logcat(OvtFrame) 和日志文件
    if (overtakeFullFrameLog) {
        val nowMs = System.currentTimeMillis()
        if (nowMs - overtakeFullFrameLastTime >= overtakeFullFrameIntervalMs) {
            overtakeFullFrameLastTime = nowMs
            val frame = overtakeSnapshotLine()
            Log.d("OvtFrame", frame)
            OvertakeLogger.onFrame(frame)
        }
    }

    if (!overtakeDebugEnable) return
    Log.d("OvertakeDbg", "$stage → $decision : $reason")
    // 注意：这里不要直接整页重绘。决策是每个控制周期都跑的，在决策里重绘会把
    // 阶段/结论/数值以决策频率反复刷到界面上（视觉上就是整块面板一直在闪）。
    // 面板显示期间由 startOvertakeDebugRefresh() 的定时刷新统一渲染(200ms)。
}

// -----------------------------
// 超车日志快照行(单行紧凑格式，供 OvertakeLogger 落盘)
//   把一帧决策的全部输入量压成一行，导出后可逐帧比对差异；
//   仅读状态 + 调用纯评估函数，无任何副作用。
// -----------------------------
fun MainActivity.overtakeSnapshotLine(): String {
    val s = opStateManager.state
    val p = currentOvertakeParams()
    val now = System.currentTimeMillis()
    val vEgo = s.vEgo ?: 0
    val drel = s.drel
    val b: (Boolean) -> String = { if (it) "1" else "0" }
    fun f1(v: Float?): String = if (v == null) "-" else "%.1f".format(v)
    fun mm(v: Int?): String = if (v == null) "-" else "%.2f".format(v / 1000f)

    val timeGap = if (vEgo > 0 && drel != null) "%.1fs".format(drel / (vEgo / 3.6f)) else "-"
    val cdLeft = (overtakeCooldownUntil - now).coerceAtLeast(0L)
    val stable = if (overtakeTriggerStableTime == 0L) 0L else now - overtakeTriggerStableTime
    val (trigger, triggerReason) = evaluateOvertakeTrigger()
    val leftEval = evaluateOvertakeSide(LaneChangeAction.LEFT)
    val rightEval = evaluateOvertakeSide(LaneChangeAction.RIGHT)

    return buildString {
        // 基础：车速/门限/模式/道路/开关/领航/定速/数据新鲜度
        append("v=").append(vEgo).append('/').append(if (bHighWay) 60 else 30)
        append(" cruise=").append(s.vCruise ?: -1)
        append(" mode=").append(p.modeName).append(p.mode)
        append(" road=").append(if (bHighWay) "HW" else "RD")
        append(" sw=").append(b(if (bHighWay) app_overtake else app_road_overtake))
        append(" navi=").append(b(overtakeDebugNaviFlag))
        append(" eng=").append(b(s.engaged))
        append(" atc=").append(s.atc_state ?: -1)
        append(" dataAge=").append(if (s.data_time > 0) "${now - s.data_time}ms" else "-")

        // 本车道前车
        append(" | lead=").append(b(s.lead1))
        append(" drel=").append(drel ?: -1)
        append(" vlead=").append(s.vlead ?: -1)
        append(" vrel=").append(s.vrel ?: -1)
        append(" gap=").append(timeGap)

        // 侧向传感器原始值：毫米波前角(m) + 激光雷达四角(距离,横距 m / 相对速度)
        append(" | LfRadar=").append(if (s.l_lead) s.l_drel ?: -1 else "-")
        append(" LfLidar=").append(mm(s.lf_drel)).append(',').append(mm(s.lf_xrel)).append(" vr=").append(s.lf_vrel ?: -1)
        append(" RfRadar=").append(if (s.r_lead) s.r_drel ?: -1 else "-")
        append(" RfLidar=").append(mm(s.rf_drel)).append(',').append(mm(s.rf_xrel)).append(" vr=").append(s.rf_vrel ?: -1)
        append(" LbLidar=").append(mm(s.lb_drel)).append(',').append(mm(s.lb_xrel)).append(" vr=").append(s.lb_vrel ?: -1)
        append(" RbLidar=").append(mm(s.rb_drel)).append(',').append(mm(s.rb_xrel)).append(" vr=").append(s.rb_vrel ?: -1)

        // 盲区 / 传感器在线
        append(" | blindF=").append(b(s.l_front_blind)).append('/').append(b(s.r_front_blind))
        append(" lidarBlind=").append(b(s.lidar_lblind)).append('/').append(b(s.lidar_rblind))
        append(" bp=").append(b(s.left_blindspot)).append('/').append(b(s.right_blindspot))
        append(" onlineLR=").append(b(s.lidar_l)).append('/').append(b(s.lidar_r))

        // 车道
        append(" | lw=").append(f1(s.l_lane_width)).append('/').append(f1(s.r_lane_width))
        append(" laneL=").append(vLaneResult.leftLaneCnt).append("(id").append(vLaneResult.leftLaneId).append(')')
        append(" laneR=").append(vLaneResult.rightLaneCnt).append("(id").append(vLaneResult.rightLaneId).append(')')

        // 变道状态机 / 冷却 / 触发稳定
        append(" | cmd=").append(b(overtakeCmdSent))
        append(" pause=").append(b(overtakeActionPause))
        append(" prog=").append(b(overtakeInProgress))
        append(" cd=").append(cdLeft)
        append(" cutIn=").append((cutInSuppressUntil - now).coerceAtLeast(0L))
        append(" stable=").append(stable).append('/').append(p.stableTime)
        append(" lastDir=").append(overtakeLastDirection)

        // 评估结论
        append(" | trg=").append(b(trigger)).append('(').append(triggerReason).append(')')
        append(" needRear=").append("%.1f".format(overtakeRequiredRearDist()))
        append(" L=").append(leftEval.score).append('/').append(b(leftEval.safe)).append('(').append(leftEval.reason).append(')')
        append(" R=").append(rightEval.score).append('/').append(b(rightEval.safe)).append('(').append(rightEval.reason).append(')')
    }
}


