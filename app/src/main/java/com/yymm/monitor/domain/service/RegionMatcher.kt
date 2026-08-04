package com.yymm.monitor.domain.service

import com.yymm.monitor.data.model.RegionData
import com.yymm.monitor.data.repository.RegionRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * 地区词典反查 + 模糊匹配（P0：根治 OCR 地名乱码/结构错位）。
 *
 * 司机端订单卡只显示"济宁任城→上海…"，无省名，且 OCR 常把地名读错
 * （兖→充、鄄→郭、任城→任城成、渭→谓）。这里用 assets/regions.json 建 省/市/区 三级词典，
 * 对 OCR 地名串做「分层反查」，重建正确的省市区层级。
 *
 * 匹配策略（关键洞察）：
 *  - 省名/市名：OCR 识别基本准确，用「精确包含」匹配（避免编辑距离产生的假匹配，
 *    如"济宁"vs"辽宁"编辑距离仅1但完全不同）
 *  - 区名：OCR 常错且候选集小（限定在市内），用「编辑距离≤1」模糊匹配
 *    （兖→充、鄄→郭 单字替换都能命中）
 *  - 尾部垃圾字符（佩运週/園运赛）自然忽略——只取前缀命中部分
 */
@Singleton
open class RegionMatcher @Inject constructor(
    private val regionRepository: RegionRepository
) {

    /** 解析结果：命中的省/市/区（后两级可为空，均用显示名） */
    data class RegionMatch(
        val province: String,
        val city: String?,
        val district: String?
    )

    /** 省名集合（规范名） */
    private var provinces = emptySet<String>()
    /** 全部市名（规范名） */
    private var allCities = emptySet<String>()
    /** 省 -> 该省下所有市（规范名） */
    private var citiesByProvince = emptyMap<String, Set<String>>()
    /** "省|市" -> 该市下所有区（规范名） */
    private var districtsByCity = emptyMap<String, Set<String>>()
    /** 规范名 -> 显示名 */
    private var displayOfProvince = emptyMap<String, String>()
    private var displayOfCity = emptyMap<String, String>()
    private var displayOfDistrict = emptyMap<String, String>()
    /** 市规范名 -> 所属省规范名 */
    private var provinceOfCity = emptyMap<String, String>()
    /** 区规范名 -> (所属省, 所属市) 规范名 */
    private var parentOfDistrict = emptyMap<String, Pair<String, String>>()

    private var loaded = false

    /** 懒加载地区词典（首次调用时从 RegionRepository 加载，之后复用） */
    private suspend fun ensureLoaded() {
        if (loaded) return
        buildIndex(regionRepository.loadRegions())
        loaded = true
    }

    private fun buildIndex(data: RegionData) {
        val pDisplayMap = mutableMapOf<String, String>()
        val cDisplayMap = mutableMapOf<String, String>()
        val dDisplayMap = mutableMapOf<String, String>()
        val cityMap = mutableMapOf<String, MutableSet<String>>()
        val districtMap = mutableMapOf<String, MutableSet<String>>()
        val cityProvinceMap = mutableMapOf<String, String>()
        val districtParentMap = mutableMapOf<String, Pair<String, String>>()
        val allCitySet = mutableSetOf<String>()

        for (province in data.provinces) {
            val pNorm = normalize(province.name)
            pDisplayMap[pNorm] = displayName(province.name)
            val citySet = mutableSetOf<String>()
            for (city in province.cities) {
                val cNorm = normalize(city.name)
                cDisplayMap[cNorm] = displayName(city.name)
                citySet.add(cNorm)
                allCitySet.add(cNorm)
                cityProvinceMap[cNorm] = pNorm
                val key = "$pNorm|$cNorm"
                val dSet = districtMap.getOrPut(key) { mutableSetOf() }
                for (district in city.districts) {
                    val dNorm = normalize(district.name)
                    dDisplayMap[dNorm] = displayName(district.name)
                    dSet.add(dNorm)
                    districtParentMap[dNorm] = pNorm to cNorm
                }
            }
            cityMap[pNorm] = citySet
        }

        provinces = pDisplayMap.keys
        displayOfProvince = pDisplayMap
        citiesByProvince = cityMap
        displayOfCity = cDisplayMap
        districtsByCity = districtMap
        displayOfDistrict = dDisplayMap
        allCities = allCitySet
        provinceOfCity = cityProvinceMap
        parentOfDistrict = districtParentMap
    }

    /**
     * 从 OCR 地名串反查词典，重建省市区。
     * 无法命中任何词典返回 null（调用方回退到原始串，触发宽松放行）。
     */
    suspend fun resolve(ocrRegion: String): RegionMatch? {
        if (ocrRegion.isBlank()) return null
        ensureLoaded()
        val input = normalize(ocrRegion)
        if (input.isEmpty()) return null

        // 1. 优先精确匹配省名（直辖市区县直接跟在省后：重庆忠县 / 北京顺义）
        val provinceNorm = matchProvince(input)
        if (provinceNorm != null) {
            val cityNorm = resolveCity(provinceNorm, input)
            // 区名匹配用市名之后的后缀（去掉已命中的省市前缀）
            val suffix = input.removePrefix(provinceNorm).let {
                if (cityNorm != null) it.removePrefix(cityNorm) else it
            }
            val districtNorm = cityNorm?.let { resolveDistrict(provinceNorm, it, suffix) }
            return RegionMatch(
                displayOfProvince[provinceNorm] ?: provinceNorm,
                cityNorm?.let { displayOfCity[it] ?: it },
                districtNorm?.let { displayOfDistrict[it] ?: it }
            )
        }

        // 2. 无省名 → 精确匹配市名（普通省份订单卡"市+区"连写：济宁任城）
        val cityNorm = matchCity(input)
        if (cityNorm != null) {
            val province = provinceOfCity[cityNorm]
            if (province != null) {
                // 区名匹配用市名之后的后缀
                val suffix = input.removePrefix(cityNorm)
                val districtNorm = resolveDistrict(province, cityNorm, suffix)
                return RegionMatch(
                    displayOfProvince[province] ?: province,
                    displayOfCity[cityNorm] ?: cityNorm,
                    districtNorm?.let { displayOfDistrict[it] ?: it }
                )
            }
        }

        // 3. 无省无市 → 尝试区名（县级市独立显示：余姚）
        val districtNorm = matchDistrictGlobal(input)
        if (districtNorm != null) {
            val parent = parentOfDistrict[districtNorm]
            if (parent != null) {
                return RegionMatch(
                    displayOfProvince[parent.first] ?: parent.first,
                    displayOfCity[parent.second] ?: parent.second,
                    displayOfDistrict[districtNorm] ?: districtNorm
                )
            }
        }

        return null
    }

    /** 省名精确匹配：输入以省名开头 或 包含省名（取最长匹配） */
    private fun matchProvince(input: String): String? {
        val sorted = provinces.sortedByDescending { it.length }
        return sorted.firstOrNull { input.startsWith(it) || it in input }
    }

    /** 市名精确匹配：输入以市名开头 或 包含市名（取最长匹配） */
    private fun matchCity(input: String): String? {
        val sorted = allCities.sortedByDescending { it.length }
        return sorted.firstOrNull { input.startsWith(it) || it in input }
    }

    /** 省命中后，在该省候选市内精确匹配市名（直辖市无独立市则回退省名） */
    private fun resolveCity(provinceNorm: String, input: String): String? {
        val cityCandidates = citiesByProvince[provinceNorm] ?: emptySet()
        val sorted = cityCandidates.sortedByDescending { it.length }
        val matched = sorted.firstOrNull { it in input }
        // 直辖市（重庆/北京/天津/上海）：区跟在省后，市名即省名
        return matched ?: if (provinceNorm in DIRECT_CITIES) provinceNorm else null
    }

    /** 市命中后，在该市候选区内编辑距离≤1 模糊匹配区名（suffix 为去掉省市前缀后的部分） */
    private fun resolveDistrict(province: String, cityNorm: String, suffix: String): String? {
        val districtCandidates = districtsByCity["$province|$cityNorm"] ?: emptySet()
        return matchDistrictFuzzy(suffix, districtCandidates)
    }

    /** 全局区名模糊匹配（县级市独立显示场景） */
    private fun matchDistrictGlobal(input: String): String? {
        return matchDistrictFuzzy(input, parentOfDistrict.keys)
    }

    /**
     * 区名模糊匹配：候选集内命中（OCR 单字替换/插入/丢失）。
     *
     * 策略：候选与后缀长度差≤1，且候选前缀与后缀前缀编辑距离≤1，取编辑距离最小者。
     * 覆盖 OCR 错误：
     *  - "充州" vs "兖州"：单字替换
     *  - "任城成" vs "任城"：插入字
     *  - "余姚" vs "余姚"：县级市独立显示
     *  - "佩运週" 纯垃圾：无候选长度匹配 → 返回 null
     */
    private fun matchDistrictFuzzy(suffix: String, candidates: Collection<String>): String? {
        var best: String? = null
        var bestScore = Int.MAX_VALUE
        for (c in candidates) {
            if (c.length > MAX_NAME_LEN || c.isEmpty()) continue
            // 候选与后缀长度差≤1（OCR 单字插入/删除）
            if (Math.abs(c.length - suffix.length) > 1) continue
            // 候选前缀与后缀前缀编辑距离≤1，取编辑距离最小者（最相似）
            val l = min(c.length, suffix.length)
            val d = levenshtein(c.take(l), suffix.take(l))
            if (d <= 1 && d < bestScore) {
                best = c
                bestScore = d
            }
        }
        return best
    }

    /** 判断 OCR 地名是否能反查到词典（宽松放行依据：能查到→严格匹配，查不到→放行） */
    open suspend fun isKnownRegion(ocrRegion: String): Boolean = resolve(ocrRegion) != null

    /** 直辖市：无独立市，区直接挂省下 */
    private val DIRECT_CITIES = setOf("重庆", "北京", "天津", "上海")

    companion object {
        /** 单个区名最大长度（区名如"济宁高新技术产业开发区"很长，但 OCR 一般只读前几个字） */
        private const val MAX_NAME_LEN = 6
    }

    /**
     * 地名规范化：去省/市/区等行政后缀，保留"县"（忠县/鄄城县是区县名，县是名字一部分）。
     * 匹配用与返回用同一规范名，保证与 LocationRule 精确比较一致。
     */
    private fun normalize(name: String): String = name
        .replace("壮族自治区", "")
        .replace("回族自治区", "")
        .replace("维吾尔自治区", "")
        .replace("自治区", "")
        .replace("自治州", "")
        .replace("特别行政区", "")
        .replace("地区", "")
        .replace("盟", "")
        .replace("省", "")
        .replace("市", "")
        .replace("区", "")
        .trim()

    /** 显示名：与 normalize 相同 */
    private fun displayName(name: String): String = normalize(name)

    /** 编辑距离（Levenshtein） */
    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = min(min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost)
            }
        }
        return dp[a.length][b.length]
    }
}
