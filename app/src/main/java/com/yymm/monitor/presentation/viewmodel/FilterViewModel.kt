package com.yymm.monitor.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.yymm.monitor.data.local.entity.MatchRuleEntity
import com.yymm.monitor.data.model.LocationRule
import com.yymm.monitor.data.model.Province
import com.yymm.monitor.data.repository.RegionRepository
import com.yymm.monitor.data.repository.RuleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 筛选页 ViewModel（地区筛选：出发地/目的地）。
 *
 * 管理筛选状态：
 * - 出发地/目的地各自独立的已选列表（互不干扰）
 * - 级联导航状态（当前选中的省/市，用于面包屑）
 * - 搜索状态
 * 确认时生成一条 STANDBY 规则进入备战区。
 */
@HiltViewModel
class FilterViewModel @Inject constructor(
    private val regionRepository: RegionRepository,
    private val ruleRepository: RuleRepository
) : ViewModel() {

    private val gson = Gson()

    // ── 已选地区 ──
    private val _fromSelected = MutableStateFlow<List<LocationRule>>(emptyList())
    val fromSelected: StateFlow<List<LocationRule>> = _fromSelected.asStateFlow()

    private val _toSelected = MutableStateFlow<List<LocationRule>>(emptyList())
    val toSelected: StateFlow<List<LocationRule>> = _toSelected.asStateFlow()

    // ── 当前 Tab：FROM=出发地 / TO=目的地 ──
    private val _activeTab = MutableStateFlow("FROM")
    val activeTab: StateFlow<String> = _activeTab.asStateFlow()

    // ── 级联导航 ──
    private val _currentProvince = MutableStateFlow<Province?>(null)
    val currentProvince: StateFlow<Province?> = _currentProvince.asStateFlow()

    private val _currentCityIndex = MutableStateFlow<Int?>(null)
    val currentCityIndex: StateFlow<Int?> = _currentCityIndex.asStateFlow()

    // ── 地区数据 ──
    private val _provinces = MutableStateFlow<List<Province>>(emptyList())
    val provinces: StateFlow<List<Province>> = _provinces.asStateFlow()

    // ── 搜索 ──
    private val _keyword = MutableStateFlow("")
    val keyword: StateFlow<String> = _keyword.asStateFlow()

    private val _searchResults = MutableStateFlow<List<LocationRule>>(emptyList())
    val searchResults: StateFlow<List<LocationRule>> = _searchResults.asStateFlow()

    // ── 保存结果 ──
    private val _savedRuleId = MutableStateFlow<Long?>(null)
    val savedRuleId: StateFlow<Long?> = _savedRuleId.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** 层级规则提示（如"山东已包含济南，无需重复选择"） */
    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    fun consumeToast() {
        _toastMessage.value = null
    }

    init {
        viewModelScope.launch {
            try {
                _provinces.value = regionRepository.loadRegions().provinces
            } catch (_: Exception) {
                _errorMessage.value = "地区数据加载失败"
            }
        }
    }

    // ── Tab 切换 ──
    fun setActiveTab(tab: String) {
        if (tab == "FROM" || tab == "TO") {
            _activeTab.value = tab
            _keyword.value = ""
            _searchResults.value = emptyList()
            // 切换 Tab 时重置级联
            _currentProvince.value = null
            _currentCityIndex.value = null
        }
    }

    // ── 已选列表操作（层级包含规则） ──

    /**
     * 选择地区（层级互斥/包含规则）：
     *
     * 规则A：上级已选时，禁止再选下级 → Toast"山东已包含济南，无需重复选择"
     * 规则B：下级已选时，选择上级则替换 → Toast"【济南】已替换【历下区】"
     * 规则C：同级可多选
     *
     * @return true=选择生效；false=被规则A拦截（已包含，无操作）
     */
    fun toggleSelected(rule: LocationRule): Boolean {
        val selected = selectedList()

        // 已选相同的 → 取消选中
        if (selected.any { sameLocation(it, rule) }) {
            updateSelected(selected.filterNot { sameLocation(it, rule) })
            return true
        }

        // 规则A：上级已选，禁止选下级
        val coveredBy = selected.firstOrNull { covers(it, rule) }
        if (coveredBy != null) {
            _toastMessage.value = "${displayName(coveredBy)}已包含${displayName(rule)}，无需重复选择"
            return false
        }

        // 规则B：下级已选，选上级 → 替换所有被包含的下级
        val covered = selected.filter { covers(rule, it) }
        val newList = if (covered.isNotEmpty()) {
            val removed = selected.filterNot { covers(rule, it) }
            val replacedNames = covered.joinToString("、") { displayName(it) }
            _toastMessage.value = "【${displayName(rule)}】已替换【$replacedNames】"
            removed + rule
        } else {
            selected + rule
        }
        updateSelected(newList)
        return true
    }

    fun removeSelected(rule: LocationRule) {
        updateSelected(selectedList().filterNot { sameLocation(it, rule) })
    }

    fun clearSelected() {
        updateSelected(emptyList())
    }

    /** 是否被包含：parent 包含 child（parent 是 child 的上级或同级） */
    private fun covers(parent: LocationRule, child: LocationRule): Boolean {
        // 全国包含一切
        if (parent.province == "全国") return true
        // 省级包含同省下级
        if (parent.city == null && parent.district == null) {
            return child.province == parent.province
        }
        // 市级包含同市下级
        if (parent.district == null) {
            return child.province == parent.province && child.city == parent.city
        }
        // 区级仅包含自身
        return sameLocation(parent, child)
    }

    /** 地区显示名（含层级） */
    private fun displayName(rule: LocationRule): String =
        "${rule.province}${rule.city ?: ""}${rule.district ?: ""}"

    private fun selectedList(): List<LocationRule> =
        if (_activeTab.value == "FROM") _fromSelected.value else _toSelected.value

    private fun updateSelected(list: List<LocationRule>) {
        if (_activeTab.value == "FROM") {
            _fromSelected.value = list
        } else {
            _toSelected.value = list
        }
    }

    /** 同地判定：省/市/区完全相同即视为同一地 */
    private fun sameLocation(a: LocationRule, b: LocationRule): Boolean =
        a.province == b.province && a.city == b.city && a.district == b.district

    /** 判断某地区是否已被上级包含（用于 UI 禁用态） */
    fun isCoveredBySelection(rule: LocationRule): Boolean {
        val selected = selectedList()
        return selected.any { sel -> !sameLocation(sel, rule) && covers(sel, rule) }
    }

    // ── 级联导航 ──
    fun selectProvince(province: Province?) {
        _currentProvince.value = province
        _currentCityIndex.value = null
    }

    fun selectCity(index: Int?) {
        _currentCityIndex.value = index
    }

    fun backToProvince() {
        _currentProvince.value = null
        _currentCityIndex.value = null
    }

    fun backToCity() {
        _currentCityIndex.value = null
    }

    // ── 搜索 ──
    fun updateKeyword(k: String) {
        _keyword.value = k
        if (k.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        val results = mutableListOf<LocationRule>()
        for (p in _provinces.value) {
            if (p.name.contains(k)) {
                results.add(LocationRule(province = p.name))
            }
            for (c in p.cities) {
                if (c.name.contains(k)) {
                    results.add(LocationRule(province = p.name, city = c.name))
                }
                for (d in c.districts) {
                    if (d.name.contains(k)) {
                        results.add(LocationRule(province = p.name, city = c.name, district = d.name))
                    }
                }
            }
        }
        _searchResults.value = results
    }

    // ── 历史地区（简化：内存保存最近选择的） ──
    private val _history = MutableStateFlow<List<LocationRule>>(emptyList())
    val history: StateFlow<List<LocationRule>> = _history.asStateFlow()

    fun addToHistory(rule: LocationRule) {
        val newList = (listOf(rule) + _history.value.filterNot { sameLocation(it, rule) }).take(10)
        _history.value = newList
    }

    /**
     * 确认筛选：生成 STANDBY 规则进备战区。
     *
     * 规则名自动生成（如 "出发：山东济宁 → 上海"），
     * fromLocationsJson/toLocationsJson 填入筛选结果。
     * 至少选一个出发地或目的地才允许保存。
     */
    fun confirmFilter(onSuccess: () -> Unit) {
        val from = _fromSelected.value
        val to = _toSelected.value
        if (from.isEmpty() && to.isEmpty()) {
            _errorMessage.value = "请至少选择一个出发地或目的地"
            return
        }
        val rule = MatchRuleEntity(
            name = buildRuleName(from, to),
            ruleStatus = "STANDBY", // 进入备战区
            fromLocationsJson = gson.toJson(from),
            fromCustomPointsJson = "[]",
            toLocationsJson = gson.toJson(to),
            toCustomPointsJson = "[]",
            cargoTypesJson = "[]",
            vehicleLengthsJson = "[]",
            vehicleTypesJson = "[]",
            loadTimeOption = "ANY",
            weightRangesJson = "[]"
        )
        viewModelScope.launch {
            val id = ruleRepository.saveRule(rule)
            _savedRuleId.value = id
            onSuccess()
        }
    }

    private fun buildRuleName(from: List<LocationRule>, to: List<LocationRule>): String {
        fun format(list: List<LocationRule>): String {
            if (list.isEmpty()) return "不限"
            return list.joinToString("/") { "${it.province}${it.city ?: ""}${it.district ?: ""}" }
        }
        val fromText = format(from).take(10)
        val toText = format(to).take(10)
        return "筛选：$fromText → $toText"
    }

    fun consumeError() {
        _errorMessage.value = null
    }
}
