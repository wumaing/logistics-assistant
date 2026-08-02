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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.yymm.monitor.data.model.City
import com.yymm.monitor.data.model.LocationRule
import com.yymm.monitor.data.model.Province
import com.yymm.monitor.presentation.ui.theme.Primary
import com.yymm.monitor.presentation.ui.theme.PrimaryContainer
import com.yymm.monitor.presentation.ui.theme.StatusRed
import com.yymm.monitor.presentation.viewmodel.FilterViewModel
import kotlinx.coroutines.launch

/**
 * 地区筛选页（出发地/目的地）。
 *
 * 结构：顶部 Tab（出发地/目的地）→ 搜索框 → 已选地区 Tag →
 *      历史地区 → 面包屑 → 地区网格 → 底部（清空筛选/确认）
 * 确认后生成一条 STANDBY 规则进入备战区。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterScreen(
    navController: NavController,
    viewModel: FilterViewModel = hiltViewModel()
) {
    val provinces by viewModel.provinces.collectAsState()
    val fromSelected by viewModel.fromSelected.collectAsState()
    val toSelected by viewModel.toSelected.collectAsState()
    val activeTab by viewModel.activeTab.collectAsState()
    val currentProvince by viewModel.currentProvince.collectAsState()
    val currentCityIndex by viewModel.currentCityIndex.collectAsState()
    val keyword by viewModel.keyword.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val history by viewModel.history.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val toastMessage by viewModel.toastMessage.collectAsState()

    var historyExpanded by remember { mutableStateOf(false) }
    var toastMsg by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    // 错误消息展示（Toast）
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            toastMsg = it
            viewModel.consumeError()
        }
    }
    // 层级规则提示（如"山东已包含济南"）
    LaunchedEffect(toastMessage) {
        toastMessage?.let {
            toastMsg = it
            viewModel.consumeToast()
        }
    }
    LaunchedEffect(toastMsg) {
        toastMsg?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            toastMsg = null
        }
    }

    val selected = if (activeTab == "FROM") fromSelected else toSelected

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("地区筛选", fontSize = 17.sp) },
                navigationIcon = {
                    TextButton(onClick = { navController.popBackStack() }) { Text("←") }
                }
            )
        },
        bottomBar = {
            // 底部操作栏：清空筛选 + 确认
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { viewModel.clearSelected() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("清空筛选", color = StatusRed)
                }
                Button(
                    onClick = {
                        viewModel.confirmFilter(onSuccess = { navController.popBackStack() })
                    },
                    modifier = Modifier.weight(2f),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) {
                    Text("确认筛选方案", fontSize = 15.sp)
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ── 顶部 Tab：出发地 / 目的地 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                FilterTab(
                    text = "📍 出发地",
                    selected = activeTab == "FROM",
                    onClick = { viewModel.setActiveTab("FROM") }
                )
                FilterTab(
                    text = "📍 目的地",
                    selected = activeTab == "TO",
                    onClick = { viewModel.setActiveTab("TO") }
                )
            }
            Spacer(Modifier.height(4.dp))

            // ── 搜索框 ──
            OutlinedTextField(
                value = keyword,
                onValueChange = { viewModel.updateKeyword(it) },
                placeholder = { Text("输入省份/城市/市区搜索") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                singleLine = true
            )
            Spacer(Modifier.height(4.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                // ── 已选地区 ──
                item {
                    Text(
                        if (activeTab == "FROM") "已选出发地（最多5个）" else "已选目的地（最多5个）",
                        fontSize = 13.sp,
                        color = Color.Gray
                    )
                    Spacer(Modifier.height(4.dp))
                }
                item {
                    if (selected.isEmpty()) {
                        Text(
                            if (activeTab == "FROM") "未选择出发地（默认全国不限）" else "未选择目的地（默认全国不限）",
                            fontSize = 13.sp,
                            color = Color.Gray
                        )
                    } else {
                        FlowRow {
                            selected.forEach { rule ->
                                SelectedRegionTag(
                                    text = "${rule.province}${rule.city ?: ""}${rule.district ?: ""}",
                                    onRemove = { viewModel.removeSelected(rule) }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // ── 历史地区 ──
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("历史地区", fontSize = 13.sp, color = Color.Gray)
                        TextButton(onClick = { historyExpanded = !historyExpanded }) {
                            Text(if (historyExpanded) "收起" else "展开", fontSize = 13.sp)
                        }
                    }
                }
                if (historyExpanded && history.isNotEmpty()) {
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(history) { rule ->
                                HistoryRegionTag(
                                    text = "${rule.province}${rule.city ?: ""}${rule.district ?: ""}",
                                    onClick = {
                                        viewModel.toggleSelected(rule)
                                        viewModel.addToHistory(rule)
                                    }
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }

                // ── 搜索模式 ──
                if (keyword.isNotBlank()) {
                    item {
                        if (searchResults.isEmpty()) {
                            Text("未找到相关地区", fontSize = 13.sp, color = Color.Gray)
                        } else {
                            Column {
                                searchResults.take(30).forEach { rule ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                viewModel.toggleSelected(rule)
                                                viewModel.addToHistory(rule)
                                            }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "${rule.province}${rule.city ?: ""}${rule.district ?: ""}",
                                            fontSize = 14.sp,
                                            color = if (selected.any { sameLocation(it, rule) }) Primary else Color.Black
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // ── 级联选择 ──
                    item {
                        Breadcrumb(
                            province = currentProvince,
                            city = currentCityIndex?.let { idx ->
                                currentProvince?.cities?.getOrNull(idx)
                            },
                            onBackProvince = { viewModel.backToProvince() },
                            onBackCity = { viewModel.backToCity() }
                        )
                        Spacer(Modifier.height(4.dp))
                    }

                    when {
                        // 第一级：省级网格（含"全国"；已被"全国"包含的省禁用）
                        currentProvince == null -> {
                            item {
                                RegionGrid(
                                    items = listOf("全国") + provinces.map { it.name },
                                    selectedItems = selected.map { it.province }.toSet(),
                                    disabledItems = if (selected.any { it.province == "全国" }) {
                                        provinces.map { it.name }.toSet()
                                    } else {
                                        emptySet()
                                    },
                                    onClick = { name ->
                                        if (name == "全国") {
                                            viewModel.toggleSelected(LocationRule(province = "全国"))
                                        } else {
                                            viewModel.selectProvince(
                                                provinces.firstOrNull { it.name == name }
                                            )
                                        }
                                    }
                                )
                            }
                        }
                        // 第二级：市级网格（含"全市"；已被"全省"包含的市禁用）
                        currentCityIndex == null -> {
                            currentProvince?.let { province ->
                                item {
                                    val provinceSelected = selected.any {
                                        it.province == province.name && it.city == null && it.district == null
                                    }
                                    RegionGrid(
                                        items = listOf("全市") + province.cities.map { it.name },
                                        selectedItems = selected
                                            .filter { it.province == province.name }
                                            .map { it.city ?: "全市" }
                                            .toSet(),
                                        disabledItems = if (provinceSelected) {
                                            province.cities.map { it.name }.toSet()
                                        } else {
                                            emptySet()
                                        },
                                        onClick = { name ->
                                            if (name == "全市") {
                                                viewModel.toggleSelected(
                                                    LocationRule(province = province.name)
                                                )
                                            } else {
                                                val idx = province.cities.indexOfFirst { it.name == name }
                                                if (idx >= 0) viewModel.selectCity(idx)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                        // 第三级：区县级网格（已被"全市"包含的区禁用）
                        else -> {
                            currentProvince?.let { province ->
                                currentCityIndex?.let { idx ->
                                    val city = province.cities.getOrNull(idx) ?: return@let
                                    item {
                                        val citySelected = selected.any {
                                            it.province == province.name && it.city == city.name && it.district == null
                                        }
                                        RegionGrid(
                                            items = city.districts.map { it.name },
                                            selectedItems = selected
                                                .filter { it.city == city.name }
                                                .map { it.district ?: "" }
                                                .toSet(),
                                            disabledItems = if (citySelected) {
                                                city.districts.map { it.name }.toSet()
                                            } else {
                                                emptySet()
                                            },
                                            onClick = { name ->
                                                viewModel.toggleSelected(
                                                    LocationRule(
                                                        province = province.name,
                                                        city = city.name,
                                                        district = name
                                                    )
                                                )
                                                viewModel.addToHistory(
                                                    LocationRule(
                                                        province = province.name,
                                                        city = city.name,
                                                        district = name
                                                    )
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

/** 判断两个 LocationRule 是否为同一地点 */
private fun sameLocation(a: LocationRule, b: LocationRule): Boolean =
    a.province == b.province && a.city == b.city && a.district == b.district

/** 顶部 Tab（出发地/目的地） */
@Composable
private fun FilterTab(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
            .then(
                if (selected) Modifier.border(2.dp, Primary, RoundedCornerShape(8.dp))
                else Modifier.border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) Primary else Color.Black
        )
    }
}

/** 已选地区 Tag（带 × 删除） */
@Composable
private fun SelectedRegionTag(text: String, onRemove: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(end = 6.dp, bottom = 6.dp)
            .border(1.dp, Primary, RoundedCornerShape(16.dp))
            .background(PrimaryContainer, RoundedCornerShape(16.dp))
            .clickable(onClick = onRemove)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text("$text ✕", fontSize = 13.sp, color = Primary)
    }
}

/** 历史地区 Tag */
@Composable
private fun HistoryRegionTag(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .border(1.dp, Color.Gray, RoundedCornerShape(16.dp))
            .background(Color.White, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, fontSize = 13.sp, color = Color.Black)
    }
}

/** 面包屑导航：省 > 市 > 请选择区（当前步红色下划线高亮） */
@Composable
private fun Breadcrumb(
    province: Province?,
    city: City?,
    onBackProvince: () -> Unit,
    onBackCity: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        BreadcrumbItem("全国", highlighted = province == null, onClick = onBackProvince)
        if (province != null) {
            Text(">", fontSize = 13.sp, color = Color.Gray)
            BreadcrumbItem(province.name, highlighted = city == null, onClick = onBackProvince)
        }
        if (province != null && city != null) {
            Text(">", fontSize = 13.sp, color = Color.Gray)
            BreadcrumbItem(city.name, highlighted = false, onClick = onBackCity)
            Text(">", fontSize = 13.sp, color = Color.Gray)
            BreadcrumbItem("请选择区", highlighted = true, onClick = {})
        }
    }
}

/** 面包屑单项 */
@Composable
private fun BreadcrumbItem(text: String, highlighted: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text,
            fontSize = 14.sp,
            color = if (highlighted) Primary else Color.Black,
            fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal
        )
        if (highlighted) {
            Box(
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(width = 24.dp, height = 2.dp)
                    .background(Primary)
            )
        }
    }
}

/** 地区网格（省/市/区通用，支持禁用态） */
@Composable
private fun RegionGrid(
    items: List<String>,
    selectedItems: Set<String>,
    disabledItems: Set<String> = emptySet(),
    onClick: (String) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier
            .fillMaxWidth()
            .height(androidx.compose.ui.unit.Dp((items.size / 4 + 1) * 56f))
    ) {
        items(items) { name ->
            RegionGridCell(
                name = name,
                selected = name in selectedItems,
                disabled = name in disabledItems,
                onClick = { onClick(name) }
            )
        }
    }
}

/** 网格单元格（选中红框红字 / 被上级包含禁用灰字 / 正常黑字） */
@Composable
private fun RegionGridCell(name: String, selected: Boolean, disabled: Boolean, onClick: () -> Unit) {
    val borderColor = when {
        selected -> Primary
        disabled -> Color(0xFFE0E0E0)
        else -> Color.Gray
    }
    val textColor = when {
        selected -> Primary
        disabled -> Color(0xFFBDBDBD)
        else -> Color.Black
    }
    Box(
        modifier = Modifier
            .padding(4.dp)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .background(if (selected) PrimaryContainer else Color.White, RoundedCornerShape(8.dp))
            .then(if (disabled) Modifier else Modifier.clickable(onClick = onClick))
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            name,
            fontSize = 13.sp,
            color = textColor
        )
    }
}
