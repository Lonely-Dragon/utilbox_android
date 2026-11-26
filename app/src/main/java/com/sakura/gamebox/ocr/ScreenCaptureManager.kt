package com.sakura.gamebox.ocr

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MediaProjection 截屏管理器（常驻 VirtualDisplay 版）。
 *
 * 关键约束：Android 14+ 起，同一个 MediaProjection 实例**只允许 createVirtualDisplay 一次**，
 * 重复调用会抛
 * `SecurityException: Don't take multiple captures by invoking MediaProjection#createVirtualDisplay multiple times on the same instance.`
 * 因此这里在 [prepare] 时一次性创建 VirtualDisplay + ImageReader 并全程保活，
 * 点 OCR 时只是从常驻的 ImageReader 里取一帧，不再重建。
 *
 * 空闲时不 acquire 帧：ImageReader 的缓冲区（MAX_IMAGES）填满后生产者自动阻塞，
 * CPU 占用接近 0，画面也不会继续堆积。取帧时先冲掉积压帧再等下一帧，保证拿到的是当前画面。
 */
class ScreenCaptureManager(
    private val context: Context
) {
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private val capturing = AtomicBoolean(false)

    /** 当前等待中的取帧请求；为空表示空闲（不消费帧）。 */
    @Volatile
    private var pendingFrame: ((image: Image) -> Unit)? = null

    @Volatile
    private var pendingDelivered: (() -> Unit)? = null

    /** 投影被系统/用户停止时的回调（例如用户点了「停止投放」）。 */
    @Volatile
    private var onProjectionStopped: (() -> Unit)? = null

    /**
     * 持有投影权限并创建常驻 VirtualDisplay + ImageReader。整个过程只需调用一次，
     * 之后可长期复用，不再请求授权、也不再 createVirtualDisplay。
     */
    fun prepare(
        projection: MediaProjection,
        width: Int,
        height: Int,
        density: Int,
        onProjectionStopped: () -> Unit
    ) {
        release()
        this.projection = projection
        this.onProjectionStopped = onProjectionStopped
        workerThread = HandlerThread("GameboxImageReader").also { it.start() }
        val handler = workerThread?.looper?.let(::Handler)
        workerHandler = handler

        // Android 14+（targetSdk 34+）要求在 createVirtualDisplay 之前注册 Callback，
        // 否则抛 IllegalStateException: Must register a callback before starting capture。
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "projection stopped by system/user")
                clearProjection(stopProjection = false)
                onProjectionStopped()
            }
        }
        projectionCallback = callback
        projection.registerCallback(callback, handler)

        // 屏幕镜像的 VirtualDisplay 生产者输出 RGBA_8888；必须按同格式创建 ImageReader，
        // 否则 acquireLatestImage() 抛 UnsupportedOperationException（0x1 vs 0x23）。
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES)
        imageReader = reader
        reader.setOnImageAvailableListener({ r ->
            // 运行在 HandlerThread 上：任何未捕获异常都会杀掉整个进程，必须整体兜住。
            val cb = pendingFrame ?: return@setOnImageAvailableListener
            var image: Image? = null
            try {
                image = r.acquireLatestImage()
                if (image != null) {
                    pendingFrame = null
                    cb(image)
                }
            } catch (e: Exception) {
                Log.w(TAG, "frame callback failed: ${e.message}", e)
            } catch (t: Throwable) {
                Log.w(TAG, "frame callback crashed: ${t.message}", t)
            } finally {
                runCatching { image?.close() }
                capturing.set(false)
                val delivered = pendingDelivered
                pendingDelivered = null
                runCatching { delivered?.invoke() }
            }
        }, handler)

        try {
            virtualDisplay = projection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )
            Log.d(TAG, "virtual display created (resident) ${width}x${height}")
        } catch (e: Exception) {
            // 令牌失效 / 重复创建都会走到这里，不能让异常冒到服务里崩进程。
            Log.w(TAG, "createVirtualDisplay failed: ${e.message}", e)
            clearProjection(stopProjection = false)
            onProjectionStopped()
        }
    }

    /**
     * 从常驻 ImageReader 取一帧。
     * 先丢弃积压的旧帧，再等下一帧（此时生产者因缓冲区腾空会立即产出当前画面）。
     */
    fun captureOneFrame(
        onFrame: (image: Image) -> Unit,
        onDelivered: () -> Unit
    ) {
        val reader = imageReader
        val handler = workerHandler
        if (reader == null || handler == null || projection == null) {
            Log.w(TAG, "captureOneFrame: projection not prepared")
            onDelivered()
            return
        }
        if (!capturing.compareAndSet(false, true)) {
            Log.w(TAG, "captureOneFrame: already capturing, skip")
            return
        }
        pendingDelivered = onDelivered
        handler.post {
            // 冲掉缓冲里的旧画面，保证回调拿到的是点击 OCR 之后的新帧。
            var stale = 0
            while (true) {
                val old = runCatching { reader.acquireLatestImage() }.getOrNull() ?: break
                runCatching { old.close() }
                stale++
                if (stale >= MAX_IMAGES) break
            }
            pendingFrame = onFrame
            Log.d(TAG, "captureOneFrame: flushed $stale stale frames, waiting for next")
        }
    }

    /** 彻底释放投影权限与所有资源（仅在服务销毁或投影失效时调用）。 */
    fun release() {
        clearProjection(stopProjection = true)
    }

    private fun clearProjection(stopProjection: Boolean) {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        pendingFrame = null
        pendingDelivered = null

        val proj = projection
        val cb = projectionCallback
        if (proj != null && cb != null) {
            runCatching { proj.unregisterCallback(cb) }
        }
        projectionCallback = null
        projection = null
        onProjectionStopped = null
        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null
        capturing.set(false)
        if (stopProjection && proj != null) {
            runCatching { proj.stop() }
        }
        Log.d(TAG, "projection resources released")
    }

    val isPrepared: Boolean get() = projection != null && virtualDisplay != null
    val isCapturing: Boolean get() = capturing.get()

    companion object {
        private const val TAG = "ScreenCaptureManager"
        private const val VIRTUAL_DISPLAY_NAME = "GameboxCapture"
        private const val MAX_IMAGES = 2
    }
}
