package com.yymm.monitor.domain.service

import android.content.Context
import com.yymm.monitor.data.model.ParsedOrder
import com.yymm.monitor.data.repository.HistoryRepository
import com.yymm.monitor.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 提醒管理器空壳（TECH_SPEC 2.3.4）。
 *
 * TODO: Step 5 实现弹窗/震动/响铃/语音等强提醒能力。
 */
@Singleton
class AlertManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val historyRepository: HistoryRepository
) {

    fun isAlertedToday(orderId: String): Boolean = false

    suspend fun triggerAlert(order: ParsedOrder) {}

    fun showPageError(message: String) {}

    fun destroy() {}
}
