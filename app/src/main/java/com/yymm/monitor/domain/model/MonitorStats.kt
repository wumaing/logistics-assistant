package com.yymm.monitor.domain.model

/**
 * 监控统计（TECH_SPEC 2.3.1）。
 *
 * - refreshCount：刷新次数
 * - matchCount：匹配成功并提醒次数
 * - confirmCount：用户确认次数
 */
data class MonitorStats(
    val refreshCount: Int = 0,
    val matchCount: Int = 0,
    val confirmCount: Int = 0
)
