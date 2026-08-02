package com.yymm.monitor.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.yymm.monitor.data.local.entity.MatchRuleEntity
import com.yymm.monitor.data.model.LocationRule
import com.yymm.monitor.data.model.WeightRange
import com.yymm.monitor.data.repository.RegionRepository
import com.yymm.monitor.data.repository.RuleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 规则编辑 ViewModel（PRD 3.2 B.2/B.3）。
 * 管理编辑中的规则状态，保存时校验。
 */
@HiltViewModel
class RuleViewModel @Inject constructor(
    private val ruleRepository: RuleRepository,
    private val regionRepository: RegionRepository
) : ViewModel() {

    private val gson = Gson()

    private val _currentRule = MutableStateFlow<MatchRuleEntity?>(null)
    val currentRule: StateFlow<MatchRuleEntity?> = _currentRule.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    /** 保存校验结果（由 UI 层展示 Snackbar） */
    private val _validationMessage = MutableStateFlow<String?>(null)
    val validationMessage: StateFlow<String?> = _validationMessage.asStateFlow()

    init {
        createNewRule()
    }

    fun loadRule(ruleId: Long) {
        viewModelScope.launch {
            val all = ruleRepository.getAllRules()
            _currentRule.value = all.firstOrNull { it.id == ruleId }
        }
    }

    fun createNewRule() {
        _currentRule.value = MatchRuleEntity(
            name = "",
            ruleStatus = "STANDBY",
            fromLocationsJson = "[]",
            fromCustomPointsJson = "[]",
            toLocationsJson = "[]",
            toCustomPointsJson = "[]",
            cargoTypesJson = "[]",
            vehicleLengthsJson = "[]",
            vehicleTypesJson = "[]",
            loadTimeOption = "ANY",
            weightRangesJson = "[]"
        )
    }

    fun updateName(name: String) {
        updateCurrent { it.copy(name = name) }
    }

    fun updateFromLocations(locations: List<LocationRule>) {
        updateCurrent { it.copy(fromLocationsJson = gson.toJson(locations)) }
    }

    fun updateToLocations(locations: List<LocationRule>) {
        updateCurrent { it.copy(toLocationsJson = gson.toJson(locations)) }
    }

    fun toggleCargoType(type: String) {
        updateCurrent {
            val current = parseStringList(it.cargoTypesJson).toMutableList()
            if (current.contains(type)) current.remove(type) else current.add(type)
            it.copy(cargoTypesJson = gson.toJson(current))
        }
    }

    fun toggleVehicleLength(length: String) {
        updateCurrent {
            val current = parseStringList(it.vehicleLengthsJson).toMutableList()
            if (current.contains(length)) current.remove(length) else current.add(length)
            it.copy(vehicleLengthsJson = gson.toJson(current))
        }
    }

    fun toggleVehicleType(type: String) {
        updateCurrent {
            val current = parseStringList(it.vehicleTypesJson).toMutableList()
            if (current.contains(type)) current.remove(type) else current.add(type)
            it.copy(vehicleTypesJson = gson.toJson(current))
        }
    }

    fun setLoadTimeOption(option: String) {
        updateCurrent { it.copy(loadTimeOption = option) }
    }

    fun setWeightMin(min: Float?) {
        updateCurrent { it.copy(weightMin = min) }
    }

    fun setWeightMax(max: Float?) {
        updateCurrent { it.copy(weightMax = max) }
    }

    fun toggleWeightRange(range: WeightRange) {
        updateCurrent {
            val current = parseWeightRanges(it.weightRangesJson).toMutableList()
            if (current.any { r -> r.min == range.min && r.max == range.max }) {
                current.removeAll { r -> r.min == range.min && r.max == range.max }
            } else {
                current.add(range)
            }
            it.copy(weightRangesJson = gson.toJson(current))
        }
    }

    fun setMinPrice(price: Int?) {
        updateCurrent { it.copy(minPrice = price) }
    }

    fun toggleDianyiWithPrice(enabled: Boolean) {
        updateCurrent { it.copy(includeDianyiWithPrice = enabled) }
    }

    fun toggleDianyiWithoutPrice(enabled: Boolean) {
        updateCurrent { it.copy(includeDianyiWithoutPrice = enabled) }
    }

    fun toggleExtra(tag: String, enabled: Boolean) {
        updateCurrent { rule ->
            when (tag) {
                "高信用货主" -> rule.copy(requireHighCredit = enabled)
                "满运宝" -> rule.copy(requireManyunbao = enabled)
                "订金可退" -> rule.copy(requireDepositRefundable = enabled)
                "送抢单特权" -> rule.copy(requirePrivilege = enabled)
                else -> rule
            }
        }
    }

    /**
     * 保存规则（PRD B.3 校验）：
     * - 出发地和目的地至少选一个，否则返回校验消息
     * - 自定义重量最小 > 最大时提示
     */
    fun saveRule(onSuccess: () -> Unit) {
        val rule = _currentRule.value ?: return
        val fromLocations = parseLocations(rule.fromLocationsJson)
        val toLocations = parseLocations(rule.toLocationsJson)

        if (fromLocations.isEmpty() && toLocations.isEmpty()) {
            _validationMessage.value = "请至少选择一个出发地"
            return
        }
        if (rule.weightMin != null && rule.weightMax != null && rule.weightMin > rule.weightMax) {
            _validationMessage.value = "最小重量不能大于最大重量"
            return
        }
        if (rule.name.isBlank()) {
            _validationMessage.value = "请输入规则名称"
            return
        }

        _isSaving.value = true
        viewModelScope.launch {
            ruleRepository.saveRule(rule.copy(updatedAt = System.currentTimeMillis()))
            _isSaving.value = false
            onSuccess()
        }
    }

    fun consumeValidationMessage() {
        _validationMessage.value = null
    }

    private fun updateCurrent(transform: (MatchRuleEntity) -> MatchRuleEntity) {
        _currentRule.value = _currentRule.value?.let { transform(it) }
    }

    private fun parseLocations(json: String): List<LocationRule> {
        return try { gson.fromJson(json, Array<LocationRule>::class.java).toList() }
        catch (e: Exception) { emptyList() }
    }

    private fun parseStringList(json: String): List<String> {
        return try { gson.fromJson(json, Array<String>::class.java).toList() }
        catch (e: Exception) { emptyList() }
    }

    private fun parseWeightRanges(json: String): List<WeightRange> {
        return try { gson.fromJson(json, Array<WeightRange>::class.java).toList() }
        catch (e: Exception) { emptyList() }
    }

    /** 辅助：供 UI 层读取解析后的列表 */
    fun parseLocationsList(json: String): List<LocationRule> = parseLocations(json)
    fun parseStringListData(json: String): List<String> = parseStringList(json)
    fun parseWeightRangesData(json: String): List<WeightRange> = parseWeightRanges(json)

    /** 地区词典加载（RegionPicker 使用） */
    suspend fun loadRegions() = regionRepository.loadRegions()
}
