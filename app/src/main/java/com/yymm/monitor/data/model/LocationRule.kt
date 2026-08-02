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
     * @param includeChildren true=包含下级匹配（默认）：选"山东省"匹配省内所有市/区订单，
     *   选"济宁市"匹配市内所有区订单；false=同层精确匹配：选"山东省"仅匹配省一级，
     *   选"济宁市"仅匹配省+市。
     * @param orderProvince 订单省份
     * @param orderCity 订单城市，可空
     * @param orderDistrict 订单区县，可空
     */
    fun matches(
        orderProvince: String,
        orderCity: String?,
        orderDistrict: String? = null,
        includeChildren: Boolean = true
    ): Boolean {
        // 规则选全国：恒匹配
        if (province == "全国") return true
        // 省必须精确匹配（规范化后）
        if (normalizeProvince(province) != normalizeProvince(orderProvince)) return false

        // 规则只到省（city==null）：
        // - 包含下级模式：该省所有市/区都匹配
        // - 精确模式：只匹配省一级（订单无市信息）
        if (city == null) {
            return includeChildren || orderCity == null
        }
        // 规则到市：订单无市信息时宽松放行（避免误杀），有市则市必须精确匹配
        if (orderCity == null) return true
        if (normalizeCity(city) != normalizeCity(orderCity)) return false

        // 规则到区：订单无区信息时宽松放行；有区则区必须精确匹配
        if (district == null) {
            return includeChildren || orderDistrict == null
        }
        if (orderDistrict == null) return true
        return district == orderDistrict
    }

    companion object {
        /** 省名规范化：去"省/市/自治区/壮族自治区/回族自治区/维吾尔自治区/特别行政区"后缀 */
        fun normalizeProvince(name: String): String = name
            .replace("壮族自治区", "")
            .replace("回族自治区", "")
            .replace("维吾尔自治区", "")
            .replace("自治区", "")
            .replace("特别行政区", "")
            .replace("省", "")
            .replace("市", "")

        /** 市名规范化：去"市/自治州/地区/盟/区"后缀 */
        fun normalizeCity(name: String): String = name
            .replace("自治州", "")
            .replace("地区", "")
            .replace("盟", "")
            .replace("市", "")
            .replace("区", "")
    }
}
