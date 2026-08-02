package com.yymm.monitor.domain.model

/**
 * 监控状态机（TECH_SPEC 2.3.1 / PRD E.1）。
 *
 * - IDLE：等待启动（悬浮窗显示，未点击开始）
 * - CHECKING：检测运满满页面状态中
 * - RUNNING：监控中（定时刷新+解析+匹配）
 * - PAUSED：已暂停（用户手动暂停或页面异常）
 * - ERROR：错误状态（无障碍服务被关闭等）
 */
enum class MonitorState { IDLE, CHECKING, RUNNING, PAUSED, ERROR }
