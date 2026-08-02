package com.yymm.monitor.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yymm.monitor.data.local.entity.MatchRuleEntity

/**
 * 规则表 DAO（TECH_SPEC 2.4）。
 */
@Dao
interface RuleDao {

    @Query("SELECT * FROM match_rules ORDER BY updatedAt DESC")
    suspend fun getAll(): List<MatchRuleEntity>

    @Query("SELECT * FROM match_rules WHERE ruleStatus = 'STANDBY' ORDER BY updatedAt DESC")
    suspend fun getStandby(): List<MatchRuleEntity>

    @Query("SELECT * FROM match_rules WHERE ruleStatus = 'ACTIVE' ORDER BY updatedAt DESC")
    suspend fun getActive(): List<MatchRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: MatchRuleEntity): Long

    @Query("DELETE FROM match_rules WHERE id = :ruleId")
    suspend fun delete(ruleId: Long)

    @Query("UPDATE match_rules SET ruleStatus = 'ACTIVE', updatedAt = :timestamp WHERE id = :ruleId")
    suspend fun activate(ruleId: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE match_rules SET ruleStatus = 'STANDBY', updatedAt = :timestamp WHERE id = :ruleId")
    suspend fun deactivate(ruleId: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE match_rules SET ruleStatus = 'ACTIVE', updatedAt = :timestamp WHERE ruleStatus = 'STANDBY'")
    suspend fun activateAll(timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE match_rules SET ruleStatus = 'STANDBY', updatedAt = :timestamp WHERE ruleStatus = 'ACTIVE'")
    suspend fun deactivateAll(timestamp: Long = System.currentTimeMillis())
}
