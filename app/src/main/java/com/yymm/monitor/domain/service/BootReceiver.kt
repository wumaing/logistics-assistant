package com.yymm.monitor.domain.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机自启广播接收器。
 *
 * 用户开启 autoStartOnBoot 后，开机时记录日志。
 * AccessibilityService 由系统绑定，无需手动启动服务。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("yymm_settings", Context.MODE_PRIVATE)
            if (prefs.getBoolean("auto_start_on_boot", false)) {
                // AccessibilityService 由系统自动绑定，此处仅记录日志（轻量操作）
                Log.i("BootReceiver", "开机自启已触发")
            }
        }
    }
}
