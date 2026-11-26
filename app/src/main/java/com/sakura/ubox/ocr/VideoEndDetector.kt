package com.sakura.ubox.ocr

import android.media.Image

/**
 * 快手「自动重播」场景的视频播完检测。
 *
 * 不依赖 OCR 文字或无障碍节点，而是把画面中心区域压成 8×8 灰度 aHash 指纹：
 * 视频循环回到开头时，当前帧指纹会和若干秒前记录过的开头帧高度相似，
 * 据此判定「上一条播完一轮」。
 */
class VideoEndDetector {

    private data class Frame(val timeMs: Long, val hash: Long)

    private val history = ArrayDeque<Frame>()
    private var lastHash: Long? = null
    private var lastDetectMs = 0L
    /** 画面开始连续静止的时间戳；0 表示当前不在静止状态。 */
    private var staticSinceMs = 0L

    /** 重新开始一轮检测（开启定时识别 / 上滑切换视频后调用）。 */
    fun reset() {
        history.clear()
        lastHash = null
        lastDetectMs = 0L
        staticSinceMs = 0L
    }

    /**
     * 从 Image 中心区域采样 8×8 像素生成 64 位 aHash。
     * 跳过顶部/底部固定 UI；全黑或接近纯色的转场帧返回 null。
     */
    fun fingerprint(image: Image): Long? {
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val w = image.width
            val h = image.height

            val x0 = (w * 0.10f).toInt()
            val x1 = (w * 0.90f).toInt()
            val y0 = (h * 0.20f).toInt()
            val y1 = (h * 0.80f).toInt()

            val grays = IntArray(GRID * GRID)
            var idx = 0
            var sum = 0
            for (gy in 0 until GRID) {
                val py = y0 + (y1 - y0) * gy / (GRID - 1)
                for (gx in 0 until GRID) {
                    val px = x0 + (x1 - x0) * gx / (GRID - 1)
                    val offset = py * rowStride + px * pixelStride
                    if (offset + 2 >= buffer.capacity()) return null
                    val r = buffer.get(offset).toInt() and 0xFF
                    val g = buffer.get(offset + 1).toInt() and 0xFF
                    val b = buffer.get(offset + 2).toInt() and 0xFF
                    val gray = (r * 299 + g * 587 + b * 114) / 1000
                    grays[idx++] = gray
                    sum += gray
                }
            }
            val avg = sum / grays.size
            if (avg < 12 || avg > 245) return null

            var hash = 0L
            for (i in grays.indices) {
                if (grays[i] > avg) hash = hash or (1L shl i)
            }
            hash
        } catch (e: Exception) {
            null
        }
    }

    /** 一帧的检测结果：无 / 循环点命中 / 连续静止超时（均视为完播）。 */
    enum class DetectResult { NONE, LOOP, STATIC_TIMEOUT }

    /**
     * 判定当前帧是否命中循环点，或画面是否已连续静止超时。
     * 条件：
     * - 静止超时：与上一帧几乎一致且持续 ≥ [STATIC_TIMEOUT_MS]（广告页、静态片尾等）；
     * - 循环点：与 [MIN_ANCHOR_AGE_MS] 之前的某帧汉明距离 ≤ [MAX_HASH_DISTANCE]，且本帧非静止。
     */
    fun detect(hash: Long, nowMs: Long): DetectResult {
        val previous = lastHash
        lastHash = hash
        val isStatic = previous != null && hamming(hash, previous) <= STATIC_DISTANCE

        // 连续静止计时：静止帧出现时开始计时，画面一动就清零。
        if (isStatic) {
            if (staticSinceMs == 0L) staticSinceMs = nowMs
        } else {
            staticSinceMs = 0L
        }

        // 连续静止超过阈值视为完播（静态广告页 / 片尾）。
        if (staticSinceMs != 0L && nowMs - staticSinceMs >= STATIC_TIMEOUT_MS) {
            staticSinceMs = 0L
            lastDetectMs = nowMs
            return DetectResult.STATIC_TIMEOUT
        }

        // 循环点检测：静止帧不参与，避免污染历史指纹。
        if (isStatic || nowMs - lastDetectMs < MIN_DETECT_INTERVAL_MS) return DetectResult.NONE

        var matched = false
        synchronized(history) {
            for (frame in history) {
                if (nowMs - frame.timeMs < MIN_ANCHOR_AGE_MS) continue
                if (hamming(hash, frame.hash) <= MAX_HASH_DISTANCE) {
                    matched = true
                    break
                }
            }
            history.addLast(Frame(nowMs, hash))
            while (history.isNotEmpty() && nowMs - history.first().timeMs > HISTORY_WINDOW_MS) {
                history.removeFirst()
            }
        }
        if (matched) {
            lastDetectMs = nowMs
            return DetectResult.LOOP
        }
        return DetectResult.NONE
    }

    private fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    companion object {
        private const val GRID = 8
        /** 64 位中允许不同的位数（越小越严格）。 */
        private const val MAX_HASH_DISTANCE = 6
        /** 少于 4 秒前的帧不作为循环锚点，避免同一镜头内误判。 */
        private const val MIN_ANCHOR_AGE_MS = 4_000L
        /** 两次「播完」判定最小间隔。 */
        private const val MIN_DETECT_INTERVAL_MS = 5_000L
        /** 历史窗口；短视频 2 分钟足够覆盖一轮。 */
        private const val HISTORY_WINDOW_MS = 120_000L
        /** 连续静止多久视为完播（毫秒）。 */
        private const val STATIC_TIMEOUT_MS = 3_000L
        /** 与上一帧几乎一致视为静止画面（不参与循环指纹比对）。 */
        private const val STATIC_DISTANCE = 2
    }
}
