package com.sakura.ubox.util

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import com.sakura.ubox.service.UBoxAccessibilityService

object AccessibilityPermission {

    fun isEnabled(context: Context): Boolean {
        val expected = ComponentName(context, UBoxAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    /**
     * 优先一步直达「UBox 无障碍服务」详情页（开关所在页）；
     * 失败再退到「已下载的应用」列表 / 服务详情 / 无障碍主页 / 应用详情，
     * 全部失败才 Toast 提示，绝不把异常抛给调用方。
     */
    fun openSettings(context: Context) {
        val serviceComponent = ComponentName(context, UBoxAccessibilityService::class.java)
        val componentString = serviceComponent.flattenToString()

        // 候选 1：一步直达 UBox 无障碍服务的详情页（就是那个开关页面）。
        // 真机（MIUI/HyperOS）已验证：SubSettings + show_fragment 指定
        // AccessibilityDetailsSettingsFragment，并把组件名作为顶层 String extra 传入，
        // 可直接落到「使用 UBox 无障碍服务」开关页，无需用户再点列表。
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
            putExtra(":settings:show_fragment_title", "UBox 无障碍服务")
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
            "无法直接打开无障碍设置，请到系统设置中手动开启「UBox 无障碍服务」",
            Toast.LENGTH_LONG
        ).show()
    }
}
