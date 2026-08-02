package com.yymm.monitor.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yymm.monitor.data.local.entity.HistoryRecordEntity

/**
 * 历史记录表 DAO（TECH_SPEC 2.4）。
 */
@Dao
interface HistoryDao {

    @Query("SELECT * FROM history_records WHERE date = :date ORDER BY alertTime DESC")
    suspend fun getByDate(date: String): List<HistoryRecordEntity>

    @Query("SELECT orderId FROM history_records WHERE date = :date")
    suspend fun getOrderIdsByDate(date: String): List<String>

    @Query("UPDATE history_records SET action = :action WHERE orderId = :orderId AND date = :date")
    suspend fun updateAction(orderId: String, action: String, date: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: HistoryRecordEntity)

    @Query("DELETE FROM history_records WHERE date < :date")
    suspend fun deleteBefore(date: String)
}
