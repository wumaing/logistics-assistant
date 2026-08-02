package com.yymm.monitor.data.model

/**
 * 提醒设置（TECH_SPEC 未单独定义，由 SettingsRepository.getAlertSettings() 返回）。
 *
 * 从 [UserSettings] 中提取提醒相关字段构造。
 */
data class AlertSettings(
    val popupEnabled: Boolean,
    val vibrateEnabled: Boolean,
    val vibrateDurationMs: Long,
    val ringtoneEnabled: Boolean,
    val ringtoneUri: String?,
    val ringtoneVolume: Float,
    val ttsEnabled: Boolean,
    val ttsSpeed: Float
)
