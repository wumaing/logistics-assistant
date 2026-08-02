package com.yymm.monitor.domain.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.gson.Gson
import com.yymm.monitor.data.model.UserSettings

/**
 * 开机自启广播接收器。
 *
 * 用户开启 autoStartOnBoot 后，开机时自动进入监控（记录日志）。
 * AccessibilityService 由系统绑定，无需手动启动服务。
 *
 * 注意：设置以 Gson 序列化存于 "user_settings" key 下，这里必须读同一份，
 * 否则读到的永远是默认值（之前读扁平 key "auto_start_on_boot" 导致功能失效）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("yymm_settings", Context.MODE_PRIVATE)
        val settings = try {
            Gson().fromJson(prefs.getString("user_settings", null), UserSettings::class.java)
        } catch (_: Exception) {
            null
        }
        if (settings?.autoStartOnBoot == true) {
            // AccessibilityService 由系统自动绑定；这里仅记录日志（轻量操作）
            Log.i("BootReceiver", "开机自启已触发，autoStartOnBoot=true")
        }
    }
}
