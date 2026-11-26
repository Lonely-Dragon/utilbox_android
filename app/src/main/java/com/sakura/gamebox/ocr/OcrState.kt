package com.sakura.gamebox.ocr

import android.content.Context
import android.content.SharedPreferences

/**
 * OCR 辅助功能的全局状态：规则、开关、最近一次识别结果与命中记录。
 * 规则与开关做轻量持久化；识别结果只在内存中保留。
 */
object OcrState {
    private const val PREFS_NAME = "gamebox_ocr_settings"
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
}
