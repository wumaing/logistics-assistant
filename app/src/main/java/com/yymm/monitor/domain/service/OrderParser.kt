package com.yymm.monitor.domain.service

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.yymm.monitor.data.model.ParsedOrder
import java.math.BigInteger
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 订单解析器（TECH_SPEC 2.3.2）。
 *
 * 从无障碍节点树中提取订单列表，逐条解析为 [ParsedOrder]。
 * 所有 extract 方法均有 try-catch 保护，单 item 解析失败返回 null 不中断整体流程。
 */
@Singleton
class OrderParser @Inject constructor() {

    companion object {
        val WEIGHT_PATTERN = Regex("""(\d+(?:\.\d+)?)(?:-(\d+(?:\.\d+)?))?吨|小于(\d+(?:\.\d+)?)吨|大于(\d+(?:\.\d+)?)吨""")
        val LENGTH_PATTERN = Regex("""(\d+(?:\.\d+)?)米""")
        val PRICE_PATTERN = Regex("""(\d+)元|电议|面议""")
        val DISTANCE_PATTERN = Regex("""约(\d+(?:\.\d+)?)km|(\d+(?:\.\d+)?)公里""")
        val VEHICLE_TYPES = setOf("平板", "高栏", "厢式", "集装箱", "拖车", "自卸", "冷藏",
            "厢式保温", "高低板", "面包车", "棉被车", "爬梯车", "飞翼车", "依维柯")
        val CARGO_TYPE_TAGS = setOf("整车货", "优车货", "即时单", "拼车货")
        val EXTRA_TAGS = setOf("满运宝", "高信用货主", "订金可退", "送抢单特权")
        val TIME_KEYWORDS = setOf("今天", "明天", "后天", "昨天", "即时")
    }

    fun parse(rootNode: AccessibilityNodeInfo): List<ParsedOrder> {
        val orders = mutableListOf<ParsedOrder>()
        val listNodes = findListNodes(rootNode)
        if (listNodes.isNotEmpty()) {
            listNodes.forEach { listNode ->
                for (i in 0 until listNode.childCount) {
                    listNode.getChild(i)?.let { itemNode ->
                        parseOrderItem(itemNode)?.let { orders.add(it) }
                    }
                }
            }
            return orders
        }
        return parseByTextFeatures(rootNode)
    }

    private fun findListNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        if (root.className?.contains("RecyclerView") == true ||
            root.className?.contains("ListView") == true) {
            result.add(root)
        }
        for (i in 0 until root.childCount) {
            root.getChild(i)?.let { child ->
                result.addAll(findListNodes(child))
            }
        }
        return result
    }

    private fun parseOrderItem(itemNode: AccessibilityNodeInfo): ParsedOrder? {
        val texts = extractAllTexts(itemNode)
        if (texts.isEmpty()) return null
        try {
            val (from, to) = extractLocations(texts) ?: return null
            val cargo = extractCargo(texts)
            val weight = extractWeight(texts)
            val lengths = extractVehicleLengths(texts)
            val types = extractVehicleTypes(texts)
            val (priceAmount, priceText, isDianyi) = extractPrice(texts)
            val loadTime = extractLoadTime(texts)
            val distance = extractDistance(texts)
            val tags = extractTags(texts)
            val id = generateOrderId(from, to, cargo, weight)
            return ParsedOrder(
                id = id,
                fromProvince = from.first,
                fromCity = from.second,
                toProvince = to.first,
                toCity = to.second,
                cargoName = cargo,
                weightMin = weight?.first,
                weightMax = weight?.second,
                vehicleLengths = lengths,
                vehicleTypes = types,
                priceAmount = priceAmount,
                priceText = priceText,
                isDianyi = isDianyi,
                loadTimeText = loadTime ?: "",
                distance = distance,
                tags = tags,
                rawTexts = texts
            )
        } catch (e: Exception) {
            Log.e("OrderParser", "Parse item error", e)
            return null
        }
    }

    private fun extractAllTexts(node: AccessibilityNodeInfo): List<String> {
        val texts = mutableListOf<String>()
        node.text?.toString()?.let { if (it.isNotBlank()) texts.add(it.trim()) }
        node.contentDescription?.toString()?.let { if (it.isNotBlank()) texts.add(it.trim()) }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                texts.addAll(extractAllTexts(child))
            }
        }
        return texts
    }

    private fun extractLocations(texts: List<String>): Pair<Pair<String, String?>, Pair<String, String?>>? {
        for (text in texts) {
            // 分隔符支持 ->、-、~、>（原 TECH_SPEC 的 [\-\>~] 中 \> 为正则无效转义，修正为 [->~]{1,2}）
            val arrowPattern = Regex("""([一-龥]+)\s*([一-龥]+)?\s*[->~]{1,2}\s*([一-龥]+)\s*([一-龥]+)?""")
            val match = arrowPattern.find(text)
            if (match != null) {
                val fromProvince = match.groupValues[1]
                val fromCity = match.groupValues[2].ifEmpty { null }
                val toProvince = match.groupValues[3]
                val toCity = match.groupValues[4].ifEmpty { null }
                return Pair(Pair(fromProvince, fromCity), Pair(toProvince, toCity))
            }
        }
        val locationTexts = texts.filter { it.length in 2..10 && it.matches(Regex("""[一-龥]+""")) }
        if (locationTexts.size >= 2) {
            return Pair(Pair(locationTexts[0], null), Pair(locationTexts[1], null))
        }
        return null
    }

    private fun extractPrice(texts: List<String>): Triple<Int?, String, Boolean> {
        var priceAmount: Int? = null
        var priceText = ""
        var isDianyi = false
        for (text in texts) {
            if (text.contains("电议") || text.contains("面议")) {
                isDianyi = true
                priceText = text
            }
            val priceMatch = Regex("""(\d+)元""").find(text)
            if (priceMatch != null) {
                priceAmount = priceMatch.groupValues[1].toInt()
                if (priceText.isEmpty()) priceText = text
            }
        }
        if (isDianyi && priceText.isEmpty()) priceText = "电议"
        return Triple(priceAmount, priceText, isDianyi)
    }

    private fun extractWeight(texts: List<String>): Pair<Float, Float>? {
        for (text in texts) {
            val match = WEIGHT_PATTERN.find(text)
            if (match != null) {
                return when {
                    match.groupValues[3].isNotEmpty() -> Pair(0f, match.groupValues[3].toFloat())
                    match.groupValues[4].isNotEmpty() -> Pair(match.groupValues[4].toFloat(), Float.MAX_VALUE)
                    match.groupValues[2].isNotEmpty() -> Pair(
                        match.groupValues[1].toFloat(),
                        match.groupValues[2].toFloat()
                    )
                    else -> Pair(match.groupValues[1].toFloat(), match.groupValues[1].toFloat())
                }
            }
        }
        return null
    }

    private fun extractVehicleLengths(texts: List<String>): List<String> {
        return texts.flatMap { text ->
            LENGTH_PATTERN.findAll(text).map { it.groupValues[1] }.toList()
        }.distinct()
    }

    private fun extractVehicleTypes(texts: List<String>): List<String> {
        return VEHICLE_TYPES.filter { type -> texts.any { it.contains(type) } }
    }

    private fun extractCargo(texts: List<String>): String? {
        return texts.firstOrNull { text ->
            text.length in 2..8 &&
                !text.contains("吨") && !text.contains("米") && !text.contains("元") &&
                !VEHICLE_TYPES.any { text.contains(it) } &&
                !CARGO_TYPE_TAGS.any { text.contains(it) } &&
                text.matches(Regex("""[一-龥]+"""))
        }
    }

    private fun extractLoadTime(texts: List<String>): String? {
        return texts.firstOrNull { text ->
            TIME_KEYWORDS.any { text.contains(it) } || text.contains("装货")
        }
    }

    private fun extractDistance(texts: List<String>): String? {
        return texts.firstOrNull { text -> DISTANCE_PATTERN.containsMatchIn(text) }
    }

    private fun extractTags(texts: List<String>): List<String> {
        return (CARGO_TYPE_TAGS + EXTRA_TAGS).filter { tag -> texts.any { it.contains(tag) } }
    }

    private fun generateOrderId(
        from: Pair<String, String?>,
        to: Pair<String, String?>,
        cargo: String?,
        weight: Pair<Float, Float>?,
        timestamp: Long = System.currentTimeMillis()
    ): String {
        val raw = "${from.first}${from.second}_${to.first}${to.second}_${cargo}_${weight?.first}_${timestamp / 60000}"
        return MessageDigest.getInstance("MD5").run {
            update(raw.toByteArray())
            BigInteger(1, digest()).toString(16).take(16)
        }
    }

    private fun parseByTextFeatures(root: AccessibilityNodeInfo): List<ParsedOrder> {
        // 后续步骤实现：无 RecyclerView 时按文本特征分块解析
        return emptyList()
    }
}
