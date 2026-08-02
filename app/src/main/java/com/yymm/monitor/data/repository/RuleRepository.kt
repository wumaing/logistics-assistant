package com.yymm.monitor.data.repository

import com.yymm.monitor.data.local.entity.MatchRuleEntity

/**
 * 规则仓库接口（TECH_SPEC 2.2）。
 */
interface RuleRepository {

    suspend fun getAllRules(): List<MatchRuleEntity>

    suspend fun getStandbyRules(): List<MatchRuleEntity>

    suspend fun getActiveRules(): List<MatchRuleEntity>

    suspend fun saveRule(rule: MatchRuleEntity): Long

    suspend fun deleteRule(ruleId: Long)

    suspend fun activateRule(ruleId: Long)

    suspend fun deactivateRule(ruleId: Long)

    suspend fun activateAll()

    suspend fun deactivateAll()

    suspend fun duplicateRule(ruleId: Long): Long
}
