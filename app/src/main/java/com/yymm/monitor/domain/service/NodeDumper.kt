package com.yymm.monitor.domain.service

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 节点树 dump 工具。
 *
 * 把当前窗口的 AccessibilityNodeInfo 树（含 className / viewId / text / desc / 坐标 / 可见性）
 * 导出到 App 私有目录 files/node_dumps/，用于真机采集运满满订单页的真实结构，
 * 以便针对性地优化 [OrderParser] 的节点定位（viewId 优先）与兜底解析。
 */
object NodeDumper {

    fun dump(context: Context, root: AccessibilityNodeInfo?): File? {
        if (root == null) return null
        val dir = File(context.filesDir, "node_dumps")
        if (!dir.exists()) dir.mkdirs()
        val fileName = "nodes_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
        val file = File(dir, fileName)
        val sb = StringBuilder()
        sb.appendLine("========== 节点树 dump ==========")
        sb.appendLine("time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        sb.appendLine("pkg : ${root.packageName}")
        sb.appendLine("win : ${root.windowId}  cls: ${root.className}")
        sb.appendLine("================================")
        dumpNode(root, sb, 0)
        file.writeText(sb.toString())
        return file
    }

    private fun dumpNode(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (depth > 40) return
        val indent = "  ".repeat(depth)
        val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
        val vid = node.viewIdResourceName?.toString() ?: ""
        val text = node.text?.toString()?.replace('\n', '⏎') ?: ""
        val desc = node.contentDescription?.toString()?.replace('\n', '⏎') ?: ""
        val rect = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        val visible = node.isVisibleToUser
        val clickable = node.isClickable
        val scrollable = node.isScrollable
        sb.appendLine(
            "$indent<$cls> " +
                (if (vid.isNotEmpty()) "id=$vid " else "") +
                (if (text.isNotEmpty()) "text=\"$text\" " else "") +
                (if (desc.isNotEmpty()) "desc=\"$desc\" " else "") +
                "[$rect] vis=$visible click=$clickable scroll=$scrollable"
        )
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { dumpNode(it, sb, depth + 1) }
        }
    }
}
