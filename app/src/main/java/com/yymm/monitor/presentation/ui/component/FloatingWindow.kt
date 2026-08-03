package com.yymm.monitor.presentation.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yymm.monitor.domain.model.FloatingWindowState
import com.yymm.monitor.domain.model.MonitorState

/**
 * 悬浮窗内容（PRD 3.4 D.2/D.3/D.4）。
 *
 * 收起状态：56dp 迷你球（状态颜色 + 图标 + "监控"）
 * 展开状态：200dp 白色卡片控制面板（状态/统计/规则汇总/控制按钮/快捷入口）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FloatingWindowContent(
    state: FloatingWindowState,
    onExpandToggle: () -> Unit,
    onStartClick: () -> Unit,
    onPauseClick: () -> Unit,
    onNavigate: (String) -> Unit,
    onClose: () -> Unit
) {
    if (!state.isExpanded) {
        // ─────────── 收起状态 - 迷你球 ───────────
        var menuExpanded by remember { mutableStateOf(false) }

        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(getStatusColor(state.monitorState))
                .combinedClickable(
                    onClick = { onExpandToggle() },
                    onLongClick = { menuExpanded = true }
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(getStatusIcon(state.monitorState), fontSize = 20.sp)
                Text("监控", fontSize = 10.sp, color = Color.White)
            }

            // 长按弹出"隐藏悬浮窗"菜单（PRD D.5）
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("导出节点树（调试）") },
                    onClick = {
                        menuExpanded = false
                        FloatingWindowHideHandler.dumpNodeTree()
                    }
                )
                DropdownMenuItem(
                    text = { Text("🎯 设为刷新起点") },
                    onClick = {
                        menuExpanded = false
                        FloatingWindowHideHandler.setRefreshPoint()
                    }
                )
                DropdownMenuItem(
                    text = { Text("隐藏悬浮窗") },
                    onClick = {
                        menuExpanded = false
                        FloatingWindowHideHandler.hide()
                    }
                )
            }
        }
    } else {
        // ─────────── 展开状态 - 控制面板 ───────────
        Card(
            modifier = Modifier.width(200.dp),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // 标题栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("浏览助手", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("✕", fontSize = 18.sp, modifier = Modifier.clickable { onClose() })
                }
                Spacer(Modifier.height(12.dp))

                // 异常状态提示条（PRD D.4）
                if (state.statusMessage.isNotEmpty() && state.monitorState == MonitorState.PAUSED) {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))) {
                        Text(
                            "⚠️ ${state.statusMessage}",
                            fontSize = 12.sp,
                            color = Color(0xFFE65100),
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // 状态大图标区
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(getStatusColor(state.monitorState)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(getStatusIcon(state.monitorState), fontSize = 24.sp, color = Color.White)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(getStatusText(state.monitorState), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(Modifier.height(8.dp))

                // 实时统计
                Text("刷新: ${state.refreshCount}次  匹配: ${state.matchCount}单", fontSize = 12.sp, color = Color.Gray)
                Text("确认: ${state.confirmCount}单", fontSize = 12.sp, color = Color.Gray)
                Spacer(Modifier.height(8.dp))

                Divider()
                Spacer(Modifier.height(8.dp))

                // 当前匹配规则汇总
                Text("当前匹配规则 (${state.activeRuleCount}套):", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                state.activeRuleNames.forEach { name ->
                    Text(
                        "• $name",
                        fontSize = 11.sp,
                        color = Color.Gray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(8.dp))

                Divider()
                Spacer(Modifier.height(8.dp))

                // 控制按钮（按状态显示）
                when (state.monitorState) {
                    MonitorState.RUNNING -> {
                        Button(
                            onClick = onPauseClick,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                        ) {
                            Text("⏸ 暂停监控")
                        }
                    }
                    MonitorState.READY, MonitorState.PAUSED, MonitorState.IDLE, MonitorState.ERROR -> {
                        Button(
                            onClick = onStartClick,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                        ) {
                            Text("🚀 开始监控")
                        }
                    }
                    else -> {
                        Button(onClick = onStartClick, modifier = Modifier.fillMaxWidth()) {
                            Text("🚀 开始监控")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                // 快捷入口（2x2 网格）
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        TextButton(onClick = { onNavigate("filter") }) { Text("🔍 筛选") }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        TextButton(onClick = { onNavigate("rules") }) { Text("📋 规则") }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        TextButton(onClick = { onNavigate("alert") }) { Text("🔔 提醒") }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        TextButton(onClick = { onNavigate("history") }) { Text("📊 历史") }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        TextButton(onClick = { onNavigate("settings") }) { Text("⚙️ 设置") }
                    }
                }
            }
        }
    }
}

/**
 * 悬浮窗动作处理器。
 *
 * FloatingWindow.kt 无法直接引用 FloatingWindowManager（避免 UI 层依赖单例），
 * 由 YymmMonitorService 在初始化时注入 hide 回调；导出节点树回调也在此挂接。
 */
object FloatingWindowHideHandler {
    var hideCallback: (() -> Unit)? = null
    var dumpNodeTreeCallback: (() -> Unit)? = null
    var setRefreshPointCallback: (() -> Unit)? = null
    fun hide() = hideCallback?.invoke()
    fun dumpNodeTree() = dumpNodeTreeCallback?.invoke()
    fun setRefreshPoint() = setRefreshPointCallback?.invoke()
}

/** 状态颜色（PRD D.2）：绿=监控中, 红=暂停/错误, 黄=检测中, 蓝=就绪, 灰=等待启动 */
fun getStatusColor(state: MonitorState): Color = when (state) {
    MonitorState.RUNNING -> Color(0xFF4CAF50)
    MonitorState.PAUSED, MonitorState.ERROR -> Color(0xFFF44336)
    MonitorState.READY -> Color(0xFF2196F3)
    MonitorState.CHECKING -> Color(0xFFFFC107)
    MonitorState.IDLE -> Color(0xFF9E9E9E)
}

/** 状态图标 */
fun getStatusIcon(state: MonitorState): String = when (state) {
    MonitorState.RUNNING -> "●"
    MonitorState.READY, MonitorState.PAUSED, MonitorState.IDLE -> "▶"
    MonitorState.ERROR -> "!"
    MonitorState.CHECKING -> "?"
}

/** 状态文本 */
fun getStatusText(state: MonitorState): String = when (state) {
    MonitorState.RUNNING -> "监控运行中"
    MonitorState.READY -> "就绪，待开始"
    MonitorState.PAUSED -> "已暂停"
    MonitorState.CHECKING -> "检测中..."
    MonitorState.IDLE -> "等待启动"
    MonitorState.ERROR -> "页面异常"
}
