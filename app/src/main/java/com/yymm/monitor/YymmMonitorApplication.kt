package com.yymm.monitor

import android.app.Application
import com.yymm.monitor.data.repository.HistoryRepository
import com.yymm.monitor.data.repository.SettingsRepository
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口。
 *
 * 职责：
 * 1. 初始化 Hilt 依赖注入容器。
 * 2. 启动时按设置清理过期历史记录。
 */
@HiltAndroidApp
class YymmMonitorApplication : Application() {

    @Inject lateinit var historyRepository: HistoryRepository
    @Inject lateinit var settingsRepository: SettingsRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 按 historyRetentionDays 清理过期历史（0=永久保留，不清理）
        appScope.launch {
            val retentionDays = settingsRepository.getHistoryRetentionDays()
            if (retentionDays > 0) {
                historyRepository.cleanupOldRecords(retentionDays)
            }
        }
    }
}
