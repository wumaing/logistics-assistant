package com.yymm.monitor.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.yymm.monitor.data.local.dao.HistoryDao
import com.yymm.monitor.data.local.dao.RuleDao
import com.yymm.monitor.data.local.entity.HistoryRecordEntity
import com.yymm.monitor.data.local.entity.MatchRuleEntity

/**
 * 浏览助手数据库（TECH_SPEC 2.4）。
 *
 * v1 -> v2：重构规则与历史记录表结构。
 * 使用 fallbackToDestructiveMigration()（见 AppModule），无需迁移脚本。
 */
@Database(
    entities = [MatchRuleEntity::class, HistoryRecordEntity::class],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun ruleDao(): RuleDao

    abstract fun historyDao(): HistoryDao
}
