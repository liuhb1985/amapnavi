package com.carrot.amapnavi

import android.app.Activity
import android.content.Context
import android.graphics.*
import android.util.Log
import android.widget.ImageView
import android.widget.TextView
import com.carrot.lane.LaneDetect
import com.carrot.lane.SegResult
import com.carrot.lane.protoSize
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.lang.ref.WeakReference

private var imageViewRef: WeakReference<ImageView>? = null
private var tvDevicIDRef: WeakReference<TextView>? = null
private var mainActivityRef: WeakReference<Activity>? = null

var left_lane_safe_cnt = 0
var right_lane_safe_cnt = 0
var left_lane_type = -1
var right_lane_type = -1
var laneDetectCnt = 0
var laneSendCnt = 0
var lane_detect_valid = false

@Volatile
var sendLaneResultFlag = false

private var laneDetectJob: Job? = null

// ===============================
// 性能测试配置（原样）
// ===============================
private val ENABLE_BENCHMARK = false
private val BENCHMARK_FRAMES = 100
private val BENCHMARK_INTERVAL_MS = 0L
private val INFER_INTERVAL_MS = 200L

data class LaneUiInfo(
        val leftTypeCn: String,
        val rightTypeCn: String
)

fun setlaneDetectActivity(_imageView: ImageView, _tvDevicID: TextView) {
    imageViewRef = WeakReference(_imageView)
    tvDevicIDRef = WeakReference(_tvDevicID)
}

private fun laneDetectResultSend(){
    sendLaneResultFlag = true
    /*
    mainActivityRef?.get()?.let {
        (it as? MainActivity)
                ?.keepAliveService
                ?.sendLaneType(left_lane_type, right_lane_type)
    }
     */
}

/**
 * 由 KeepAliveService 调用
 */
fun startLaneDetectInternal(
        context: Context,
        activity: Activity,
        serviceScope: CoroutineScope
) {
    mainActivityRef = WeakReference(activity)

    // 防止重复启动（原样）
    if (laneDetectJob?.isActive == true) return

    val yolo = LaneDetect(context)
    val client = OkHttpClient()

    laneDetectJob = serviceScope.launch(Dispatchers.IO) {

        var frameCount = 0
        var totalTimeMs = 0L
        var minTimeMs = Long.MAX_VALUE
        var maxTimeMs = 0L
        var inferPrevTimeMs = 0L
        var inferTimeMs = 0L
        var results: List<SegResult> = emptyList()
        var infer_flag = false

        while (isActive) {

            // -------------------------
            // server IP 选择（原样）
            // -------------------------
            val serverIp = when {
                !laneSrvIp.isNullOrEmpty() -> laneSrvIp
                !carrotManIp.isNullOrEmpty() -> carrotManIp
                else -> {
                    delay(500)
                    lane_detect_valid = false
                    continue
                }
            }

            // -------------------------
            // 车道检测开关（原样）
            // -------------------------
            if (!lane_detect || lane_detect_enable <= 0) {
                if (left_lane_type > -1 || right_lane_type > -1) {
                    if (left_lane_type > -1) left_lane_type--
                    if (right_lane_type > -1) right_lane_type--
                    laneDetectResultSend()
                }
                // 检测关闭：线型判据不再可信，复位滤波器防止下次开启时沿用旧值
                resetLaneTypeFilter()
                results = emptyList()
            }

            if ((!lane_detect || lane_detect_enable <= 0) &&
                    (!video_show || lane_show_enable <= 0)
            ) {
                delay(500)
                lane_detect_valid = false
                continue
            }

            val url = if (lane_color)
                "http://$serverIp:8888/roadrgb.jpg"
            else
                "http://$serverIp:8888/roadgray.jpg"

            val t0 = System.currentTimeMillis()

            try {
                val request = Request.Builder().url(url).get().build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use

                    val bytes = response.body?.bytes() ?: return@use
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            ?: return@use

                    infer_flag = false
                    val infer_gap = System.currentTimeMillis() - inferPrevTimeMs

                    // ======================
                    // 推理 → Default
                    // ======================
                    if ((lane_detect && lane_detect_enable > 0) &&
                            (infer_gap > lane_detect_interval || !video_show_fast)
                    ) {
                        try {
                            results = withContext(Dispatchers.Default) {
                                yolo.infer(bitmap)
                            }
                            inferTimeMs = System.currentTimeMillis() - t0
                            inferPrevTimeMs = System.currentTimeMillis()
                            infer_flag = true
                            laneDetectCnt++
                            if(laneDetectCnt >= 100) laneDetectCnt = 1
                            lane_detect_valid = true
                        } catch (e: Exception) {
                            Log.e("LaneDetect", "infer error", e)
                        }
                    }

                    //val outBitmap = drawResultsWithLaneType(bitmap, results, inferTimeMs, activity, infer_flag)
                    val (outBitmap, uiInfo) = drawResultsWithLaneType(bitmap, results, inferTimeMs)

                    // ======================
                    // UI → Main
                    // ======================
                    withContext(Dispatchers.Main) {
                        // 显示画面
                        if (video_show && lane_show_enable > 0) {
                            imageViewRef?.get()?.setImageBitmap(outBitmap)
                        }
                        // 更新文字
                        if (infer_flag) {
                            tvDevicIDRef?.get()?.let { tv ->
                                val deviceId = Global.deviceId
                                tv.text =
                                        if (lane_detect && lane_detect_enable > 0)
                                            "ID:$deviceId 左->${uiInfo.leftTypeCn} 右->${uiInfo.rightTypeCn} 时间:${inferTimeMs}ms 次数:${laneDetectCnt}/${laneSendCnt}"
                                        else
                                            "软件ID:$deviceId"
                            }
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e("LaneDetect", "fetch snapshot error", e)
                lane_detect_valid = false
            }

            val frameTime = System.currentTimeMillis() - t0
            if (!infer_flag) {
                laneDetectResultSend()
            }

            // -------------------------
            // Benchmark（原样）
            // -------------------------
            if (ENABLE_BENCHMARK) {
                frameCount++
                totalTimeMs += frameTime
                minTimeMs = minOf(minTimeMs, frameTime)
                maxTimeMs = maxOf(maxTimeMs, frameTime)
                if (frameCount >= BENCHMARK_FRAMES) cancel()
            }

            var sleepMs = lane_detect_interval.toLong() / 2
            if (video_show_fast) sleepMs = 50L
            if (!ENABLE_BENCHMARK) sleepMs -= frameTime
            if (sleepMs > 0) delay(sleepMs)
        }
    }
}

// ===============================
// 车道类型映射（只初始化一次）
// ===============================
private val CLASS_TO_TYPE = mapOf(
        0 to "solid",
        1 to "dashed",
        2 to "solid",
        5 to "solid"
)

private val CLASS_TO_TYPE_CN = mapOf(
        0 to "实线",
        1 to "虚线",
        2 to "实线",
        5 to "实线"
)

private val CLASS_TO_TYPE_CN_LITE = mapOf(
        0 to "实",
        1 to "虚",
        2 to "实",
        5 to "实"
)

private val IGNORE_CLASSES = setOf(
        3, // random-line
        4  // road-sign-line
)

// ===============================
// 车道线线型时间滤波(非对称迟滞)
// ===============================
// 原因：ONNX 分割结果原来每帧直接采信，一帧误判(实线被判成 dashed / 没检出)
//       就会立刻放行变道，这是"实线也变道"的直接成因之一。
// 策略：压实线的代价远大于错过一次变道，因此确认速度不对称——
//         判实线要快(连续 SOLID_CONFIRM_FRAMES 帧即可，约 0.4s @5Hz)
//         判非实线要慢(连续 CLEAR_CONFIRM_FRAMES 帧才释放，约 1.6s @5Hz)
private const val LANE_SOLID_CONFIRM_FRAMES = 2
private const val LANE_CLEAR_CONFIRM_FRAMES = 8

// 视觉线型结果的有效期：超过该时长没有新帧，说明检测已停摆，线型判据不可信
const val LANE_TYPE_FRESH_MS = 2000L

private class LaneTypeFilter {
    var value: Int = -1
    var inited = false
    private var solidCnt = 0
    private var clearCnt = 0

    fun update(raw: Int): Int {
        if (!inited) {                 // 首帧直接采信，避免启动后长时间停留在未知态
            inited = true
            value = raw
            solidCnt = 0
            clearCnt = 0
            return value
        }
        if (raw == 1) {                // 判实线：快确认
            solidCnt++
            clearCnt = 0
            if (solidCnt >= LANE_SOLID_CONFIRM_FRAMES) value = 1
        } else {                       // 判非实线：慢释放(含 -1 无线)
            clearCnt++
            solidCnt = 0
            if (clearCnt >= LANE_CLEAR_CONFIRM_FRAMES) value = raw
        }
        return value
    }

    fun reset() {
        value = -1
        inited = false
        solidCnt = 0
        clearCnt = 0
    }
}

private val leftLaneFilter = LaneTypeFilter()
private val rightLaneFilter = LaneTypeFilter()

// 最近一次拿到视觉线型结果的时间(ms)
var laneTypeLastUpdateTime = 0L

// 视觉车道线线型判据是否可信：检测开关打开 + 检测在跑 + 结果新鲜
fun laneTypeDetectValid(): Boolean {
    if (!lane_detect || lane_detect_enable <= 0) return false
    if (!laneLineValid) return false
    return (System.currentTimeMillis() - laneTypeLastUpdateTime) < LANE_TYPE_FRESH_MS
}

// 单帧线型结果入口(带时间滤波)
internal fun updateLaneTypeFiltered(rawLeft: Int, rawRight: Int) {
    laneTypeLastUpdateTime = System.currentTimeMillis()
    left_lane_type = leftLaneFilter.update(rawLeft)
    right_lane_type = rightLaneFilter.update(rawRight)
}

// 检测关闭/重启时复位，避免残留旧值被当成有效判据
internal fun resetLaneTypeFilter() {
    leftLaneFilter.reset()
    rightLaneFilter.reset()
    laneTypeLastUpdateTime = 0L
}

// 调试用：直接注入一帧原始线型(走同一套滤波，供 adb 模拟验证)
fun simInjectLaneTypeRaw(rawLeft: Int, rawRight: Int) {
    updateLaneTypeFiltered(rawLeft, rawRight)
}

// --------------------
// Paint 对象复用
// --------------------
private val maskPaint = Paint().apply { style = Paint.Style.FILL; alpha = 120 }
private val leftTextPaint = Paint().apply { textSize = 20f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.LEFT }
private val rightTextPaint = Paint().apply { textSize = 20f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.RIGHT }
private val framePaint = Paint().apply { color = Color.YELLOW; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }

// --------------------
// mask 临时像素缓存（复用）
// --------------------
private val maskPixels = IntArray(protoSize * protoSize)
private val maskBitmap = Bitmap.createBitmap(protoSize, protoSize, Bitmap.Config.ARGB_8888)

/**
 * 左右车道线选择结果
 */
internal data class LaneSelection(
        val leftType: String,
        val rightType: String,
        val leftTypeCn: String,
        val rightTypeCn: String,
        val leftTypeCnLite: String,
        val rightTypeCnLite: String
)

/**
 * 从检测结果里挑出左右最近的两条线并判定线型。
 * 抽成独立函数是为了让「车道线推理测试页」复用完全相同的判据，避免两边逻辑漂移。
 * 注意：这里不写任何全局状态，生产绘制仍由 drawResultsWithLaneType 负责。
 */
internal fun selectLanes(results: List<SegResult>): LaneSelection {

    val centerX = protoSize / 2f

    // =====================
    // Step 1：一次扫描 mask，缓存 bottom / cx
    // =====================
    data class LaneInfo(val res: SegResult, val bottom: Int, val cxBottom: Float)
    val leftLines = mutableListOf<LaneInfo>()
    val rightLines = mutableListOf<LaneInfo>()

    for (res in results) {
        if (IGNORE_CLASSES.contains(res.classId)) continue

        val mask = res.mask
        var bottom = -1
        var cxBottom = 0f

        for (y in protoSize - 1 downTo 0) {
            var sumX = 0
            var count = 0
            val rowOffset = y * protoSize
            for (x in 0 until protoSize) {
                if (mask[rowOffset + x] > 0) {
                    sumX += x
                    count++
                }
            }
            if (count > 0) {
                bottom = y
                cxBottom = sumX.toFloat() / count
                break
            }
        }
        if (bottom < protoSize / 2) continue

        val info = LaneInfo(res, bottom, cxBottom)
        if (cxBottom < centerX) leftLines.add(info) else rightLines.add(info)
    }

    // =====================
    // Step 2：选择最近的左右线
    // =====================
    fun selectNearest(lines: List<LaneInfo>): LaneInfo? =
            lines.minByOrNull {
                val dx = it.cxBottom - centerX
                val dy = it.bottom - (protoSize - 1)
                dx * dx + 2 * dy * dy
            }

    val leftRes = selectNearest(leftLines)
    val rightRes = selectNearest(rightLines)

    // =====================
    // Step 3：线型判断
    // =====================
    return LaneSelection(
            leftType = leftRes?.let { CLASS_TO_TYPE[it.res.classId] } ?: "none",
            rightType = rightRes?.let { CLASS_TO_TYPE[it.res.classId] } ?: "none",
            leftTypeCn = leftRes?.let { CLASS_TO_TYPE_CN[it.res.classId] } ?: "无线",
            rightTypeCn = rightRes?.let { CLASS_TO_TYPE_CN[it.res.classId] } ?: "无线",
            leftTypeCnLite = leftRes?.let { CLASS_TO_TYPE_CN_LITE[it.res.classId] } ?: "无",
            rightTypeCnLite = rightRes?.let { CLASS_TO_TYPE_CN_LITE[it.res.classId] } ?: "无"
    )
}

private fun drawResultsWithLaneType(
        bitmap: Bitmap,
        results: List<SegResult>,
        infer_time: Long
): Pair<Bitmap, LaneUiInfo> {

    // =====================
    // Step 1~3：选出左右线（与测试页共用同一套判据）
    // =====================
    val selection = selectLanes(results)

    val leftType = selection.leftType
    val rightType = selection.rightType
    val leftTypeCn = selection.leftTypeCn
    val rightTypeCn = selection.rightTypeCn
    val leftTypeCnLite = selection.leftTypeCnLite
    val rightTypeCnLite = selection.rightTypeCnLite

    val frameTime = infer_time

    fun getLaneType(type: String) = when (type) {
        "solid" -> 1
        "dashed" -> 0
        else -> -1
    }

    // 单帧结果先过时间滤波再对外生效(实线快确认 / 非实线慢释放)
    updateLaneTypeFiltered(getLaneType(leftType), getLaneType(rightType))
    if (lane_detect && lane_detect_enable > 0) {
        laneDetectResultSend()
    }

    // =====================
    // Step 4：不显示直接返回
    // =====================
    if (!video_show || lane_show_enable <= 0){
        return bitmap to LaneUiInfo(
                leftTypeCnLite,
                rightTypeCnLite
        )
    }else {
        // =====================
        // Step 5：绘制 mask（复用 maskBitmap）
        // =====================
        val outBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(outBitmap)

        fun drawMask(res: SegResult) {
            val color = when (CLASS_TO_TYPE[res.classId]) {
                "solid" -> Color.RED
                "dashed" -> Color.GREEN
                else -> Color.BLUE
            }
            maskPaint.color = color

            val mask = res.mask
            // 填充 maskPixels
            for (i in mask.indices) maskPixels[i] = if (mask[i] > 0) color else 0
            maskBitmap.setPixels(maskPixels, 0, protoSize, 0, 0, protoSize, protoSize)

            val destRect = Rect(0, 0, bitmap.width, bitmap.height)
            canvas.drawBitmap(maskBitmap, null, destRect, maskPaint)
        }

        if (lane_show && lane_detect && lane_detect_enable > 0) {
            // 遍历所有有效 lane
            for (res in results) {
                if (IGNORE_CLASSES.contains(res.classId)) continue
                drawMask(res) // 这里只用于绘制，不需要 bottom/cx
            }
            //leftRes?.let { drawMask(it) }
            //rightRes?.let { drawMask(it) }
        }

        // =====================
        // Step 6：绘制文字
        // =====================
        fun getColorForType(type: String) = when (type) {
            "solid" -> Color.RED
            "dashed" -> Color.GREEN
            else -> Color.YELLOW
        }

        leftTextPaint.color = getColorForType(leftType)
        rightTextPaint.color = getColorForType(rightType)

        val margin = 10f
        val yText = 60f
        canvas.drawText("左侧: $leftTypeCn", margin, yText, leftTextPaint)
        canvas.drawText("右侧: $rightTypeCn", bitmap.width - margin, yText, rightTextPaint)
        canvas.drawText("Time: $frameTime ms", bitmap.width / 2f, 30f, framePaint)

        return outBitmap to LaneUiInfo(
                leftTypeCnLite,
                rightTypeCnLite
        )
    }
}

