package com.yymm.monitor.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yymm.monitor.data.model.UserSettings
import com.yymm.monitor.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 提醒设置 ViewModel（PRD 3.3 模块C）。
 * 拆分 UserSettings 为独立字段状态，保存时合并写回。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _settings = MutableStateFlow(UserSettings())
    val settings: StateFlow<UserSettings> = _settings.asStateFlow()

    init {
        loadSettings()
    }

    fun loadSettings() {
        _settings.value = settingsRepository.getSettings()
    }

    fun updatePopupEnabled(enabled: Boolean) = update { it.copy(popupEnabled = enabled) }
    fun updateVibrateEnabled(enabled: Boolean) = update { it.copy(vibrateEnabled = enabled) }
    fun updateVibrateDuration(seconds: Float) = update { it.copy(vibrateDurationMs = (seconds * 1000).toLong()) }
    fun updateRingtoneEnabled(enabled: Boolean) = update { it.copy(ringtoneEnabled = enabled) }
    fun updateRingtoneVolume(volume: Float) = update { it.copy(ringtoneVolume = volume) }
    fun updateRingtoneUri(uri: String?) = update { it.copy(ringtoneUri = uri) }
    fun updateTtsEnabled(enabled: Boolean) = update { it.copy(ttsEnabled = enabled) }
    fun updateTtsSpeed(speed: Float) = update { it.copy(ttsSpeed = speed) }
    fun updatePopupDuration(seconds: Int) = update { it.copy(popupDurationSeconds = seconds) }
    fun updateMultiOrderHandling(mode: String) = update { it.copy(multiOrderHandling = mode) }
    fun updateRefreshInterval(seconds: Int) = update { it.copy(refreshIntervalSeconds = seconds) }
    fun updateMatchMode(mode: String) = update { it.copy(matchMode = mode) }

    fun saveSettings() {
        viewModelScope.launch {
            settingsRepository.saveSettings(_settings.value)
        }
    }

    private fun update(transform: (UserSettings) -> UserSettings) {
        val updated = transform(_settings.value)
        _settings.value = updated
        // 每次修改立即保存（修复"设置不生效"：不再依赖"保存设置"按钮）
        settingsRepository.saveSettings(updated)
    }
}
