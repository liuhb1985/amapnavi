package com.carrot.amapnavi

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/* =========================================================
 * 超车专用日志（独立于主日志 / app_log 开关）
 *
 * 用途：把超车决策的现场数据落到独立文件，导出后可直接离线分析。
 *       主要回答两个问题：「为什么没超车」「这次超车为什么不合理」。
 * 目录：/sdcard/AmapNavi/overtake/       ← 单独的子文件夹，跟主日志分开
 *       文件：yyyy-MM-dd_NNNN.log（NNNN = 当天第几份，0001 起）
 *       名字短，分享时选择列表里不会折行；文件管理器直接可见，可自行挑文件发送
 *       （主日志在 /sdcard/AmapNavi/app/yyyy-MM-dd.log，同样是 AmapNavi/ 下的独立文件夹；
 *         没有存储权限时整体回退 Android/data/<包名>/files/logs/，见 LogStorage）
 *
 * 记录策略——按「状态」记，不按「时间」记，同一原因不会反复刷屏：
 *   [EVENT] 决策状态(阶段+结论+原因)发生变化时一条。
 *           比较时会把原因里的数字抹掉(车速/距离/剩余ms/稳定计时)，
 *           所以「车速52<30」和「车速53<30」算同一状态，只记第一条；
 *           只有状态真的切换(过远→时距不足、不超车→执行超车)才记新的。
 *   [SNAP ] 每次状态切换记一帧现场，内容 = overtakeSnapshotLine() 的
 *           全部输入量(车速/巡航/前后车/雷达四角/车道/两侧评分…)。
 *           分析"为什么超车不合理"就看这一帧。
 *   [IDLE ] 状态长时间不变时的心跳：间隔 60s→2m→4m→8m→10m 逐级拉长，
 *           只记一行「静止时长 + 关键量 + 未变的原因」，
 *           用来区分"一直不满足条件"和"程序没在跑"，且不会刷屏。
 *   [EVENT] dataAge 从正常跳到超标(或反向)时额外一条，
 *           用于区分"传感器不在线"和"数据流卡死"。
 *
 * 分份规则：每点一次「记录」都新开一份，编号接在当天之后递增(0001、0002…)，
 *           这样一次针对性测试就一个小文件，不用发整天的大日志；
 *           单份写到 10MB 也会自动 +1 续写，记录不中断；
 *           只有 APP 重启且开关仍是开的时候，才续写当天最后一份没写满的；
 *           跨天则从当天的 0001 重新开始。
 *
 * 开关：诊断页底部「记录/停止记录」，状态持久化在 user_settings。
 *       记录挂在超车决策主循环上（logOvertakeStage），与诊断页是否显示无关。
 * ========================================================= */
object OvertakeLogger {

    private const val TAG = "OVT"
    private const val PREF_FILE = "user_settings"
    private const val PREF_KEY = "overtake_log"
    /** 超车日志子目录名（位于 /sdcard/AmapNavi/ 下），与主日志分开存放 */
    const val SUB_DIR = "overtake"
    // 老版本的旧文件名带这个前缀，只用于识别/收编，新文件不再带
    private const val LEGACY_PREFIX = "overtake"

    private const val CHECK_INTERVAL_MS = 1000L           // 状态未变时的检查节流(1Hz)
    private const val IDLE_FIRST_INTERVAL_MS = 60_000L    // 静止心跳：首次 1 分钟
    private const val IDLE_MAX_INTERVAL_MS = 600_000L     // 静止心跳：上限 10 分钟
    private const val DATA_LATE_MS = 1500L                // dataAge 超过该值即视为数据延迟
    private const val MAX_FILE_BYTES = 10L * 1024 * 1024  // 单份上限，写满自动开下一份
    private const val SEQ_WIDTH = 4                       // 份数编号位数：0001、0002…

    // 原因文本里的数字(车速/距离/剩余ms/稳定计时)：比较状态时统一抹成 #，
    // 这样同一结论下的数值抖动不会反复产生新日志
    private val REASON_NUM = Regex("\\d+(?:\\.\\d+)?")
    private val DATA_AGE = Regex("dataAge=(\\d+)ms")
    // 2026-09-13_0002.log ← 2026-09-13 的第 2 份
    private val FILE_RE = Regex("^(\\d{4}-\\d{2}-\\d{2})_(\\d+)\\.log$")
    // 老版本留下的 overtake-2026-09-13.log，只用于识别/收编，不再新建
    private val LEGACY_RE = Regex("^$LEGACY_PREFIX-(\\d{4}-\\d{2}-\\d{2})\\.log$")
    // IDLE 摘要保留的关键量(快照行按空格分列，按前缀挑选)
    private val IDLE_FIELDS = arrayOf("v=", "cruise=", "drel=", "gap=", "onlineLR=", "dataAge=")

    private val writeExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OvertakeLog").apply { isDaemon = true }
    }

    @Volatile
    private var appContext: Context? = null

    /** 记录开关（持久化） */
    @Volatile
    var enabled: Boolean = false
        private set

    private var lastStateKey = ""      // 决策状态(阶段|结论|已抹数字的原因)
    private var lastStateAt = 0L       // 上次状态变化时间
    private var idleIntervalMs = IDLE_FIRST_INTERVAL_MS
    private var idleNextAt = 0L        // 下一次静止心跳时间
    private var nextCheckAt = 0L       // 状态未变时的下次检查时间
    private var dataLate = false       // 当前是否处于数据延迟状态

    // 当前在写的文件（写入线程改，展示接口在同一个锁里读）
    private var curDate = ""
    private var curSeq = 0
    private var curFile: File? = null

    /** 刚点了「记录」：这次测试单独一份，编号接在当天之后 */
    @Volatile
    private var startNewFile = false

    // -----------------------------
    // 开关
    // -----------------------------

    /** 读取上次的开关状态（onCreate 调用一次） */
    fun init(context: Context) {
        appContext = context.applicationContext
        enabled = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .getBoolean(PREF_KEY, false)
        migrateLegacyFiles(context)   // 历史遗留的超车日志搬进子目录
        if (enabled) event("=== APP启动，继续记录超车日志 ===")
    }

    /** 切换开关并持久化，返回切换后的状态 */
    fun toggle(context: Context): Boolean {
        appContext = context.applicationContext
        val on = !enabled
        if (on) {
            LogStorage.reset()   // 存储授权可能刚变化，重新探测用哪个目录
            migrateLegacyFiles(context)
            enabled = true
            lastStateKey = ""
            lastStateAt = 0L
            idleIntervalMs = IDLE_FIRST_INTERVAL_MS
            idleNextAt = 0L
            nextCheckAt = 0L
            dataLate = false
            synchronized(this) {   // 目录可能变了，文件重新选；本次单独一份
                curDate = ""
                curSeq = 0
                curFile = null
                startNewFile = true
            }
            event("=== 开始记录超车日志 ===")
        } else {
            event("=== 停止记录超车日志 ===")
            enabled = false
        }
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_KEY, on).apply()
        return on
    }

    // -----------------------------
    // 记录
    // -----------------------------

    /**
     * 决策回调（挂在超车决策主循环上，可能几十毫秒调用一次）。
     *
     * 状态 = 阶段 + 结论 + 抹掉数字后的原因。只有状态变化才落盘，
     * 所以「雷达不在线」这类固定原因只会记一条，不会反复写；
     * 状态不变时最多 1Hz 检查一次，仅在需要时补一条 IDLE 或数据延迟提示。
     */
    fun onDecision(stage: String, decision: String, reason: String, snapshot: () -> String) {
        if (!enabled) return

        val now = System.currentTimeMillis()
        val stateKey = "$stage|$decision|" + REASON_NUM.replace(reason, "#")

        // ---- 状态切换：事件 + 一帧现场，这是后续分析的主线 ----
        if (stateKey != lastStateKey) {
            lastStateKey = stateKey
            lastStateAt = now
            idleIntervalMs = IDLE_FIRST_INTERVAL_MS
            idleNextAt = now + idleIntervalMs
            nextCheckAt = 0L
            line("EVENT", "$stage → $decision : $reason")
            line("SNAP ", snapshot())
            return
        }

        // ---- 状态未变：低频(1Hz)检查，避免同一条原因把日志刷满 ----
        if (now < nextCheckAt) return
        nextCheckAt = now + CHECK_INTERVAL_MS
        val text = snapshot()

        // 数据延迟跳变：区分"传感器不在线"与"数据流卡死"
        val age = DATA_AGE.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
        val late = age > DATA_LATE_MS
        if (late != dataLate) {
            dataLate = late
            val what = if (late) "数据延迟 dataAge=${age}ms（>${DATA_LATE_MS}ms）"
            else "数据恢复正常 dataAge=${age}ms"
            line("EVENT", what)
        }

        // 静止心跳：间隔逐级拉长，只记摘要，证明"程序在跑但条件一直不满足"
        if (now >= idleNextAt) {
            idleIntervalMs = (idleIntervalMs * 2).coerceAtMost(IDLE_MAX_INTERVAL_MS)
            idleNextAt = now + idleIntervalMs
            line("IDLE ", "静止${(now - lastStateAt) / 1000}s，" + brief(text) +
                " | 状态未变：$stage → $decision : $reason")
        }
    }

    /** 从快照行里挑出少量关键量，供 IDLE 摘要使用 */
    private fun brief(snap: String): String =
        snap.split(' ').filter { tok -> IDLE_FIELDS.any { tok.startsWith(it) } }.joinToString(" ")

    /** 记一条事件(带时间戳) */
    fun event(msg: String) {
        if (!enabled) return
        line("EVENT", msg)
    }

    /** 当前日志文件名（界面展示用：这次正在写 / 将要新建的那一份） */
    fun todayFileName(context: Context): String = synchronized(this) {
        val date = today()
        val directory = logDir(context)
        val cur = curFile
        val seq = if (cur != null && curDate == date) {
            if (cur.length() <= MAX_FILE_BYTES) curSeq else curSeq + 1   // 满了下一步就 +1
        } else {
            (lastOfDay(directory, date)?.first ?: 0) + 1
        }
        fileName(date, seq)
    }

    /** 超车日志目录展示路径（提示用户去文件管理器哪里找） */
    fun logDirPath(context: Context): String = LogStorage.displayDir(context, SUB_DIR)

    /** 超车日志目录：手机存储里的 AmapNavi/overtake/ */
    private fun logDir(context: Context): File = LogStorage.dir(context, SUB_DIR)

    private fun line(level: String, msg: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        enqueue("[$time][$TAG][$level] $msg")
    }

    private fun enqueue(text: String) {
        try {
            writeExec.execute { appendToFile(text) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // -----------------------------
    // 文件选择（按日期 + 编号分份）
    // -----------------------------

    // 单线程顺序追加，避免多线程写乱
    private fun appendToFile(text: String) {
        val context = appContext ?: return
        try {
            val file = currentFile(context)
            file.parentFile?.takeIf { !it.exists() }?.mkdirs()
            FileWriter(file, true).use { it.append(text).append('\n') }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 当前该写的文件。按日期 + 份数分段：
     *   1) 刚点过「记录」→ 新开一份，编号 = 当天已有最大编号 + 1
     *   2) 手里这份没写满 → 继续写
     *   3) 手里这份已满   → 编号 +1 续写（自动接着记，不停止记录）
     *   4) 本进程当天还没落笔（APP 重启）→ 续写当天最后一份没满的
     * 跨天则从新日期的 0001 重新开始。
     */
    @Synchronized
    private fun currentFile(context: Context): File {
        val date = today()
        if (date != curDate) {          // 跨天：编号从当天的第一份重新算
            curDate = date
            curSeq = 0
            curFile = null
        }

        val dir = logDir(context)

        if (startNewFile) {             // 这次测试单独一份
            startNewFile = false
            return openNew(dir, date, (lastOfDay(dir, date)?.first ?: 0) + 1, rolled = false)
        }

        curFile?.let { if (it.length() <= MAX_FILE_BYTES) return it }

        if (curSeq == 0) {              // 本进程当天还没落过笔
            val last = lastOfDay(dir, date)
            if (last != null && last.second.length() <= MAX_FILE_BYTES) {
                curSeq = last.first
                curFile = last.second
                return last.second
            }
            return openNew(dir, date, (last?.first ?: 0) + 1, rolled = last != null)
        }

        return openNew(dir, date, curSeq + 1, rolled = true)   // 写满，接着开下一份
    }

    /** 新建一份并记为当前文件；rolled = 因为上一份写满而切换 */
    private fun openNew(dir: File, date: String, seq: Int, rolled: Boolean): File {
        curSeq = seq
        val file = File(dir, fileName(date, seq))
        curFile = file
        if (rolled) {
            // 单独导出这一份时也能看出上下文
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            file.parentFile?.takeIf { !it.exists() }?.mkdirs()
            FileWriter(file, true).use {
                it.append("[$time][$TAG][EVENT] 上一份已达${MAX_FILE_BYTES / 1024 / 1024}MB上限，自动续写新文件 ${file.name}\n")
            }
        }
        return file
    }

    /**
     * 当天已存在的最后一份日志（编号最大的那份；文件名一定带 NNNN 编号）。
     * 老版本留下的无编号文件会被收编成带编号的名字，避免出现「有的日志没编号」。
     */
    private fun lastOfDay(dir: File, date: String): Pair<Int, File>? {
        adoptLegacy(dir, date)
        var seq = 0
        var file: File? = null
        dir.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            val m = FILE_RE.matchEntire(f.name) ?: return@forEach
            if (m.groupValues[1] != date) return@forEach
            val n = m.groupValues[2].toIntOrNull() ?: return@forEach
            if (n > seq) {
                seq = n
                file = f
            }
        }
        return file?.let { seq to it }
    }

    /**
     * 收编老版本的无编号文件 overtake-<date>.log：占一个空编号改名，
     * 这样升级后日志文件名统一带编号，旧内容也不会被丢下。
     */
    private fun adoptLegacy(dir: File, date: String) {
        try {
            val legacy = File(dir, "$LEGACY_PREFIX-$date.log")
            if (!legacy.isFile) return
            var seq = 1
            while (File(dir, fileName(date, seq)).exists()) seq++
            val target = File(dir, fileName(date, seq))
            if (legacy.renameTo(target)) {
                android.util.Log.i(TAG, "旧日志已更名：${legacy.name} → ${target.name}")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun fileName(date: String, seq: Int): String =
        "${date}_" + String.format(Locale.US, "%0${SEQ_WIDTH}d", seq) + ".log"

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    // -----------------------------
    // 导出
    // -----------------------------

    /**
     * 导出超车日志：目录里有多个文件时弹出多选框，用户自由勾选后走系统分享；
     * 只有一个文件时直接分享。
     */
    fun shareLogs(context: Context) {
        migrateLegacyFiles(context)     // 保证根目录的历史超车日志也出现在列表里
        val files = LogStorage.listLogs(logDir(context), "")
        LogStorage.shareLogs(context, "分享超车日志", files, "${LogStorage.PUBLIC_DIR_NAME}/$SUB_DIR")
    }

    /**
     * 把早期版本留在日志根目录的超车文件搬进 overtake/ 子目录，让两种日志彻底分开：
     *   overtake-2026-09-12.log → overtake/2026-09-12_0001.log
     *   2026-09-12_0002.log     → overtake/2026-09-12_0002.log
     * 幂等，可以重复调用。
     */
    private fun migrateLegacyFiles(context: Context) = synchronized(this) {
        try {
            val root = LogStorage.dir(context)
            val target = logDir(context)
            if (root.absolutePath == target.absolutePath) return@synchronized
            val olds = root.listFiles()?.filter { it.isFile && isOvertakeLog(it.name) } ?: return@synchronized
            for (f in olds) {
                val dest = if (FILE_RE.matches(f.name)) File(target, f.name) else {
                    val date = LEGACY_RE.matchEntire(f.name)?.groupValues?.get(1) ?: continue
                    var seq = 1
                    while (File(target, fileName(date, seq)).exists()) seq++
                    File(target, fileName(date, seq))
                }
                if (dest.exists()) continue
                if (f.renameTo(dest)) android.util.Log.i(TAG, "历史超车日志已归档：${f.name} → ${dest.name}")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 是不是超车日志：yyyy-MM-dd_NNNN.log；
     * 老版本的 overtake-*.log 也算，便于主日志导出时把它排除掉。
     */
    fun isOvertakeLog(name: String): Boolean = FILE_RE.matches(name) || LEGACY_RE.matches(name)
}
