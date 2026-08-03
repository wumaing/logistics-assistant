package com.yymm.monitor.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.Gson
import com.yymm.monitor.data.model.ParsedOrder
import com.yymm.monitor.data.repository.HistoryRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 订单提醒弹窗（PRD G.2）。
 *
 * 全屏半透明遮罩 + 底部滑入白色卡片。
 * 弹窗不自动消失，必须用户点击"忽略"或"我知道了"关闭。
 * - 忽略 → 记录 action=IGNORED
 * - 我知道了 → 记录 action=VIEWED
 */
@AndroidEntryPoint
class AlertPopupActivity : ComponentActivity() {

    @Inject lateinit var historyRepository: HistoryRepository

    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val orderJson = intent.getStringExtra("order_json")
        val order = try {
            gson.fromJson(orderJson, ParsedOrder::class.java)
        } catch (e: Exception) {
            null
        }
        if (order == null) {
            finish()
            return
        }

        setContent {
            MaterialTheme {
                AlertPopupContent(
                    order = order,
                    onIgnore = { onIgnore(order.id) },
                    onDismiss = { onDismiss(order.id) }
                )
            }
        }
    }

    private fun onIgnore(orderId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            historyRepository.recordAction(orderId, com.yymm.monitor.data.model.AlertAction.IGNORED)
        }
        finish()
    }

    private fun onDismiss(orderId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            historyRepository.recordAction(orderId, com.yymm.monitor.data.model.AlertAction.VIEWED)
        }
        finish()
    }

    @Composable
    private fun AlertPopupContent(
        order: ParsedOrder,
        onIgnore: () -> Unit,
        onDismiss: () -> Unit
    ) {
        var visible by remember { mutableStateOf(false) }

        // 返回键等同于"我知道了"
        BackHandler { onDismiss() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f)),
            contentAlignment = Alignment.BottomCenter
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(300)),
                exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(300))
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 32.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // 标题
                        Text(
                            "🚨 发现匹配订单",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Red
                        )
                        Spacer(Modifier.height(16.dp))

                        // 订单信息卡片
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                // 出发地 → 目的地（大字）
                                // OCR 来源订单优先展示原始文本（比解析的地名准确，真人可对照屏幕）
                                val ocrRaw = order.rawTexts.firstOrNull()
                                if (ocrRaw != null && (order.fromProvince.length <= 2 || order.toProvince.length <= 2)) {
                                    Text(
                                        ocrRaw,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                } else {
                                    Row(
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            "${order.fromProvince} ${order.fromCity ?: ""}",
                                            fontSize = 22.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text("↓", fontSize = 20.sp)
                                        Text(
                                            "${order.toProvince} ${order.toCity ?: ""}",
                                            fontSize = 22.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Spacer(Modifier.height(12.dp))

                                // 详细信息
                                Text("货物：${order.cargoName ?: "未知"}")
                                Text("重量：${formatWeight(order.weightMin, order.weightMax)}")
                                Text("车型：${order.vehicleTypes.joinToString("/").ifEmpty { "不限" }}")
                                Text("车长：${order.vehicleLengths.joinToString("/").ifEmpty { "不限" }}")
                                Text("运费：${order.priceText}")
                                Text("装货：${order.loadTimeText}")
                                Text("距离：${order.distance ?: "未知"}")
                                Text("标签：${order.tags.joinToString(" · ")}")
                            }
                        }

                        Spacer(Modifier.height(20.dp))

                        // 按钮区
                        Button(
                            onClick = onIgnore,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
                        ) {
                            Text("❌ 忽略此单")
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
                        ) {
                            Text("✓ 我知道了（关闭）")
                        }
                    }
                }
            }
        }

        // 触发滑入动画
        LaunchedEffect(Unit) {
            visible = true
        }
    }

    private fun formatWeight(min: Float?, max: Float?): String = when {
        min != null && max != null && min == max -> "${min}吨"
        min != null && max != null -> "${min}-${max}吨"
        min != null -> "${min}吨"
        max != null -> "${max}吨"
        else -> "未知"
    }
}
