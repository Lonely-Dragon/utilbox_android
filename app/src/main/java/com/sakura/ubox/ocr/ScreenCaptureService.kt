package com.sakura.ubox.ocr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.Image
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.sakura.ubox.MainActivity
import com.sakura.ubox.R
import com.sakura.ubox.service.UBoxAccessibilityService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OCR 截屏识别前台服务（常驻模式）：
 *  - 启动授权一次后长期持有 MediaProjection，前台通知常驻（Android 强制要求）。
 *  - 之后每次点 OCR 走 ACTION_CAPTURE，复用已持有的投影截一帧、识别、弹框展示结果，
 *    不再请求授权；截帧资源用完即释放，不占 CPU。
 *  - 进程被杀 / 手机重启 / 用户停止投放后投影失效，需重新走 ACTION_START 授权。
 */
class ScreenCaptureService : Service() {

    private var capture: ScreenCaptureManager? = null
    private var ocrEngine: OcrEngine? = null
    private val busy = AtomicBoolean(false)
    private var screenWidth = 0
    private var screenHeight = 0
    private var densityDpi = 0
    private var rotation = 0

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
                // 授权时若要求立刻截一帧（App 首次启动场景），复用新投影马上识别。
                if (intent.getBooleanExtra(EXTRA_CAPTURE_NOW, false)) {
                    requestCaptureFrame()
                }
            }
            ACTION_CAPTURE -> {
                if (isRunning && capture?.isPrepared == true) {
                    requestCaptureFrame()
                } else {
                    Log.w(TAG, "ACTION_CAPTURE but projection not ready; need re-authorization")
                    stopSelf()
                }
            }
            ACTION_STOP -> stopSelf()
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
        // Android 14+ 只允许对同一个 MediaProjection 调用一次 createVirtualDisplay，
        // 所以 VirtualDisplay + ImageReader 在这里一次性建好并全程复用，取帧时不再重建。
        capture = ScreenCaptureManager(this).also {
            it.prepare(projection, screenWidth, screenHeight, densityDpi) {
                // 投影被系统/用户停止：整个服务失效，清理并退出，下次需重新授权。
                Log.w(TAG, "projection lost, stopping service")
                stopSelf()
            }
        }
        OcrState.setCapturing(true)
        isRunning = true
        Log.d(TAG, "projection prepared (resident): ${screenWidth}x${screenHeight}, rotation=$rotation")
    }

    /** 复用已持有的投影截一帧 → 识别 → 弹框。 */
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

    /** 一帧识别完成后弹框展示结果；服务不停止，投影继续保活。 */
    private fun finishSingleShot() {
        val texts = OcrState.lastResults.map { it.text }
        val matchName = OcrState.lastMatch?.rule?.name
        Log.d(TAG, "single frame delivered: ${texts.size} texts, match=$matchName")
        OcrResultActivity.launch(this, texts, matchName)
    }

    /** 截屏回调在 ImageReader 后台线程；忙时丢弃新帧。 */
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

            if (results.isNotEmpty()) {
                UBoxTaskStateBridge.setStatus(
                    "OCR 识别 ${results.size} 处：" + results.take(2).joinToString(" / ") { it.text }
                )
            }
            if (match != null && OcrState.isEnabled) {
                executeAction(match.action, match.matchedText)
            }
        } catch (e: Exception) {
            Log.w(TAG, "OCR frame failed: ${e.message}")
        } finally {
            image.close()
            busy.set(false)
        }
    }

    private fun executeAction(action: OcrAction, matched: RecognizedText) {
        when (action) {
            is OcrAction.Click -> {
                val x = if (action.x > 0f) action.x else (matched.left + matched.right) / 2f
                val y = if (action.y > 0f) action.y else (matched.top + matched.bottom) / 2f
                UBoxAccessibilityService.requestClick(x, y)
                UBoxTaskStateBridge.setStatus("命中「${matched.text}」→ 点击 (${x.toInt()}, ${y.toInt()})")
            }
            OcrAction.Swipe -> {
                UBoxAccessibilityService.requestSwipeUp()
                UBoxTaskStateBridge.setStatus("命中「${matched.text}」→ 滑动")
            }
            OcrAction.Stop -> {
                UBoxAccessibilityService.stopOcrDrivenTask()
                UBoxTaskStateBridge.setStatus("命中「${matched.text}」→ 停止任务")
            }
        }
    }

    override fun onDestroy() {
        busy.set(false)
        capture?.release()
        capture = null
        ocrEngine?.close()
        ocrEngine = null
        OcrState.setCapturing(false)
        isRunning = false
        super.onDestroy()
    }

    private fun startAsForeground() {
        val channelId = ensureChannel()
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle("UBox OCR 待命")
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
                NotificationChannel(CHANNEL_ID, "UBox OCR 截屏", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return CHANNEL_ID
    }

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "ubox_ocr_capture"
        private const val NOTIFICATION_ID = 1002
        const val ACTION_START = "com.sakura.ubox.ocr.START"
        const val ACTION_CAPTURE = "com.sakura.ubox.ocr.CAPTURE"
        const val ACTION_STOP = "com.sakura.ubox.ocr.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_SINGLE_SHOT = "single_shot"
        const val EXTRA_CAPTURE_NOW = "capture_now"

        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
