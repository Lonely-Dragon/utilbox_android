package com.sakura.ubox.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.sakura.ubox.UBoxTaskState
import com.sakura.ubox.act.read_vedio

class UBoxAccessibilityService : AccessibilityService() {

    private var reader: read_vedio? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val taskListener: (Boolean) -> Unit = { running ->
        if (running) {
            reader?.start()
        } else {
            reader?.stop()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        instance = this
        reader = read_vedio(this)
        UBoxTaskState.addListener(taskListener)
        if (UBoxTaskState.isRunning) {
            reader?.start()
        }
        Log.d(TAG, "accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
    }

    override fun onInterrupt() {
        Log.d(TAG, "accessibility service interrupted")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        UBoxTaskState.removeListener(taskListener)
        reader?.release()
        reader = null
        instance = null
        isRunning = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        UBoxTaskState.removeListener(taskListener)
        reader?.release()
        reader = null
        instance = null
        isRunning = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "UBoxA11yService"

        @Volatile
        private var instance: UBoxAccessibilityService? = null

        @Volatile
        var isRunning: Boolean = false
            private set

        fun requestSwipeUp(): Boolean {
            val service = instance ?: return false
            return service.reader?.performSwipeUp() ?: false
        }

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

        /** OCR 命中停止规则时暂停自动任务。 */
        fun stopOcrDrivenTask() {
            UBoxTaskState.setRunning(false)
        }

        private const val CLICK_DURATION_MILLIS = 80L
    }
}