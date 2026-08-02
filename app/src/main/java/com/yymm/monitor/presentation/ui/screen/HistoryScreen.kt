package com.yymm.monitor.presentation.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.yymm.monitor.data.local.entity.HistoryRecordEntity
import com.yymm.monitor.presentation.viewmodel.HistoryViewModel
import com.yymm.monitor.util.DateUtil

/**
 * 历史记录页（PRD 3.8 模块H）。
 *
 * TabRow 全部/已查看/已忽略，数据从 HistoryRepository 实时读取。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    navController: NavController,
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val currentTab by viewModel.currentTab.collectAsState()
    val tabs = listOf("全部", "已查看", "已忽略")

    // 加载今日真实数据
    LaunchedEffect(Unit) {
        viewModel.loadTodayRecords()
    }

    val records = viewModel.recordsForCurrentTab()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("今日历史记录") },
                navigationIcon = {
                    TextButton(onClick = { navController.popBackStack() }) { Text("←") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = currentTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = currentTab == index,
                        onClick = { viewModel.filterByTab(index) },
                        text = { Text(title) }
                    )
                }
            }

            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                if (records.isEmpty()) {
                    item { EmptyHistory() }
                }
                items(records, key = { it.orderId }) { record ->
                    HistoryRecordCard(record)
                }
            }
        }
    }
}

/** 历史记录卡片 */
@Composable
private fun HistoryRecordCard(record: HistoryRecordEntity) {
    val viewed = record.action == "VIEWED"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(if (viewed) Color(0xFF4CAF50) else Color.Gray, CircleShape)
            )
            Spacer(Modifier.size(8.dp))
            Text(
                "${record.fromDisplay} → ${record.toDisplay}",
                fontSize = 14.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "${record.cargoDisplay} | ${record.weightDisplay} | ${record.priceDisplay}",
            fontSize = 13.sp,
            color = Color.Gray
        )
        Text("提醒时间：${DateUtil.formatTime(record.alertTime)}", fontSize = 12.sp, color = Color.Gray)
    }
}

/** 空历史提示 */
@Composable
private fun EmptyHistory() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text("暂无记录", fontSize = 14.sp, color = Color.Gray)
    }
}
