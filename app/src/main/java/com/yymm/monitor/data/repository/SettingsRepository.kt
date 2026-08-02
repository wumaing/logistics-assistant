package com.yymm.monitor.data.repository

import com.yymm.monitor.data.model.AlertSettings
import com.yymm.monitor.data.model.UserSettings

/**
 * 设置仓库接口（TECH_SPEC 2.2）。
 *
 * SharedPreferences 操作允许非 suspend。
 */
interface SettingsRepository {

    fun getSettings(): UserSettings

    fun saveSettings(settings: UserSettings)

    fun getRefreshInterval(): Int

    /** 匹配模式：INCLUDE_CHILDREN=包含下级匹配, EXACT=同层精确匹配 */
    fun getMatchMode(): String

    fun getAlertSettings(): AlertSettings

    fun getPopupDuration(): Int

    fun getMultiOrderHandling(): String

    fun getHistoryRetentionDays(): Int
}
