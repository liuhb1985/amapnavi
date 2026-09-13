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
        // 找最右侧可通行车道
        for (i in nav_cnt - 1 downTo 0) {
            if (!isBlocked(nav_lanes[i])) {
                targetIdx = i
                break
            }
        }
    } else {
        // 优先找推荐车道（带有*号的）
        var starIdx = -1
        if(suggest_lane_en && !disableSuggestLane) { //是否允许走推荐车道
            starIdx = nav_lanes.indexOfFirst { it.contains("*") && !isBlocked(it) }
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
                        if (right < nav_cnt && !isBlocked(nav_lanes[right])) {
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
// -----------------------------
fun MainActivity.decideOvertakeLane(
    vLaneResult: LaneCalcResult,
    _navLane: NavLane,
    edgeType: LaneChangeAction,   // LEFT / RIGHT / KEEP
    highWay: Boolean,             // 高速公路
    roadMedian: Boolean           // 道路中间有隔离带
): Triple<Int, LaneChangeAction, Int> {

    //计算目标车道在视觉车道的第几车道
    fun targetIdxToRightBased(targetIdx: Int, count: Int): Int {
        if (count <= 0) return 0
        return (count - 1 - targetIdx).coerceIn(0, count - 1)
    }

    val nowTime = nowTimeStr()
    val lanes = _navLane.laneStrArray
    val count = _navLane.validCount

    //导航引导车道是否变化? 导航引导有变化?
    if(!isSameNavLane(_navLane, lastOvertakeNavLane) || resetOvertakeSuggestLaneTimer){
        //重置推荐车道计时和禁止推荐车道的标志
        inOvertakeSuggestLaneTime = System.currentTimeMillis() //重置在推荐车道的计时
        if (disableOvertakeSuggestLane) {
            disableOvertakeSuggestLane = false

            if(suggest_lane_en) {
                val msg = "${nowTime} 导航车道或引导发行变化，恢复推荐车道变道功能"
                Log.w("LaneDecision", msg)
                showLaneDebug(msg)
            }
        }

        if (!overtakeNavLaneChange) {
            //...
        }
        overtakeNavLaneChange = true
        resetOvertakeSuggestLaneTimer = false
    }else{
        overtakeNavLaneChange = false
    }
    lastOvertakeNavLane = _navLane

    // 导航未给车道引导数据 或者 推荐车道/禁行车道功能关闭
    if (count <= 0 || lanes.isEmpty() || (!suggest_lane_en && !dis_lane_en)) {
        inOvertakeSuggestLaneTime = System.currentTimeMillis()

        //检测左侧是否允许行驶
        if(edgeType == LaneChangeAction.LEFT && vLaneResult.leftLaneId > 1){ //车辆不在最左侧
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
        //检测右侧是否允许行驶
        else if(edgeType == LaneChangeAction.RIGHT){
            val rightLaneId = vLaneResult.rightLaneId
            if(highWay){
                if(rightLaneId > 2){ //车辆不在最右车道(应急车道除外)
                    return Triple(-1, LaneChangeAction.RIGHT, 1)
                }else{
                    if(vLaneResult.leftLaneId > 1){ //左侧有车道
                        return Triple(-1, LaneChangeAction.LEFT, vLaneResult.leftLaneId-1)
                    }
                }
            }else{
                if(rightLaneId > 1){ //车辆不在最右车道
                    return Triple(-1, LaneChangeAction.RIGHT, 0)
                }else{
                    if(vLaneResult.leftLaneId > 1){ //左侧有车道
                        return Triple(-1, LaneChangeAction.LEFT, vLaneResult.leftLaneId-1)
                    }
                }
            }
        }

        return Triple(-1, LaneChangeAction.KEEP, -1)
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
    fun isSuggested(s: String): Boolean = s.contains("*")

    //车辆是否已在推荐车道上？
    if (isSuggested(lanes[currentIdx])) {
        //在推荐车道上的时间超过5秒后则临时禁用超车功能（直到导航车道信息有变或者导航引导信息有变）
        if((System.currentTimeMillis() - inSuggestLaneTime) > 5000L){
            if(!disableOvertakeSuggestLane) {
                disableOvertakeSuggestLane = true

                if(suggest_lane_en) {
                    val msg = "${nowTime} 行驶在推荐车道已超过5秒，当前路段暂停超车"
                    Log.w("LaneDecision", msg)
                    showLaneDebug(msg)
                }
            }
        }
        return Triple(-1, LaneChangeAction.KEEP, -1)
    }else{
        if(!disableOvertakeSuggestLane) {
            inOvertakeSuggestLaneTime = System.currentTimeMillis()
        }else{
            //当前路段已禁止超车
            return Triple(-1, LaneChangeAction.KEEP, -1)
        }
    }

    //currentIdx为车辆所在的导航车道数组的索引
    var targetIdx = currentIdx

    // =====================================================
    // 左右侧是否允许通行检测
    // =====================================================
    //检测左边车道是否允许通行
    if (edgeType == LaneChangeAction.LEFT) {
        //车辆不在最左侧车道
        if(currentIdx > 0){
            if(isBlocked(lanes[currentIdx-1]) && dis_lane_en){
                //左边车道为禁行车道，则需要判断右侧车道是否可以通行
            }else {
                //允许向左变道
                targetIdx = currentIdx - 1
                val visualLaneId = targetIdxToRightBased(targetIdx, _navLane.validCount)
                return Triple(targetIdx, LaneChangeAction.LEFT, visualLaneId)
            }
        }

        //导航车道数量大于当前车道所在车道
        if(count > (currentIdx+1) && (isBlocked(lanes[currentIdx+1]) && dis_lane_en)){ //右边车道为禁行车道
            return Triple(-1, LaneChangeAction.KEEP, -1)
        }
        //从视觉车道上继续判断右侧车道是否可以通行
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

        return Triple(-1, LaneChangeAction.KEEP, -1)
    } else if (edgeType == LaneChangeAction.RIGHT) {
        //车辆不在最右侧车道
        if((currentIdx+1) < count){
            if(isBlocked(lanes[currentIdx-1]) && dis_lane_en){
                //右边车道为禁行车道，则需要判断左侧车道是否可以通行
            }else {
                //允许向右变道
                targetIdx = currentIdx + 1
                val visualLaneId = targetIdxToRightBased(targetIdx, _navLane.validCount)
                return Triple(targetIdx, LaneChangeAction.RIGHT, visualLaneId)
            }
        }else if(currentIdx == 0){
            return Triple(-1, LaneChangeAction.KEEP, -1)
        }

        //导航车道数量大于当前车道所在车道
        if(isBlocked(lanes[currentIdx-1]) && dis_lane_en){ //左边车道为禁行车道
            return Triple(-1, LaneChangeAction.KEEP, -1)
        }
        //从视觉车道上继续判断左侧车道是否可以通行
        if(vLaneResult.leftLaneId > 1){ //车辆不在最左车道
            return Triple(-1, LaneChangeAction.LEFT, vLaneResult.leftLaneId - 1)
        }

        return Triple(-1, LaneChangeAction.KEEP, -1)
    } else {
        return Triple(-1, LaneChangeAction.KEEP, -1)
    }
}
