package com.carrot.amapnavi

// -----------------------------
// 车道检测 / 显示开关
// -----------------------------
var lane_show = false
var video_show = false
var video_show_fast = false
var lane_detect = false
var lane_color = false
var lane_detect_enable = 0
var lane_show_enable = 0
var lane_detect_interval = 500

var show_param_log = true

var navi_lane_cnt = -1

// -----------------------------
// 车辆 / 导航运行态
// -----------------------------
var carState = CarState()
val opStateManager = OpStateManager()
var vLaneResult = LaneCalcResult()
var vLaneText = ""
var vNavLaneText = ""
var navLane = NavLane(
    laneStrArray = arrayOf(),  // 空数组
    validCount = 0             // 初始有效数量为0
)
var lastNavLane = NavLane(
    laneStrArray = arrayOf(),  // 空数组
    validCount = 0             // 初始有效数量为0
)
var navLaneChange = false
var resetSuggestLaneTimer = false
var disableSuggestLane = false
var enableLaneChange = true
var enableLaneChangeTimer = 0L
var disableLaneChangeTimer = 0L

var laneLineValid = false

var appSpeakFile = ""

var lastOvertakeNavLane = NavLane(
    laneStrArray = arrayOf(),  // 空数组
    validCount = 0             // 初始有效数量为0
)
var overtakeNavLaneChange = false
var resetOvertakeSuggestLaneTimer = false
var disableOvertakeSuggestLane = false
