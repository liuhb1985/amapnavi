package com.carrot.lane

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import ai.onnxruntime.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.exp
import android.util.Log
import kotlin.math.max
import kotlin.math.min

//val inputSize = 640
//val protoSize = 160
val inputSize = 416
val protoSize = 104

// 调试：每帧打印输出张量形状（正式版关闭，避免日志 I/O 拖慢推理）
private const val DEBUG_SHAPE = false

// ----------------------------
// 数据结构
// ----------------------------
data class SegResult(
    val classId: Int,
    val score: Float,
    val box: RectF,
    //val mask: FloatArray // 160*160
    //val mask: Array<FloatArray> // mask[h][w], 二值化 0/1，尺寸与原图一致
    val mask: ByteArray   // 0 / 1
)

// ----------------------------
// YOLOv8-Seg Kotlin 推理类
// ----------------------------
class LaneDetect(context: Context) {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val modelBytes = context.assets.open("lane.onnx").readBytes()

        // 线程数：手机上别用满核（小核参与反而拖慢），2~4 通常最优
        val threadNum = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

        val opts = OrtSession.SessionOptions()
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        opts.setIntraOpNumThreads(threadNum)
        opts.setInterOpNumThreads(1)

        // XNNPACK：ARM 上有手写汇编 kernel，对 fp32 Conv 明显加速
        // 失败（个别 ABI / 版本不支持）时回退纯 CPU EP，不影响功能
        session = try {
            opts.addXnnpack(mapOf<String, String>())
            env.createSession(modelBytes, opts)
        } catch (e: Exception) {
            Log.w("LaneDetect", "addXnnpack failed, fallback to CPU EP", e)
            val cpuOpts = OrtSession.SessionOptions()
            cpuOpts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            cpuOpts.setIntraOpNumThreads(threadNum)
            cpuOpts.setInterOpNumThreads(1)
            env.createSession(modelBytes, cpuOpts)
        }
    }

    // 复用 Bitmap 和 Canvas，避免每次缩放分配
    private val resizeBitmap = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
    private val resizeCanvas = android.graphics.Canvas(resizeBitmap)
    private val resizeDstRect = android.graphics.Rect(0, 0, inputSize, inputSize)

    // 输入张量只创建一次，并绑定 direct（堆外）内存：
    //  1) 省掉每帧「Java 堆数组 → native」的整块拷贝（3*416*416*4 ≈ 2MB）
    //  2) 省掉每帧 new / close OnnxTensor 的开销
    private val inputShape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
    private val inputFloatBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(3 * inputSize * inputSize * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    private val inputTensor: OnnxTensor = OnnxTensor.createTensor(env, inputFloatBuffer, inputShape)
    private val inputFeed: Map<String, OnnxTensor> = mapOf("images" to inputTensor)

    // 复用像素数组（原来每帧 new IntArray(416*416) ≈ 692KB，会造成 GC 抖动）
    private val pixels = IntArray(inputSize * inputSize)

    // ----------------------------
    // 复用 bitmap → direct 输入内存（零分配、零额外拷贝）
    // ----------------------------
    private fun fillInputBuffer(src: Bitmap) {
        // 缩放 bitmap 到 inputSize x inputSize
        resizeCanvas.drawBitmap(src, null, resizeDstRect, null)

        resizeBitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        val inv255 = 1f / 255f
        val buf = inputFloatBuffer
        buf.clear()

        // 三段顺序写入，内存访问连续
        for (p in pixels) {
            buf.put(((p shr 16) and 0xFF) * inv255)   // R
        }
        for (p in pixels) {
            buf.put(((p shr 8) and 0xFF) * inv255)    // G
        }
        for (p in pixels) {
            buf.put((p and 0xFF) * inv255)            // B
        }

        // position 归零，保证 ORT 从头部读取整块数据
        buf.position(0)
    }

    // ----------------------------
    // 优化 infer()
    // ----------------------------
    fun infer(bitmap: Bitmap): List<SegResult> {
        // 复用同一块 direct 输入内存（tensor 已在 init 阶段建好，不再每帧创建）
        fillInputBuffer(bitmap)

        var outputs: OrtSession.Result? = null

        try {
            val result = session.run(inputFeed)
            outputs = result

            // 调试输出模型形状（默认关闭，避免每帧日志 I/O 拖慢推理）
            if (DEBUG_SHAPE) {
                for ((name, value) in result) {
                    val t = value as OnnxTensor
                    Log.d("ONNX", "name=$name, shape=${t.info.shape.contentToString()}")
                }
            }

            // 输出只有 2 个，按名字取即可（遍历开销可忽略）
            var predsTensor: OnnxTensor? = null
            var protoTensor: OnnxTensor? = null
            for (entry in result) {
                when (entry.key) {
                    "output0" -> predsTensor = entry.value as OnnxTensor
                    "output1" -> protoTensor = entry.value as OnnxTensor
                }
            }
            if (predsTensor == null || protoTensor == null) {
                throw RuntimeException("ONNX outputs missing output0 or output1")
            }

            // 类型 cast 安全
            @Suppress("UNCHECKED_CAST")
            val preds = predsTensor.value as Array<Array<FloatArray>>       // [1,42,3549]
            @Suppress("UNCHECKED_CAST")
            val proto = protoTensor.value as Array<Array<Array<FloatArray>>> // [1,32,104,104]

            return postProcess(preds[0], proto[0])

        } finally {
            try { outputs?.close() } catch (_: Exception) {}
        }
    }

    // ----------------------------
    // 释放 native 资源（只有「车道线推理测试页」的独立实例会调用；
    // 生产实例常驻不释放。调用前必须确保没有 infer() 正在跑）
    // ----------------------------
    fun close() {
        try { inputTensor.close() } catch (_: Throwable) {}
        try { session.close() } catch (_: Throwable) {}
    }

    // ----------------------------
    // 自检：用「参考实现」(单次遍历、逐像素内联取 RGB) 重算一遍预处理张量，
    // 与当前「复用 direct buffer + 三段顺序写入」的结果逐元素比较。
    // 两者应完全相同(最大差异 0)，用于在真机上验证优化没有改变输入数据。
    // ----------------------------
    fun verifyInputPath(src: Bitmap): String {
        val plane = inputSize * inputSize
        val inv255 = 1f / 255f

        // 1) 当前(优化后)的写入路径
        fillInputBuffer(src)

        // 2) 参考实现
        resizeCanvas.drawBitmap(src, null, resizeDstRect, null)
        val refPixels = IntArray(plane)
        resizeBitmap.getPixels(refPixels, 0, inputSize, 0, 0, inputSize, inputSize)

        val refBuf = FloatArray(plane * 3)
        for (i in 0 until plane) {
            val p = refPixels[i]
            refBuf[i] = ((p shr 16) and 0xFF) * inv255
            refBuf[plane + i] = ((p shr 8) and 0xFF) * inv255
            refBuf[plane * 2 + i] = (p and 0xFF) * inv255
        }

        // 3) 逐元素比较，同时统计各通道均值（用于确认数据确实写入）
        var maxDiff = 0f
        var diffCnt = 0
        var firstDiffIdx = -1
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var minVal = Float.MAX_VALUE
        var maxVal = -Float.MAX_VALUE

        for (i in 0 until plane * 3) {
            val v = inputFloatBuffer.get(i)
            val d = kotlin.math.abs(v - refBuf[i])
            if (d > 0f) {
                diffCnt++
                if (firstDiffIdx < 0) firstDiffIdx = i
            }
            if (d > maxDiff) maxDiff = d
            if (v < minVal) minVal = v
            if (v > maxVal) maxVal = v
            when {
                i < plane -> sumR += v
                i < plane * 2 -> sumG += v
                else -> sumB += v
            }
        }

        val sb = StringBuilder()
        sb.append("=== 预处理自检（优化路径 vs 参考实现）===\n")
        sb.append("张量: 1x3x").append(inputSize).append('x').append(inputSize)
            .append("  元素数 ").append(plane * 3).append('\n')
        sb.append("逐元素最大差异: ").append(maxDiff).append('\n')
        sb.append("有差异的元素个数: ").append(diffCnt)
        if (firstDiffIdx >= 0) sb.append("  首个位置 ").append(firstDiffIdx)
        sb.append('\n')
        sb.append("buffer position/limit: ").append(inputFloatBuffer.position())
            .append('/').append(inputFloatBuffer.limit()).append('\n')
        sb.append(
            "均值 R=" + "%.4f".format(sumR / plane) +
                    " G=" + "%.4f".format(sumG / plane) +
                    " B=" + "%.4f".format(sumB / plane) + '\n'
        )
        sb.append("取值范围: ").append(minVal).append(" ~ ").append(maxVal).append('\n')
        sb.append('\n')
        if (diffCnt == 0) {
            sb.append("结论: 与参考实现逐元素完全一致，优化未改变输入数据 [OK]\n")
        } else {
            sb.append("结论: 存在差异，预处理实现有问题 [FAIL]\n")
        }
        return sb.toString()
    }

    private fun composeMask(
        coeff: FloatArray,
        proto: Array<Array<FloatArray>>, // [32][104][104]
        bbox: RectF,
        mask: ByteArray // 传入数组
    ): ByteArray {

        val mw = protoSize
        val mh = protoSize
        mask.fill(0)  //清空为0

        // ---- bbox 映射到 proto 坐标 ----
        val x0 = ((bbox.left / inputSize) * mw).toInt().coerceIn(0, mw - 1)
        val y0 = ((bbox.top / inputSize) * mh).toInt().coerceIn(0, mh - 1)
        val x1 = ((bbox.right / inputSize) * mw).toInt().coerceIn(0, mw - 1)
        val y1 = ((bbox.bottom / inputSize) * mh).toInt().coerceIn(0, mh - 1)

        // ---- ROI 内做 matmul ----
        for (y in y0..y1) {
            val rowBase = y * mw
            for (x in x0..x1) {
                var sum = 0f
                // 手动展开 32 通道（JIT 更容易优化）
                for (k in 0 until 32) {
                    sum += coeff[k] * proto[k][y][x]
                }
                if (sum > 0f) { // sigmoid(x) > 0.5 等价于 x > 0
                    mask[rowBase + x] = 1
                }
            }
        }

        return mask
    }

    private val maskPool = mutableListOf<ByteArray>()

    private fun postProcess(
        preds: Array<FloatArray>,          // [42][8400]
        proto: Array<Array<FloatArray>>,   // [32][104][104]
        confThreshold: Float = 0.25f,
        iouThreshold: Float = 0.5f
    ): List<SegResult> {

        val numAnchors = preds[0].size
        val numClasses = 6
        val candidates = ArrayList<FastAnchor>(128)

        for (i in 0 until numAnchors) {

            // ---------- class score（没有 objectness） ----------
            var bestCls = -1
            var bestScore = 0f
            val clsBase = 4   // ✅ 和 Debug 一致

            for (c in 0 until numClasses) {
                val s = preds[clsBase + c][i]
                if (s > bestScore) {
                    bestScore = s
                    bestCls = c
                }
            }
            if (bestScore < confThreshold) continue

            // ---------- mask coeff ----------
            val coeff = FloatArray(32)
            val coeffBase = 10   // ✅ 和 Debug 一致
            for (k in 0 until 32) {
                coeff[k] = preds[coeffBase + k][i]
            }

            candidates.add(
                FastAnchor(
                    cx = preds[0][i],
                    cy = preds[1][i],
                    w  = preds[2][i],
                    h  = preds[3][i],
                    cls = bestCls,
                    score = bestScore,
                    coeff = coeff
                )
            )
        }

        if (candidates.isEmpty()) return emptyList()

        // ---------- 2. score 排序 ----------
        candidates.sortByDescending { it.score }

        // ---------- 3. NMS ----------
        val keep = ArrayList<FastAnchor>(candidates.size)
        for (a in candidates) {
            var keepIt = true
            for (b in keep) {
                if (a.cls == b.cls && fastIou(a, b) > iouThreshold) {
                    keepIt = false
                    break
                }
            }
            if (keepIt) keep.add(a)
        }

        // ---------- 4. 输出结果（此处不算 mask） ----------
        val results = ArrayList<SegResult>(keep.size)
        for (idx in keep.indices) {
            val a = keep[idx]
            val box = RectF(
                a.cx - a.w * 0.5f,
                a.cy - a.h * 0.5f,
                a.cx + a.w * 0.5f,
                a.cy + a.h * 0.5f
            )

            // 从池里按索引取数组复用（池不够才新建）。
            // 注意：数组会被下一帧 infer() 覆盖，消费方必须在本帧内用完——
            // 生产是 infer 后同步绘制，测试页也在下次 infer 前画完，均满足。
            val mask = if (idx < maskPool.size) {
                maskPool[idx]
            } else {
                ByteArray(protoSize * protoSize).also { maskPool.add(it) }
            }

            // mask 延迟计算（如非必须，可传空数组）
            composeMask(a.coeff, proto, box, mask)

            results.add(
                SegResult(
                    classId = a.cls,
                    score = a.score,
                    box = box,
                    mask = mask
                )
            )
        }

        // 池按需增长，收缩到本帧实际用量，避免偶发的多目标帧把容量永久撑大
        while (maskPool.size > results.size) {
            maskPool.removeAt(maskPool.size - 1)
        }

        return results
    }

    private inline fun fastIou(a: FastAnchor, b: FastAnchor): Float {
        val ax1 = a.cx - a.w * 0.5f
        val ay1 = a.cy - a.h * 0.5f
        val ax2 = a.cx + a.w * 0.5f
        val ay2 = a.cy + a.h * 0.5f

        val bx1 = b.cx - b.w * 0.5f
        val by1 = b.cy - b.h * 0.5f
        val bx2 = b.cx + b.w * 0.5f
        val by2 = b.cy + b.h * 0.5f

        val ix1 = if (ax1 > bx1) ax1 else bx1
        val iy1 = if (ay1 > by1) ay1 else by1
        val ix2 = if (ax2 < bx2) ax2 else bx2
        val iy2 = if (ay2 < by2) ay2 else by2

        val iw = ix2 - ix1
        val ih = iy2 - iy1
        if (iw <= 0f || ih <= 0f) return 0f

        val inter = iw * ih
        val union = a.w * a.h + b.w * b.h - inter
        return inter / union
    }
}

// 数据结构用于存储有效的 Anchor 信息
private class FastAnchor(
    var cx: Float,
    var cy: Float,
    var w: Float,
    var h: Float,
    var cls: Int,
    var score: Float,
    val coeff: FloatArray
)