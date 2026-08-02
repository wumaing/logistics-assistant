package com.yymm.monitor.data.model

/**
 * 历史记录动作类型（TECH_SPEC 引用但未定义，依据 PRD 历史记录页 Tab 推导）。
 *
 * - ALERTED：已触发提醒（首次弹窗时写入）
 * - VIEWED： 用户已查看详情
 * - IGNORED：用户已忽略（当日不再提醒）
 */
enum class AlertAction {
    ALERTED,
    VIEWED,
    IGNORED
}
