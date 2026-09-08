package com.carrot.lane

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import ai.onnxruntime.*
import java.nio.FloatBuffer
import kotlin.math.exp
import android.util.Log
import kotlin.math.max
import kotlin.math.min

//val inputSize = 640
//val protoSize = 160
val inputSize = 416
val protoSize = 104

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
        val opts = OrtSession.SessionOptions()
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        session = env.createSession(modelBytes, opts)
    }

    // 复用 Bitmap 和 Canvas，避免每次缩放分配
    private val resizeBitmap = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
    private val resizeCanvas = android.graphics.Canvas(resizeBitmap)
    private val resizeDstRect = android.graphics.Rect(0, 0, inputSize, inputSize)

    // 复用 FloatBuffer，避免每次分配
    private val inputFloatArray = FloatArray(1 * 3 * inputSize * inputSize)
    private val inputFloatBuffer = java.nio.FloatBuffer.wrap(inputFloatArray)

    // ----------------------------
    // 复用 bitmap → FloatBuffer（零分配）
    // ----------------------------
    private fun bitmapToFloatBuffer(src: Bitmap): java.nio.FloatBuffer {
        // 缩放 bitmap 到 inputSize x inputSize
        resizeCanvas.drawBitmap(src, null, resizeDstRect, null)

        val hw = inputSize * inputSize
        val pixels = IntArray(hw)
        resizeBitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        val inv255 = 1f / 255f

        var r = 0
        var g = hw
        var b = hw * 2

        for (p in pixels) {
            inputFloatArray[r++] = ((p shr 16) and 0xFF) * inv255
            inputFloatArray[g++] = ((p shr 8) and 0xFF) * inv255
            inputFloatArray[b++] = (p and 0xFF) * inv255
        }

        inputFloatBuffer.rewind()
        return inputFloatBuffer
    }

    // ----------------------------
    // 优化 infer()
    // ----------------------------
    fun infer(bitmap: Bitmap): List<SegResult> {
        val input = bitmapToFloatBuffer(bitmap)
        val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())

        var tensor: OnnxTensor? = null
        var outputs: OrtSession.Result? = null

        try {
            tensor = OnnxTensor.createTensor(env, input, shape)
            outputs = session.run(mapOf("images" to tensor))

            // 调试输出模型形状
            for ((name, value) in outputs) {
                val t = value as OnnxTensor
                Log.d("ONNX", "name=$name, shape=${t.info.shape.contentToString()}")
            }

            var predsTensor: OnnxTensor? = null
            var protoTensor: OnnxTensor? = null

            for (entry in outputs) {
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
            val preds = predsTensor.value as Array<Array<FloatArray>>       // [1,42,8400]
            @Suppress("UNCHECKED_CAST")
            val proto = protoTensor.value as Array<Array<Array<FloatArray>>> // [1,32,160,160]

            return postProcess(preds[0], proto[0])

        } finally {
            try { outputs?.close() } catch (_: Exception) {}
            try { tensor?.close() } catch (_: Exception) {}
        }
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
        maskPool.clear() // 每帧开始清空池
        for (a in keep) {
            val box = RectF(
                a.cx - a.w * 0.5f,
                a.cy - a.h * 0.5f,
                a.cx + a.w * 0.5f,
                a.cy + a.h * 0.5f
            )

            // 从池里拿数组
            val mask = if (maskPool.size > results.size) {
                maskPool[results.size]
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