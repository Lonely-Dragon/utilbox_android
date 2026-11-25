package com.sakura.ubox.ocr

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.util.Log
import com.equationl.paddleocr4android.CpuPowerMode
import com.equationl.paddleocr4android.OCR
import com.equationl.paddleocr4android.OcrConfig

/**
 * PaddleOCR（Paddle-Lite，PP-OCRv4 离线模型）文字识别。
 *
 * 模型与字典放在 assets 下（models/ch_PP-OCRv4 与 labels/ppocr_keys_v1.txt），首次识别时
 * 在工作线程加载到 cache，之后复用。recognize() 为同步方法，供 ImageReader 回调线程调用。
 */
class OcrEngine(context: Context) {
    private val ocr = OCR(context.applicationContext)
    private val initLock = Any()
    @Volatile
    private var initialized = false

    /** 在工作线程确保模型已加载。 */
    private fun ensureInit(): Boolean {
        if (initialized) return true
        synchronized(initLock) {
            if (initialized) return true
            val config = OcrConfig().apply {
                modelPath = "models/ch_PP-OCRv4"
                clsModelFilename = "cls.nb"
                detModelFilename = "det.nb"
                recModelFilename = "rec.nb"
                isRunDet = true
                isRunCls = true
                isRunRec = true
                cpuPowerMode = CpuPowerMode.LITE_POWER_FULL
            }
            val result = ocr.initModelSync(config)
            initialized = result.getOrDefault(false).also {
                if (!it) Log.w(TAG, "PaddleOCR init failed: ${result.exceptionOrNull()?.message}")
            }
            Log.d(TAG, "PaddleOCR initialized=$initialized")
            return initialized
        }
    }

    /**
     * 识别一帧 YUV 图像，返回文字块（坐标为旋转后图像中的像素，与屏幕坐标一致）。
     * @param image ImageReader 提供的 YUV_420_888 图像，调用方负责在返回后 close()。
     */
    fun recognize(image: Image): List<RecognizedText> = recognize(image, 1f)

    /**
     * 与 [recognize] 相同，但可在 OCR 前把画面等比缩小（如 0.5 倍）以降低耗时。
     * 用于定时检测等高频场景。
     */
    fun recognize(image: Image, scale: Float): List<RecognizedText> {
        if (!ensureInit()) return emptyList()
        val bitmap = rgbaToBitmap(image, scale) ?: return emptyList()
        return try {
            val result = ocr.runSync(bitmap)
            if (result.isFailure) {
                Log.w(TAG, "PaddleOCR run failed: ${result.exceptionOrNull()?.message}")
                return emptyList()
            }
            val ocrResult = result.getOrNull() ?: return emptyList()
            val items = mutableListOf<RecognizedText>()
            for (model in ocrResult.outputRawResult) {
                val label = model.label?.trim().orEmpty()
                if (label.isEmpty()) continue
                val points = model.points
                if (points.isEmpty()) continue
                var left = Int.MAX_VALUE
                var top = Int.MAX_VALUE
                var right = Int.MIN_VALUE
                var bottom = Int.MIN_VALUE
                for (p in points) {
                    left = minOf(left, p.x)
                    top = minOf(top, p.y)
                    right = maxOf(right, p.x)
                    bottom = maxOf(bottom, p.y)
                }
                items += RecognizedText(
                    text = label,
                    left = left.toFloat(),
                    top = top.toFloat(),
                    right = right.toFloat(),
                    bottom = bottom.toFloat()
                )
            }
            Log.d(TAG, "PaddleOCR recognized ${items.size} blocks")
            items
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * RGBA_8888 的 Image → Bitmap。
     * 注意 rowStride 可能大于 width*4（存在行尾 padding），必须按实际 stride 拷贝后再裁剪。
     */
    private fun rgbaToBitmap(image: Image, scale: Float = 1f): Bitmap? {
        return try {
            val width = image.width
            val height = image.height
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val paddedWidth = width + rowPadding / pixelStride

            buffer.rewind()
            val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(buffer)

            // 镜像缓冲已经是用户看到的画面朝向，无需再按 rotation 旋转。
            val cropped = if (paddedWidth != width) {
                Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
            } else {
                padded
            }
            if (scale < 1f) {
                val w = (width * scale).toInt().coerceAtLeast(1)
                val h = (height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(cropped, w, h, true).also { cropped.recycle() }
            } else {
                cropped
            }
        } catch (e: Exception) {
            Log.w(TAG, "Image -> Bitmap failed: ${e.message}", e)
            null
        }
    }

    fun close() {
        ocr.releaseModel()
    }

    companion object {
        private const val TAG = "OcrEngine"
    }
}
