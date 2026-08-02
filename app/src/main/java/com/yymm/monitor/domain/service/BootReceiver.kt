package com.yymm.monitor.domain.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启广播接收器空壳。
 *
 * 开机后自动拉起浏览助手，保证监控持续运行。逻辑在后续步骤实现。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        // 后续步骤实现：开机后启动无障碍服务/前台服务
    }
}
