package com.yymm.monitor.data.repository

import com.yymm.monitor.data.model.City
import com.yymm.monitor.data.model.District
import com.yymm.monitor.data.model.LocationRule
import com.yymm.monitor.data.model.RegionData

/**
 * 地区词典仓库接口（TECH_SPEC 2.2）。
 */
interface RegionRepository {

    suspend fun loadRegions(): RegionData

    fun searchRegions(keyword: String): List<LocationRule>

    fun getCitiesByProvince(provinceName: String): List<City>

    fun getDistrictsByCity(provinceName: String, cityName: String): List<District>
}
