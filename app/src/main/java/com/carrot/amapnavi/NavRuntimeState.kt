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

// -----------------------------
// 超车决策运行态
// -----------------------------
var overtakeCooldownUntil = 0L                         // 超车冷却截止时间(ms)
var overtakeCooldownDuration = 0L                      // 本次冷却时长(ms)
var overtakeLastDirection = LaneChangeAction.KEEP      // 上次超车方向
var overtakeInProgress = false                         // 超车变道进行中
var overtakeTriggerStableTime = 0L                     // 触发条件连续满足起始时间(ms)
var overtakeLastReason = ""                            // 上次记录的决策原因(用于去重日志)
var overtakeLastLogTime = 0L                           // 上次记录决策原因的时间(ms)
