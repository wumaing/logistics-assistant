package com.yymm.monitor.presentation.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.yymm.monitor.data.model.LocationRule
import com.yymm.monitor.data.model.WeightRange
import com.yymm.monitor.presentation.ui.component.RegionPicker
import com.yymm.monitor.presentation.ui.component.TagSelector
import com.yymm.monitor.presentation.viewmodel.RuleViewModel
import kotlinx.coroutines.launch

/**
 * 规则编辑页（PRD 3.2 B.2）。
 *
 * 包含：规则名称、出发地、目的地、货源类型、车长、车型、装货时间、
 *      重量、价格筛选、更多筛选。保存时校验。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RuleEditScreen(
    ruleId: Long?,
    navController: NavController,
    viewModel: RuleViewModel = hiltViewModel()
) {
    val rule by viewModel.currentRule.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val validationMessage by viewModel.validationMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showFromPicker by remember { mutableStateOf(false) }
    var showToPicker by remember { mutableStateOf(false) }

    // 加载已有规则
    LaunchedEffect(ruleId) {
        if (ruleId != null && ruleId >= 0) {
            viewModel.loadRule(ruleId)
        }
    }

    // 校验消息展示
    LaunchedEffect(validationMessage) {
        validationMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeValidationMessage()
        }
    }

    val currentRule = rule

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("编辑规则") },
                navigationIcon = {
                    TextButton(onClick = { navController.popBackStack() }) { Text("←") }
                },
                actions = {
                    TextButton(onClick = {
                        viewModel.saveRule(onSuccess = { navController.popBackStack() })
                    }, enabled = !isSaving) {
                        Text("保存")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (currentRule == null) {
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // 规则名称
            OutlinedTextField(
                value = currentRule.name,
                onValueChange = { viewModel.updateName(it) },
                label = { Text("规则名称") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))

            // 出发地
            SectionTitle("📍 出发地（可多选，无上限）")
            val fromLocations = viewModel.parseLocationsList(currentRule.fromLocationsJson)
            FlowRow {
                fromLocations.forEach { loc ->
                    SelectedTag("${loc.province}${loc.city ?: ""}${loc.district ?: ""}") {
                        viewModel.updateFromLocations(fromLocations.filterNot { it == loc })
                    }
                }
                AddTag("+ 添加") { showFromPicker = true }
            }
            Spacer(Modifier.height(16.dp))

            // 目的地
            SectionTitle("📍 目的地（可多选，无上限）")
            val toLocations = viewModel.parseLocationsList(currentRule.toLocationsJson)
            FlowRow {
                toLocations.forEach { loc ->
                    SelectedTag("${loc.province}${loc.city ?: ""}${loc.district ?: ""}") {
                        viewModel.updateToLocations(toLocations.filterNot { it == loc })
                    }
                }
                AddTag("+ 添加") { showToPicker = true }
            }
            Spacer(Modifier.height(16.dp))

            // 货源类型
            SectionTitle("📦 货源类型（可多选）")
            TagSelector(
                items = listOf("整车货", "优车货", "即时单", "拼车货"),
                selectedItems = viewModel.parseStringListData(currentRule.cargoTypesJson).toSet(),
                onToggle = { viewModel.toggleCargoType(it) }
            )
            Spacer(Modifier.height(16.dp))

            // 车长
            SectionTitle("🚛 车长（可多选）")
            val lengthItems = listOf("1.4", "1.8", "2.7", "3.3", "3.8", "4.2", "5", "6.2")
            TagSelector(
                items = lengthItems,
                selectedItems = viewModel.parseStringListData(currentRule.vehicleLengthsJson).toSet(),
                onToggle = { len: String -> viewModel.toggleVehicleLength(len) },
                itemLabel = { len: String -> "${len}米" }
            )
            Spacer(Modifier.height(16.dp))

            // 车型
            SectionTitle("🚛 车型（可多选）")
            TagSelector(
                items = listOf(
                    "平板", "高栏", "厢式", "集装箱", "拖车", "自卸", "冷藏",
                    "厢式保温", "高低板", "面包车", "棉被车", "爬梯车", "飞翼车", "依维柯"
                ),
                selectedItems = viewModel.parseStringListData(currentRule.vehicleTypesJson).toSet(),
                onToggle = { viewModel.toggleVehicleType(it) }
            )
            Spacer(Modifier.height(16.dp))

            // 装货时间
            SectionTitle("⏰ 装货时间")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ANY" to "任意", "TODAY" to "今天", "TOMORROW" to "明天", "AFTER_TOMORROW" to "明天以后").forEach { (key, label) ->
                    TimeOption(
                        label = label,
                        selected = currentRule.loadTimeOption == key,
                        onClick = { viewModel.setLoadTimeOption(key) }
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            // 重量
            SectionTitle("⚖️ 重量（吨）")
            val presetRanges = listOf(
                WeightRange(0f, 1f) to "小于1",
                WeightRange(1f, 2f) to "1-2",
                WeightRange(2f, 5f) to "2-5",
                WeightRange(5f, 10f) to "5-10",
                WeightRange(10f, 20f) to "10-20",
                WeightRange(20f, 30f) to "20-30",
                WeightRange(30f, 40f) to "30-40",
                WeightRange(40f, Float.MAX_VALUE) to "40以上"
            )
            val selectedRanges = viewModel.parseWeightRangesData(currentRule.weightRangesJson).toSet()
            TagSelector(
                items = presetRanges.map { it.second },
                selectedItems = presetRanges.filter { (r, _) -> selectedRanges.any { it.min == r.min && it.max == r.max } }.map { it.second }.toSet(),
                onToggle = { label ->
                    val (range, _) = presetRanges.first { it.second == label }
                    viewModel.toggleWeightRange(range)
                }
            )
            // 自定义重量
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = currentRule.weightMin?.toString() ?: "",
                    onValueChange = { viewModel.setWeightMin(it.toFloatOrNull()) },
                    label = { Text("最小") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                Text("~")
                OutlinedTextField(
                    value = currentRule.weightMax?.toString() ?: "",
                    onValueChange = { viewModel.setWeightMax(it.toFloatOrNull()) },
                    label = { Text("最大") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                Text("吨", fontSize = 13.sp, color = Color.Gray)
            }
            if (currentRule.weightMin != null && currentRule.weightMax != null &&
                currentRule.weightMin!! > currentRule.weightMax!!
            ) {
                Text("最小重量不能大于最大重量", fontSize = 12.sp, color = Color.Red)
            }
            Spacer(Modifier.height(16.dp))

            // 价格筛选
            SectionTitle("💰 价格筛选")
            var priceInputError by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = currentRule.minPrice?.toString() ?: "",
                onValueChange = { input ->
                    val parsed = input.toIntOrNull()
                    priceInputError = input.isNotBlank() && parsed == null
                    if (parsed != null || input.isBlank()) {
                        viewModel.setMinPrice(parsed)
                    }
                },
                label = { Text("最低运费（元，0或不填=不限制）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = priceInputError,
                supportingText = if (priceInputError) {
                    { Text("请输入有效金额", color = Color.Red, fontSize = 12.sp) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth()
            )
            CheckboxRow("包含\"电议\"且有显示具体金额", currentRule.includeDianyiWithPrice) {
                viewModel.toggleDianyiWithPrice(it)
            }
            CheckboxRow("包含\"电议\"且不显示具体金额（纯电话议价，不受最低运费限制）", currentRule.includeDianyiWithoutPrice) {
                viewModel.toggleDianyiWithoutPrice(it)
            }
            Spacer(Modifier.height(16.dp))

            // 更多筛选
            SectionTitle("⭐ 更多筛选")
            val extraTags = listOf("高信用货主", "满运宝", "订金可退", "送抢单特权")
            TagSelector(
                items = extraTags,
                selectedItems = extraTags.filter { tag ->
                    when (tag) {
                        "高信用货主" -> currentRule.requireHighCredit
                        "满运宝" -> currentRule.requireManyunbao
                        "订金可退" -> currentRule.requireDepositRefundable
                        "送抢单特权" -> currentRule.requirePrivilege
                        else -> false
                    }
                }.toSet(),
                onToggle = { tag ->
                    val currently = when (tag) {
                        "高信用货主" -> currentRule.requireHighCredit
                        "满运宝" -> currentRule.requireManyunbao
                        "订金可退" -> currentRule.requireDepositRefundable
                        else -> currentRule.requirePrivilege
                    }
                    viewModel.toggleExtra(tag, !currently)
                }
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    // 地区选择器
    if (showFromPicker) {
        RegionPicker(
            initialSelected = fromLocationsPlaceholder(rule),
            onDismiss = { showFromPicker = false },
            onConfirm = { viewModel.updateFromLocations(it) }
        )
    }
    if (showToPicker) {
        RegionPicker(
            initialSelected = toLocationsPlaceholder(rule),
            onDismiss = { showToPicker = false },
            onConfirm = { viewModel.updateToLocations(it) }
        )
    }
}

/** 从当前规则提取出发地列表（供 RegionPicker 回填） */
@Composable
private fun fromLocationsPlaceholder(rule: com.yymm.monitor.data.local.entity.MatchRuleEntity?): List<LocationRule> {
    val vm: RuleViewModel = hiltViewModel()
    return rule?.let { vm.parseLocationsList(it.fromLocationsJson) } ?: emptyList()
}

/** 从当前规则提取目的地列表（供 RegionPicker 回填） */
@Composable
private fun toLocationsPlaceholder(rule: com.yymm.monitor.data.local.entity.MatchRuleEntity?): List<LocationRule> {
    val vm: RuleViewModel = hiltViewModel()
    return rule?.let { vm.parseLocationsList(it.toLocationsJson) } ?: emptyList()
}

/** 已选地点标签（带删除） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectedTag(text: String, onRemove: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(end = 8.dp, bottom = 8.dp)
            .border(1.dp, Color.Red, RoundedCornerShape(16.dp))
            .background(Color(0xFFFFEBEE), RoundedCornerShape(16.dp))
            .clickable(onClick = onRemove)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text("$text ✕", fontSize = 14.sp, color = Color.Red)
    }
}

/** 添加按钮 */
@Composable
private fun AddTag(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(bottom = 8.dp)
            .border(1.dp, Color(0xFF9E9E9E), RoundedCornerShape(16.dp))
            .background(Color.White, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text, fontSize = 14.sp, color = Color.Black)
    }
}

/** 区块标题 */
@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
}

/** 装货时间单选 */
@Composable
private fun TimeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .border(1.dp, if (selected) Color.Red else Color.Gray, RoundedCornerShape(8.dp))
            .background(if (selected) Color(0xFFFFEBEE) else Color.White, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(label, fontSize = 14.sp, color = if (selected) Color.Red else Color.Black)
    }
}

/** Checkbox 行 */
@Composable
private fun CheckboxRow(text: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { onCheckedChange(it) })
        Text(text, fontSize = 13.sp)
    }
}


