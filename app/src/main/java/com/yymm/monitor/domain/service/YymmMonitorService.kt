package com.yymm.monitor.domain.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import dagger.hilt.android.AndroidEntryPoint

/**
 * 无障碍服务空壳。
 *
 * 职责（后续实现）：
 * 观察运满满订单列表，主动刷新页面，匹配成功时提醒用户。
 * 严格不执行任何自动点击/抢单操作，一切接单动作由真人完成。
 */
@AndroidEntryPoint
class YymmMonitorService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 后续步骤实现：监听 typeWindowStateChanged / typeViewScrolled 事件
    }

    override fun onInterrupt() {
        // 无障碍服务被中断时的处理
    }
}
