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
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MediaProjection 截屏管理器（常驻 VirtualDisplay 版）。
 *
 * 关键约束：Android 14+ 起，同一个 MediaProjection 实例**只允许 createVirtualDisplay 一次**，
 * 重复调用会抛
 * `SecurityException: Don't take multiple captures by invoking MediaProjection#createVirtualDisplay multiple times on the same instance.`
 * 因此这里在 [prepare] 时一次性创建 VirtualDisplay + ImageReader 并全程保活。
 *
 * 取帧策略：**点一次 OCR 只取一帧、只识别一次、不做循环**。
 *  - 空闲时 listener 持续 acquire+close 维持 ImageReader buffer 流转（心跳）：否则
 *    MAX_IMAGES 个 buffer 被填满后生产者（VirtualDisplay）会彻底停帧（实测掉到 0fps），
 *    连超时兜底都拿不到帧。这只是底层 buffer 心跳，不是业务层 OCR 循环。
 *  - 点 OCR 时置 capturing=true，listener 在下一帧到达时取最新一帧并立即 CAS 结算，
 *    之后 capturing 变回 false，其余帧直接 close 丢弃，一次点击只识别一次。
 *  - 静止画面没有新帧触发 listener 时，超时兜底取 buffer 里最后一帧识别。
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

    /** 最近一次收到真实帧的时间（elapsedRealtime），用于检测虚拟屏是否停帧。 */
    @Volatile
    private var lastFrameElapsedMs = 0L

    /** 投影被系统/用户停止时的回调（例如用户点了「停止投放」）。 */
    @Volatile
    private var onProjectionStopped: (() -> Unit)? = null

    /**
     * 取帧超时兜底，运行在 handler 线程。
     * 静止画面可能没有新帧触发 listener，此时取 buffer 里最后一帧识别；
     * 连这一帧都没有才放弃。无论如何都结算本次请求，绝不留下卡死的 capturing 标志。
     */
    private val frameTimeout = Runnable {
        Log.w(TAG, "captureOneFrame: timeout, fallback to latest buffered frame")
        val image = runCatching { imageReader?.acquireLatestImage() }.getOrNull()
        if (image != null) settleFrame(image, deliver = true) else settleFrame(null, deliver = false)
    }

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
        lastFrameElapsedMs = SystemClock.elapsedRealtime()
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
            // 必须持续 acquire 空闲帧：ImageReader 只有 MAX_IMAGES 个 buffer，若消费者
            // 长期不 acquire，生产者（VirtualDisplay）会 buffer 耗尽而彻底停帧，
            // 实测从 77fps 掉到 0fps，连 acquireLatestImage() 兜底都拿不到帧。
            //
            // 注意这不是「OCR 循环」：持续 acquire 只是为了维持 buffer 流转（心跳），
            // 业务上一次点击只识别一次——只有 capturing=true（用户点了 OCR）时
            // settleFrame 才把这帧交给 onFrame；其余帧在 CAS 失败时直接 close 丢弃。
            // 运行在 HandlerThread 上：任何未捕获异常都会杀掉整个进程，必须整体兜住。
            val image = runCatching { r.acquireLatestImage() }.getOrNull()
            if (image != null) {
                lastFrameElapsedMs = SystemClock.elapsedRealtime()
                settleFrame(image, deliver = true)
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
     * 统一结算一帧。由 [setOnImageAvailableListener] 的回调或超时兜底二选一触发，
     * 用 CAS 保证同一请求只结算一次，从而同时避免重复回调和标志泄漏。
     */
    private fun settleFrame(image: Image?, deliver: Boolean) {
        workerHandler?.removeCallbacks(frameTimeout)
        if (!capturing.compareAndSet(true, false)) {
            // 已经结算过（超时或上一次请求），丢弃这帧
            runCatching { image?.close() }
            return
        }
        val cb = pendingFrame
        pendingFrame = null
        try {
            if (image != null && cb != null) cb(image)
        } catch (e: Exception) {
            Log.w(TAG, "frame callback failed: ${e.message}", e)
        } catch (t: Throwable) {
            Log.w(TAG, "frame callback crashed: ${t.message}", t)
        } finally {
            runCatching { image?.close() }
        }
        val delivered = pendingDelivered
        pendingDelivered = null
        // 只有真正拿到帧并识别后才弹结果框；超时复位不弹空框。
        if (deliver && image != null) runCatching { delivered?.invoke() }
    }

    /**
     * 从常驻 ImageReader 取一帧：置 capturing=true 后由 listener 取下一帧（或超时兜底取缓冲帧）。
     * 一次调用只取一帧、只回调一次 [onFrame] 与 [onDelivered]，不做任何循环。
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
        // 上一次请求若仍挂起，先强制复位（不弹旧结果），避免 capturing 永久卡在 true。
        if (capturing.get() && capturing.compareAndSet(true, false)) {
            pendingFrame = null
            pendingDelivered = null
        }
        handler.removeCallbacks(frameTimeout)
        pendingFrame = onFrame
        pendingDelivered = onDelivered
        capturing.set(true)
        handler.postDelayed(frameTimeout, FRAME_TIMEOUT_MS)
        Log.d(TAG, "captureOneFrame: waiting for one frame (single shot)")
    }

    /** 彻底释放投影权限与所有资源（仅在服务销毁或投影失效时调用）。 */
    fun release() {
        clearProjection(stopProjection = true)
    }

    /**     * 虚拟屏是否疑似停帧：距最后一帧超过 [staleMs]，说明 producer 不再产帧，
     * 此时点 OCR 拿不到帧，需要重建 VirtualDisplay。刚 prepare 尚无帧时按未停帧处理。
     */
    fun isFrameStale(staleMs: Long): Boolean {
        val last = lastFrameElapsedMs
        return last > 0L && SystemClock.elapsedRealtime() - last > staleMs
    }

    private fun clearProjection(stopProjection: Boolean) {
        workerHandler?.removeCallbacks(frameTimeout)
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
        lastFrameElapsedMs = 0L
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
        /** 取帧超时：静止画面无新帧时，等多久改用缓冲帧兜底。 */
        private const val FRAME_TIMEOUT_MS = 3000L
    }
}
