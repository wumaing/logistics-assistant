package com.yymm.monitor.di

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import com.yymm.monitor.data.local.AppDatabase
import com.yymm.monitor.data.local.dao.HistoryDao
import com.yymm.monitor.data.local.dao.RuleDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 依赖注入模块。
 *
 * 提供：
 * - AppDatabase（Room 数据库）
 * - RuleDao / HistoryDao
 * - SharedPreferences（yymm_settings）
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "yymm_monitor.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideRuleDao(db: AppDatabase): RuleDao = db.ruleDao()

    @Provides
    fun provideHistoryDao(db: AppDatabase): HistoryDao = db.historyDao()

    @Provides
    @Singleton
    fun provideSharedPreferences(@ApplicationContext context: Context): SharedPreferences =
        context.getSharedPreferences("yymm_settings", Context.MODE_PRIVATE)
}
