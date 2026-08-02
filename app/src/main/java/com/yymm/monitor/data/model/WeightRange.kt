package com.yymm.monitor.data.model

/**
 * 重量区间（TECH_SPEC 2.1.3）。
 *
 * 用于规则中的自定义重量范围配置，单位为吨。
 */
data class WeightRange(
    val min: Float,
    val max: Float
)
