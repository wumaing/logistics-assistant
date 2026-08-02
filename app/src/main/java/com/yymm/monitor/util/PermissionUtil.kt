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
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
            ?: return false
        val expectedComponent = "${context.packageName}/${YymmMonitorService::class.java.name}"
        try {
            val enabledServices = (manager as android.view.accessibility.AccessibilityManager)
                .getEnabledAccessibilityServiceList(AccessibilityEvent.TYPES_ALL_MASK)
            return enabledServices.any { info ->
                val serviceInfo = info.resolveInfo.serviceInfo
                "${serviceInfo.packageName}/${serviceInfo.name}" == expectedComponent
            }
        } catch (_: Exception) {
            return false
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
