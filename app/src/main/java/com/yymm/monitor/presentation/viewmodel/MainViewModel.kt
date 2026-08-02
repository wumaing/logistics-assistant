package com.yymm.monitor.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yymm.monitor.data.local.entity.MatchRuleEntity
import com.yymm.monitor.data.repository.RuleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 主界面 ViewModel（PRD 3.2 模块B）。
 * 管理备战区/匹配区规则列表。
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val ruleRepository: RuleRepository
) : ViewModel() {

    private val _standbyRules = MutableStateFlow<List<MatchRuleEntity>>(emptyList())
    val standbyRules: StateFlow<List<MatchRuleEntity>> = _standbyRules.asStateFlow()

    private val _activeRules = MutableStateFlow<List<MatchRuleEntity>>(emptyList())
    val activeRules: StateFlow<List<MatchRuleEntity>> = _activeRules.asStateFlow()

    init {
        loadRules()
    }

    fun loadRules() {
        viewModelScope.launch {
            _standbyRules.value = ruleRepository.getStandbyRules()
            _activeRules.value = ruleRepository.getActiveRules()
        }
    }

    fun activateRule(ruleId: Long) {
        viewModelScope.launch {
            ruleRepository.activateRule(ruleId)
            loadRules()
        }
    }

    fun deactivateRule(ruleId: Long) {
        viewModelScope.launch {
            ruleRepository.deactivateRule(ruleId)
            loadRules()
        }
    }

    fun deleteRule(ruleId: Long) {
        viewModelScope.launch {
            ruleRepository.deleteRule(ruleId)
            loadRules()
        }
    }

    fun duplicateRule(ruleId: Long) {
        viewModelScope.launch {
            ruleRepository.duplicateRule(ruleId)
            loadRules()
        }
    }

    fun activateAll() {
        viewModelScope.launch {
            ruleRepository.activateAll()
            loadRules()
        }
    }

    fun deactivateAll() {
        viewModelScope.launch {
            ruleRepository.deactivateAll()
            loadRules()
        }
    }
}
