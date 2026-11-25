package com.carrot.amapnavi

// -----------------------------
// 交通灯 / 车辆状态常量
// -----------------------------
val traffic_off = 0
val traffic_red = 1
val traffic_green = 2

val lead = 0
val cruise = 1
val e2eCruise = 2
val e2eStop = 3
val e2ePrepare = 4
val e2eStopped = 5

val red_light = 2
val green_light = 3
val yellow_light = 4

// -----------------------------
// 变道状态常量
// -----------------------------
const val LaneChangeOff = 0
const val PreLaneChange = 1
const val LaneChangeStarting = 2
const val LaneChangeFinishing = 3

// -----------------------------
// 引导类型常量
// -----------------------------
const val TurnInfo_TurnLeft = 1
const val TurnInfo_TurnRight = 2
const val TurnInfo_ForkLeft = 3
const val TurnInfo_ForkRight = 4

// -----------------------------
// 变道动作
// -----------------------------
enum class LaneChangeAction(val value: Int) {
    KEEP(0),
    LEFT(1),
    RIGHT(2),
    LEFT_TURN(3),
    RIGHT_TURN(4),
}
