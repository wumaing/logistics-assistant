package com.yymm.monitor.domain.service

import android.content.Context
import com.yymm.monitor.domain.model.MonitorState

/**
 * 悬浮窗管理器空壳（TECH_SPEC 2.3.5）。
 *
 * 单例 object（非 Hilt 注入），由 Service 直接调用。
 *
 * TODO: Step 6 实现悬浮窗展示监控状态、统计与匹配区规则汇总。
 */
object FloatingWindowManager {

    fun show(context: Context) {}

    fun updateState(state: MonitorState, message: String = "") {}

    fun updateStats(refresh: Int, match: Int, confirm: Int) {}

    fun updateActiveRules(ruleNames: List<String>) {}

    fun showError(message: String) {}

    fun hide() {}
}
