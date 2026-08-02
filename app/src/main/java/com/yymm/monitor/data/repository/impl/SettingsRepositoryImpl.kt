package com.yymm.monitor.data.repository.impl

import android.content.SharedPreferences
import com.google.gson.Gson
import com.yymm.monitor.data.model.AlertSettings
import com.yymm.monitor.data.model.UserSettings
import com.yymm.monitor.data.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设置仓库实现（TECH_SPEC 2.2）。
 *
 * getSettings/saveSettings 用 Gson 序列化/反序列化整个 UserSettings，
 * 其余方法从 UserSettings 中提取对应字段返回。
 */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val prefs: SharedPreferences
) : SettingsRepository {

    private val gson = Gson()
    private val keySettings = "user_settings"

    override fun getSettings(): UserSettings {
        val json = prefs.getString(keySettings, null) ?: return UserSettings()
        return try {
            gson.fromJson(json, UserSettings::class.java)
        } catch (_: Exception) {
            UserSettings()
        }
    }

    override fun saveSettings(settings: UserSettings) {
        prefs.edit().putString(keySettings, gson.toJson(settings)).apply()
    }

    override fun getRefreshInterval(): Int = getSettings().refreshIntervalSeconds

    override fun getAlertSettings(): AlertSettings {
        val s = getSettings()
        return AlertSettings(
            popupEnabled = s.popupEnabled,
            vibrateEnabled = s.vibrateEnabled,
            vibrateDurationMs = s.vibrateDurationMs,
            ringtoneEnabled = s.ringtoneEnabled,
            ringtoneUri = s.ringtoneUri,
            ringtoneVolume = s.ringtoneVolume,
            ttsEnabled = s.ttsEnabled,
            ttsSpeed = s.ttsSpeed
        )
    }

    override fun getPopupDuration(): Int = getSettings().popupDurationSeconds

    override fun getMultiOrderHandling(): String = getSettings().multiOrderHandling

    override fun getHistoryRetentionDays(): Int = getSettings().historyRetentionDays
}
