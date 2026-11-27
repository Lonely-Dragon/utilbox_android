package com.sakura.gamebox.net

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 后端公开只读接口客户端（接口文档：http://134.175.64.76:18000/docs）。
 * 只访问无需鉴权的查询接口；写操作（Basic Auth）客户端不涉及。
 * 使用平台自带的 HttpURLConnection + org.json，不新增第三方依赖。
 */
object ScriptConfigApi {

    private const val BASE_URL = "http://134.175.64.76:18000"
    private const val TIMEOUT_MS = 10_000

    /**
     * GET /script-config/list?game=<name> —— 某游戏下启用脚本清单（ScriptListOut），需激活码鉴权。
     * game 必传（后端不传返回 422）；返回体为 {game, scripts:[{id,name,group_id}], total}。
     */
    suspend fun listScripts(activationCode: String, game: String): List<ScriptBrief> = withContext(Dispatchers.IO) {
        val json = JSONObject(getJson("$BASE_URL/script-config/list?game=${Uri.encode(game)}", activationCode))
        val arr = json.optJSONArray("scripts") ?: JSONArray()
        (0 until arr.length()).map { i -> arr.getJSONObject(i).toScriptBrief() }
    }

    /**
     * GET /script-config/{script_id} —— 用激活码鉴权获取指定脚本的完整配置。
     * 后端网关校验：激活码有效、脚本启用、脚本 game 与激活码一致才放行。
     */
    suspend fun getConfig(
        scriptId: String,
        activationCode: String
    ): ScriptConfig = withContext(Dispatchers.IO) {
        JSONObject(
            getJson("$BASE_URL/script-config/${Uri.encode(scriptId)}", activationCode)
        ).toScriptConfig()
    }

    /** GET /tokens/{code}/remaining —— 查询激活码剩余时间（无需鉴权）。 */
    suspend fun getTokenRemaining(code: String): TokenRemaining = withContext(Dispatchers.IO) {
        JSONObject(getJson("$BASE_URL/tokens/${Uri.encode(code)}/remaining")).let { json ->
            TokenRemaining(
                code = json.optString("code"),
                expired = json.optBoolean("expired"),
                remainingSeconds = json.optInt("remaining_seconds"),
                remaining = json.optString("remaining")
            )
        }
    }

    private fun getJson(urlString: String, activationCode: String? = null): String {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.requestMethod = "GET"
        if (!activationCode.isNullOrBlank()) {
            conn.setRequestProperty("X-Activation-Code", activationCode.trim())
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code: ${text.take(200)}")
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key)

    private fun JSONObject.toScriptBrief() = ScriptBrief(
        id = optString("id"),
        name = optString("name"),
        game = optString("game"),
        groupId = optString("group_id"),
        scriptVersion = optString("script_version"),
        version = optInt("version"),
        updatedAt = optStringOrNull("updated_at")
    )

    private fun JSONObject.toScriptConfig(): ScriptConfig {
        val rulesArray = optJSONArray("rules")
        val rules = if (rulesArray == null) emptyList() else
            (0 until rulesArray.length()).map { i -> rulesArray.getJSONObject(i) }
        return ScriptConfig(
            id = optString("id"),
            name = optString("name"),
            enabled = optBoolean("enabled", true),
            scriptVersion = optString("script_version"),
            forceUpdate = optBoolean("force_update", false),
            game = optString("game"),
            groupId = optString("group_id"),
            pollInterval = optInt("poll_interval", 300),
            loopDelay = optInt("loop_delay", 800),
            maxRounds = optInt("max_rounds"),
            actionDelayMin = optInt("action_delay_min", 300),
            actionDelayMax = optInt("action_delay_max", 900),
            announcement = optString("announcement"),
            extra = optJSONObject("extra")?.toString().orEmpty(),
            rules = rules,
            version = optInt("version"),
            updatedAt = optStringOrNull("updated_at")
        )
    }
}

/** GET /script-config/list 返回项（ScriptBriefOut）。 */
data class ScriptBrief(
    val id: String,
    val name: String,
    val game: String = "",
    val groupId: String = "",
    val scriptVersion: String = "",
    val version: Int = 0,
    val updatedAt: String? = null
)

/** GET /tokens/{code}/remaining 返回项（TokenRemaining）。 */
data class TokenRemaining(
    val code: String,
    val expired: Boolean,
    val remainingSeconds: Int,
    val remaining: String
)

/** GET /script-config 返回的完整配置（ScriptConfigOut，仅保留客户端需要的字段）。 */
data class ScriptConfig(
    val id: String = "",
    val name: String = "",
    val enabled: Boolean = true,
    val scriptVersion: String = "",
    val forceUpdate: Boolean = false,
    val game: String = "",
    val groupId: String = "",
    val pollInterval: Int = 300,
    val loopDelay: Int = 800,
    val maxRounds: Int = 0,
    val actionDelayMin: Int = 300,
    val actionDelayMax: Int = 900,
    val announcement: String = "",
    val extra: String = "",
    val rules: List<JSONObject> = emptyList(),
    val version: Int = 0,
    val updatedAt: String? = null
)
