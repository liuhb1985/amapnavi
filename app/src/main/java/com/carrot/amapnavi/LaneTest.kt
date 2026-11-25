package com.carrot.amapnavi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.carrot.lane.LaneDetect
import com.carrot.lane.SegResult
import com.carrot.lane.inputSize
import com.carrot.lane.protoSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Arrays
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/* =========================================================
 * 车道线推理测试页（真机自测用）
 *
 * 用途：在手机上直接验证 lane.onnx 的推理链路，用来确认
 *       「复用 direct buffer / 三段顺序写入」等优化有没有改变识别结果。
 *
 * 三种测试：
 *   参考图 —— 用内置 assets/test1.jpg 推理（不依赖车机），结果与 PC 端一致即正常
 *   实时帧 —— 抓 openpilot 8888 端口的当前画面推理（需同网段）
 *   自检   —— 对比优化后的输入张量与参考实现，逐元素差异应为 0
 *
 * 判据（左右车道线选择）直接复用生产代码的 selectLanes()，保证两边完全一致。
 * ========================================================= */

// 测试专用：与生产使用独立的 LaneDetect 实例，互不干扰
@Volatile
private var testDetector: LaneDetect? = null
private val testDetectorLock = Any()

// 防止重复点击
private val testRunning = AtomicBoolean(false)

// 每个类别的显示颜色（0实线/1虚线/2实线/3随机线/4路牌线/5实线）
private val TEST_CLASS_COLORS = intArrayOf(
    Color.RED,      // 0
    Color.GREEN,    // 1
    Color.MAGENTA,  // 2
    Color.GRAY,     // 3
    Color.YELLOW,   // 4
    Color.CYAN      // 5
)

private val testMaskPaint = Paint().apply { style = Paint.Style.FILL }
private val testBoxPaint = Paint().apply { style = Paint.Style.STROKE }
private val testTextFill = Paint().apply {
    style = Paint.Style.FILL
    typeface = Typeface.DEFAULT_BOLD
}
private val testTextStroke = Paint().apply {
    style = Paint.Style.STROKE
    typeface = Typeface.DEFAULT_BOLD
    color = Color.BLACK
}

private const val TEST_LOOPS = 3

private fun classColor(id: Int): Int =
    TEST_CLASS_COLORS[((id % TEST_CLASS_COLORS.size) + TEST_CLASS_COLORS.size) % TEST_CLASS_COLORS.size]

private fun withAlpha(color: Int, alpha: Int): Int =
    Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

private fun obtainTestDetector(ctx: Context): LaneDetect {
    testDetector?.let { return it }
    synchronized(testDetectorLock) {
        testDetector?.let { return it }
        val d = LaneDetect(ctx.applicationContext)
        testDetector = d
        return d
    }
}

// 关闭测试页时释放独立实例（省一份模型常驻内存）。
// 只在没有测试在跑时调用，避免在 infer() 中途关掉 session。
private fun releaseTestDetector() {
    synchronized(testDetectorLock) {
        val d = testDetector ?: return
        testDetector = null
        try { d.close() } catch (_: Throwable) {}
    }
}

// =========================================================
// 入口：由 MainActivity 的「车道线测试」按钮调用
// =========================================================
fun MainActivity.showLaneTestPage() {
    val activity = this
    val dp = { v: Int -> (v * resources.displayMetrics.density + 0.5f).toInt() }

    val info = TextView(activity).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        text = buildString {
            append("车道线推理测试\n\n")
            append("· 参考图：内置 test1.jpg 推理（不依赖车机）\n")
            append("· 实时帧：抓 openpilot 8888 画面推理\n")
            append("· 自检：优化后的输入张量 vs 参考实现，差异应为 0\n\n")
            append("输入尺寸 ${inputSize}x${inputSize} / proto ${protoSize}x${protoSize}\n")
            append("模型 ${ortClassInfo()}\n")
        }
    }

    val image = ImageView(activity).apply {
        setBackgroundColor(Color.BLACK)
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    val statsScroll = ScrollView(activity).apply {
        addView(info, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    val btnRef = testButton(activity, "参考图", dp)
    val btnLive = testButton(activity, "实时帧", dp)
    val btnCheck = testButton(activity, "自检", dp)

    val buttonRow = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(btnRef)
        addView(btnLive)
        addView(btnCheck)
    }

    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(10), dp(10), dp(10))
        addView(buttonRow)
        addView(
            image,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(320)
            ).apply { topMargin = dp(8); bottomMargin = dp(8) }
        )
        addView(
            statsScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(180)
            )
        )
    }

    val dialog = AlertDialog.Builder(activity)
        .setTitle("车道线推理测试")
        .setView(root)
        .setPositiveButton("关闭", null)
        .create()

    dialog.show()
    dialog.window?.setLayout(
        (resources.displayMetrics.widthPixels * 0.96f).toInt(),
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    // 关掉测试页就释放独立模型实例，避免和生产实例同时常驻占内存
    dialog.setOnDismissListener {
        if (!testRunning.get()) releaseTestDetector()
    }

    // ---------- 参考图 ----------
    btnRef.setOnClickListener {
        startLaneTest(
            activity, info, image,
            title = "【参考图】test1.jpg",
            loops = TEST_LOOPS,
            loader = {
                activity.assets.open("test1.jpg").use { BitmapFactory.decodeStream(it) }
            }
        )
    }

    // ---------- 实时帧 ----------
    btnLive.setOnClickListener {
        startLaneTest(
            activity, info, image,
            title = "【实时帧】8888 端口画面",
            loops = TEST_LOOPS,
            loader = {
                val ip = if (!laneSrvIp.isNullOrEmpty()) laneSrvIp else carrotManIp
                if (ip.isNullOrEmpty()) {
                    null
                } else {
                    val url =
                        if (lane_color) "http://$ip:8888/roadrgb.jpg"
                        else "http://$ip:8888/roadgray.jpg"
                    val bytes = JpegFrameFetcher(url).fetchFrame()
                    if (bytes == null) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }
        )
    }

    // ---------- 预处理自检 ----------
    btnCheck.setOnClickListener {
        if (!testRunning.compareAndSet(false, true)) {
            Toast.makeText(activity, "测试进行中，请稍候", Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }
        info.text = "自检中（首次加载模型需要几秒）..."

        activity.lifecycleScope.launch {
            try {
                val src = withContext(Dispatchers.IO) {
                    activity.assets.open("test1.jpg").use { BitmapFactory.decodeStream(it) }
                }
                val text = withContext(Dispatchers.Default) {
                    val detector = obtainTestDetector(activity)
                    detector.verifyInputPath(src)
                }
                info.text = text
            } catch (e: Exception) {
                info.text = "自检失败: ${e.message}"
            } finally {
                testRunning.set(false)
            }
        }
    }
}

// =========================================================
// 通用测试流程：取图 → 连续推理 N 次 → 画结果 → 出报告
// =========================================================
private fun startLaneTest(
    activity: MainActivity,
    info: TextView,
    image: ImageView,
    title: String,
    loops: Int,
    loader: () -> Bitmap?
) {
    if (!testRunning.compareAndSet(false, true)) {
        Toast.makeText(activity, "测试进行中，请稍候", Toast.LENGTH_SHORT).show()
        return
    }

    info.text = "$title\n取图中（首次加载模型需要几秒）..."

    activity.lifecycleScope.launch {
        try {
            val src = withContext(Dispatchers.IO) { loader() }
            if (src == null) {
                info.text = "$title\n\n取图失败：请检查是否与 openpilot 在同一网段，以及车道线检测是否已连接"
                return@launch
            }

            val times = LongArray(loops)
            var results: List<SegResult> = emptyList()

            val overlay = withContext(Dispatchers.Default) {
                val detector = obtainTestDetector(activity)
                for (i in 0 until loops) {
                    val t0 = SystemClock.elapsedRealtime()
                    results = detector.infer(src)
                    times[i] = SystemClock.elapsedRealtime() - t0
                }
                // mask 是复用数组，必须在下次 infer 之前画完
                drawTestOverlay(src, results)
            }

            image.setImageBitmap(overlay)
            info.text = buildTestReport(activity, title, src, results, times)
        } catch (e: Exception) {
            info.text = "$title\n\n异常: ${e.message}"
        } finally {
            testRunning.set(false)
        }
    }
}

// =========================================================
// 结果绘制：mask 半透明叠加 + 检测框 + 类别/分数
// =========================================================
private fun drawTestOverlay(src: Bitmap, results: List<SegResult>): Bitmap {
    val out = src.copy(Bitmap.Config.ARGB_8888, true) ?: src
    val w = out.width
    val h = out.height
    val canvas = Canvas(out)

    // ---- mask（104x104 → 原图尺寸）----
    val maskBitmap = Bitmap.createBitmap(protoSize, protoSize, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(protoSize * protoSize)
    val dstRect = Rect(0, 0, w, h)
    for (res in results) {
        val color = classColor(res.classId)
        Arrays.fill(pixels, 0)
        val mask = res.mask
        val n = minOf(mask.size, pixels.size)
        for (i in 0 until n) if (mask[i].toInt() > 0) pixels[i] = color
        maskBitmap.setPixels(pixels, 0, protoSize, 0, 0, protoSize, protoSize)
        testMaskPaint.color = withAlpha(color, 110)
        canvas.drawBitmap(maskBitmap, null, dstRect, testMaskPaint)
    }
    maskBitmap.recycle()

    // ---- 检测框（box 在 416 坐标系，需要按原图尺寸放大）----
    val sx = w / inputSize.toFloat()
    val sy = h / inputSize.toFloat()
    testBoxPaint.strokeWidth = maxOf(2f, w / 240f)
    val textSize = maxOf(14f, w / 32f)
    testTextFill.textSize = textSize
    testTextStroke.textSize = textSize
    testTextStroke.strokeWidth = maxOf(2f, textSize / 7f)

    for (res in results) {
        val color = classColor(res.classId)
        testBoxPaint.color = color

        val l = res.box.left * sx
        val t = res.box.top * sy
        val r = res.box.right * sx
        val b = res.box.bottom * sy
        canvas.drawRect(l, t, r, b, testBoxPaint)

        val tag = String.format(Locale.US, "c%d %.2f", res.classId, res.score)
        val ty = if (t > textSize + 4f) t - 4f else t + textSize + 4f
        canvas.drawText(tag, l + 2f, ty, testTextStroke)
        testTextFill.color = color
        canvas.drawText(tag, l + 2f, ty, testTextFill)
    }

    return out
}

// =========================================================
// 结果报告
// =========================================================
private fun buildTestReport(
    activity: MainActivity,
    title: String,
    src: Bitmap,
    results: List<SegResult>,
    times: LongArray
): String {
    val sb = StringBuilder()
    sb.append(title).append('\n')
    sb.append("图: ").append(src.width).append('x').append(src.height)
        .append("   检测: ").append(results.size).append(" 条\n")

    val first = times.firstOrNull() ?: 0L
    val rest = times.drop(1)
    val avgRest = if (rest.isEmpty()) first.toDouble() else rest.average()
    sb.append("耗时: 首次 ").append(first).append("ms")
        .append("  后续均值 ").append(String.format(Locale.US, "%.1f", avgRest)).append("ms")
        .append("  最小 ").append(times.minOrNull() ?: 0L)
        .append("  最大 ").append(times.maxOrNull() ?: 0L)
        .append("  (").append(times.size).append(" 次)\n")

    // 各分类数量
    val cnt = IntArray(6)
    for (r in results) if (r.classId in 0..5) cnt[r.classId]++
    sb.append("类别: ")
    for (i in 0..5) if (cnt[i] > 0) sb.append("c").append(i).append("=").append(cnt[i]).append(' ')
    if (results.isEmpty()) sb.append("无")
    sb.append('\n')

    // 与生产完全一致的左右判定
    val sel = selectLanes(results)
    sb.append("判据(与生产一致): 左=").append(sel.leftTypeCn)
        .append("  右=").append(sel.rightTypeCn).append('\n')

    if (results.isEmpty()) {
        sb.append("【注意】未识别到车道线，请确认图片内容或模型是否正常\n")
    }

    sb.append("颜色: 0红/2紫/5青=实线 1绿=虚线 3灰/4黄=生产忽略\n")
    sb.append("---- 明细(c=类别 id) ----\n")
    sb.append("cls  score   box(x0,y0 - x1,y1)       mask\n")
    for (r in results) {
        var maskCnt = 0
        for (v in r.mask) if (v.toInt() > 0) maskCnt++
        sb.append(
            String.format(
                Locale.US, "%2d  %5.3f   %4.0f,%-4.0f - %4.0f,%-4.0f  %4d\n",
                r.classId, r.score,
                r.box.left / inputSize * src.width,
                r.box.top / inputSize * src.height,
                r.box.right / inputSize * src.width,
                r.box.bottom / inputSize * src.height,
                maskCnt
            )
        )
    }

    sb.append("\n环境: ").append(Build.SUPPORTED_ABIS.firstOrNull() ?: "-")
        .append("  核数 ").append(Runtime.getRuntime().availableProcessors())
        .append("  版本 ").append(appVersionName(activity)).append('\n')
    sb.append("提示: 车道线检测同时运行时 CPU 争用，耗时会偏高\n")
    return sb.toString()
}

private fun appVersionName(activity: MainActivity): String = try {
    activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "-"
} catch (e: Exception) {
    "-"
}

private fun ortClassInfo(): String =
    try {
        Class.forName("ai.onnxruntime.OrtEnvironment")
        "onnxruntime-android 1.16.3"
    } catch (e: Exception) {
        "onnxruntime 未加载"
    }

private fun testButton(activity: MainActivity, text: String, dp: (Int) -> Int): Button =
    Button(activity).apply {
        this.text = text
        textSize = 13f
        minWidth = 0
        minHeight = 0
        setPadding(dp(2), dp(4), dp(2), dp(4))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginEnd = dp(2); marginStart = dp(2) }
    }
