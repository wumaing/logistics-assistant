package com.yymm.monitor.presentation.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yymm.monitor.util.PermissionUtil
import kotlinx.coroutines.delay

/**
 * 权限引导页（PRD 3.1 模块A）。
 *
 * 4 步引导：无障碍服务 → 悬浮窗权限 → 电池白名单（可选）→ 完成。
 * 每 500ms 轮询权限状态，全部开启后才显示"完成"按钮。
 */
@Composable
fun GuideScreen(onGuideComplete: () -> Unit) {
    val context = LocalContext.current

    // 权限状态（每 500ms 轮询）
    var accessibilityEnabled by remember { mutableStateOf(false) }
    var overlayAllowed by remember { mutableStateOf(false) }
    var batteryIgnored by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            accessibilityEnabled = PermissionUtil.isAccessibilityServiceEnabled(context)
            overlayAllowed = PermissionUtil.isFloatingWindowAllowed(context)
            batteryIgnored = PermissionUtil.isBatteryOptimizationIgnored(context)
            delay(500)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        Text("欢迎使用浏览助手", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(32.dp))

        // 步骤1：无障碍服务
        GuideStepItem(
            stepNum = 1,
            title = "开启无障碍服务",
            desc = "用于读取运满满订单列表信息，不会执行任何自动点击操作",
            isCompleted = accessibilityEnabled,
            buttonText = if (accessibilityEnabled) "✓ 已开启" else "去开启",
            onClick = {
                if (!accessibilityEnabled) {
                    PermissionUtil.openAccessibilitySettings(context)
                }
            }
        )
        Spacer(Modifier.height(16.dp))

        // 步骤2：悬浮窗权限
        GuideStepItem(
            stepNum = 2,
            title = "开启悬浮窗权限",
            desc = "用于显示监控状态和订单提醒悬浮球",
            isCompleted = overlayAllowed,
            buttonText = if (overlayAllowed) "✓ 已开启" else "去开启",
            onClick = {
                if (!overlayAllowed) {
                    PermissionUtil.openOverlaySettings(context)
                }
            }
        )
        Spacer(Modifier.height(16.dp))

        // 步骤3：电池白名单（可选）
        GuideStepItem(
            stepNum = 3,
            title = "加入电池白名单（可选）",
            desc = "防止系统杀后台，确保监控持续运行",
            isCompleted = batteryIgnored,
            buttonText = if (batteryIgnored) "✓ 已加入" else "去设置",
            onClick = {
                if (!batteryIgnored) {
                    PermissionUtil.openBatteryOptimizationSettings(context)
                }
            }
        )
        Spacer(Modifier.height(32.dp))

        // 步骤4：完成（需无障碍 + 悬浮窗都开启）
        if (accessibilityEnabled && overlayAllowed) {
            Button(
                onClick = onGuideComplete,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
            ) {
                Text("完成，进入主界面", fontSize = 16.sp)
            }
        } else {
            Text(
                "请先完成步骤 1-2",
                fontSize = 13.sp,
                color = Color.Gray
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 引导步骤条目。
 *
 * 左侧：圆形步骤编号（完成时绿色 ✓，未完成时灰色数字）
 * 中间：标题 + 描述
 * 右侧：操作按钮（完成则禁用绿色，未完成红色可点）
 */
@Composable
private fun GuideStepItem(
    stepNum: Int,
    title: String,
    desc: String,
    isCompleted: Boolean,
    buttonText: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧步骤编号圆
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(
                    color = if (isCompleted) Color(0xFF4CAF50) else Color(0xFF9E9E9E),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (isCompleted) "✓" else "$stepNum",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }
        Spacer(Modifier.width(12.dp))

        // 中间标题 + 描述
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(desc, fontSize = 12.sp, color = Color.Gray)
        }

        // 右侧按钮
        Button(
            onClick = onClick,
            enabled = !isCompleted,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isCompleted) Color(0xFF4CAF50) else Color(0xFFF44336),
                disabledContainerColor = Color(0xFF4CAF50)
            ),
            modifier = Modifier.width(90.dp)
        ) {
            Text(buttonText, fontSize = 12.sp)
        }
    }
}
