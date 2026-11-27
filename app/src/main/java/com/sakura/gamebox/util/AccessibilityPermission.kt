package com.sakura.gamebox.util

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.sakura.gamebox.service.GameboxAccessibilityService

object AccessibilityPermission {

    private const val TAG = "AccessibilityPermission"
    private const val PREFS_NAME = "gamebox_a11y_state"
    private const val KEY_ONCE_ENABLED = "once_enabled"

    fun isEnabled(context: Context): Boolean {
        val component = ComponentName(context, GameboxAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabled.containsService(component)
    }

    /**
     * ENABLED_ACCESSIBILITY_SERVICES 里组件名有两种合法写法：
     * - 长格式 com.sakura.gamebox/com.sakura.gamebox.service.GameboxAccessibilityService
     *   （adb / gradle 脚本写入，即 flattenToString()）
     * - 短格式 com.sakura.gamebox/.service.GameboxAccessibilityService
     *   （系统设置 UI 手动开启时写入，即 flattenToShortString()）
     * 只匹配长格式会把手动开启的正确状态误判为「权限已掉」。两种都要认。
     */
    private fun String.containsService(component: ComponentName): Boolean {
        val longName = component.flattenToString()
        val shortName = component.flattenToShortString()
        return split(':').any { entry ->
            val value = entry.trim()
            value.equals(longName, ignoreCase = true) || value.equals(shortName, ignoreCase = true)
        }
    }

    /**
     * 冷启动时恢复无障碍权限，并通过 [onLostAfterGrant] 通知「曾授权、现在丢了」的场景。
     *
     * 系统硬限制：强行停止会禁用无障碍服务，普通应用无法靠代码静默重新开启。
     * 这里先尝试写回系统设置——只有设备通过 adb 授予 WRITE_SECURE_SETTINGS 时才会成功
     * （通常是调试机）；否则写入会抛异常被捕获，退化为回调让 UI 主动弹窗引导。
     */
    fun ensureEnabledOnStart(context: Context, onLostAfterGrant: () -> Unit) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val wasEnabledBefore = isEnabled(context) || prefs.getBoolean(KEY_ONCE_ENABLED, false)

        if (isEnabled(context)) {
            prefs.edit().putBoolean(KEY_ONCE_ENABLED, true).apply()
            return
        }

        if (trySilentRestore(context)) {
            prefs.edit().putBoolean(KEY_ONCE_ENABLED, true).apply()
            Log.d(TAG, "已静默恢复无障碍权限")
            return
        }

        if (wasEnabledBefore) {
            Log.d(TAG, "无障碍权限曾开启但当前已丢失，通知 UI 引导")
            onLostAfterGrant()
        }
    }

    /** 借助 adb 授予的 WRITE_SECURE_SETTINGS 直接写回无障碍开关；未授权时会抛异常。 */
    private fun trySilentRestore(context: Context): Boolean {
        return try {
            val component = ComponentName(context, GameboxAccessibilityService::class.java)
            val expected = component.flattenToString()
            val current = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            val components = current.split(':').filter { it.isNotBlank() }.toMutableList()
            if (components.none { it.trim().equals(expected, ignoreCase = true) }) {
                components.add(expected)
            }
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                components.joinToString(":")
            )
            Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            isEnabled(context)
        } catch (t: Throwable) {
            Log.d(TAG, "静默恢复失败（缺少 WRITE_SECURE_SETTINGS 或 ROM 限制）：${t.message}")
            false
        }
    }

    /**
     * 优先一步直达「Gamebox 无障碍服务」详情页（开关所在页）；
     * 失败再退到「已下载的应用」列表 / 服务详情 / 无障碍主页 / 应用详情，
     * 全部失败才 Toast 提示，绝不把异常抛给调用方。
     */
    fun openSettings(context: Context) {
        val serviceComponent = ComponentName(context, GameboxAccessibilityService::class.java)
        val componentString = serviceComponent.flattenToString()

        // 候选 1：一步直达 Gamebox 无障碍服务的详情页（就是那个开关页面）。
        // 真机（MIUI/HyperOS）已验证：SubSettings + show_fragment 指定
        // AccessibilityDetailsSettingsFragment，并把组件名作为顶层 String extra 传入，
        // 可直接落到「使用 Gamebox 无障碍服务」开关页，无需用户再点列表。
        // 注意：EXTRA_COMPONENT_NAME 必须是 String —— 传 ComponentName 对象会让
        // 设置页 getStringExtra 时抛 ClassCastException 而崩溃（历史 bug）。
        val serviceDetails = Intent().apply {
            component = ComponentName(
                "com.android.settings",
                "com.android.settings.SubSettings"
            )
            putExtra(
                ":settings:show_fragment",
                "com.android.settings.accessibility.AccessibilityDetailsSettingsFragment"
            )
            putExtra(Intent.EXTRA_COMPONENT_NAME, componentString)
            putExtra(":settings:show_fragment_title", "Gamebox 无障碍服务")
        }
        // 候选 2：直达「已下载的应用」列表（AOSP 标准 Fragment，MIUI 沿用，真机已验证）
        val downloadedList = Intent().apply {
            component = ComponentName(
                "com.android.settings",
                "com.android.settings.SubSettings"
            )
            putExtra(
                ":settings:show_fragment",
                "com.android.settings.accessibility.AccessibilitySettings\$DownloadedServicesFragment"
            )
            putExtra(":android:show_fragment_title", "已下载的应用")
        }
        // 候选 3：标准详情页 Action（部分 ROM 可用）
        val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
            putExtra(Intent.EXTRA_COMPONENT_NAME, componentString)
        }
        // 候选 4：无障碍设置主页
        val main = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        // 候选 5：应用详情页兜底
        val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }

        for (intent in listOf(serviceDetails, downloadedList, details, main, appDetails)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                if (intent.resolveActivity(context.packageManager) == null) continue
                context.startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // 该机型没有对应页面，尝试下一个候选
            } catch (_: Exception) {
                // 部分 ROM 会抛 SecurityException / BadParcelException 等，继续兜底
            }
        }

        Toast.makeText(
            context,
            "无法直接打开无障碍设置，请到系统设置中手动开启「Gamebox 无障碍服务」",
            Toast.LENGTH_LONG
        ).show()
    }
}
