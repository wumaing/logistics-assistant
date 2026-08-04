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
    private val regionMatcher: RegionMatcher
) {

    companion object {
        /** 地点箭头：出发 → 目的（兼容 OCR 常见的 →、一、-、到 等符号） */
        val ARROW = Regex("""([一-龥]{2,8})\s*(?:到|至|发往|->|→|—|~|一|~)\s*([一-龥]{2,8})""")

        /**
         * 重量：支持 小于X吨 / 大于X吨 / X-Y吨(区间) / X吨(单值)。
         * 区间规则必须在单值前，否则 "5-6吨" 会先被单值吃掉 "6吨"。
         */
        val WEIGHT = Regex(
            """小于(\d+(?:\.\d+)?)吨|大于(\d+(?:\.\d+)?)吨|(\d+(?:\.\d+)?)\s*[-~]\s*(\d+(?:\.\d+)?)吨|(\d+(?:\.\d+)?)吨"""
        )

        /** 车长：支持 "4.2米" 或连写 "4.2/9.6/13米"（拆分后逐个匹配） */
        val LENGTH = Regex("""([\d.]+(?:/[\d.]+)*)\s*米""")

        val PRICE = Regex("""(\d{2,6})\s*元""")
        val DISTANCE = Regex("""(\d+(?:\.\d+)?)\s*(?:km|公里)""")

        /** 车型（司机端常见） */
        val VEHICLE_TYPES = listOf("平板", "高栏", "厢式", "集装箱", "拖车", "自卸", "冷藏",
            "保温", "高低板", "面包车", "棉被", "爬梯", "飞翼", "依维柯", "半挂")

        /**
         * 订单卡必需字段（车长/重量/车型/价格/距离）。
         * 无这些字段的箭头块 → 页面导航/筛选文字（如 "当前定位→山东"、"装货→装一卸"、
         * 筛选 chip "济宁→山东"、重复卡片），不是订单，跳过避免误提醒。
         *
         * 注意：真实订单卡个别信息不全（无车长/车型/重量/价格），但通常带评分"结算4.6"
         * 或"装货"字样，因此也视为订单，避免漏掉真订单。
         */
        val ORDER_FIELD_HINTS = Regex("吨|米|元|电议|面议|公里|km|装货|结算|平板|高栏|厢式|集装箱|拖车|自卸|冷藏|保温|高低板|面包车|棉被|爬梯|飞翼|依维柯|半挂")

        /** 出发/目的地出现的非地名词 → 该箭头是页面导航/详情行，不是订单 */
        val NON_REGION_STOPWORDS = listOf("当前定位", "装货", "装一卸", "听单", "我的")
    }

    /**
     * 从 OCR 文本解析订单列表。
     *
     * ML Kit OCR 输出是整块文本，订单行可能跨行/字段分散。这里用「订单块」划分：
     * 按「X→Y」结构定位订单起点，每块内搜索所有字段（重量/车型/车长/运费）。
     *
     * @param ocrText 截屏 OCR 识别出的完整文本
     * @return 解析出的订单列表（可能为空）
     */
    suspend fun parse(ocrText: String): List<ParsedOrder> {
        if (ocrText.isBlank()) return emptyList()
        val orders = mutableListOf<ParsedOrder>()

        // 用正则找所有「X→Y」起点，切成订单块
        val matches = ARROW.findAll(ocrText).toList()
        for (i in matches.indices) {
            val m = matches[i]
            val from = m.groupValues[1]
            val to = m.groupValues[2]
            if (from.length < 2 || to.length < 2) continue
            // 出发/目的含非地名（当前定位/装货/装一卸/听单/我的）→ 页面导航/详情行，不是订单
            if (NON_REGION_STOPWORDS.any { from.contains(it) || to.contains(it) }) continue
            // 订单块 = 当前箭头起点到下一个箭头起点之间的文本
            val blockStart = m.range.last
            val blockEnd = if (i + 1 < matches.size) matches[i + 1].range.first else ocrText.length
            val block = ocrText.substring(blockStart, blockEnd.coerceAtMost(blockStart + 300))

            // 过滤假订单：块内必须含至少一个订单字段（车长/重量/车型/价格/距离），
            // 否则是筛选 chip "济宁→山东" / 重复卡片 等页面噪音
            if (!ORDER_FIELD_HINTS.containsMatchIn(block)) continue

            // 地区词典反查（P0）：重建正确的省/市/区层级，替代"前2字当省"启发式
            val fromRegion = regionMatcher.resolve(from)
            val toRegion = regionMatcher.resolve(to)
            // 反查失败时回退到原始 OCR 串（匹配侧宽松放行兜底）
            val fromProvince = fromRegion?.province ?: from
            val fromCity = fromRegion?.city
            val fromDistrict = fromRegion?.district
            val toProvince = toRegion?.province ?: to
            val toCity = toRegion?.city
            val toDistrict = toRegion?.district

            val price = PRICE.find(block)?.groupValues?.get(1)?.toIntOrNull()
            val (weightMin, weightMax) = parseWeight(block)
            val lengths = parseLengths(block)
            val types = VEHICLE_TYPES.filter { block.contains(it) }
            val distance = DISTANCE.find(block)?.groupValues?.get(1)

            val id = generateOrderId(from, to, price, weightMin)
            orders.add(
                ParsedOrder(
                    id = id,
                    fromProvince = fromProvince,
                    fromCity = fromCity,
                    fromDistrict = fromDistrict,
                    toProvince = toProvince,
                    toCity = toCity,
                    toDistrict = toDistrict,
                    cargoName = null,
                    weightMin = weightMin,
                    weightMax = weightMax,
                    vehicleLengths = lengths,
                    vehicleTypes = types,
                    priceAmount = price,
                    priceText = price?.let { "${it}元" } ?: if (block.contains("电议") || block.contains("面议")) "电议" else "",
                    isDianyi = block.contains("电议") || block.contains("面议"),
                    loadTimeText = block,
                    distance = distance,
                    tags = emptyList(),
                    rawTexts = listOf(block)
                )
            )
        }

        // 去重（同出发地+目的地+价格只留一个）
        return orders.distinctBy { it.id }
    }

    /**
     * 解析重量：
     * - "小于3吨" → (0, 3)
     * - "大于10吨" → (10, MAX)
     * - "5-6吨" → (5, 6)
     * - "10.3吨" → (10.3, 10.3)
     * 匹配不到返回 (null, null)。
     */
    private fun parseWeight(block: String): Pair<Float?, Float?> {
        val match = WEIGHT.find(block) ?: return Pair(null, null)
        return when {
            match.groupValues[1].isNotEmpty() -> Pair(0f, match.groupValues[1].toFloatOrNull())
            match.groupValues[2].isNotEmpty() -> Pair(match.groupValues[2].toFloatOrNull(), Float.MAX_VALUE)
            match.groupValues[3].isNotEmpty() -> Pair(
                match.groupValues[3].toFloatOrNull(),
                match.groupValues[4].toFloatOrNull()
            )
            else -> {
                val v = match.groupValues[5].toFloatOrNull()
                Pair(v, v)
            }
        }
    }

    /**
     * 解析车长：支持单值 "4.2米" 和连写 "4.2/9.6/13米"。
     * 连写是订单可选车型列表，单独值要能命中规则车长 "4.2"/"9.6"/"13" 任一项。
     */
    private fun parseLengths(block: String): List<String> {
        val m = LENGTH.find(block) ?: return emptyList()
        return m.groupValues[1].split('/').map { it.trim() }.filter { it.isNotEmpty() }
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
