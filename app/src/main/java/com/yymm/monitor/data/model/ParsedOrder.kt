package com.yymm.monitor.data.model

/**
 * 订单数据模型（TECH_SPEC 2.1.2）。
 *
 * 由 AccessibilityService 读取运满满订单列表后解析生成。
 * 纯数据容器，仅用于 Gson 序列化与匹配计算。
 */
data class ParsedOrder(
    val id: String,
    val fromProvince: String,
    val fromCity: String?,
    val toProvince: String,
    val toCity: String?,
    val cargoName: String?,
    val weightMin: Float?,
    val weightMax: Float?,
    val vehicleLengths: List<String>,
    val vehicleTypes: List<String>,
    val priceAmount: Int?,
    val priceText: String,
    val isDianyi: Boolean,
    val loadTimeText: String,
    val distance: String?,
    val tags: List<String>,
    val rawTexts: List<String>,
    val parseTimestamp: Long = System.currentTimeMillis()
)
