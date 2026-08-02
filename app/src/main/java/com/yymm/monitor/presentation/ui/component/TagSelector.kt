package com.yymm.monitor.presentation.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 通用标签选择器（PRD B.2 标签式多选）。
 *
 * 选中：红色边框 + 红色文字 + 浅红底
 * 未选中：灰色边框 + 黑色文字 + 白底
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> TagSelector(
    items: List<T>,
    selectedItems: Set<T>,
    onToggle: (T) -> Unit,
    itemLabel: (T) -> String = { it.toString() }
) {
    FlowRow {
        items.forEach { item ->
            val selected = selectedItems.contains(item)
            Box(
                modifier = Modifier
                    .padding(end = 8.dp, bottom = 8.dp)
                    .border(
                        width = 1.dp,
                        color = if (selected) Color.Red else Color.Gray,
                        shape = RoundedCornerShape(16.dp)
                    )
                    .background(
                        color = if (selected) Color(0xFFFFEBEE) else Color.White,
                        shape = RoundedCornerShape(16.dp)
                    )
                    .clickable { onToggle(item) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = itemLabel(item),
                    color = if (selected) Color.Red else Color.Black,
                    fontSize = 14.sp
                )
            }
        }
    }
}
