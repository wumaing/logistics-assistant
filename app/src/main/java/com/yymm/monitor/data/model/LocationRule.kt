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
     * @param orderDistrict 订单区县，可空。null 表示订单未解析出区县信息，此时不按区县卡（宽松，避免误杀）
     * @return province == "全国" 时恒为 true
     */
    fun matches(orderProvince: String, orderCity: String?, orderDistrict: String? = null): Boolean {
        if (province == "全国") return true
        if (province != orderProvince) return false
        if (city != null && city != orderCity) return false
        // 区县级匹配：仅当规则和订单都提供了区县时才校验；任一未知则放行
        if (district != null && orderDistrict != null && district != orderDistrict) return false
        return true
    }
}
