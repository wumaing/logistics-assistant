package com.yymm.monitor.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 历史记录实体（TECH_SPEC 2.1.4）。
 *
 * @param orderId 订单唯一标识（MD5 哈希），作为主键
 * @param orderJson 完整订单 ParsedOrder 的 JSON 序列化
 * @param action 动作类型：ALERTED / VIEWED / IGNORED
 * @param date 提醒日期 yyyy-MM-dd，用于按日查询与清理
 */
@Entity(tableName = "history_records")
data class HistoryRecordEntity(
    @PrimaryKey
    val orderId: String,
    val orderJson: String,
    val alertTime: Long,
    val action: String,
    val date: String,
    val fromDisplay: String,
    val toDisplay: String,
    val cargoDisplay: String,
    val weightDisplay: String,
    val priceDisplay: String
)
