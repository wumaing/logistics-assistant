package com.yymm.monitor.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.yymm.monitor.domain.service.YymmMonitorService

/**
 * 权限工具类。
 *
 * 基于官方 AccessibilityManager.getEnabledAccessibilityServiceList()
 * 检测无障碍服务（比读 Settings.Secure 更可靠），并提供引导跳转。
 */
object PermissionUtil {

    /**
     * 检查无障碍服务是否已开启。
     *
     * 遍历系统已启用的无障碍服务，匹配本应用包名 + YymmMonitorService 类名。
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expectedComponent = "${context.packageName}/${YymmMonitorService::class.java.name}"
        // 最强信号：服务实例已连接（onServiceConnected 已执行）→ 无障碍必定开启
        if (YymmMonitorService.instance != null) return true
        // 次选：官方 API 检测
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
            ?: return false
        try {
            val enabledServices = (manager as android.view.accessibility.AccessibilityManager)
                .getEnabledAccessibilityServiceList(AccessibilityEvent.TYPES_ALL_MASK)
            val matched = enabledServices.any { info ->
                val serviceInfo = info.resolveInfo.serviceInfo
                "${serviceInfo.packageName}/${serviceInfo.name}" == expectedComponent
            }
            if (matched) return true
        } catch (_: Exception) {
            // 官方 API 异常时降级到 Settings.Secure 检测
        }
        // 兜底：读系统持久化的 enabled_accessibility_services（OPPO/ColorOS 上官方 API
        // 可能在服务刚启用尚未绑定事件时返回空，用 Settings 校验更稳）
        return try {
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            enabled.split(':').any { it == expectedComponent }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 服务是否「显示已启用但实际未连接」。
     *
     * OPPO 重装 APK 后：settings 保留 enabled 记录，但系统不重新 bind 服务
     * （Bound services 为空），导致 isAccessibilityServiceEnabled 返回 true
     * 但 onServiceConnected 从未触发、监控不工作。此方法用于检测该异常状态，
     * 返回 true 时引导用户重新开关一次无障碍。
     */
    fun isServiceRegisteredButNotConnected(context: Context): Boolean {
        // 已连接 → 正常
        if (YymmMonitorService.instance != null) return false
        // settings 里显示启用但服务未连接 → 异常
        val expectedComponent = "${context.packageName}/${YymmMonitorService::class.java.name}"
        return try {
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            enabled.split(':').any { it == expectedComponent }
        } catch (_: Exception) {
            false
        }
    }

    /** 检查悬浮窗权限是否已授予 */
    fun isFloatingWindowAllowed(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    /** 检查电池优化是否已被忽略（加入白名单） */
    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 跳转无障碍服务设置页 */
    fun openAccessibilitySettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** 跳转悬浮窗权限设置页 */
    fun openOverlaySettings(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
        )
    }

    /** 跳转电池优化设置页 */
    fun openBatteryOptimizationSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
