package com.sakura.gamebox.net

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 设置页「激活码 + 脚本列表」的本地持久化。
 * 与 OcrState 一样使用 SharedPreferences，进程启动时由 MainActivity 调 init()。
 */
object ScriptConfigStore {
    private const val PREFS_NAME = "gamebox_script_config"
    private const val KEY_ACTIVATION_CODE = "activation_code"
    private const val KEY_SCRIPTS = "scripts"
    private const val KEY_SELECTED_ID = "selected_script_id"
    private const val KEY_SCRIPT_APPLIED = "script_applied"
    private const val KEY_APPLIED_NAME = "applied_script_name"
    private const val KEY_POLL_INTERVAL = "script_poll_interval_ms"
    private const val KEY_LOOP_DELAY = "script_loop_delay_ms"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var activationCode: String
        get() = prefs?.getString(KEY_ACTIVATION_CODE, "").orEmpty()
        set(value) {
            prefs?.edit()?.putString(KEY_ACTIVATION_CODE, value)?.apply()
        }

    var scripts: List<ScriptBrief>
        get() {
            val raw = prefs?.getString(KEY_SCRIPTS, null) ?: return emptyList()
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    ScriptBrief(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        game = o.optString("game"),
                        groupId = o.optString("group_id"),
                        scriptVersion = o.optString("script_version"),
                        version = o.optInt("version"),
                        updatedAt = if (o.isNull("updated_at")) null else o.optString("updated_at")
                    )
                }
            }.getOrDefault(emptyList())
        }
        set(value) {
            val arr = JSONArray()
            value.forEach { s ->
                arr.put(JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("game", s.game)
                    put("group_id", s.groupId)
                    put("script_version", s.scriptVersion)
                    put("version", s.version)
                    put("updated_at", s.updatedAt ?: JSONObject.NULL)
                })
            }
            prefs?.edit()?.putString(KEY_SCRIPTS, arr.toString())?.apply()
        }

    var selectedScriptId: String
        get() = prefs?.getString(KEY_SELECTED_ID, "").orEmpty()
        set(value) {
            prefs?.edit()?.putString(KEY_SELECTED_ID, value)?.apply()
        }

    /** 「确定/解锁」按钮状态：true=脚本已确定（显示红色「解锁」），false=未确定（显示蓝色「确定」）。 */
    var scriptApplied: Boolean
        get() = prefs?.getBoolean(KEY_SCRIPT_APPLIED, false) ?: false
        set(value) {
            prefs?.edit()?.putBoolean(KEY_SCRIPT_APPLIED, value)?.apply()
        }

    /** 已确定脚本的名称（用于 UI 显示与持久化）。 */
    var appliedScriptName: String
        get() = prefs?.getString(KEY_APPLIED_NAME, "").orEmpty()
        set(value) {
            prefs?.edit()?.putString(KEY_APPLIED_NAME, value)?.apply()
        }

    /** 脚本轮询间隔（毫秒），来自配置的 poll_interval。 */
    var pollIntervalMs: Long
        get() = prefs?.getLong(KEY_POLL_INTERVAL, 1000L) ?: 1000L
        set(value) {
            prefs?.edit()?.putLong(KEY_POLL_INTERVAL, value)?.apply()
        }

    /** 脚本每轮结束后的延时（毫秒），来自配置的 loop_delay。 */
    var loopDelayMs: Long
        get() = prefs?.getLong(KEY_LOOP_DELAY, 500L) ?: 500L
        set(value) {
            prefs?.edit()?.putLong(KEY_LOOP_DELAY, value)?.apply()
        }
}
