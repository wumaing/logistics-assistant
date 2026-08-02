package com.yymm.monitor.presentation.ui.component

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yymm.monitor.data.model.City
import com.yymm.monitor.data.model.District
import com.yymm.monitor.data.model.LocationRule
import com.yymm.monitor.data.model.Province
import com.yymm.monitor.data.repository.RegionRepository
import com.yymm.monitor.presentation.viewmodel.RuleViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch

/**
 * 地区选择器（PRD B.2 出发地/目的地选择器）。
 *
 * 三级级联：省 → 市 → 区县，"全市"选项，搜索模式，全国选项。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RegionPicker(
    initialSelected: List<LocationRule>,
    onDismiss: () -> Unit,
    onConfirm: (List<LocationRule>) -> Unit,
    viewModel: RuleViewModel = hiltViewModel()
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    // 已选地区
    var selected by remember { mutableStateOf<List<LocationRule>>(initialSelected) }
    // 地区数据
    var provinces by remember { mutableStateOf<List<Province>>(emptyList()) }
    // 级联状态
    var selectedProvince by remember { mutableStateOf<Province?>(null) }
    var selectedCity by remember { mutableStateOf<City?>(null) }
    // 搜索
    var keyword by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<LocationRule>>(emptyList()) }
    // 历史地区（简化：初始为空，后续可接 HistoryRepository）
    var historyExpanded by remember { mutableStateOf(false) }

    // 加载地区数据
    LaunchedEffect(Unit) {
        try {
            val regions = viewModel.loadRegions()
            provinces = regions.provinces
        } catch (_: Exception) {
            // 加载失败静默
        }
    }

    // 搜索过滤
    LaunchedEffect(keyword) {
        if (keyword.isNotBlank()) {
            val results = mutableListOf<LocationRule>()
            for (p in provinces) {
                if (p.name.contains(keyword)) results.add(LocationRule(province = p.name))
                for (c in p.cities) {
                    if (c.name.contains(keyword)) results.add(LocationRule(province = p.name, city = c.name))
                    for (d in c.districts) {
                        if (d.name.contains(keyword)) {
                            results.add(LocationRule(province = p.name, city = c.name, district = d.name))
                        }
                    }
                }
            }
            searchResults = results
        } else {
            searchResults = emptyList()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            // 顶部栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("选择地区", fontSize = 18.sp)
                TextButton(onClick = onDismiss) { Text("关闭") }
            }

            // 搜索框
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                placeholder = { Text("输入省份/城市/市区搜索") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            // 已选地区标签
            if (selected.isNotEmpty()) {
                Text("已选地区（可多选，无上限）", fontSize = 13.sp, color = Color.Gray)
                FlowRow {
                    selected.forEach { rule ->
                        Box(
                            modifier = Modifier
                                .padding(end = 6.dp, bottom = 6.dp)
                                .border(1.dp, Color.Red, RoundedCornerShape(16.dp))
                                .background(Color(0xFFFFEBEE), RoundedCornerShape(16.dp))
                                .clickable {
                                    selected = selected.filterNot { it == rule }
                                }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                "${rule.province}${rule.city ?: ""}${rule.district ?: ""} ✕",
                                fontSize = 13.sp,
                                color = Color.Red
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            // 历史地区（展开/收起）
            if (historyExpanded) {
                Text("历史地区", fontSize = 13.sp, color = Color.Gray)
                Text("暂无历史地区", fontSize = 13.sp, color = Color.Gray)
            } else {
                Text("历史地区  [展开]", fontSize = 13.sp, color = Color.Gray, modifier = Modifier.clickable {
                    historyExpanded = true
                })
            }
            Spacer(Modifier.height(4.dp))

            if (keyword.isBlank()) {
                // ─── 级联选择区 ───
                // 第一级：省级 Grid（含"全国"）
                Text("请选择省/市/区", fontSize = 14.sp)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.height(160.dp)
                ) {
                    items(listOf("全国") + provinces.map { it.name }) { name ->
                        ProvinceGridCell(
                            name = name,
                            selected = selectedProvince?.name == name || (name == "全国" && selected.any { it.province == "全国" }),
                            onClick = {
                                if (name == "全国") {
                                    selected = listOf(LocationRule(province = "全国"))
                                    selectedProvince = null
                                    selectedCity = null
                                } else {
                                    selectedProvince = provinces.firstOrNull { it.name == name }
                                    selectedCity = null
                                }
                            }
                        )
                    }
                }

                // 第二级：市级 Grid
                selectedProvince?.let { province ->
                    Text("— ${province.name} —", fontSize = 12.sp, color = Color.Gray)
                    // "全市"选项
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .border(1.dp, Color.Blue, RoundedCornerShape(8.dp))
                            .clickable {
                                selected = (selected.filterNot { it.province == province.name } + LocationRule(province = province.name))
                            }
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text("全市（选中${province.name}所有区县）", fontSize = 13.sp, color = Color.Blue)
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier.height(140.dp)
                    ) {
                        items(province.cities.map { it.name }) { name ->
                            ProvinceGridCell(
                                name = name,
                                selected = selectedCity?.name == name,
                                onClick = {
                                    selectedCity = province.cities.firstOrNull { it.name == name }
                                }
                            )
                        }
                    }
                }

                // 第三级：区县级 Grid
                selectedCity?.let { city ->
                    Text("— ${city.name} —", fontSize = 12.sp, color = Color.Gray)
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier.height(120.dp)
                    ) {
                        items(city.districts.map { it.name }) { name ->
                            ProvinceGridCell(
                                name = name,
                                selected = selected.any { it.province == selectedProvince?.name && it.city == city.name && it.district == name },
                                onClick = {
                                    val rule = LocationRule(
                                        province = selectedProvince!!.name,
                                        city = city.name,
                                        district = name
                                    )
                                    selected = (selected.filterNot { it == rule } + rule)
                                }
                            )
                        }
                    }
                }
            } else {
                // ─── 搜索模式结果 ───
                if (searchResults.isEmpty()) {
                    Text("未找到相关地区", fontSize = 13.sp, color = Color.Gray)
                } else {
                    Column {
                        searchResults.take(20).forEach { rule ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selected = (selected.filterNot { it == rule } + rule)
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${rule.province}${rule.city ?: ""}${rule.district ?: ""}",
                                    fontSize = 14.sp,
                                    color = if (selected.contains(rule)) Color.Red else Color.Black
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // 底部栏：清空 + 确认
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { selected = emptyList() }) { Text("清空筛选") }
                Button(
                    onClick = { onConfirm(selected); onDismiss() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
                ) {
                    Text("确认")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 省份/城市/区县网格单元格 */
@Composable
private fun ProvinceGridCell(
    name: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .padding(4.dp)
            .border(1.dp, if (selected) Color.Red else Color.Gray, RoundedCornerShape(8.dp))
            .background(if (selected) Color(0xFFFFEBEE) else Color.White, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            name,
            fontSize = 13.sp,
            color = if (selected) Color.Red else Color.Black
        )
    }
}
