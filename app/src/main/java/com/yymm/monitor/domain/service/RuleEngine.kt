package com.yymm.monitor.domain.service

import com.google.gson.Gson
import com.yymm.monitor.data.local.entity.MatchRuleEntity
import com.yymm.monitor.data.model.LocationRule
import com.yymm.monitor.data.model.ParsedOrder
import com.yymm.monitor.data.model.WeightRange
import com.yymm.monitor.data.repository.RuleRepository
import com.yymm.monitor.data.repository.SettingsRepository
import com.yymm.monitor.domain.model.MatchResult
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 规则引擎（TECH_SPEC 2.3.3 / PRD 3.6 F.2）。
 *
 * 匹配区内多套规则 OR 关系（满足任意一套即提醒），单套规则内各字段快速失败。
 * 宽松模式：用户未设置/订单字段缺失/订单"不限" 均自动通过。
 */
@Singleton
class RuleEngine @Inject constructor(
    private val ruleRepository: RuleRepository,
    private val settingsRepository: SettingsRepository,
    private val regionMatcher: RegionMatcher
) {

    suspend fun match(order: ParsedOrder): MatchResult? {
        val activeRules = ruleRepository.getActiveRules()
        if (activeRules.isEmpty()) return null
        for (rule in activeRules) {
            matchOrderWithRule(order, rule)?.let { return it }
        }
        return null
    }

    /** 获取全部激活规则（用于悬浮窗"当前匹配规则"汇总展示） */
    suspend fun activeRules(): List<MatchRuleEntity> = ruleRepository.getActiveRules()

    suspend fun matchOrderWithRule(order: ParsedOrder, rule: MatchRuleEntity): MatchResult? {
        if (rule.ruleStatus != "ACTIVE") return null

        // 匹配模式：INCLUDE_CHILDREN=包含下级（选省匹配省内所有市区），EXACT=同层精确
        val includeChildren = settingsRepository.getMatchMode() != "EXACT"

        val fromLocations = parseLocations(rule.fromLocationsJson)
        val toLocations = parseLocations(rule.toLocationsJson)
        val cargoTypes = parseStringList(rule.cargoTypesJson)
        val lengths = parseStringList(rule.vehicleLengthsJson)
        val types = parseStringList(rule.vehicleTypesJson)
        val weightRanges = parseWeightRanges(rule.weightRangesJson)

        // ========== 第一优先级：出发地与目的地 ==========
        // 1. 出发地匹配（OR）
        // 规则地区匹配：正常按 LocationRule.matches；OCR 乱码（反查不到地区词典）时
        // 地名不可靠 → 宽松放行（由真人对照屏幕判断），避免误杀
        val fromMatch = fromLocations.isEmpty() ||
            fromLocations.any {
                it.matches(
                    order.fromProvince, order.fromCity, order.fromDistrict,
                    includeChildren = includeChildren
                )
            } ||
            !regionMatcher.isKnownRegion(order.fromProvince) || // OCR 反查不到词典 → 乱码放行
            parseStringList(rule.fromCustomPointsJson).any { point ->
                order.rawTexts.any { it.contains(point) }
            }
        if (!fromMatch) return null

        // 2. 目的地匹配（OR）
        val toMatch = toLocations.isEmpty() ||
            toLocations.any {
                it.matches(
                    order.toProvince, order.toCity, order.toDistrict,
                    includeChildren = includeChildren
                )
            } ||
            !regionMatcher.isKnownRegion(order.toProvince) ||
            parseStringList(rule.toCustomPointsJson).any { point ->
                order.rawTexts.any { it.contains(point) }
            }
        if (!toMatch) return null

        // ========== 次要优先级：其他筛选条件 ==========
        // 3. 货源类型匹配（OR）
        val cargoMatch = cargoTypes.isEmpty() || cargoTypes.any { type ->
            when (type) {
                "FULL_VEHICLE" -> order.tags.contains("整车货")
                "PREMIUM" -> order.tags.contains("优车货")
                "INSTANT" -> order.tags.contains("即时单")
                "CARPOOL" -> order.tags.contains("拼车货")
                else -> false
            }
        }
        if (!cargoMatch) return null

        // 4. 车长匹配（OR）— 宽松模式：订单"不限车长"时自动通过
        // it.startsWith(len) 处理 "3.8" 匹配 "3.8米" 的情况
        val lengthMatch = lengths.isEmpty() ||
            order.vehicleLengths.isEmpty() ||
            lengths.any { len -> order.vehicleLengths.any { it == len || it.startsWith(len) } }
        if (!lengthMatch) return null

        // 5. 车型匹配（OR）— 宽松模式：订单"不限车型"时自动通过
        val typeMatch = types.isEmpty() ||
            order.vehicleTypes.isEmpty() ||
            types.any { type -> order.vehicleTypes.contains(type) }
        if (!typeMatch) return null

        // 6. 装货时间匹配（兼容"昨天"）
        val timeMatch = when (rule.loadTimeOption) {
            "ANY" -> true
            "TODAY" -> order.loadTimeText.contains("今天")
            "TOMORROW" -> order.loadTimeText.contains("明天")
            "AFTER_TOMORROW" -> order.loadTimeText.contains("后天") || order.loadTimeText.contains("明天以后")
            "CUSTOM" -> {
                val orderTime = parseOrderTime(order.loadTimeText)
                orderTime != null && rule.loadTimeStart != null && rule.loadTimeEnd != null &&
                    orderTime in rule.loadTimeStart..rule.loadTimeEnd
            }
            else -> true
        }
        if (!timeMatch) return null

        // 7. 重量匹配（OR）— 宽松模式：字段缺失时自动通过
        val weightMatch = if (weightRanges.isEmpty() && rule.weightMin == null && rule.weightMax == null) {
            true
        } else if (order.weightMin == null && order.weightMax == null) {
            true
        } else {
            val orderMin = order.weightMin ?: 0f
            val orderMax = order.weightMax ?: Float.MAX_VALUE
            val inPreset = weightRanges.any { range -> orderMin >= range.min && orderMax <= range.max }
            // 仅在用户设置了自定义重量范围时才参与计算（否则恒真，预设区间形同虚设）
            val hasCustom = rule.weightMin != null || rule.weightMax != null
            val inCustom = hasCustom &&
                (rule.weightMin == null || orderMin >= rule.weightMin) &&
                (rule.weightMax == null || orderMax <= rule.weightMax)
            inPreset || inCustom
        }
        if (!weightMatch) return null

        // 8. 价格匹配（宽松模式核心）
        val priceMatch = matchPrice(order, rule)
        if (!priceMatch) return null

        // 9. 更多筛选（AND）
        val extraMatch = (!rule.requireHighCredit || order.tags.contains("高信用货主")) &&
            (!rule.requireManyunbao || order.tags.contains("满运宝")) &&
            (!rule.requireDepositRefundable || order.tags.contains("订金可退")) &&
            (!rule.requirePrivilege || order.tags.contains("送抢单特权"))
        if (!extraMatch) return null

        // ========== 匹配成功：计算缺失字段（P1） ==========
        // 宽松模式下字段缺失仍放行，但记录缺失项供提醒标注"字段不全"
        val missingFields = mutableSetOf<String>()
        // 车长：规则设了车长条件，但订单没车长
        if (lengths.isNotEmpty() && order.vehicleLengths.isEmpty()) missingFields.add("车长")
        // 车型：规则设了车型条件，但订单没车型
        if (types.isNotEmpty() && order.vehicleTypes.isEmpty()) missingFields.add("车型")
        // 重量：规则设了重量条件，但订单没重量
        val weightRuleSet = weightRanges.isNotEmpty() || rule.weightMin != null || rule.weightMax != null
        if (weightRuleSet && order.weightMin == null && order.weightMax == null) missingFields.add("重量")
        // 价格：规则设了价格条件，但订单没价格且非电议
        val priceRuleSet = rule.minPrice != null && rule.minPrice > 0 ||
            rule.includeDianyiWithPrice || rule.includeDianyiWithoutPrice
        if (priceRuleSet && order.priceAmount == null && !order.isDianyi) missingFields.add("价格")
        // 装货时间：规则设了装货时间条件，但订单没装货时间
        if (rule.loadTimeOption != "ANY" && order.loadTimeText.isBlank()) missingFields.add("装货时间")

        return MatchResult(order, rule, missingFields)
    }

    /**
     * 价格匹配核心逻辑（宽松模式）。
     *
     * - 电议有价格：检查 includeDianyiWithPrice，再检查 minPrice
     * - 电议无价格：直接返回 includeDianyiWithoutPrice（不受 minPrice 限制）
     * - 非电议有价格：检查 minPrice
     * - 非电议无价格（字段缺失）：宽松模式返回 true
     */
    private fun matchPrice(order: ParsedOrder, rule: MatchRuleEntity): Boolean {
        // 情况1：订单是电议
        if (order.isDianyi) {
            if (order.priceAmount != null) {
                // 电议有价格
                if (!rule.includeDianyiWithPrice) return false
                if (rule.minPrice != null && rule.minPrice > 0 && order.priceAmount < rule.minPrice) {
                    return false
                }
                return true
            } else {
                // 电议无价格
                return rule.includeDianyiWithoutPrice // 不受最低运费限制
            }
        }

        // 情况2：订单不是电议，有明确价格
        if (order.priceAmount != null) {
            if (rule.minPrice != null && rule.minPrice > 0 && order.priceAmount < rule.minPrice) {
                return false
            }
            return true
        }

        // 情况3：订单无价格且不是电议（字段缺失，宽松模式自动通过）
        return true // 信息不完整仍推送，由真人判断
    }

    private fun parseLocations(json: String): List<LocationRule> {
        return try { Gson().fromJson(json, Array<LocationRule>::class.java).toList() }
        catch (e: Exception) { emptyList() }
    }

    private fun parseStringList(json: String): List<String> {
        return try { Gson().fromJson(json, Array<String>::class.java).toList() }
        catch (e: Exception) { emptyList() }
    }

    private fun parseWeightRanges(json: String): List<WeightRange> {
        return try { Gson().fromJson(json, Array<WeightRange>::class.java).toList() }
        catch (e: Exception) { emptyList() }
    }

    private fun parseOrderTime(loadTimeText: String): Long? {
        val calendar = Calendar.getInstance()
        return when {
            loadTimeText.contains("今天") -> calendar.timeInMillis
            loadTimeText.contains("明天") -> calendar.apply { add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
            loadTimeText.contains("后天") -> calendar.apply { add(Calendar.DAY_OF_YEAR, 2) }.timeInMillis
            loadTimeText.contains("昨天") -> calendar.apply { add(Calendar.DAY_OF_YEAR, -1) }.timeInMillis
            else -> null
        }
    }
}
