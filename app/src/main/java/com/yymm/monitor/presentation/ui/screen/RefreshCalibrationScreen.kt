package com.yymm.monitor.presentation.ui.screen

import android.content.Context
import android.widget.Toast
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

    // 起点/终点坐标（默认：起点 20% 屏高，终点 65% 屏高；已校准用校准值）
    var startX by remember { mutableFloatStateOf(
        if (saved.refreshStartX in 0..screenW) saved.refreshStartX.toFloat() else screenW / 2f
    ) }
    var startY by remember { mutableFloatStateOf(
        if (saved.refreshStartY in 0..screenH) saved.refreshStartY.toFloat() else screenH * 0.2f
    ) }
    var endX by remember { mutableFloatStateOf(startX) }
    var endY by remember { mutableFloatStateOf(screenH * 0.65f) }
    var gestureDuration by remember { mutableStateOf(400L) }

    // 实时拖拽中的终点（预览用）
    var previewY by remember { mutableFloatStateOf(-1f) }

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
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0x22000000))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { startOffset ->
                            startX = startOffset.x
                            startY = startOffset.y
                            endX = startOffset.x
                            endY = startOffset.y
                            previewY = startOffset.y
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            endX = change.position.x
                            endY = change.position.y
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
            val lineEndY = if (previewY > 0) previewY else endY
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawLine(
                    color = Color(0xFF4CAF50),
                    start = Offset(startX, startY),
                    end = Offset(endX, lineEndY),
                    strokeWidth = 6f
                )
            }
            // 起点标记（绿点）
            CalibrationMarker(
                x = startX, y = startY, color = Color(0xFF4CAF50), label = "起点"
            )
            // 终点标记（红点）
            CalibrationMarker(
                x = endX, y = if (previewY > 0) previewY else endY, color = Color(0xFFF44336), label = "终点"
            )
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
                                refreshStartY = startY.roundToInt()
                            )
                            prefs.edit().putString("user_settings", gson.toJson(updated)).apply()
                            Toast.makeText(context, "刷新起点已保存 (${startX.roundToInt()},${startY.roundToInt()})", Toast.LENGTH_SHORT).show()
                            navController.popBackStack()
                        } catch (e: Exception) {
                            Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
                ) {
                    Text("保存刷新起点", fontSize = 15.sp)
                }
                Button(
                    onClick = {
                        startX = screenW / 2f
                        startY = screenH * 0.2f
                        endX = startX
                        endY = screenH * 0.65f
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
