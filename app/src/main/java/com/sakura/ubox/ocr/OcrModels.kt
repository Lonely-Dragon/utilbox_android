package com.sakura.ubox.ocr

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

/** 规则命中后要执行的动作。 */
sealed interface OcrAction {
    /** 点击屏幕绝对坐标。x/y 任一 <= 0 时表示点击命中文字的中心。 */
    data class Click(val x: Float, val y: Float) : OcrAction
    /** 按当前配置的滑动模式执行滑动。 */
    data object Swipe : OcrAction
    /** 停止当前自动任务（并可选返回桌面）。 */
    data object Stop : OcrAction
}

/** 一条规则：识别文本包含任意关键词时触发动作。 */
data class OcrRule(
    val id: String,
    val name: String,
    val keywords: List<String>,
    val action: OcrAction,
    val enabled: Boolean = true
) {
    fun matches(text: String): Boolean {
        if (!enabled || keywords.isEmpty()) return false
        return keywords.any { it.isNotBlank() && text.contains(it, ignoreCase = true) }
    }
}

/** 规则引擎输出：哪条规则命中、由哪条识别文字触发、执行什么动作。 */
data class RuleMatch(
    val rule: OcrRule,
    val matchedText: RecognizedText,
    val action: OcrAction
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
            matches += RuleMatch(rule, text, rule.action)
        }
        return matches
    }
}
