package com.yymm.monitor.domain.service

import com.yymm.monitor.data.model.City
import com.yymm.monitor.data.model.District
import com.yymm.monitor.data.model.LocationRule
import com.yymm.monitor.data.model.Province
import com.yymm.monitor.data.model.RegionData
import com.yymm.monitor.data.repository.RegionRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RegionMatcher 单元测试。
 *
 * 用真实地区层级结构（山东/济宁/任城兖州、菏泽/鄄城、重庆/忠县）
 * 验证 OCR 地名乱码（单字替换/插入/尾缀垃圾）能否正确反查到省市区。
 */
class RegionMatcherTest {

    private lateinit var matcher: RegionMatcher

    /** fake RegionRepository：返回与 regions.json 一致的关键地区层级 */
    private class FakeRegionRepository : RegionRepository {
        override suspend fun loadRegions(): RegionData {
            return RegionData(
                listOf(
                    Province("370000", "山东省", listOf(
                        City("3708", "济宁市", listOf(
                            District("370811", "任城区"),
                            District("370812", "兖州区"),
                            District("370830", "汶上县"),
                            District("370829", "嘉祥县")
                        )),
                        City("3717", "菏泽市", listOf(
                            District("371702", "牡丹区"),
                            District("371728", "鄄城县"),
                            District("371726", "郓城县")
                        ))
                    )),
                    Province("500000", "重庆市", listOf(
                        City("5000", "重庆", listOf(
                            District("500233", "忠县"),
                            District("500112", "渝北区")
                        ))
                    ))
                )
            )
        }
        override fun searchRegions(keyword: String) = emptyList<LocationRule>()
        override fun getCitiesByProvince(provinceName: String) = emptyList<City>()
        override fun getDistrictsByCity(provinceName: String, cityName: String) = emptyList<District>()
    }

    @Before
    fun setup() {
        matcher = RegionMatcher(FakeRegionRepository())
    }

    // ─── 正常地名 ───

    @Test
    fun `正常地名_济宁任城解析为山东济宁任城`() = runTest {
        val r = matcher.resolve("济宁任城")
        assertNotNull(r)
        assertEquals("山东", r?.province)
        assertEquals("济宁", r?.city)
        assertEquals("任城", r?.district)
    }

    @Test
    fun `正常地名_重庆忠县解析为重庆忠县`() = runTest {
        val r = matcher.resolve("重庆忠县")
        assertNotNull(r)
        assertEquals("重庆", r?.province)
        assertEquals("重庆", r?.city) // 直辖市：市名即省名
        assertEquals("忠县", r?.district)
    }

    @Test
    fun `正常地名_山东济宁解析为山东济宁`() = runTest {
        val r = matcher.resolve("山东济宁")
        assertNotNull(r)
        assertEquals("山东", r?.province)
        assertEquals("济宁", r?.city)
        assertNull(r?.district)
    }

    // ─── OCR 乱码（单字替换/插入） ───

    @Test
    fun `OCR乱码_兖州被读成充州_编辑距离1命中`() = runTest {
        val r = matcher.resolve("济宁充州")
        assertNotNull(r)
        assertEquals("山东", r?.province)
        assertEquals("济宁", r?.city)
        assertEquals("兖州", r?.district)
    }

    @Test
    fun `OCR乱码_鄄城被读成郭城_编辑距离1命中`() = runTest {
        val r = matcher.resolve("菏泽郭城")
        assertNotNull(r)
        assertEquals("山东", r?.province)
        assertEquals("菏泽", r?.city)
        assertEquals("鄄城县", r?.district)
    }

    @Test
    fun `OCR乱码_任城被读成任城成_插入字命中`() = runTest {
        val r = matcher.resolve("济宁任城成")
        assertNotNull(r)
        assertEquals("山东", r?.province)
        assertEquals("济宁", r?.city)
        assertEquals("任城", r?.district)
    }

    @Test
    fun `OCR乱码_目的地带尾缀垃圾_重庆县佩运週省市命中区名宽松`() = runTest {
        val r = matcher.resolve("重庆县佩运週")
        assertNotNull(r)
        assertEquals("重庆", r?.province)
        // 灾难乱码：区名丢失但省市已命中，RuleEngine 区缺失走宽松放行
    }

    @Test
    fun `OCR乱码_通谓命中定西前缀_需补充定西到词典`() = runTest {
        // 定西不在 fake 词典，验证不崩溃返回 null（调用方宽松放行兜底）
        val r = matcher.resolve("定西通谓")
        assertNull(r)
    }

    // ─── 无省名直接市名 ───

    @Test
    fun `无省名_独立市名匹配_余姚`() = runTest {
        // 余姚不在词典，返回 null
        assertNull(matcher.resolve("余姚"))
    }

    // ─── isKnownRegion ───

    @Test
    fun `已知地区返回true`() = runTest {
        assertTrue(matcher.isKnownRegion("济宁"))
    }

    @Test
    fun `乱码返回false_触发宽松放行`() = runTest {
        // 完全无法反查 → false
        assertTrue(!matcher.isKnownRegion("佩运週"))
    }

    @Test
    fun `空字符串返回false`() = runTest {
        assertTrue(!matcher.isKnownRegion(""))
    }
}
