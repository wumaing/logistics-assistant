package com.yymm.monitor.data.model

/**
 * 地点规则（TECH_SPEC 2.1.3）。
 *
 * @param province 省级行政区名称，"全国" 表示不限制出发/目的地所在省份
 * @param city 市级行政区名称，可空（仅按省匹配）
 * @param district 区县级行政区名称，可空
 */
data class LocationRule(
    val province: String,
    val city: String? = null,
    val district: String? = null
) {
    /**
     * 判断订单地点是否匹配本规则。
     *
     * @param orderProvince 订单省份
     * @param orderCity 订单城市，可空
     * @return province == "全国" 时恒为 true
     */
    fun matches(orderProvince: String, orderCity: String?): Boolean {
        if (province == "全国") return true
        if (province != orderProvince) return false
        if (city != null && city != orderCity) return false
        return true
    }
}
