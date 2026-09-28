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

// 车道线判据不可用(视觉线型 -1 且 CP 无实线判据)时是否拦截变道：
//   默认 true(fail-safe)；视觉长期识别不出导致变道功能整体不可用时，可车上热调关闭。
//   放在全局是因为判据函数 laneLineJudgement() 是顶层函数，需要直接访问。
var lane_unknown_block = true

// -----------------------------
// 决策循环单飞保护(重要)
//   startTimeoutChecker() 会被 UDP 初始化回调重复调用，导致多个协程同时跑 naviProcess：
//   表现为同一条变道命令被多个线程重复下发、状态机标志与冷却计时互相覆盖，
//   触发/超时时机不可控。这里用原子标志保证任一时刻只有一个决策在跑。
// -----------------------------
val naviProcessBusy = java.util.concurrent.atomic.AtomicBoolean(false)
val timeoutCheckerRunning = java.util.concurrent.atomic.AtomicBoolean(false)

// -----------------------------
// 匝道/分岔提前减速运行态
//   active 期间把下发到 OP 的道路限速压到 rampSlowdownSpeed；
//   rampSlowdownUntil 是维持期截止时间，避免刚进匝道就撤销减速。
// -----------------------------
var rampSlowdownActive = false
var rampSlowdownSpeed = -1
// 匝道减速当前应压到的限速(km/h)：减速区内=曲线当前值，进匝道维持期=目标速度，其余=-1。
//   下发限速时与高德计算值取较小值，保证"匝道期间不会被高德抬高，但采纳更低的限速"
var rampActiveLimit = -1
// 匝道减速过程日志限频：每跨过 50m 记一次曲线值(便于车上核对减速是否逐档进行)
var lastRampLogBucket = -1
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

// -----------------------------
// 导航(车道领航)接管粘滞窗口
//   导航一旦有靠边/变道意图(或已发命令、或变道状态机在动作)，就获得一段优先权，
//   期间超车变道不得下发命令。
//   原因(实车证据)：导航引导会在 KEEP/变道 之间反复抖动，原实现只看当前一帧的意图，
//   抖动回 KEEP 的那一帧超车就会"乘虚而入"发命令，出现导航变道与超车变道
//   各自下发命令、互相打架(同一方向连续两条变道命令)。
// -----------------------------
var naviTakeoverUntil = 0L
const val NAVI_TAKEOVER_HOLD_MS = 3000L

// 当前是否处于"导航接管"(含粘滞窗口)
fun naviTakeoverActive(): Boolean = System.currentTimeMillis() < naviTakeoverUntil

// -----------------------------
// 变道命令序号(导航与超车共用同一个递增源)
//   CP 侧按 carrotIndex 是否变化判断"这是新命令"，值相同会被当成重复命令丢弃。
//   导航(laneChangeCmdIndex)与超车(overtakeCmdIndex)原来各维护一套计数器，
//   且都用时间戳低位初始化(启动时间接近就撞号)，实车日志里出现过两条链路使用
//   同一个 index 的情况(导航 17:03:51 index=...155，超车 17:04:09 index=...155)。
//   这里统一到一个递增源，彻底避免撞号。
// -----------------------------
private var laneCmdIndexSeq = (System.currentTimeMillis() % 1_000_000_000L).toInt()
private val laneCmdIndexLock = Any()

fun nextLaneCmdIndex(): Int = synchronized(laneCmdIndexLock) { ++laneCmdIndexSeq }

// -----------------------------
// 变道安全门"手写版兼容"总开关(默认开，设置页"宽松变道条件"默认勾选)
//   打开后，变道安全门使用 9-13 手写版(提交 65982ce1)的判据集合，一条命令回到原来的手感：
//     原车前侧雷达(-2) / 原车 BSD 后盲区(-4) / 激光侧方(-8，不含前角·后角)
//     / 视觉明确实线(-1，按 left_lane_type==1 直接采信)
//   并且：
//     · 不做侧向去抖(判安全立即放行，旧版是零延时)
//     · 不含"车道线判据不可用(-128)"
//     · 不含"综合盲区(-16)"(blind_l/r 及摄像头/车身分解来源)
//     · 不含激光自算时距/TTC(-32/-64)
//   用途：与当前严格版做 A/B 对比，定位到底哪条新判据让变道变迟钝。
// -----------------------------
var safety_legacy_mode = true

// -----------------------------
// 高速是否把最右一条车道当"应急车道"预留(不作为靠边/变道目标)
//   默认 false = 恢复 9-13 手写版行为：靠边可一路靠到最右可通行车道。
//   置 true = f42fbe3 的行为：高速靠边最多只到倒数第二条。
//   背景(实车 2026-09-27)：高德下发的车道数组是"可通行的导航车道"，并不含
//   应急车道，所以"预留最后一条"会把最右的正常车道也排除，表现为
//   "高速该靠边、右侧明明还有一条车道却完全不动"。
// -----------------------------
var highway_reserve_rightmost_lane = false

// -----------------------------
// 行车日志落盘总开关(默认开；设置页"行车日志(调试用)"默认勾选)
//   控制三类行车日志是否**写文件**(主日志 AmapNavi/app/yyyy-MM-dd.log)：
//     · NavSnap     领航现场快照(引导/车道/决策/命令号)
//     · LaneDebug   关键事件(靠边意图/播报/阻止变道/命令下发)
//     · RadarCards  雷达卡片
//   关掉后不再写盘(仍打 logcat)，用于调试完成后长期使用，避免每天十几 MB。
//   注：与 app_log(UI 原始日志)相互独立；超车日志仍由超车页「记录」按钮单独控制。
// -----------------------------
var drive_log_en = true

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
// 模拟会临时改动导航车道与相关开关，启动时保存、结束时恢复
var simSavedNavLane: NavLane? = null
var simSavedSuggestLaneEn = false
var simSavedDisLaneEn = false

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
