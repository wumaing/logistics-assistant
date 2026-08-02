package com.yymm.monitor.data.model

/**
 * 地区词典数据模型（TECH_SPEC 2.1.6）。
 *
 * 与 assets/regions.json 结构一一对应，由 Gson 解析。
 */
data class RegionData(val provinces: List<Province>)

data class Province(val code: String, val name: String, val cities: List<City>)

data class City(val code: String, val name: String, val districts: List<District>)

data class District(val code: String, val name: String)
