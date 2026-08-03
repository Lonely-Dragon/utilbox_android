package com.sakura.ubox.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.sakura.ubox.MainActivity
import com.sakura.ubox.R
import com.sakura.ubox.UBoxTaskState

class FloatingWindowService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var layoutParams: WindowManager.LayoutParams
    private var floatingView: View? = null
    private var bubbleView: View? = null
    private var bubbleLabel: TextView? = null

    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialWindowX = 0
    private var initialWindowY = 0
    private var dragThreshold = 0

    private val taskListener: (Boolean) -> Unit = { running ->
        bubbleView?.post { updateBubbleState(running) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        UBoxTaskState.addListener(taskListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (floatingView == null) addFloatingView()
        isRunning = true
        return START_STICKY
    }

    override fun onDestroy() {
        UBoxTaskState.removeListener(taskListener)
        removeFloatingView()
        isRunning = false
        super.onDestroy()
    }

    private fun addFloatingView() {
        val view = buildFloatingView()
        floatingView = view
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(120)
        }
        windowManager.addView(view, layoutParams)
    }

    private fun buildFloatingView(): View {
        val label = TextView(this).apply {
            text = if (UBoxTaskState.isRunning) "运行中" else "上划"
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(12), dp(18), dp(12))
            isClickable = false
            isFocusable = false
            isLongClickable = false
        }

        bubbleLabel = label

        dragThreshold = dp(8)

        val bubble = FrameLayout(this).apply {
            background = bubbleBackground(if (UBoxTaskState.isRunning) COLOR_RED else COLOR_BLUE)
            addView(label)
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        initialWindowX = this@FloatingWindowService.layoutParams.x
                        initialWindowY = this@FloatingWindowService.layoutParams.y
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        this@FloatingWindowService.layoutParams.x = initialWindowX + dx
                        this@FloatingWindowService.layoutParams.y = initialWindowY + dy
                        windowManager.updateViewLayout(floatingView, this@FloatingWindowService.layoutParams)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val dx = Math.abs(event.rawX - initialTouchX)
                        val dy = Math.abs(event.rawY - initialTouchY)
                        if (dx < dragThreshold && dy < dragThreshold) {
                            toggleAutoSwipeTask()
                        }
                        true
                    }
                    else -> false
                }
            }
        }
        bubbleView = bubble
        return bubble
    }

    private fun updateBubbleState(running: Boolean) {
        bubbleLabel?.text = if (running) "运行中" else "上划"
        bubbleView?.background = bubbleBackground(if (running) COLOR_RED else COLOR_BLUE)
    }

    private fun toggleAutoSwipeTask() {
        if (!UBoxAccessibilityService.isRunning) {
            Toast.makeText(this, "请先开启 UBox 无障碍服务", Toast.LENGTH_SHORT).show()
            return
        }
        UBoxTaskState.toggle()
    }

    private fun bubbleBackground(color: String): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(24).toFloat()
            setColor(Color.parseColor(color))
        }
    }

    private fun removeFloatingView() {
        floatingView?.let { runCatching { windowManager.removeView(it) } }
        floatingView = null
        bubbleView = null
        bubbleLabel = null
    }

    private fun startAsForeground() {
        val channelId = ensureChannel()
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle(getString(R.string.floating_notification_title))
            .setContentText(getString(R.string.floating_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel(): String {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.floating_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
        return CHANNEL_ID
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()

    companion object {
        private const val CHANNEL_ID = "ubox_floating_window"
        private const val NOTIFICATION_ID = 1001
        private const val COLOR_RED = "#CCE53935"
        private const val COLOR_BLUE = "#CC1E88E5"

        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
