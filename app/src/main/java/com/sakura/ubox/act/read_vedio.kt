package com.sakura.ubox.act

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.sakura.ubox.UBoxTaskState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

enum class SwipeMode(val label: String) {
    CENTER_UP("中点往上"),
    BOTTOM_UP("从下往上"),
    RIGHT_LEFT("从右往左")
}

class read_vedio(
    private val service: AccessibilityService,
    private val intervalProvider: () -> Long = { UBoxTaskState.intervalSeconds },
    private val pollCountProvider: () -> Int = { UBoxTaskState.pollCount },
    private val swipeModeProvider: () -> SwipeMode = { UBoxTaskState.swipeMode }
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val touchFeedback = AccessibilityTouchFeedback(service)

    @Volatile
    private var running = false

    @Volatile
    private var executedCount = 0

    private var workerThread: Thread? = null

    fun start() {
        if (running) return
        running = true
        executedCount = 0

        val intervalMs = intervalMillis()
        val totalCount = pollCountProvider().coerceAtLeast(1)
        Log.d(TAG, "read_vedio started, interval=${intervalMs}ms, pollCount=$totalCount")
        UBoxTaskState.setStatus("任务启动，准备第 1/$totalCount 次")

        workerThread = thread(name = "UBoxAutoSwipeTask") {
            runAutoSwipeLoop(intervalMs, totalCount)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        workerThread?.interrupt()
        workerThread = null
        mainHandler.post { touchFeedback.release() }
        UBoxTaskState.setStatus("任务已停止")
        Log.d(TAG, "read_vedio stopped")
    }

    fun release() {
        stop()
        mainHandler.post { touchFeedback.release() }
    }

    fun performSwipeUp(): Boolean {
        return scrollForward()
    }

    private fun runAutoSwipeLoop(intervalMs: Long, totalCount: Int) {
        while (running && executedCount < totalCount) {
            val nextIndex = executedCount + 1
            UBoxTaskState.setStatus("正在执行第 $nextIndex/$totalCount 次滑动")
            val dispatched = performSwipeOnMainThread()
            if (!dispatched) {
                Log.w(TAG, "gesture dispatch failed, stopping task")
                UBoxTaskState.setStatus("手势派发失败，任务中止")
                mainHandler.post { UBoxTaskState.setRunning(false) }
                return
            }

            executedCount += 1
            if (executedCount >= totalCount) {
                Log.d(TAG, "poll count reached: executed=$executedCount")
                UBoxTaskState.setStatus("已完成 $executedCount/$totalCount 次，返回桌面")
                finishTaskAndReturnHome()
                return
            }

            UBoxTaskState.setStatus("已完成 $executedCount/$totalCount 次，等待 ${intervalMs / 1000} 秒")
            waitWithCountdown(intervalMs, executedCount, totalCount)
        }
    }

    /**
     * 间隔等待期间每 5 秒输出一次倒计时日志（如 18 秒间隔会在 13/8/3 秒时各输出一次）。
     * 分段 sleep 保持可中断，stop() 时立即退出。
     */
    private fun waitWithCountdown(intervalMs: Long, done: Int, total: Int) {
        var remainingMs = intervalMs
        while (remainingMs > 0 && running) {
            val step = minOf(STATUS_TICK_MILLIS, remainingMs)
            try {
                Thread.sleep(step)
            } catch (_: InterruptedException) {
                return
            }
            remainingMs -= step
            if (remainingMs > 0) {
                val remainSec = (remainingMs + 999) / 1000
                UBoxTaskState.setStatus("已完成 $done/$total 次，剩余 $remainSec 秒")
            }
        }
    }

    private fun performSwipeOnMainThread(): Boolean {
        var dispatched = false
        val dispatchLatch = CountDownLatch(1)
        val gestureLatch = CountDownLatch(1)
        mainHandler.post {
            dispatched = scrollForward { gestureLatch.countDown() }
            dispatchLatch.countDown()
            if (!dispatched) {
                gestureLatch.countDown()
            }
        }
        runCatching { dispatchLatch.await() }
        if (dispatched) {
            runCatching { gestureLatch.await(GESTURE_FINISH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }
        }
        return dispatched
    }

    private fun finishTaskAndReturnHome() {
        mainHandler.post {
            UBoxTaskState.setRunning(false)
            val success = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            Log.d(TAG, "return home after task finished: success=$success")
        }
    }

    @Suppress("DEPRECATION")
    private fun scrollForward(onGestureFinished: (() -> Unit)? = null): Boolean {
        val mode = swipeModeProvider()
        Log.d(TAG, "before accessibility gesture: mode=${mode.label}")
        UBoxTaskState.setStatus("滑动方向：${mode.label}")

        val metrics = service.resources.displayMetrics
        val centerX = metrics.widthPixels / 2f
        val centerY = metrics.heightPixels / 2f

        val swipeStartX: Float
        val swipeStartY: Float
        val swipeEndX: Float
        val swipeEndY: Float

        when (mode) {
            SwipeMode.CENTER_UP -> {
                swipeStartX = centerX
                swipeStartY = centerY
                swipeEndX = centerX
                swipeEndY = (centerY - metrics.heightPixels * 0.3f).coerceAtLeast(metrics.heightPixels * 0.15f)
            }
            SwipeMode.BOTTOM_UP -> {
                swipeStartX = centerX
                swipeStartY = metrics.heightPixels * 0.85f
                swipeEndX = centerX
                swipeEndY = metrics.heightPixels * 0.15f
            }
            SwipeMode.RIGHT_LEFT -> {
                swipeStartX = metrics.widthPixels * 0.85f
                swipeStartY = centerY
                swipeEndX = metrics.widthPixels * 0.15f
                swipeEndY = centerY
            }
        }

        val swipePath = Path().apply {
            moveTo(swipeStartX, swipeStartY)
            lineTo(swipeEndX, swipeEndY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(swipePath, 0L, SWIPE_DURATION_MILLIS))
            .build()

        val dispatched = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    Log.d(TAG, "accessibility swipe completed")
                    onGestureFinished?.invoke()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    Log.w(TAG, "accessibility swipe cancelled")
                    onGestureFinished?.invoke()
                }
            },
            mainHandler
        )
        if (dispatched) {
            touchFeedback.showGesture(swipeStartX, swipeStartY, swipeEndX, swipeEndY)
        }
        Log.d(TAG, "after accessibility gesture: dispatched=$dispatched, mode=${mode.label}, nextInterval=${intervalMillis()}ms")
        return dispatched
    }

    private fun intervalMillis(): Long {
        return intervalProvider().coerceAtLeast(1L) * 1000L
    }

    companion object {
        private const val TAG = "read_vedio"
        const val DEFAULT_INTERVAL_SECONDS = 32L
        const val DEFAULT_POLL_COUNT = 100
        private const val SWIPE_DURATION_MILLIS = 450L
        private const val GESTURE_FINISH_TIMEOUT_MILLIS = 3_000L
        private const val STATUS_TICK_MILLIS = 5_000L
    }
}
