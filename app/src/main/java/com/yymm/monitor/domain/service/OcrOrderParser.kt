package com.yymm.monitor.domain.service

import com.yymm.monitor.data.model.ParsedOrder
import java.math.BigInteger
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OCR 订单解析器。
 *
 * 从截屏 OCR 识别出的文本流中解析订单（司机端屏蔽无障碍节点树，改用截屏 OCR）。
 * OCR 文本是纯文本行，无节点结构，这里把整段文本按「地点箭头分隔」拆成候选订单块，
 * 逐块复用 [OrderParser] 的正则逻辑解析字段。
 */
@Singleton
class OcrOrderParser @Inject constructor(
    private val orderParser: OrderParser
) {

    companion object {
        /** 地点箭头：出发 → 目的（兼容 OCR 常见的 →、一、-、到 等符号） */
        val ARROW = Regex("""([一-龥]{2,8})\s*(?:到|至|发往|->|→|—|~|一|~)\s*([一-龥]{2,8})""")
        val PRICE = Regex("""(\d{2,6})\s*元""")
        val WEIGHT = Regex("""(\d+(?:\.\d+)?)\s*吨""")
        val DISTANCE = Regex("""(\d+(?:\.\d+)?)\s*(?:km|公里)""")
    }

    /**
     * 从 OCR 文本解析订单列表。
     *
     * OCR 文本示例（司机端订单行）：
     * "山东济南 → 江苏南京  3.5吨  平板  500元  今天装货"
     * "济宁鱼台 → 日照东阳  10吨  高栏  1200元"
     *
     * @param ocrText 截屏 OCR 识别出的完整文本
     * @return 解析出的订单列表（可能为空）
     */
    fun parse(ocrText: String): List<ParsedOrder> {
        if (ocrText.isBlank()) return emptyList()
        val orders = mutableListOf<ParsedOrder>()

        // 按行拆，逐行找地点箭头（每条订单一行或跨行）
        val lines = ocrText.lines().map { it.trim() }.filter { it.isNotBlank() }

        // 先尝试单行匹配（最常见）
        for (line in lines) {
            val match = ARROW.find(line) ?: continue
            val from = match.groupValues[1]
            val to = match.groupValues[2]
            if (from.length < 2 || to.length < 2) continue

            val (fromProvince, fromCity) = splitRegion(from)
            val (toProvince, toCity) = splitRegion(to)

            val price = PRICE.find(line)?.groupValues?.get(1)?.toIntOrNull()
            val weight = WEIGHT.find(line)?.groupValues?.get(1)?.toFloatOrNull()
            val distance = DISTANCE.find(line)?.groupValues?.get(1)

            val id = generateOrderId(from, to, price, weight)
            orders.add(
                ParsedOrder(
                    id = id,
                    fromProvince = fromProvince,
                    fromCity = fromCity,
                    toProvince = toProvince,
                    toCity = toCity,
                    cargoName = null,
                    weightMin = weight,
                    weightMax = weight,
                    vehicleLengths = emptyList(),
                    vehicleTypes = emptyList(),
                    priceAmount = price,
                    priceText = price?.let { "${it}元" } ?: "",
                    isDianyi = line.contains("电议") || line.contains("面议"),
                    loadTimeText = line,
                    distance = distance,
                    tags = emptyList(),
                    rawTexts = listOf(line)
                )
            )
        }

        // 去重（同出发地+目的地+价格只留一个）
        return orders.distinctBy { it.id }
    }

    /**
     * 把 OCR 识别的地点拆成省/市。
     * OCR 文本常见："山东济南" → (山东, 济南)；"济宁市" → 可能只识别出"济宁"。
     * 注意：这里无法保证省/市拆分绝对正确（OCR 文本无结构化），
     * 采用「首2字为省，其余为市」的启发式 + 匹配时宽松处理。
     */
    private fun splitRegion(text: String): Pair<String, String?> {
        val t = text.trim()
        if (t.length <= 3) return Pair(t, null)
        // 直辖市（北京/天津/上海/重庆）后跟区 → 省=直辖市名
        val direct = listOf("北京", "天津", "上海", "重庆")
        for (d in direct) {
            if (t.startsWith(d) && t.length > d.length) {
                return Pair(d, t.substring(d.length))
            }
        }
        // 普通：前2字为省，其余为市/区
        val province = t.substring(0, 2)
        val rest = t.substring(2)
        return Pair(province, rest.ifEmpty { null })
    }

    /** 订单 id：内容 MD5（同内容不去重提醒） */
    private fun generateOrderId(from: String, to: String, price: Int?, weight: Float?): String {
        val raw = "${from}_${to}_${price}_${weight}"
        return MessageDigest.getInstance("MD5").run {
            update(raw.toByteArray())
            BigInteger(1, digest()).toString(16).take(16)
        }
    }
}
