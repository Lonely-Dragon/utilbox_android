package com.sakura.gamebox.ocr

import android.content.Context
import android.content.SharedPreferences

/**
 * OCR 脚本运行时的全局状态：规则（来自设置里确定的脚本）、最近一次识别结果与命中记录。
 * 规则做轻量持久化；识别结果只在内存中保留。
 * App 不再内置默认规则，规则完全由「设置 → 确定」下发的脚本提供。
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
    var rules: List<OcrRule> = emptyList()
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

    /**
     * 设置脚本规则。空列表表示「解锁」——没有任何规则参与 OCR 匹配。
     */
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

    /** 规则序列化：id|name|enabled|actions(;分隔)|关键词(;分隔) */
    private fun encodeRules(value: List<OcrRule>): String = value.joinToString("\n") { rule ->
        val actions = rule.actions.joinToString(";") { action ->
            when (action) {
                is OcrAction.Click -> "CLICK:${action.x},${action.y}"
                is OcrAction.Swipe ->
                    "SWIPE:${action.x1},${action.y1},${action.x2},${action.y2},${action.durationMs}"
                is OcrAction.Sleep -> "SLEEP:${action.ms}"
                is OcrAction.Input -> "INPUT:${action.text}"
                OcrAction.Stop -> "STOP:"
            }
        }
        listOf(
            rule.id, rule.name, if (rule.enabled) "1" else "0", actions,
            rule.keywords.joinToString(";")
        ).joinToString("|")
    }

    private fun decodeRules(raw: String): List<OcrRule>? = runCatching {
        raw.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size < 4) return@mapNotNull null
            val id = parts[0]
            val name = parts[1]
            val enabled = parts[2] == "1"
            val actions = parts[3].split(";").filter { it.isNotBlank() }.mapNotNull { a ->
                runCatching {
                    val colon = a.indexOf(':')
                    if (colon < 0) return@runCatching null
                    val type = a.substring(0, colon)
                    val args = a.substring(colon + 1)
                    when (type) {
                        "CLICK" -> {
                            val c = args.split(",")
                            OcrAction.Click(c[0].toFloat(), c[1].toFloat())
                        }
                        "SWIPE" -> {
                            val c = args.split(",")
                            OcrAction.Swipe(
                                c[0].toFloat(), c[1].toFloat(), c[2].toFloat(), c[3].toFloat(),
                                c.getOrNull(4)?.toLong() ?: 400L
                            )
                        }
                        "SLEEP" -> OcrAction.Sleep(args.toLongOrNull() ?: 0L)
                        "INPUT" -> OcrAction.Input(args)
                        "STOP" -> OcrAction.Stop
                        else -> null
                    }
                }.getOrNull()
            }
            val keywords = parts.getOrNull(4).orEmpty().split(";").filter { it.isNotBlank() }
            OcrRule(id, name, keywords, actions, enabled)
        }
    }.getOrNull()
}
