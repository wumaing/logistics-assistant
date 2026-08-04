package com.yymm.monitor.domain.model

import com.yymm.monitor.data.local.entity.MatchRuleEntity
import com.yymm.monitor.data.model.ParsedOrder

/**
 * 匹配结果（P1：字段不全宽松放行增强）。
 *
 * 相比原来 match() 返回 Boolean，这里携带更多上下文：
 * - [order]：匹配成功的订单
 * - [rule]：命中的规则（多条规则 OR 关系时可能有多条命中，这里记录首条）
 * - [missingFields]：规则要求但订单缺失的字段（用于提醒里标注"字段不全"）
 *
 * 字段缺失的含义（宽松模式）：规则设了某筛选条件，但订单 OCR 没读到该字段
 * （如规则要求车长=4.2米，但订单卡没显示车长）。这种情况下规则仍放行（宽松），
 * 但提醒要提示真人"这单信息不全，需多核实"。
 */
data class MatchResult(
    val order: ParsedOrder,
    val rule: MatchRuleEntity,
    val missingFields: Set<String>
) {
    /** 是否有规则要求的字段缺失 */
    val hasMissingFields: Boolean get() = missingFields.isNotEmpty()
}
