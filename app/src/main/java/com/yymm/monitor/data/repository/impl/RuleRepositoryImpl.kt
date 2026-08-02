package com.yymm.monitor.data.repository.impl

import com.yymm.monitor.data.local.dao.RuleDao
import com.yymm.monitor.data.local.entity.MatchRuleEntity
import com.yymm.monitor.data.repository.RuleRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 规则仓库实现（TECH_SPEC 2.2）。
 *
 * duplicateRule：查询原规则，name 加 "-副本"，ruleStatus 设为 STANDBY，保存后返回新 id。
 */
@Singleton
class RuleRepositoryImpl @Inject constructor(
    private val ruleDao: RuleDao
) : RuleRepository {

    override suspend fun getAllRules(): List<MatchRuleEntity> = ruleDao.getAll()

    override suspend fun getStandbyRules(): List<MatchRuleEntity> = ruleDao.getStandby()

    override suspend fun getActiveRules(): List<MatchRuleEntity> = ruleDao.getActive()

    override suspend fun saveRule(rule: MatchRuleEntity): Long = ruleDao.insert(rule)

    override suspend fun deleteRule(ruleId: Long) = ruleDao.delete(ruleId)

    override suspend fun activateRule(ruleId: Long) = ruleDao.activate(ruleId)

    override suspend fun deactivateRule(ruleId: Long) = ruleDao.deactivate(ruleId)

    override suspend fun activateAll() = ruleDao.activateAll()

    override suspend fun deactivateAll() = ruleDao.deactivateAll()

    override suspend fun duplicateRule(ruleId: Long): Long {
        val original = ruleDao.getAll().firstOrNull { it.id == ruleId }
            ?: return -1L
        val copy = original.copy(
            id = 0, // 自增主键，插入时重新分配
            name = "${original.name}-副本",
            ruleStatus = "STANDBY",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        return ruleDao.insert(copy)
    }
}
