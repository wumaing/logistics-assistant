package com.yymm.monitor.presentation.ui.screen

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.google.gson.Gson
import kotlin.math.roundToInt

/**
 * 刷新校准页：可视化设置下拉刷新起点/终点。
 *
 * 参考开源 AutoAction 的「手势录制」范式，真人直接手指在屏幕上做下拉手势：
 * - 按住起点（绿点区）往下拉到终点（红点区）
 * - 实时显示滑动轨迹线 + 坐标
 * - 保存后刷新手势从起点滑到终点（含时长）
 *
 * 校准坐标是屏幕绝对坐标，与页面内容无关，适用于司机端任意页面。
 */
@Composable
fun RefreshCalibrationScreen(
    navController: NavController,
    context: Context = androidx.compose.ui.platform.LocalContext.current
) {
    val metrics = context.resources.displayMetrics
    val screenW = metrics.widthPixels
    val screenH = metrics.heightPixels

    val prefs = remember {
        context.getSharedPreferences("yymm_settings", Context.MODE_PRIVATE)
    }
    val gson = remember { Gson() }
    val saved = remember {
        try {
            val json = prefs.getString("user_settings", null)
            if (json != null) gson.fromJson(json, com.yymm.monitor.data.model.UserSettings::class.java)
            else com.yymm.monitor.data.model.UserSettings()
        } catch (_: Exception) {
            com.yymm.monitor.data.model.UserSettings()
        }
    }

    // 起点/终点坐标（默认：起点 33% 屏高，终点 87% 屏高；已校准用校准值）
    var startX by remember { mutableFloatStateOf(
        if (saved.refreshStartX in 0..screenW) saved.refreshStartX.toFloat() else screenW / 2f
    ) }
    var startY by remember { mutableFloatStateOf(
        if (saved.refreshStartY in 0..screenH) saved.refreshStartY.toFloat() else screenH * 0.33f
    ) }
    var endX by remember { mutableFloatStateOf(
        if (saved.refreshEndX in 0..screenW) saved.refreshEndX.toFloat() else startX
    ) }
    var endY by remember { mutableFloatStateOf(
        if (saved.refreshEndY in 0..screenH) saved.refreshEndY.toFloat() else screenH * 0.87f
    ) }
    var gestureDuration by remember { mutableStateOf(400L) }

    // 实时拖拽中的终点（预览用）
    var previewY by remember { mutableFloatStateOf(-1f) }

    // 刷新动画进度（0~1f）：监控每执行一次刷新手势，重播轨迹动画
    var refreshAnimProgress by remember { mutableFloatStateOf(0f) }
    // 刷新事件计数（广播+1，LaunchedEffect 监听到变化后驱动动画）
    var refreshTickCount by remember { mutableStateOf(0) }

    // 监听监控的刷新事件广播：收到后重播轨迹动画（直观显示"正在这里刷新"）
    androidx.compose.runtime.DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                if (intent?.action == com.yymm.monitor.domain.service.YymmMonitorService.ACTION_REFRESH_TICK) {
                    refreshTickCount++
                }
            }
        }
        val filter = android.content.IntentFilter(com.yymm.monitor.domain.service.YymmMonitorService.ACTION_REFRESH_TICK)
        context.registerReceiver(receiver, filter)
        onDispose { context.unregisterReceiver(receiver) }
    }

    // 每次 refreshTickCount 变化，动画进度 0→1（约 600ms 滑完轨迹）
    androidx.compose.runtime.LaunchedEffect(refreshTickCount) {
        if (refreshTickCount > 0) {
            refreshAnimProgress = 0f
            for (step in 1..60) {
                refreshAnimProgress = step / 60f
                kotlinx.coroutines.delay(10)
            }
            refreshAnimProgress = 0f
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.White)) {
        // 顶部标题栏
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { navController.popBackStack() }) { Text("←") }
            Text("校准刷新位置", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }

        // 校准层（占满剩余空间，手指下拉实时校准）
        // 记录校准层相对屏幕的偏移（局部坐标→屏幕绝对坐标转换）
        var boxOffsetY by remember { mutableFloatStateOf(0f) }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0x22000000))
                .onGloballyPositioned { coords ->
                    // Box 左上角相对屏幕的位置（含状态栏/标题栏偏移）
                    boxOffsetY = coords.positionInWindow().y
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { startOffset ->
                            startX = startOffset.x
                            startY = startOffset.y + boxOffsetY
                            endX = startOffset.x
                            endY = startOffset.y + boxOffsetY
                            previewY = startOffset.y
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            endX = change.position.x
                            endY = change.position.y + boxOffsetY
                            previewY = change.position.y
                        },
                        onDragEnd = {
                            previewY = -1f
                            gestureDuration = 400
                        }
                    )
                }
        ) {
            // 滑动轨迹线（起点→终点，实时预览）
            // 存储的 startY/endY 是屏幕绝对坐标，绘制时转回 Box 局部坐标（减 boxOffsetY）
            val lineEndY = if (previewY > 0) previewY else endY - boxOffsetY
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawLine(
                    color = Color(0xFF4CAF50),
                    start = Offset(startX, startY - boxOffsetY),
                    end = Offset(endX, lineEndY),
                    strokeWidth = 6f
                )
            }
            // 起点标记（绿点）
            CalibrationMarker(
                x = startX, y = startY - boxOffsetY, color = Color(0xFF4CAF50), label = "起点"
            )
            // 终点标记（红点）
            CalibrationMarker(
                x = endX, y = if (previewY > 0) previewY else endY - boxOffsetY, color = Color(0xFFF44336), label = "终点"
            )
            // 刷新动画：监控执行刷新手势时，一个蓝点沿轨迹从起点滑到终点（直观显示"正在这里刷新"）
            if (refreshAnimProgress > 0f && refreshAnimProgress < 1f) {
                val animEndY = (startY - boxOffsetY) + (endY - boxOffsetY - (startY - boxOffsetY)) * refreshAnimProgress
                val animEndX = startX + (endX - startX) * refreshAnimProgress
                Box(
                    modifier = Modifier
                        .offset { IntOffset(animEndX.roundToInt() - 12, animEndY.roundToInt() - 12) }
                        .size(24.dp)
                        .background(Color(0xFF2196F3), CircleShape)
                )
            }
        }

        // 底部操作栏
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                "起点(${startX.roundToInt()},${startY.roundToInt()}) 下滑到 终点(${endX.roundToInt()},${endY.roundToInt()})，时长 ${gestureDuration}ms",
                fontSize = 13.sp,
                color = Color.Gray
            )
            Text(
                "💡 在屏幕任意处按住往下滑，即可实时校准起点→终点轨迹",
                fontSize = 12.sp,
                color = Color(0xFF2196F3)
            )
            Text(
                "🔍 保存后自动启动监控，回到运满满页面即可看到刷新手势从起点滑到终点",
                fontSize = 12.sp,
                color = Color(0xFF4CAF50)
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        try {
                            val json = prefs.getString("user_settings", null)
                            val current = if (json != null) {
                                gson.fromJson(json, com.yymm.monitor.data.model.UserSettings::class.java)
                            } else {
                                com.yymm.monitor.data.model.UserSettings()
                            }
                            val updated = current.copy(
                                refreshStartX = startX.roundToInt(),
                                refreshStartY = startY.roundToInt(),
                                refreshEndX = endX.roundToInt(),
                                refreshEndY = endY.roundToInt(),
                                refreshDurationMs = gestureDuration.toInt()
                            )
                            prefs.edit().putString("user_settings", gson.toJson(updated)).commit()
                            // 通知监控服务立即重读设置（保存即生效，不依赖重启）
                            try {
                                val svcIntent = android.content.Intent(
                                    context,
                                    com.yymm.monitor.domain.service.YymmMonitorService::class.java
                                ).apply {
                                    action = com.yymm.monitor.domain.service.YymmMonitorService.ACTION_SETTINGS_CHANGED
                                }
                                context.startService(svcIntent)
                            } catch (_: Exception) {
                                // 服务不可用时忽略，下次轮询仍会读新设置
                            }
                            Toast.makeText(
                                context,
                                "刷新轨迹已保存 (${startX.roundToInt()},${startY.roundToInt()})→(${endX.roundToInt()},${endY.roundToInt()})",
                                Toast.LENGTH_SHORT
                            ).show()
                            // 不返回，留在校准页：启动监控（如果未在监控中），
                            // 让用户能直观看到刷新手势在新轨迹上运行
                            try {
                                val startIntent = android.content.Intent(
                                    context,
                                    com.yymm.monitor.domain.service.YymmMonitorService::class.java
                                ).apply {
                                    action = com.yymm.monitor.domain.service.YymmMonitorService.ACTION_START_MONITOR
                                }
                                context.startService(startIntent)
                            } catch (_: Exception) {
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
                ) {
                    Text("保存刷新起点", fontSize = 15.sp)
                }
                // 试刷一次：用当前轨迹坐标立刻执行真实下拉手势（在校准页上直观看到滑动效果）
                Button(
                    onClick = {
                        try {
                            val testIntent = android.content.Intent(
                                context,
                                com.yymm.monitor.domain.service.YymmMonitorService::class.java
                            ).apply {
                                action = com.yymm.monitor.domain.service.YymmMonitorService.ACTION_TEST_REFRESH
                                putExtra("start_x", startX.roundToInt())
                                putExtra("start_y", startY.roundToInt())
                                putExtra("end_x", endX.roundToInt())
                                putExtra("end_y", endY.roundToInt())
                                putExtra("duration_ms", gestureDuration.toInt())
                            }
                            context.startService(testIntent)
                            Toast.makeText(
                                context,
                                "试刷中：3秒后从(${startX.roundToInt()},${startY.roundToInt()})下拉到(${endX.roundToInt()},${endY.roundToInt()})",
                                Toast.LENGTH_SHORT
                            ).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "试刷失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
                ) {
                    Text("试刷一次", fontSize = 15.sp)
                }
                Button(
                    onClick = {
                        startX = screenW / 2f
                        startY = screenH * 0.33f
                        endX = screenW / 2f
                        endY = screenH * 0.87f
                        gestureDuration = 400
                    },
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text("恢复默认", fontSize = 15.sp)
                }
            }
        }
    }
}

/** 校准标记 */
@Composable
private fun CalibrationMarker(
    x: Float,
    y: Float,
    color: Color,
    label: String
) {
    Box(
        modifier = Modifier
            .offset { IntOffset(x.roundToInt() - 24, y.roundToInt() - 24) }
            .size(48.dp)
            .border(2.dp, Color.White, CircleShape)
            .background(color, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 10.sp, color = Color.White)
    }
}
