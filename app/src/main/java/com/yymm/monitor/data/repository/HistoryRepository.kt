package com.yymm.monitor.data.repository

import com.yymm.monitor.data.local.entity.HistoryRecordEntity
import com.yymm.monitor.data.model.AlertAction
import com.yymm.monitor.data.model.ParsedOrder

/**
 * 历史记录仓库接口（TECH_SPEC 2.2）。
 */
interface HistoryRepository {

    suspend fun getTodayRecords(): List<HistoryRecordEntity>

    suspend fun getTodayAlertedOrderIds(): Set<String>

    suspend fun recordAlert(order: ParsedOrder)

    suspend fun recordAction(orderId: String, action: AlertAction)

    suspend fun cleanupOldRecords(retentionDays: Int)
}
