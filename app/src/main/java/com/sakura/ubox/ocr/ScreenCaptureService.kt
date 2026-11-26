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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private val videoEndDetector = VideoEndDetector()
    /** 「点击进入直播间口令」弹窗的滑动冷却时间（毫秒），防止弹窗未消失时反复上滑。 */
    private var liveRoomSwipeCooldownMs = 0L
    /** 上次为直播间口令跑 OCR 的时间；与逐帧 aHash 检测解耦后独立降频，避免每帧 1.5 秒全屏 OCR 拖垮取帧节奏。 */
    private var lastLiveRoomOcrMs = 0L
    private fun swipeUpForLiveRoom() {
        if (System.currentTimeMillis() < liveRoomSwipeCooldownMs) return
        liveRoomSwipeCooldownMs = System.currentTimeMillis() + LIVE_ROOM_SWIPE_COOLDOWN_MS
        if (UBoxAccessibilityService.requestSwipeUp()) {
            UBoxTaskStateBridge.setStatus("检测到「点击进入直播间」，已上滑")
        } else {
            UBoxTaskStateBridge.setStatus("检测到「点击进入直播间」，但无障碍服务未就绪")
        }
    }
    private val replaySwipeRunnable = Runnable {
        if (UBoxAccessibilityService.requestSwipeUp()) {
            UBoxTaskStateBridge.setStatus("视频播完 2 秒后已上滑，切换到下一条")
        } else {
            UBoxTaskStateBridge.setStatus("视频播完，但无障碍服务未就绪，无法上滑")
        }
        videoEndDetector.reset()
        mainHandler.postDelayed(periodicRunnable, KUAISHOU_REPLAY_INTERVAL_MS)
    }
    private val periodicRunnable = object : Runnable {
        override fun run() {
            if (!OcrState.isPeriodicEnabled || !isRunning) return
            // 视频结束检测：一旦判定播完就停止轮询，保留「视频已播完」状态。
            if (OcrState.actionMode == OcrState.MODE_DETECT_VIDEO_END && OcrState.isVideoFinished) {
                setPeriodicEnabled(false)
                UBoxTaskStateBridge.setStatus("视频已播完，定时识别已停止（再次点「识别」可重新开始）")
                return
            }
            // 悬浮窗展开/拖动动画期间暂停取帧，避免全屏截图抢占资源导致动画卡顿；
            // 短暂轮询等待，恢复后立刻取帧，不打断定时识别本身。
            if (OcrState.captureSuspended) {
                mainHandler.postDelayed(this, CAPTURE_SUSPEND_RECHECK_MS)
                return
            }
            val interval = if (OcrState.actionMode == OcrState.MODE_KUAISHOU_REPLAY) {
                KUAISHOU_REPLAY_INTERVAL_MS
            } else {
                OcrState.periodicIntervalMs
            }
            requestCaptureFrame(periodic = true)
            mainHandler.postDelayed(this, interval)
        }
    }

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
                // 悬浮窗点「识别」时未授权，授权成功后自动开启定时识别。
                if (OcrState.requestPeriodicAfterAuth) {
                    OcrState.requestPeriodicAfterAuth = false
                    setPeriodicEnabled(true)
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
            ACTION_SET_PERIODIC -> {
                // startForegroundService 必须在 5 秒内调用 startForeground，否则系统抛异常。
                startAsForeground()
                if (isRunning && capture?.isPrepared == true) {
                    setPeriodicEnabled(intent.getBooleanExtra(EXTRA_ENABLED, false))
                } else if (intent.getBooleanExtra(EXTRA_ENABLED, false)) {
                    // 投影未就绪但要开启：先请求授权，授权后自动开启。
                    OcrState.requestPeriodicAfterAuth = true
                    OcrState.setPeriodicEnabled(true)
                    CapturePermissionActivity.launch(this, captureNow = false)
                }
            }
        }
        return START_STICKY
    }

    /** 开启/关闭定时识别。开启时立即识别一次，之后按间隔轮询。 */
    private fun setPeriodicEnabled(enabled: Boolean) {
        mainHandler.removeCallbacks(periodicRunnable)
        mainHandler.removeCallbacks(replaySwipeRunnable)
        if (enabled) {
            videoEndDetector.reset()
            OcrState.setVideoFinished(false)
            lastLiveRoomOcrMs = 0L
        }
        OcrState.setPeriodicEnabled(enabled)
        if (enabled && isRunning && capture?.isPrepared == true) {
            val mode = when (OcrState.actionMode) {
                OcrState.MODE_DETECT_VIDEO_END -> "视频结束检测"
                OcrState.MODE_KUAISHOU_REPLAY -> "快手重播检测"
                else -> "游戏辅助"
            }
            val interval = if (OcrState.actionMode == OcrState.MODE_KUAISHOU_REPLAY) {
                KUAISHOU_REPLAY_INTERVAL_MS
            } else {
                OcrState.periodicIntervalMs
            }
            UBoxTaskStateBridge.setStatus("定时识别[$mode]已开启，每 ${interval / 1000} 秒一次")
            periodicRunnable.run()
        } else if (!enabled) {
            UBoxTaskStateBridge.setStatus("定时识别已关闭")
        }
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

    /** 复用已持有的投影截一帧 → 识别；手动点按才弹结果框，定时轮询只更新状态条。 */
    private fun requestCaptureFrame(periodic: Boolean = false) {
        val manager = capture
        if (manager == null || !manager.isPrepared) {
            Log.w(TAG, "requestCaptureFrame: no projection")
            return
        }
        manager.captureOneFrame(
            onFrame = { image -> handleFrame(image) },
            onDelivered = { if (!periodic) finishSingleShot() }
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
        // 悬浮窗动画期间直接丢弃已经取到的帧，尽快把 CPU/图形带宽让给动画。
        if (OcrState.captureSuspended) {
            image.close()
            busy.set(false)
            OcrState.setRecognizing(false)
            return
        }
        // 正在处理一帧：通知悬浮窗切换为「识别中」状态（轻量回调，不刷新设置页）。
        OcrState.setRecognizing(true)
        // 快手重播检测：画面指纹判循环/静止（毫秒级，逐帧跑）；
        // 「点击进入直播间」口令 OCR（~1.5 秒）与指纹检测解耦，独立降频到每 LIVE_ROOM_OCR_INTERVAL_MS 一次，
        // 避免全屏 OCR 每帧占据 CPU 导致取帧间隔被拉长、静止检测 3 秒累计被饿死。
        if (OcrState.actionMode == OcrState.MODE_KUAISHOU_REPLAY) {
            try {
                val nowMs = System.currentTimeMillis()
                val hash = videoEndDetector.fingerprint(image)
                if (hash != null && videoEndDetector.detect(hash, nowMs) != VideoEndDetector.DetectResult.NONE) {
                    onReplayDetected()
                }
                if (nowMs - lastLiveRoomOcrMs >= LIVE_ROOM_OCR_INTERVAL_MS) {
                    lastLiveRoomOcrMs = nowMs
                    ocrEngine?.let { engine ->
                        val texts = engine.recognize(image, LIVE_ROOM_OCR_SCALE)
                        if (isLiveRoomPrompt(texts)) swipeUpForLiveRoom()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "replay frame failed: ${e.message}")
            } finally {
                image.close()
                busy.set(false)
                OcrState.setRecognizing(false)
            }
            return
        }
        val engine = ocrEngine
        if (engine == null) {
            image.close()
            busy.set(false)
            OcrState.setRecognizing(false)
            return
        }
        try {
            val results = engine.recognize(image)
            val match = OcrRuleEngine(OcrState.rules).match(results).firstOrNull()
            OcrState.publish(results, match)
            handleOcrResult(match, results)
            if (isLiveRoomPrompt(results)) swipeUpForLiveRoom()
        } catch (e: Exception) {
            Log.w(TAG, "OCR frame failed: ${e.message}")
        } finally {
            image.close()
            busy.set(false)
            OcrState.setRecognizing(false)
        }
    }

    /** 画面出现「点击进入直播间」口令弹窗。 */
    private fun isLiveRoomPrompt(texts: List<RecognizedText>): Boolean =
        texts.any { it.text.contains("点击进入直播间") }

    /** 检测到视频循环回到开头：停止本轮轮询，2 秒后上滑切下一条。 */
    private fun onReplayDetected() {
        OcrState.setVideoFinished(true)
        UBoxTaskStateBridge.setStatus("检测到视频重播（本条已播完），2 秒后上滑")
        mainHandler.removeCallbacks(periodicRunnable)
        mainHandler.removeCallbacks(replaySwipeRunnable)
        mainHandler.postDelayed(replaySwipeRunnable, REPLAY_SWIPE_DELAY_MS)
    }

    /** OCR 完成后的回调：按当前动作模式决定是点击、滑动还是停止识别。 */
    private fun handleOcrResult(match: RuleMatch?, texts: List<RecognizedText>) {
        when (OcrState.actionMode) {
            OcrState.MODE_DETECT_VIDEO_END -> {
                val finished = isVideoEndDetected(match, texts)
                OcrState.setVideoFinished(finished)
                val keyword = texts.firstOrNull { t ->
                    listOf("视频已结束", "播放完毕", "已播完", "观看完成", "播放结束", "剧终", "任务完成")
                        .any { w -> t.text.contains(w) }
                }?.text ?: "片尾"
                UBoxTaskStateBridge.setStatus(
                    if (finished) "视频已播完（检测到「$keyword」）"
                    else "视频进行中（定时识别中）"
                )
            }
            else -> {
                if (match != null && OcrState.isEnabled) executeAction(match.action, match.matchedText)
            }
        }
    }

    /** 判断本帧是否出现「视频结束」标志：优先命中规则，否则文本关键词兜底。 */
    private fun isVideoEndDetected(match: RuleMatch?, texts: List<RecognizedText>): Boolean {
        val endWords = listOf("视频已结束", "播放完毕", "已播完", "观看完成", "播放结束", "剧终", "任务完成")
        // 视频结束检测模式下忽略游戏辅助规则，只看结束关键词本身。
        return texts.any { t -> endWords.any { w -> t.text.contains(w) } }
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
        mainHandler.removeCallbacks(periodicRunnable)
        mainHandler.removeCallbacks(replaySwipeRunnable)
        OcrState.setPeriodicEnabled(false)
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
        /** 快手重播检测轮询间隔：短视频循环周期短，1 秒一次。 */
        private const val KUAISHOU_REPLAY_INTERVAL_MS = 1_000L
        /** 判定重播后延迟多久上滑。 */
        private const val REPLAY_SWIPE_DELAY_MS = 2_000L
        /** 取帧被暂停时的复查间隔（越短越能快速恢复，但不能太密）。 */
        private const val CAPTURE_SUSPEND_RECHECK_MS = 100L
        /** 直播间口令弹窗检测：OCR 前把画面缩到该比例，降低定时识别开销。 */
        private const val LIVE_ROOM_OCR_SCALE = 0.4f
        /** 直播间口令 OCR 的最小间隔：与逐帧 aHash 解耦后独立降频，尽量少占 CPU。 */
        private const val LIVE_ROOM_OCR_INTERVAL_MS = 5_000L
        /** 命中直播间弹窗后再次允许上滑的最小间隔。 */
        private const val LIVE_ROOM_SWIPE_COOLDOWN_MS = 5_000L
        const val ACTION_START = "com.sakura.ubox.ocr.START"
        const val ACTION_CAPTURE = "com.sakura.ubox.ocr.CAPTURE"
        const val ACTION_STOP = "com.sakura.ubox.ocr.STOP"
        const val ACTION_SET_PERIODIC = "com.sakura.ubox.ocr.SET_PERIODIC"
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_SINGLE_SHOT = "single_shot"
        const val EXTRA_CAPTURE_NOW = "capture_now"

        @Volatile
        var isRunning: Boolean = false
            private set

        /** 从外部开启/关闭定时识别。 */
        fun setPeriodicEnabled(context: Context, enabled: Boolean) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_SET_PERIODIC
                putExtra(EXTRA_ENABLED, enabled)
            }
            context.startForegroundService(intent)
        }
    }
}
