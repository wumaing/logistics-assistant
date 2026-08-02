package com.yymm.monitor.presentation.ui.screen

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.yymm.monitor.data.local.entity.MatchRuleEntity
import com.yymm.monitor.domain.service.FloatingWindowManager
import com.yymm.monitor.presentation.ui.theme.StatusGreen
import com.yymm.monitor.presentation.ui.theme.StatusYellow
import com.yymm.monitor.presentation.viewmodel.MainViewModel

/**
 * 主界面（PRD 3.2 模块B）。
 *
 * 布局：顶部标题栏 → 备战区规则列表 → 一键放入匹配区 →
 *      匹配区规则列表 → 一键移除 → 快捷入口 → 底部固定"去运满满开始监控"
 */
@Composable
fun MainScreen(
    navController: NavController,
    viewModel: MainViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val standbyRules by viewModel.standbyRules.collectAsState()
    val activeRules by viewModel.activeRules.collectAsState()

    var deleteTarget by remember { mutableStateOf<MatchRuleEntity?>(null) }

    // 顶部标题栏 + 规则列表 + 底部固定按钮
    Scaffold(
        bottomBar = {
            // 底部固定大按钮
            Button(
                onClick = { startMonitoring(context) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
            ) {
                Text("🚀 去运满满开始监控", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
        ) {
            // 顶部标题栏
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("浏览助手", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "⚙️",
                        fontSize = 20.sp,
                        modifier = Modifier
                            .clickable { navController.navigate("alert_settings") }
                            .padding(4.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            // 备战区
            item {
                Text("📋 备战区规则（待命）", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
            }
            if (standbyRules.isEmpty()) {
                item { EmptyHint("暂无规则，点击下方 + 添加") }
            }
            items(standbyRules, key = { it.id }) { rule ->
                RuleCard(
                    rule = rule,
                    statusColor = StatusYellow,
                    actions = listOf(
                        { ActionButton("激活") { viewModel.activateRule(rule.id) } },
                        { ActionButton("编辑") { navController.navigate("rule_edit/${rule.id}") } },
                        { ActionButton("复制") { viewModel.duplicateRule(rule.id) } },
                        { ActionButton("删除") { deleteTarget = rule } }
                    )
                )
            }
            item {
                Button(
                    onClick = { viewModel.activateAll() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
                ) {
                    Text("一键全部放入匹配区")
                }
                Spacer(Modifier.height(8.dp))
            }

            // 匹配区
            item {
                Text("🟢 匹配区规则（激活）", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
            }
            if (activeRules.isEmpty()) {
                item { EmptyHint("暂无匹配规则") }
            }
            items(activeRules, key = { it.id }) { rule ->
                RuleCard(
                    rule = rule,
                    statusColor = StatusGreen,
                    actions = listOf(
                        { ActionButton("移除") { viewModel.deactivateRule(rule.id) } },
                        { ActionButton("编辑") { navController.navigate("rule_edit/${rule.id}") } }
                    )
                )
            }
            item {
                OutlinedButton(
                    onClick = { viewModel.deactivateAll() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("一键全部移除回备战区")
                }
                Spacer(Modifier.height(8.dp))
            }

            // 快捷入口
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    OutlinedButton(onClick = { navController.navigate("alert_settings") }) {
                        Text("🔔 提醒设置")
                    }
                    OutlinedButton(onClick = { navController.navigate("history") }) {
                        Text("📊 历史记录")
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    // 删除确认对话框
    deleteTarget?.let { rule ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("确定删除此规则？") },
            text = { Text("删除后不可恢复") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteRule(rule.id)
                    deleteTarget = null
                }) { Text("删除", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }
}

/** 规则卡片 */
@Composable
private fun RuleCard(
    rule: MatchRuleEntity,
    statusColor: Color,
    actions: List<@Composable () -> Unit>
) {    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(8.dp)),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(statusColor, CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text(rule.name.ifBlank { "未命名规则" }, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                ruleSummary(rule),
                fontSize = 12.sp,
                color = Color.Gray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEach { action -> action() }
            }
        }
    }
}

/** 规则摘要：从 JSON 解析地点生成 "出发 → 目的" */
private fun ruleSummary(rule: MatchRuleEntity): String {
    val gson = com.google.gson.Gson()
    fun parse(json: String): List<com.yymm.monitor.data.model.LocationRule> =
        try { gson.fromJson(json, Array<com.yymm.monitor.data.model.LocationRule>::class.java).toList() }
        catch (_: Exception) { emptyList() }
    val from = parse(rule.fromLocationsJson).map { it.province + (it.city ?: "") }
    val to = parse(rule.toLocationsJson).map { it.province + (it.city ?: "") }
    val fromText = if (from.isEmpty()) "不限" else from.joinToString("/")
    val toText = if (to.isEmpty()) "不限" else to.joinToString("/")
    return "$fromText → $toText"
}

/** 操作按钮 */
@Composable
private fun ActionButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
        Text(text, fontSize = 13.sp)
    }
}

/** 空状态提示 */
@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(8.dp))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 13.sp, color = Color.Gray)
    }
}

/** 启动监控：检查权限 + 显示悬浮窗 + 启动运满满 */
private fun startMonitoring(context: Context) {
    // 显示悬浮窗
    FloatingWindowManager.show(context)
    // 尝试启动司机端
    listOf("com.xiwei.logistics", "com.yymm.driver", "com.yunmanman.driver").forEach { pkg ->
        context.packageManager.getLaunchIntentForPackage(pkg)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(it)
            return
        }
    }
}
