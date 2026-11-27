package com.sakura.gamebox.net

import com.sakura.gamebox.ocr.OcrAction
import com.sakura.gamebox.ocr.OcrRule
import org.json.JSONObject

/**
 * 把后端脚本配置里的 rules（JSON 对象数组）转换成客户端可执行的 [OcrRule]。
 *
 * 后端规则结构（RuleIn）：
 *   { id, name, enabled, trigger: {type:"ocr", text:"关键词"}, actions: [RuleAction, ...] }
 * RuleAction:
 *   { type, by, text, x, y, x1, y1, x2, y2, ms }
 *
 * type 是自由字符串，这里按常见约定解析：
 *   click/tap            -> 点击（有坐标用坐标，by=text/坐标缺省用命中文字中心）
 *   swipe/drag           -> 滑动（x1,y1 -> x2,y2, ms 时长）
 *   sleep/wait/delay     -> 等待 ms
 *   input/input_text     -> 输入文本（手势无法直接输入，仅记录）
 *   stop                 -> 停止脚本
 * 未知类型记日志跳过。
 */
object ScriptRuleParser {

    fun parse(rules: List<JSONObject>): List<OcrRule> = rules.mapNotNull { parseRule(it) }

    private fun parseRule(o: JSONObject): OcrRule? {
        val id = o.optString("id")
        val name = o.optString("name").ifBlank { id }
        val enabled = o.optBoolean("enabled", true)
        val trigger = o.optJSONObject("trigger")
        val text = trigger?.optString("text").orEmpty()
        // 触发关键词：后端 text 可能用 | / ; / , 分隔多个关键词
        val keywords = text.split('|', ';', ',').map { it.trim() }.filter { it.isNotEmpty() }
        if (keywords.isEmpty()) return null

        val actionsArray = o.optJSONArray("actions")
        val actions = if (actionsArray == null) emptyList() else
            (0 until actionsArray.length()).mapNotNull { i ->
                parseAction(actionsArray.optJSONObject(i) ?: return@mapNotNull null)
            }
        return OcrRule(id, name, keywords, actions, enabled)
    }

    private fun parseAction(o: JSONObject): OcrAction? {
        val type = o.optString("type").trim().lowercase()
        return when (type) {
            "click", "tap" -> {
                val x = o.optInt("x", 0).toFloat()
                val y = o.optInt("y", 0).toFloat()
                OcrAction.Click(x, y) // <=0 由执行端改成命中文字中心
            }
            "swipe", "drag" -> OcrAction.Swipe(
                o.optInt("x1", 0).toFloat(),
                o.optInt("y1", 0).toFloat(),
                o.optInt("x2", 0).toFloat(),
                o.optInt("y2", 0).toFloat(),
                (o.optInt("ms", 400)).toLong().coerceAtLeast(1L)
            )
            "sleep", "wait", "delay" -> OcrAction.Sleep(
                o.optInt("ms", 0).toLong().coerceAtLeast(0L)
            )
            "input", "input_text" -> OcrAction.Input(o.optString("text"))
            "stop" -> OcrAction.Stop
            else -> null
        }
    }
}
