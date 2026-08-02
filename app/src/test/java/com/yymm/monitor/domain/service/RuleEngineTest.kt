package com.yymm.monitor.domain.service

import com.google.gson.Gson
import com.yymm.monitor.data.local.entity.MatchRuleEntity
import com.yymm.monitor.data.model.AlertSettings
import com.yymm.monitor.data.model.LocationRule
import com.yymm.monitor.data.model.ParsedOrder
import com.yymm.monitor.data.model.UserSettings
import com.yymm.monitor.data.model.WeightRange
import com.yymm.monitor.data.repository.RuleRepository
import com.yymm.monitor.data.repository.SettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RuleEngine 单元测试。
 *
 * 覆盖：OR 关系、宽松模式、价格匹配、更多筛选、快速失败。
 */
class RuleEngineTest {

    private val gson = Gson()

    private lateinit var engine: RuleEngine
    private lateinit var repo: FakeRuleRepository
    private lateinit var settingsRepo: FakeSettingsRepository

    /** 用 runTest 运行 suspend match()，无需 Android 环境 */
    private class FakeRuleRepository : RuleRepository {
        val rules = mutableListOf<MatchRuleEntity>()
        override suspend fun getAllRules() = rules.toList()
        override suspend fun getStandbyRules() = rules.filter { it.ruleStatus == "STANDBY" }
        override suspend fun getActiveRules() = rules.filter { it.ruleStatus == "ACTIVE" }
        override suspend fun saveRule(rule: MatchRuleEntity): Long = 0
        override suspend fun deleteRule(ruleId: Long) {}
        override suspend fun activateRule(ruleId: Long) {}
        override suspend fun deactivateRule(ruleId: Long) {}
        override suspend fun activateAll() {}
        override suspend fun deactivateAll() {}
        override suspend fun duplicateRule(ruleId: Long): Long = 0
    }

    /** 默认 INCLUDE_CHILDREN 匹配模式 */
    private class FakeSettingsRepository : SettingsRepository {
        var currentMatchMode = "INCLUDE_CHILDREN"
        override fun getSettings() = UserSettings(matchMode = currentMatchMode)
        override fun saveSettings(settings: UserSettings) { currentMatchMode = settings.matchMode }
        override fun getRefreshInterval() = 5
        override fun getMatchMode() = currentMatchMode
        override fun getAlertSettings() = AlertSettings(
            popupEnabled = true,
            vibrateEnabled = true,
            vibrateDurationMs = 3000,
            ringtoneEnabled = true,
            ringtoneUri = null,
            ringtoneVolume = 1.0f,
            ttsEnabled = true,
            ttsSpeed = 1.0f
        )
        override fun getPopupDuration() = 0
        override fun getMultiOrderHandling() = "QUEUE"
        override fun getHistoryRetentionDays() = 7
    }

    private fun buildRule(
        ruleStatus: String = "ACTIVE",
        fromLocationsJson: String = "[]",
        fromCustomPointsJson: String = "[]",
        toLocationsJson: String = "[]",
        toCustomPointsJson: String = "[]",
        cargoTypesJson: String = "[]",
        vehicleLengthsJson: String = "[]",
        vehicleTypesJson: String = "[]",
        loadTimeOption: String = "ANY",
        loadTimeStart: Long? = null,
        loadTimeEnd: Long? = null,
        weightRangesJson: String = "[]",
        weightMin: Float? = null,
        weightMax: Float? = null,
        minPrice: Int? = null,
        includeDianyiWithPrice: Boolean = false,
        includeDianyiWithoutPrice: Boolean = false,
        requireHighCredit: Boolean = false,
        requireManyunbao: Boolean = false,
        requireDepositRefundable: Boolean = false,
        requirePrivilege: Boolean = false
    ) = MatchRuleEntity(
        id = 1,
        name = "测试规则",
        ruleStatus = ruleStatus,
        fromLocationsJson = fromLocationsJson,
        fromCustomPointsJson = fromCustomPointsJson,
        toLocationsJson = toLocationsJson,
        toCustomPointsJson = toCustomPointsJson,
        cargoTypesJson = cargoTypesJson,
        vehicleLengthsJson = vehicleLengthsJson,
        vehicleTypesJson = vehicleTypesJson,
        loadTimeOption = loadTimeOption,
        loadTimeStart = loadTimeStart,
        loadTimeEnd = loadTimeEnd,
        weightRangesJson = weightRangesJson,
        weightMin = weightMin,
        weightMax = weightMax,
        minPrice = minPrice,
        includeDianyiWithPrice = includeDianyiWithPrice,
        includeDianyiWithoutPrice = includeDianyiWithoutPrice,
        requireHighCredit = requireHighCredit,
        requireManyunbao = requireManyunbao,
        requireDepositRefundable = requireDepositRefundable,
        requirePrivilege = requirePrivilege
    )

    private fun locationsJson(vararg rules: LocationRule) = gson.toJson(rules.toList())
    private fun stringsJson(vararg strings: String) = gson.toJson(strings.toList())
    private fun weightRangesJson(vararg ranges: WeightRange) = gson.toJson(ranges.toList())

    private fun buildOrder(
        fromProvince: String = "江苏",
        fromCity: String? = "苏州",
        toProvince: String = "广东",
        toCity: String? = "广州",
        cargoName: String? = "机械设备",
        weightMin: Float? = 3f,
        weightMax: Float? = 4f,
        vehicleLengths: List<String> = listOf("4.2"),
        vehicleTypes: List<String> = listOf("平板"),
        priceAmount: Int? = 3800,
        priceText: String = "3800元",
        isDianyi: Boolean = false,
        loadTimeText: String = "今天装货",
        distance: String? = null,
        tags: List<String> = emptyList(),
        rawTexts: List<String> = listOf("江苏 苏州", "广东 广州")
    ) = ParsedOrder(
        id = "test-order-1",
        fromProvince = fromProvince,
        fromCity = fromCity,
        toProvince = toProvince,
        toCity = toCity,
        cargoName = cargoName,
        weightMin = weightMin,
        weightMax = weightMax,
        vehicleLengths = vehicleLengths,
        vehicleTypes = vehicleTypes,
        priceAmount = priceAmount,
        priceText = priceText,
        isDianyi = isDianyi,
        loadTimeText = loadTimeText,
        distance = distance,
        tags = tags,
        rawTexts = rawTexts
    )

    @Before
    fun setup() {
        repo = FakeRuleRepository()
        settingsRepo = FakeSettingsRepository()
        engine = RuleEngine(repo, settingsRepo)
    }

    // ─────────────────── OR 关系与空列表 ───────────────────

    @Test
    fun `空规则列表返回false`() = runTest {
        assertFalse(engine.match(buildOrder()))
    }

    @Test
    fun `多套规则OR关系_规则A不匹配但规则B匹配返回true`() = runTest {
        repo.rules.add(
            buildRule(
                fromLocationsJson = locationsJson(LocationRule("北京")),
                toLocationsJson = locationsJson(LocationRule("北京"))
            )
        )
        repo.rules.add(buildRule()) // 全空 = 无限制，必匹配
        assertTrue(engine.match(buildOrder()))
    }

    @Test
    fun `单条规则全部匹配返回true`() = runTest {
        repo.rules.add(buildRule())
        assertTrue(engine.match(buildOrder()))
    }

    // ─────────────────── 出发地 / 目的地 ───────────────────

    @Test
    fun `出发地不匹配返回false`() = runTest {
        repo.rules.add(buildRule(fromLocationsJson = locationsJson(LocationRule("北京"))))
        assertFalse(engine.match(buildOrder()))
    }

    @Test
    fun `目的地不匹配返回false`() = runTest {
        repo.rules.add(buildRule(toLocationsJson = locationsJson(LocationRule("北京"))))
        assertFalse(engine.match(buildOrder()))
    }

    @Test
    fun `出发地城市不匹配返回false`() = runTest {
        repo.rules.add(
            buildRule(
                fromLocationsJson = locationsJson(LocationRule(province = "江苏", city = "南京"))
            )
        )
        assertFalse(engine.match(buildOrder()))
    }

    @Test
    fun `全国出发地匹配任意省份`() = runTest {
        repo.rules.add(buildRule(fromLocationsJson = locationsJson(LocationRule("全国"))))
        assertTrue(engine.match(buildOrder()))
    }

    @Test
    fun `自定义出发地匹配rawTexts返回true`() = runTest {
        repo.rules.add(buildRule(fromCustomPointsJson = stringsJson("苏州相城")))
        assertTrue(engine.match(buildOrder()))
    }

    // ─────────────────── 货源类型 ───────────────────

    @Test
    fun `货源类型不匹配返回false`() = runTest {
        repo.rules.add(buildRule(cargoTypesJson = stringsJson("FULL_VEHICLE")))
        assertFalse(engine.match(buildOrder(tags = emptyList())))
    }

    @Test
    fun `货源类型匹配返回true`() = runTest {
        repo.rules.add(buildRule(cargoTypesJson = stringsJson("FULL_VEHICLE")))
        assertTrue(engine.match(buildOrder(tags = listOf("整车货"))))
    }

    // ─────────────────── 车长 / 车型（宽松） ───────────────────

    @Test
    fun `车长不匹配但订单不限车长返回true`() = runTest {
        repo.rules.add(buildRule(vehicleLengthsJson = stringsJson("9.6")))
        assertTrue(engine.match(buildOrder(vehicleLengths = emptyList())))
    }

    @Test
    fun `车长精确匹配返回true`() = runTest {
        repo.rules.add(buildRule(vehicleLengthsJson = stringsJson("4.2")))
        assertTrue(engine.match(buildOrder(vehicleLengths = listOf("4.2"))))
    }

    @Test
    fun `车长startsWith宽松匹配_4匹配4_2米`() = runTest {
        repo.rules.add(buildRule(vehicleLengthsJson = stringsJson("4")))
        assertTrue(engine.match(buildOrder(vehicleLengths = listOf("4.2"))))
    }

    @Test
    fun `车长不匹配返回false`() = runTest {
        repo.rules.add(buildRule(vehicleLengthsJson = stringsJson("9.6")))
        assertFalse(engine.match(buildOrder(vehicleLengths = listOf("4.2"))))
    }

    @Test
    fun `车型不匹配但订单不限车型返回true`() = runTest {
        repo.rules.add(buildRule(vehicleTypesJson = stringsJson("高栏")))
        assertTrue(engine.match(buildOrder(vehicleTypes = emptyList())))
    }

    @Test
    fun `车型匹配返回true`() = runTest {
        repo.rules.add(buildRule(vehicleTypesJson = stringsJson("平板")))
        assertTrue(engine.match(buildOrder(vehicleTypes = listOf("平板"))))
    }

    @Test
    fun `车型不匹配返回false`() = runTest {
        repo.rules.add(buildRule(vehicleTypesJson = stringsJson("高栏")))
        assertFalse(engine.match(buildOrder(vehicleTypes = listOf("平板"))))
    }

    // ─────────────────── 装货时间 ───────────────────

    @Test
    fun `装货时间TODAY匹配`() = runTest {
        repo.rules.add(buildRule(loadTimeOption = "TODAY"))
        assertTrue(engine.match(buildOrder(loadTimeText = "今天装货")))
    }

    @Test
    fun `装货时间TODAY不匹配返回false`() = runTest {
        repo.rules.add(buildRule(loadTimeOption = "TODAY"))
        assertFalse(engine.match(buildOrder(loadTimeText = "明天装货")))
    }

    // ─────────────────── 重量（宽松） ───────────────────

    @Test
    fun `重量不匹配返回false`() = runTest {
        repo.rules.add(buildRule(weightRangesJson = weightRangesJson(WeightRange(10f, 20f))))
        assertFalse(engine.match(buildOrder(weightMin = 3f, weightMax = 4f)))
    }

    @Test
    fun `重量缺失自动通过`() = runTest {
        repo.rules.add(buildRule(weightRangesJson = weightRangesJson(WeightRange(10f, 20f))))
        assertTrue(engine.match(buildOrder(weightMin = null, weightMax = null)))
    }

    @Test
    fun `重量在预设区间内匹配`() = runTest {
        repo.rules.add(buildRule(weightRangesJson = weightRangesJson(WeightRange(3f, 5f))))
        assertTrue(engine.match(buildOrder(weightMin = 3f, weightMax = 4f)))
    }

    @Test
    fun `自定义重量范围匹配`() = runTest {
        repo.rules.add(buildRule(weightMin = 2f, weightMax = 6f))
        assertTrue(engine.match(buildOrder(weightMin = 3f, weightMax = 4f)))
    }

    // ─────────────────── 价格 ───────────────────

    @Test
    fun `电议有价格_includeDianyiWithPrice为false返回false`() = runTest {
        repo.rules.add(buildRule(includeDianyiWithPrice = false))
        assertFalse(engine.match(buildOrder(isDianyi = true, priceAmount = 1200, priceText = "电议1200元")))
    }

    @Test
    fun `电议有价格_低于minPrice返回false`() = runTest {
        repo.rules.add(buildRule(includeDianyiWithPrice = true, minPrice = 2000))
        assertFalse(engine.match(buildOrder(isDianyi = true, priceAmount = 1200, priceText = "电议1200元")))
    }

    @Test
    fun `电议有价格_满足条件返回true`() = runTest {
        repo.rules.add(buildRule(includeDianyiWithPrice = true, minPrice = 1000))
        assertTrue(engine.match(buildOrder(isDianyi = true, priceAmount = 1200, priceText = "电议1200元")))
    }

    @Test
    fun `电议无价格_includeDianyiWithoutPrice为true返回true_不受minPrice限制`() = runTest {
        repo.rules.add(buildRule(includeDianyiWithoutPrice = true, minPrice = 5000))
        assertTrue(engine.match(buildOrder(isDianyi = true, priceAmount = null, priceText = "电议")))
    }

    @Test
    fun `电议无价格_includeDianyiWithoutPrice为false返回false`() = runTest {
        repo.rules.add(buildRule(includeDianyiWithoutPrice = false))
        assertFalse(engine.match(buildOrder(isDianyi = true, priceAmount = null, priceText = "电议")))
    }

    @Test
    fun `非电议低于minPrice返回false`() = runTest {
        repo.rules.add(buildRule(minPrice = 2000))
        assertFalse(engine.match(buildOrder(priceAmount = 1200, priceText = "1200元")))
    }

    @Test
    fun `非电议价格缺失自动通过`() = runTest {
        repo.rules.add(buildRule(minPrice = 2000))
        assertTrue(engine.match(buildOrder(priceAmount = null, priceText = "")))
    }

    // ─────────────────── 更多筛选（AND） ───────────────────

    @Test
    fun `更多筛选_高信用货主不满足返回false`() = runTest {
        repo.rules.add(buildRule(requireHighCredit = true))
        assertFalse(engine.match(buildOrder(tags = emptyList())))
    }

    @Test
    fun `更多筛选_所有条件满足返回true`() = runTest {
        repo.rules.add(
            buildRule(
                requireHighCredit = true,
                requireManyunbao = true,
                requireDepositRefundable = true,
                requirePrivilege = true
            )
        )
        assertTrue(
            engine.match(
                buildOrder(tags = listOf("高信用货主", "满运宝", "订金可退", "送抢单特权"))
            )
        )
    }

    // ─────────────────── 非 ACTIVE 规则 ───────────────────

    @Test
    fun `STANDBY规则不参与匹配`() = runTest {
        repo.rules.add(buildRule(ruleStatus = "STANDBY"))
        assertFalse(engine.match(buildOrder()))
    }

    // ─────────────────── 匹配模式（包含下级/同层精确） ───────────────────

    @Test
    fun `包含下级模式_选省匹配省内城市订单`() = runTest {
        settingsRepo.currentMatchMode = "INCLUDE_CHILDREN"
        repo.rules.add(
            buildRule(
                fromLocationsJson = locationsJson(LocationRule(province = "山东"))
            )
        )
        // 订单出发地：山东 济南 → 匹配（山东包含济南）
        assertTrue(engine.match(buildOrder(fromProvince = "山东", fromCity = "济南")))
    }

    @Test
    fun `同层精确模式_选省不匹配省内城市订单`() = runTest {
        settingsRepo.currentMatchMode = "EXACT"
        repo.rules.add(
            buildRule(
                fromLocationsJson = locationsJson(LocationRule(province = "山东"))
            )
        )
        // 订单出发地：山东 济南 → 不匹配（精确模式只匹配省一级）
        assertFalse(engine.match(buildOrder(fromProvince = "山东", fromCity = "济南")))
    }

    @Test
    fun `省名规范化_带后缀也匹配`() = runTest {
        settingsRepo.currentMatchMode = "INCLUDE_CHILDREN"
        repo.rules.add(
            buildRule(
                fromLocationsJson = locationsJson(LocationRule(province = "山东"))
            )
        )
        // 订单出发地：山东省 济南市 → 匹配（规范化后"山东"=="山东"）
        assertTrue(engine.match(buildOrder(fromProvince = "山东省", fromCity = "济南市")))
    }
}
