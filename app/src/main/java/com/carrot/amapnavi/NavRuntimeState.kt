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

// -----------------------------
// 超车诊断面板运行态(测试用，可随时开关)
//   面板把每次决策的输入/阶段/两侧评估/结论汇总显示在界面上，
//   便于测试时直接截图或复制反馈。
// -----------------------------
var overtakeDebugEnable = false                        // 面板显示开关
var overtakeDebugNaviFlag = false                      // 最近一次导航是否在控制中(领航)
var overtakeDebugStage = ""                            // 最近一次决策所处阶段
var overtakeDebugDecision = ""                         // 最近一次决策结论
var overtakeDebugReason = ""                           // 最近一次决策原因
var overtakeDebugLastText = ""                         // 上次刷新到UI的完整文本
var overtakeDebugLastHead = ""                         // 上次刷新到UI的顶栏文本(相同则不重复setText)
var overtakeDebugLastTextTime = 0L                     // 上次刷新详情文本的时间(ms，用于限速)
var overtakeDebugLastTime = 0L                         // 上次刷新UI的时间(ms)
