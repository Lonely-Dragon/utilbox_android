package com.sakura.gamebox.ocr

/**
 * 一条 OCR 识别结果：文字 + 它在屏幕上的包围盒（屏幕绝对像素坐标）。
 */
data class RecognizedText(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

/** 规则命中后要执行的动作（由后端脚本配置解析而来）。 */
sealed interface OcrAction {
    /** 点击屏幕绝对坐标。x/y 任一 <= 0 时表示点击命中文字的中心（by=text）。 */
    data class Click(val x: Float, val y: Float) : OcrAction
    /** 从 (x1,y1) 滑动到 (x2,y2)，durationMs 毫秒。 */
    data class Swipe(
        val x1: Float, val y1: Float, val x2: Float, val y2: Float,
        val durationMs: Long
    ) : OcrAction
    /** 等待指定毫秒。 */
    data class Sleep(val ms: Long) : OcrAction
    /** 输入文本（手势无法直接输入时仅记录日志）。 */
    data class Input(val text: String) : OcrAction
    /** 停止当前脚本运行。 */
    data object Stop : OcrAction
}

/** 一条规则：识别文本包含任意关键词时按顺序触发 actions。 */
data class OcrRule(
    val id: String,
    val name: String,
    val keywords: List<String>,
    val actions: List<OcrAction>,
    val enabled: Boolean = true
) {
    fun matches(text: String): Boolean {
        if (!enabled || keywords.isEmpty()) return false
        return keywords.any { it.isNotBlank() && text.contains(it, ignoreCase = true) }
    }
}

/** 规则引擎输出：哪条规则命中、由哪条识别文字触发、要执行哪些动作。 */
data class RuleMatch(
    val rule: OcrRule,
    val matchedText: RecognizedText,
    val actions: List<OcrAction>
)

/**
 * 关键词规则引擎：按规则顺序匹配，同一条识别文字只触发第一条命中的规则，
 * 关键词为空或规则关闭都不会触发。
 */
class OcrRuleEngine(private val rules: List<OcrRule>) {
    fun match(results: List<RecognizedText>): List<RuleMatch> {
        val matches = mutableListOf<RuleMatch>()
        for (text in results) {
            val rule = rules.firstOrNull { it.matches(text.text) } ?: continue
            matches += RuleMatch(rule, text, rule.actions)
        }
        return matches
    }
}
