package com.sakura.gamebox.ocr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.Image
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.sakura.gamebox.GameboxTaskState
import com.sakura.gamebox.MainActivity
import com.sakura.gamebox.R
import com.sakura.gamebox.net.ScriptConfigStore
import com.sakura.gamebox.service.GameboxAccessibilityService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OCR 截屏识别前台服务：
 *  - 启动授权一次后长期持有 MediaProjection，前台通知常驻（Android 强制要求）。
 *  - 点 OCR 走 ACTION_CAPTURE，复用投影截一帧、识别、弹框展示结果。
 *  - 点悬浮菜单「开始」走 ACTION_RUN_SCRIPT：按设置中确定的脚本规则持续截屏识别，
 *    命中规则就派发脚本动作（点击/滑动/等待），点「停止」结束。
 *  - 投影失效 / 方向尺寸变化 / 停帧时需重新授权。
 */
class ScreenCaptureService : android.app.Service() {

    private var capture: ScreenCaptureManager? = null
    private var ocrEngine: OcrEngine? = null
    private val busy = AtomicBoolean(false)
    private var screenWidth = 0
    private var screenHeight = 0
    private var densityDpi = 0
    private var rotation = 0

    /** 脚本运行调度（主线程派发手势，工作线程外只做 post）。 */
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scriptRunnable = Runnable { runScriptFrame() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        OcrState.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startAsForeground()
                // Activity.RESULT_OK 的值就是 -1，不能用 -1 作为「未提供」哨兵值。
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (code == Int.MIN_VALUE || data == null) {
                    Log.w(TAG, "missing projection permission result: code=$code, data=$data")
                    stopSelf()
                    return START_NOT_STICKY
                }
                prepareCapture(code, data)
                if (intent.getBooleanExtra(EXTRA_CAPTURE_NOW, false)) {
                    requestCaptureFrame()
                }
                if (intent.getBooleanExtra(EXTRA_RUN_SCRIPT, false)) {
                    startScriptLoop()
                }
            }
            ACTION_CAPTURE -> {
                if (isRunning && capture?.isPrepared == true && !needsReauthorization()) {
                    requestCaptureFrame()
                } else {
                    Log.w(TAG, "ACTION_CAPTURE but projection not ready; requesting re-authorization")
                    capture?.release()
                    capture = null
                    isRunning = false
                    captureReady = false
                    OcrState.setCapturing(false)
                    CapturePermissionActivity.launch(this, captureNow = true)
                }
            }
            ACTION_RUN_SCRIPT -> {
                startAsForeground()
                if (isRunning && capture?.isPrepared == true && !needsReauthorization()) {
                    startScriptLoop()
                } else {
                    Log.w(TAG, "ACTION_RUN_SCRIPT but projection not ready; requesting re-authorization")
                    capture?.release()
                    capture = null
                    isRunning = false
                    captureReady = false
                    OcrState.setCapturing(false)
                    CapturePermissionActivity.launch(this, captureNow = false, runScript = true)
                }
            }
            ACTION_STOP_SCRIPT -> stopScriptLoop()
            ACTION_STOP -> {
                stopScriptLoop()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun prepareCapture(resultCode: Int, data: Intent) {
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = pm.getMediaProjection(resultCode, data)

        @Suppress("DEPRECATION")
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        val display = windowManager.defaultDisplay
        display.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        densityDpi = metrics.densityDpi
        rotation = display.rotation

        if (ocrEngine == null) ocrEngine = OcrEngine(this)
        capture = ScreenCaptureManager(this).also {
            it.prepare(projection, screenWidth, screenHeight, densityDpi) {
                Log.w(TAG, "projection lost, stopping service")
                captureReady = false
                stopScriptLoop()
                stopSelf()
            }
        }
        OcrState.setCapturing(true)
        isRunning = true
        captureReady = true
        Log.d(TAG, "projection prepared (resident): ${screenWidth}x${screenHeight}, rotation=$rotation")
    }

    private fun needsReauthorization(): Boolean {
        @Suppress("DEPRECATION")
        val display = (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        val sizeChanged = metrics.widthPixels != screenWidth || metrics.heightPixels != screenHeight ||
            display.rotation != rotation
        if (sizeChanged) {
            Log.d(TAG, "needsReauthorization: orientation/size changed")
            return true
        }
        if (capture?.isFrameStale(6000L) == true) {
            Log.w(TAG, "needsReauthorization: virtual display frame stale > 6s")
            return true
        }
        return false
    }

    /** 单次 OCR：截一帧 → 识别 → 弹框展示结果。 */
    private fun requestCaptureFrame() {
        val manager = capture
        if (manager == null || !manager.isPrepared) {
            Log.w(TAG, "requestCaptureFrame: no projection")
            return
        }
        manager.captureOneFrame(
            onFrame = { image -> handleFrame(image) },
            onDelivered = { finishSingleShot() }
        )
    }

    private fun finishSingleShot() {
        val texts = OcrState.lastResults.map { it.text }
        val matchName = OcrState.lastMatch?.rule?.name
        Log.d(TAG, "single frame delivered: ${texts.size} texts, match=$matchName")
        OcrResultActivity.launch(this, texts, matchName)
    }

    /** 单次 OCR 的帧处理：识别 + 规则匹配（不派发动作）。 */
    private fun handleFrame(image: Image) {
        if (!busy.compareAndSet(false, true)) {
            image.close()
            return
        }
        val engine = ocrEngine
        if (engine == null) {
            image.close()
            busy.set(false)
            return
        }
        try {
            val results = engine.recognize(image)
            val match = OcrRuleEngine(OcrState.rules).match(results).firstOrNull()
            OcrState.publish(results, match)
        } catch (e: Exception) {
            Log.w(TAG, "OCR frame failed: ${e.message}")
        } finally {
            image.close()
            busy.set(false)
        }
    }

    // ---------------- 脚本运行 ----------------

    private fun startScriptLoop() {
        if (isScriptRunning) return
        // 以「设置里已确定脚本」为准，而不是规则条数：测试脚本可能暂无规则，
        // 但既然已确定就允许运行（空规则=只持续识别、不派发动作）。
        if (!ScriptConfigStore.scriptApplied) {
            GameboxTaskState.setStatus("请先在设置中确定脚本")
            return
        }
        val ruleCount = OcrState.rules.size
        isScriptRunning = true
        OcrState.setEnabled(true)
        GameboxTaskState.setStatus("脚本运行中：$ruleCount 条规则")
        Log.d(TAG, "script loop started: $ruleCount rules")
        mainHandler.removeCallbacks(scriptRunnable)
        scriptRunnable.run()
    }

    private fun stopScriptLoop() {
        if (!isScriptRunning) return
        isScriptRunning = false
        OcrState.setEnabled(false)
        mainHandler.removeCallbacks(scriptRunnable)
        mainHandler.removeCallbacksAndMessages(ACTION_TOKEN)
        GameboxTaskState.setStatus("空闲")
        Log.d(TAG, "script loop stopped")
    }

    /** 脚本模式下一帧：请求截帧 → 识别 → 命中则派发动作 → 延时下一帧。 */
    private fun runScriptFrame() {
        if (!isScriptRunning) return
        val manager = capture
        if (manager == null || !manager.isPrepared) {
            GameboxTaskState.setStatus("截屏投影不可用，请重新授权")
            isScriptRunning = false
            return
        }
        manager.captureOneFrame(
            onFrame = { image -> handleScriptFrame(image) },
            onDelivered = {
                if (isScriptRunning) {
                    val delay = ScriptConfigStore.pollIntervalMs.coerceAtLeast(100L)
                    mainHandler.postDelayed(scriptRunnable, delay)
                }
            }
        )
    }

    private fun handleScriptFrame(image: Image) {
        if (!busy.compareAndSet(false, true)) {
            image.close()
            return
        }
        val engine = ocrEngine
        if (engine == null) {
            image.close()
            busy.set(false)
            return
        }
        try {
            val results = engine.recognize(image)
            val match = OcrRuleEngine(OcrState.rules).match(results).firstOrNull()
            OcrState.publish(results, match)
            if (match != null) {
                GameboxTaskState.setStatus("命中：${match.rule.name}")
                dispatchActions(match.actions, 0, match.matchedText)
            } else {
                GameboxTaskState.setStatus("脚本运行中")
            }
        } catch (e: Exception) {
            Log.w(TAG, "script OCR frame failed: ${e.message}")
        } finally {
            image.close()
            busy.set(false)
        }
    }

    /** 按顺序执行命中规则的动作；Sleep 用延时串接，保证手势在主线程派发。 */
    private fun dispatchActions(
        actions: List<OcrAction>,
        index: Int,
        matchedText: RecognizedText?
    ) {
        if (!isScriptRunning || index >= actions.size) return
        mainHandler.postAtTime({
            if (!isScriptRunning) return@postAtTime
            when (val action = actions[index]) {
                is OcrAction.Click -> {
                    val x = if (action.x > 0f) action.x else matchedText?.run { (left + right) / 2f } ?: 0f
                    val y = if (action.y > 0f) action.y else matchedText?.run { (top + bottom) / 2f } ?: 0f
                    if (x > 0f && y > 0f) GameboxAccessibilityService.requestClick(x, y)
                    scheduleNextAction(actions, index, ACTION_GAP_MS)
                }
                is OcrAction.Swipe -> {
                    GameboxAccessibilityService.requestSwipe(
                        action.x1, action.y1, action.x2, action.y2, action.durationMs
                    )
                    scheduleNextAction(actions, index, ACTION_GAP_MS)
                }
                is OcrAction.Sleep -> scheduleNextAction(actions, index, action.ms)
                is OcrAction.Input -> {
                    Log.w(TAG, "input action not supported via gesture: ${action.text}")
                    scheduleNextAction(actions, index, ACTION_GAP_MS)
                }
                OcrAction.Stop -> stopScriptLoop()
            }
        }, ACTION_TOKEN, SystemClock.uptimeMillis())
    }

    private fun scheduleNextAction(actions: List<OcrAction>, index: Int, delayMs: Long) {
        mainHandler.postAtTime({
            dispatchActions(actions, index + 1, null)
        }, ACTION_TOKEN, SystemClock.uptimeMillis() + delayMs.coerceAtLeast(0L))
    }

    override fun onDestroy() {
        stopScriptLoop()
        capture?.release()
        capture = null
        ocrEngine?.close()
        ocrEngine = null
        OcrState.setCapturing(false)
        isRunning = false
        captureReady = false
        isScriptRunning = false
        super.onDestroy()
    }

    private fun startAsForeground() {
        val channelId = ensureChannel()
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle("Gamebox OCR 待命")
            .setContentText("截屏权限已就绪，点击 OCR 即可识别")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel(): String {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Gamebox OCR 截屏", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return CHANNEL_ID
    }

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "gamebox_ocr_capture"
        private const val NOTIFICATION_ID = 1002
        private const val ACTION_TOKEN = "script_action"
        private const val ACTION_GAP_MS = 150L
        const val ACTION_START = "com.sakura.gamebox.ocr.START"
        const val ACTION_CAPTURE = "com.sakura.gamebox.ocr.CAPTURE"
        const val ACTION_STOP = "com.sakura.gamebox.ocr.STOP"
        const val ACTION_RUN_SCRIPT = "com.sakura.gamebox.ocr.RUN_SCRIPT"
        const val ACTION_STOP_SCRIPT = "com.sakura.gamebox.ocr.STOP_SCRIPT"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_CAPTURE_NOW = "capture_now"
        const val EXTRA_RUN_SCRIPT = "run_script"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var isScriptRunning: Boolean = false
            private set

        /** 投影是否已就绪（悬浮窗据此决定复用还是重新授权）。 */
        @Volatile
        var captureReady: Boolean = false
            private set

        fun startScript(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_RUN_SCRIPT
            }
            context.startForegroundService(intent)
        }

        fun stopScript(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP_SCRIPT
            }
            context.startForegroundService(intent)
        }
    }
}
