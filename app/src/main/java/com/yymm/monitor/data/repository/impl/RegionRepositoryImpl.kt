package com.yymm.monitor.data.repository.impl

import android.content.Context
import com.google.gson.Gson
import com.yymm.monitor.data.model.City
import com.yymm.monitor.data.model.District
import com.yymm.monitor.data.model.LocationRule
import com.yymm.monitor.data.model.RegionData
import com.yymm.monitor.data.repository.RegionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 地区词典仓库实现（TECH_SPEC 2.2）。
 *
 * loadRegions()：从 assets/regions.json 读取并 Gson 解析为 RegionData，结果内存缓存。
 * searchRegions()：模糊匹配省/市/区名称，返回 List&lt;LocationRule&gt;。
 */
@Singleton
class RegionRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : RegionRepository {

    private val gson = Gson()
    private val mutex = Mutex()

    @Volatile
    private var cached: RegionData? = null

    override suspend fun loadRegions(): RegionData {
        cached?.let { return it }
        return mutex.withLock {
            cached?.let { return it }
            val data = withContext(Dispatchers.IO) { readFromAssets() }
            cached = data
            data
        }
    }

    override fun searchRegions(keyword: String): List<LocationRule> {
        val k = keyword.trim()
        if (k.isEmpty()) return emptyList()
        val data = cached ?: return emptyList()
        val result = mutableListOf<LocationRule>()
        for (province in data.provinces) {
            if (province.name.contains(k)) {
                result.add(LocationRule(province = province.name))
            }
            for (city in province.cities) {
                if (city.name.contains(k)) {
                    result.add(LocationRule(province = province.name, city = city.name))
                }
                for (district in city.districts) {
                    if (district.name.contains(k)) {
                        result.add(
                            LocationRule(
                                province = province.name,
                                city = city.name,
                                district = district.name
                            )
                        )
                    }
                }
            }
        }
        return result
    }

    override fun getCitiesByProvince(provinceName: String): List<City> =
        cached?.provinces?.firstOrNull { it.name == provinceName }?.cities ?: emptyList()

    override fun getDistrictsByCity(provinceName: String, cityName: String): List<District> =
        cached?.provinces
            ?.firstOrNull { it.name == provinceName }
            ?.cities
            ?.firstOrNull { it.name == cityName }
            ?.districts
            ?: emptyList()

    private fun readFromAssets(): RegionData {
        context.assets.open("regions.json").use { input ->
            InputStreamReader(input, StandardCharsets.UTF_8).use { reader ->
                return gson.fromJson(reader, RegionData::class.java)
            }
        }
    }
}
