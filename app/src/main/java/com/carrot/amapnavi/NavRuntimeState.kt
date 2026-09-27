package com.carrot.amapnavi

// -----------------------------
// 车道检测 / 显示开关
// -----------------------------
var lane_show = false
var video_show = false
var video_show_fast = false
var lane_detect = false
var lane_color = false
var lane_detect_enable = 1
var lane_show_enable = 1
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

// -----------------------------
// 匝道/分岔提前减速运行态
//   active 期间把下发到 OP 的道路限速压到 rampSlowdownSpeed；
//   rampSlowdownUntil 是维持期截止时间，避免刚进匝道就撤销减速。
// -----------------------------
var rampSlowdownActive = false
var rampSlowdownSpeed = -1
var rampSlowdownUntil = 0L
// 本次减速的计划起点距离(m)：激活时锁定，避免过程中反复进出减速区
var rampSlowdownLockedDist = 0f
// 本次减速的规划起始速度(km/h)：激活时锁定，用于生成匀减速曲线
var rampSlowdownV0 = 0f

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
var overtakeLastDirectionTime = 0L                     // 上次超车方向的时间(ms)，用于反向变道冷却
var overtakeInProgress = false                         // 超车变道进行中
var overtakeTriggerStableTime = 0L                     // 触发条件连续满足起始时间(ms)

// -----------------------------
// 前车 cut-in(插队)检测运行态
//   有车并入时 drel 会突降、vrel 瞬间很负；识别后进入抑制窗口，窗口内不触发超车，
//   避免把插队瞬态当成"前车慢"而立即变道。
// -----------------------------
var prevLeadDrel: Int? = null                          // 上一周期的前车距离(m)
var prevLeadPresent = false                            // 上一周期是否有前车
var cutInSuppressUntil = 0L                            // cut-in 抑制窗口截止时间(ms)
var lastCutInTime = 0L                                 // 最近一次判定 cut-in 的时间(ms)
var cutInReason = ""                                   // 最近一次 cut-in 判定说明(诊断用)
var overtakeLastReason = ""                            // 上次记录的决策原因(用于去重日志)
var overtakeLastLogTime = 0L                           // 上次记录决策原因的时间(ms)

// 全量逐帧记录(调试用，默认关)：开启后每个决策周期把一帧现场写到 logcat(OvtFrame) 和超车日志文件
//   由调试后门 adb broadcast 控制：--es cmd frame --es val on/200ms
var overtakeFullFrameLog = false
var overtakeFullFrameIntervalMs = 200L
var overtakeFullFrameLastTime = 0L

// -----------------------------
// 端到端模拟注入(仅调试用，由 DebugReceiver cmd=sim 启动)
//   simScene 非空时，naviProcess 每个周期把该场景的一帧状态灌进 opStateManager，
//   用于在无车环境下验证变道/超车决策(见 SimScenes.kt)。
// -----------------------------
var simScene: String? = null                           // 当前模拟场景名
var simHoldUntil = 0L                                  // 模拟结束时间(ms)
var simTick = 0L                                       // 已注入帧数

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
