package com.yymm.monitor.presentation.ui.screen

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.yymm.monitor.presentation.viewmodel.SettingsViewModel

/**
 * 提醒设置页（PRD 3.3 模块C）。
 *
 * 弹窗/震动/响铃/语音 四通道独立开关，各参数控件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertSettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("提醒设置") },
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // 弹窗提醒
            SettingSwitchRow("📳 弹窗提醒", settings.popupEnabled) {
                viewModel.updatePopupEnabled(it)
            }
            Spacer(Modifier.height(12.dp))

            // 震动提醒
            SettingSwitchRow("📳 震动提醒", settings.vibrateEnabled) {
                viewModel.updateVibrateEnabled(it)
            }
            if (settings.vibrateEnabled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("震动时长：", fontSize = 13.sp)
                    Text("${settings.vibrateDurationMs / 1000} 秒", fontSize = 13.sp, color = Color.Gray)
                }
                Slider(
                    value = (settings.vibrateDurationMs / 1000f).coerceIn(1f, 10f),
                    onValueChange = { viewModel.updateVibrateDuration(it) },
                    valueRange = 1f..10f
                )
            }
            Spacer(Modifier.height(12.dp))

            // 响铃提醒
            SettingSwitchRow("🔔 响铃提醒", settings.ringtoneEnabled) {
                viewModel.updateRingtoneEnabled(it)
            }
            if (settings.ringtoneEnabled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("铃声音量：", fontSize = 13.sp)
                    Text("${(settings.ringtoneVolume * 100).toInt()}%", fontSize = 13.sp, color = Color.Gray)
                }
                Slider(
                    value = settings.ringtoneVolume.coerceIn(0f, 1f),
                    onValueChange = { viewModel.updateRingtoneVolume(it) },
                    valueRange = 0f..1f
                )
                Text("选择铃声：[默认提示音]", fontSize = 13.sp, color = Color.Gray)
                Text("选择自定义音频...", fontSize = 13.sp, color = Color.Gray)
            }
            Spacer(Modifier.height(12.dp))

            // 语音播报
            SettingSwitchRow("🔊 语音播报", settings.ttsEnabled) {
                viewModel.updateTtsEnabled(it)
            }
            if (settings.ttsEnabled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("语速：", fontSize = 13.sp)
                    Text("${settings.ttsSpeed}x", fontSize = 13.sp, color = Color.Gray)
                }
                Slider(
                    value = settings.ttsSpeed.coerceIn(0.5f, 2f),
                    onValueChange = { viewModel.updateTtsSpeed(it) },
                    valueRange = 0.5f..2f
                )
                Text("播报预览：[▶ 试听]", fontSize = 13.sp, color = Color.Gray)
                Text(
                    "语音播报内容模板：\n\"发现新订单，从{出发地}到{目的地}，运费{价格}元，重量{重量}吨\"",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
            Spacer(Modifier.height(16.dp))

            // 弹窗显示时长
            OutlinedTextField(
                value = settings.popupDurationSeconds.toString(),
                onValueChange = { viewModel.updatePopupDuration(it.toIntOrNull() ?: 0) },
                label = { Text("弹窗显示时长（秒，0=一直显示）") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            // 匹配多订单时
            Text("匹配多订单时：", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                RadioOption("排队依次", settings.multiOrderHandling == "QUEUE") {
                    viewModel.updateMultiOrderHandling("QUEUE")
                }
                RadioOption("只弹最新", settings.multiOrderHandling == "LATEST") {
                    viewModel.updateMultiOrderHandling("LATEST")
                }
            }

            // 保存按钮
            Spacer(Modifier.height(24.dp))
            androidx.compose.material3.Button(
                onClick = { viewModel.saveSettings() },
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
            ) {
                Text("保存设置")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 设置开关行 */
@Composable
private fun SettingSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 15.sp)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 单选选项 */
@Composable
private fun RadioOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .border(1.dp, if (selected) Color.Red else Color.Gray, RoundedCornerShape(8.dp))
            .background(if (selected) Color(0xFFFFEBEE) else Color.White, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(label, fontSize = 14.sp, color = if (selected) Color.Red else Color.Black)
    }
}
