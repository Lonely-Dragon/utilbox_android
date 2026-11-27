package com.sakura.gamebox.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class GameboxAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        instance = this
        Log.d(TAG, "accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
    }

    override fun onInterrupt() {
        Log.d(TAG, "accessibility service interrupted")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        isRunning = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        isRunning = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "GameboxA11yService"

        @Volatile
        private var instance: GameboxAccessibilityService? = null

        @Volatile
        var isRunning: Boolean = false
            private set

        /** OCR 规则触发的点击：在主线程派发手势。 */
        fun requestClick(x: Float, y: Float): Boolean {
            val service = instance ?: return false
            val path = Path().apply { moveTo(x, y); lineTo(x + 1f, y + 1f) }
            val gesture = GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(path, 0L, CLICK_DURATION_MILLIS)
                )
                .build()
            return service.dispatchGesture(gesture, null, service.mainHandler)
        }

        /** 脚本动作触发的滑动：从起点拖到终点，durationMs 毫秒。 */
        fun requestSwipe(
            x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long
        ): Boolean {
            val service = instance ?: return false
            val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
            val gesture = GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(1L))
                )
                .build()
            return service.dispatchGesture(gesture, null, service.mainHandler)
        }

        private const val CLICK_DURATION_MILLIS = 80L
    }
}
