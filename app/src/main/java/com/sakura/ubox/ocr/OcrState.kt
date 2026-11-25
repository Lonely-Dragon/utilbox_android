package com.sakura.ubox.ocr

import android.content.Context
import android.content.SharedPreferences

/**
 * OCR 辅助功能的全局状态：规则、开关、最近一次识别结果与命中记录。
 * 规则与开关做轻量持久化；识别结果只在内存中保留。
 */
object OcrState {
    private const val PREFS_NAME = "ubox_ocr_settings"
    private const val KEY_ENABLED = "ocr_enabled"
    private const val KEY_RULES = "ocr_rules"

    private var prefs: SharedPreferences? = null
    private val listeners = mutableListOf<() -> Unit>()

    @Volatile
    var isEnabled: Boolean = false
        private set

    @Volatile
    var isCapturing: Boolean = false
        private set

    /** 定时识别开关（内存态，不持久化）。 */
    @Volatile
    var isPeriodicEnabled: Boolean = false
        private set

    /** 授权成功后是否自动开启定时识别。 */
    @Volatile
    var requestPeriodicAfterAuth: Boolean = false

    /** 定时识别间隔（毫秒），设置页可调。 */
    @Volatile
    var periodicIntervalMs: Long = DEFAULT_PERIODIC_INTERVAL_MS
        private set

    /** 定时识别的动作模式：游戏辅助（命中规则执行动作）/ 视频结束检测。 */
    @Volatile
    var actionMode: Int = MODE_GAME_HELPER
        private set

    /** 本轮定时识别是否已判定「视频播完」。 */
    @Volatile
    var isVideoFinished: Boolean = false
        private set

    /**
     * 临时暂停取帧（悬浮窗展开/拖动动画期间置 true）。
     * 不通知监听器，避免动画期间额外触发 UI 重绘。
     */
    @Volatile
    var captureSuspended: Boolean = false
        private set

    fun setCaptureSuspended(value: Boolean) {
        captureSuspended = value
    }

    /**
     * 是否正在处理某一帧（OCR/画面指纹识别进行中）。
     * 高频变化，单独用 recognitionListeners 通知，不走规则/开关的 notifyListeners，
     * 避免每帧都触发设置页的规则列表刷新。
     */
    @Volatile
    var isRecognizing: Boolean = false
        private set

    private val recognitionListeners = mutableListOf<() -> Unit>()

    fun setRecognizing(value: Boolean) {
        if (isRecognizing == value) return
        isRecognizing = value
        recognitionListeners.toList().forEach { it() }
    }

    fun addRecognitionListener(listener: () -> Unit) {
        recognitionListeners += listener
    }

    fun removeRecognitionListener(listener: () -> Unit) {
        recognitionListeners -= listener
    }

    fun setActionMode(mode: Int) {
        if (actionMode == mode) return
        actionMode = mode
        notifyListeners()
    }

    fun setVideoFinished(value: Boolean) {
        if (isVideoFinished == value) return
        isVideoFinished = value
        notifyListeners()
    }

    const val MODE_GAME_HELPER = 0
    const val MODE_DETECT_VIDEO_END = 1
    /** 快手自动重播：检测画面回到开头（循环点），判定为"播完"。 */
    const val MODE_KUAISHOU_REPLAY = 2

    fun setPeriodicEnabled(value: Boolean) {
        if (isPeriodicEnabled == value) return
        isPeriodicEnabled = value
        notifyListeners()
    }

    fun setPeriodicIntervalMs(value: Long) {
        periodicIntervalMs = value.coerceIn(1_000L, 3_600_000L)
    }

    @Volatile
    var lastResults: List<RecognizedText> = emptyList()
        private set

    @Volatile
    var lastMatch: RuleMatch? = null
        private set

    @Volatile
    var rules: List<OcrRule> = defaultRules()
        private set

    fun init(context: Context) {
        val stored = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = stored
        isEnabled = stored.getBoolean(KEY_ENABLED, false)
        stored.getString(KEY_RULES, null)?.let { decodeRules(it) }?.let { rules = it }
    }

    fun setEnabled(value: Boolean) {
        if (isEnabled == value) return
        isEnabled = value
        prefs?.edit()?.putBoolean(KEY_ENABLED, value)?.apply()
        notifyListeners()
    }

    fun setCapturing(value: Boolean) {
        if (isCapturing == value) return
        isCapturing = value
        notifyListeners()
    }

    fun setRules(value: List<OcrRule>) {
        rules = value
        prefs?.edit()?.putString(KEY_RULES, encodeRules(value))?.apply()
        notifyListeners()
    }

    fun publish(results: List<RecognizedText>, match: RuleMatch?) {
        lastResults = results
        lastMatch = match
        notifyListeners()
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun notifyListeners() {
        listeners.toList().forEach { it() }
    }

    /** 规则序列化：id|name|enabled|actionType|actionArgs|关键词1;关键词2 */
    private fun encodeRules(value: List<OcrRule>): String = value.joinToString("\n") { rule ->
        val (type, args) = when (val action = rule.action) {
            is OcrAction.Click -> "CLICK" to "${action.x},${action.y}"
            OcrAction.Swipe -> "SWIPE" to ""
            OcrAction.Stop -> "STOP" to ""
        }
        listOf(
            rule.id, rule.name, if (rule.enabled) "1" else "0", type, args,
            rule.keywords.joinToString(";")
        ).joinToString("|")
    }

    private fun decodeRules(raw: String): List<OcrRule>? = runCatching {
        raw.lines().filter { it.isNotBlank() }.map { line ->
            val parts = line.split("|")
            val id = parts[0]
            val name = parts[1]
            val enabled = parts[2] == "1"
            val action = when (parts[3]) {
                "CLICK" -> {
                    val coords = parts[4].split(",")
                    OcrAction.Click(coords[0].toFloat(), coords[1].toFloat())
                }
                "SWIPE" -> OcrAction.Swipe
                "STOP" -> OcrAction.Stop
                else -> OcrAction.Swipe
            }
            val keywords = parts.getOrNull(5).orEmpty()
                .split(";").filter { it.isNotBlank() }
            OcrRule(id, name, keywords, action, enabled)
        }
    }.getOrNull()

    fun defaultRules(): List<OcrRule> = listOf(
        OcrRule("click_continue", "点击继续/领取", listOf("继续", "领取", "确定", "下一步"), OcrAction.Click(0f, 0f)),
        OcrRule("swipe_hint", "检测到滑动提示", listOf("上滑", "下滑", "翻页"), OcrAction.Swipe),
        OcrRule("stop_fail", "任务失败停止", listOf("任务失败", "挑战失败", "失败"), OcrAction.Stop)
    )

    const val DEFAULT_PERIODIC_INTERVAL_MS = 5_000L
}
