package com.yymm.monitor.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yymm.monitor.data.local.entity.HistoryRecordEntity
import com.yymm.monitor.data.repository.HistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 历史记录 ViewModel（PRD 3.8 模块H）。
 * 加载今日记录，按 Tab 过滤。
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val historyRepository: HistoryRepository
) : ViewModel() {

    private val _allRecords = MutableStateFlow<List<HistoryRecordEntity>>(emptyList())
    val allRecords: StateFlow<List<HistoryRecordEntity>> = _allRecords.asStateFlow()

    private val _viewedRecords = MutableStateFlow<List<HistoryRecordEntity>>(emptyList())
    val viewedRecords: StateFlow<List<HistoryRecordEntity>> = _viewedRecords.asStateFlow()

    private val _ignoredRecords = MutableStateFlow<List<HistoryRecordEntity>>(emptyList())
    val ignoredRecords: StateFlow<List<HistoryRecordEntity>> = _ignoredRecords.asStateFlow()

    private val _currentTab = MutableStateFlow(0) // 0=全部, 1=已查看, 2=已忽略
    val currentTab: StateFlow<Int> = _currentTab.asStateFlow()

    fun loadTodayRecords() {
        viewModelScope.launch {
            val records = historyRepository.getTodayRecords()
            _allRecords.value = records
            _viewedRecords.value = records.filter { it.action == "VIEWED" }
            _ignoredRecords.value = records.filter { it.action == "IGNORED" }
        }
    }

    fun filterByTab(tab: Int) {
        _currentTab.value = tab
    }

    /** 当前 Tab 对应的记录列表 */
    fun recordsForCurrentTab(): List<HistoryRecordEntity> = when (_currentTab.value) {
        1 -> _viewedRecords.value
        2 -> _ignoredRecords.value
        else -> _allRecords.value
    }
}
