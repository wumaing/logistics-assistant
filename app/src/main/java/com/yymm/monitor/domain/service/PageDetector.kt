package com.yymm.monitor.domain.service

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 页面检测器（TECH_SPEC 2.3.1 辅助 / PRD E.3）。
 *
 * 判断当前页面是否为运满满订单列表页，三者满足其二即通过：
 * 1. 包名包含 "com.yymm" 或 "yunmanman"
 * 2. Activity 类名命中白名单（白名单初始为空 Set，空时视为通过）
 * 3. 节点树包含特征文本："筛选"、"智能排序"、"货源"、"去抢单" 任一
 */
@Singleton
class PageDetector @Inject constructor() {

    /** Activity 类名白名单，需运行时采集后填充；空时视为通过 */
    private val whitelistedActivities: Set<String> = emptySet()

    private val featureKeywords = setOf("筛选", "智能排序", "货源", "去抢单")

    suspend fun isOrderListPage(
        event: AccessibilityEvent?,
        rootNode: AccessibilityNodeInfo?
    ): Boolean {
        var checksPassed = 0

        // 1. 包名检测
        val packageName = event?.packageName?.toString() ?: rootNode?.packageName?.toString()
        val packageOk = packageName?.let {
            it.contains("com.xiwei.logistics") || it.contains("com.yymm") || it.contains("yunmanman")
        } == true
        if (packageOk) {
            checksPassed++
        } else {
            // 快速排除：当前窗口明显不是运满满（包名存在但不匹配）→ 直接判定不是订单页
            // 兜底 packageName 为空时（如 root 尚未就绪）继续走特征检测
            if (packageName != null) return false
        }

        // 2. Activity 类名检测（白名单空时视为通过）
        val className = event?.className?.toString()
        if (whitelistedActivities.isEmpty() || className?.let { whitelistedActivities.any { a -> it.contains(a) } } == true) {
            checksPassed++
        }

        // 3. 特征文本检测
        val allTexts = mutableListOf<String>()
        if (rootNode != null) {
            collectTexts(rootNode, allTexts)
        }
        if (allTexts.any { text -> featureKeywords.any { it in text } }) {
            checksPassed++
        }

        return checksPassed >= 2
    }

    /** 递归提取节点文本（text + contentDescription） */
    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableList<String>) {
        try {
            node.text?.toString()?.let { if (it.isNotBlank()) out.add(it) }
            node.contentDescription?.toString()?.let { if (it.isNotBlank()) out.add(it) }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    collectTexts(child, out)
                }
            }
        } catch (_: Exception) {
            // 节点树访问异常时跳过该分支，不影响整体判定
        }
    }
}
