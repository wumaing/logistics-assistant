package com.yymm.monitor.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 匹配规则实体（TECH_SPEC 2.1.3）。
 *
 * 所有 JSON 字段均以 String 存储（Gson 序列化）。
 */
@Entity(tableName = "match_rules")
data class MatchRuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,
    val ruleStatus: String = "STANDBY",      // STANDBY=备战区, ACTIVE=匹配区
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),

    // 出发地：JSON 数组，元素为 LocationRule
    val fromLocationsJson: String,
    // 自定义出发地：JSON 数组，元素为 String（自由输入的地点名）
    val fromCustomPointsJson: String,
    // 目的地：JSON 数组，元素为 LocationRule
    val toLocationsJson: String,
    // 自定义目的地：JSON 数组，元素为 String（自由输入的地点名）
    val toCustomPointsJson: String,
    // 货物类型：JSON 数组，元素为 String
    val cargoTypesJson: String,
    // 车长：JSON 数组，元素为 String
    val vehicleLengthsJson: String,
    // 车型：JSON 数组，元素为 String
    val vehicleTypesJson: String,
    // 装货时间选项（如 "ANY_TIME" / "TODAY" 等）
    val loadTimeOption: String,
    val loadTimeStart: Long? = null,
    val loadTimeEnd: Long? = null,
    // 重量区间：JSON 数组，元素为 WeightRange
    val weightRangesJson: String,
    val weightMin: Float? = null,
    val weightMax: Float? = null,
    val minPrice: Int? = null,
    val includeDianyiWithPrice: Boolean = false,
    val includeDianyiWithoutPrice: Boolean = false,
    val requireHighCredit: Boolean = false,
    val requireManyunbao: Boolean = false,
    val requireDepositRefundable: Boolean = false,
    val requirePrivilege: Boolean = false
)
