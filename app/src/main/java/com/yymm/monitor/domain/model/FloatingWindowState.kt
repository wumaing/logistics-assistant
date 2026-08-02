package com.yymm.monitor.domain.model

/**
 * 悬浮窗状态（TECH_SPEC 2.3.5 显示数据）。
 *
 * 用于悬浮窗展示监控状态、统计信息与匹配区规则汇总。
 */
data class FloatingWindowState(
    val monitorState: MonitorState = MonitorState.IDLE,
    val isExpanded: Boolean = false,
    val refreshCount: Int = 0,
    val matchCount: Int = 0,
    val confirmCount: Int = 0,
    val statusMessage: String = "",
    val activeRuleCount: Int = 0,
    val activeRuleNames: List<String> = emptyList()
)
