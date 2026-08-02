package com.yymm.monitor.di

import com.yymm.monitor.data.repository.HistoryRepository
import com.yymm.monitor.data.repository.RegionRepository
import com.yymm.monitor.data.repository.RuleRepository
import com.yymm.monitor.data.repository.SettingsRepository
import com.yymm.monitor.data.repository.impl.HistoryRepositoryImpl
import com.yymm.monitor.data.repository.impl.RegionRepositoryImpl
import com.yymm.monitor.data.repository.impl.RuleRepositoryImpl
import com.yymm.monitor.data.repository.impl.SettingsRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Repository 接口绑定模块。
 *
 * 将 Repository 接口绑定到具体实现（实现类本身为 @Singleton）。
 * 接口无法通过 @Inject constructor 自动注入，必须有 @Binds 声明。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindRuleRepository(impl: RuleRepositoryImpl): RuleRepository

    @Binds
    @Singleton
    abstract fun bindHistoryRepository(impl: HistoryRepositoryImpl): HistoryRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindRegionRepository(impl: RegionRepositoryImpl): RegionRepository
}
