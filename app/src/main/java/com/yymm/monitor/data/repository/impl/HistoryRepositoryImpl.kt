package com.yymm.monitor.data.repository.impl

import com.google.gson.Gson
import com.yymm.monitor.data.local.dao.HistoryDao
import com.yymm.monitor.data.local.entity.HistoryRecordEntity
import com.yymm.monitor.data.model.AlertAction
import com.yymm.monitor.data.model.ParsedOrder
import com.yymm.monitor.data.repository.HistoryRepository
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 历史记录仓库实现（TECH_SPEC 2.2）。
 *
 * recordAlert：将 ParsedOrder Gson 序列化为 orderJson，生成 HistoryRecordEntity 插入。
 * getTodayAlertedOrderIds：获取今日 date 的所有 orderId。
 */
@Singleton
class HistoryRepositoryImpl @Inject constructor(
    private val historyDao: HistoryDao
) : HistoryRepository {

    private val gson = Gson()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    override suspend fun getTodayRecords(): List<HistoryRecordEntity> =
        historyDao.getByDate(today())

    override suspend fun getTodayAlertedOrderIds(): Set<String> =
        historyDao.getOrderIdsByDate(today()).toSet()

    override suspend fun recordAlert(order: ParsedOrder) {
        val now = System.currentTimeMillis()
        historyDao.insert(
            HistoryRecordEntity(
                orderId = order.id,
                orderJson = gson.toJson(order),
                alertTime = now,
                action = AlertAction.ALERTED.name,
                date = dateFormat.format(Date(now)),
                fromDisplay = displayLocation(order.fromProvince, order.fromCity),
                toDisplay = displayLocation(order.toProvince, order.toCity),
                cargoDisplay = order.cargoName.orEmpty(),
                weightDisplay = displayWeight(order.weightMin, order.weightMax),
                priceDisplay = order.priceText
            )
        )
    }

    override suspend fun recordAction(orderId: String, action: AlertAction) {
        val existing = historyDao.getByDate(today())
            .firstOrNull { it.orderId == orderId } ?: return
        historyDao.insert(existing.copy(action = action.name))
    }

    override suspend fun cleanupOldRecords(retentionDays: Int) {
        if (retentionDays <= 0) return // 0=永久保留
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -retentionDays)
        val threshold = dateFormat.format(calendar.time)
        historyDao.deleteBefore(threshold)
    }

    private fun today(): String = dateFormat.format(Date())

    private fun displayLocation(province: String, city: String?): String =
        if (city.isNullOrBlank()) province else "$province $city"

    private fun displayWeight(min: Float?, max: Float?): String = when {
        min != null && max != null -> "${min}-${max}吨"
        min != null -> "${min}吨"
        max != null -> "${max}吨"
        else -> ""
    }
}
