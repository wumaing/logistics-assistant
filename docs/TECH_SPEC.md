# 浏览助手 — 技术方案 V3.0（编码Agent专用）

**版本**：V3.0  
**对应PRD**：V3.0  
**日期**：2026-08-02  
**状态**：可直接编码

---

## 1. 系统架构

### 1.1 架构分层

```
┌─────────────────────────────────────────────────────────────────────────┐
│                              Presentation 层                             │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐│
│  │ MainActivity │  │RuleEditAct   │  │AlertPopupAct │  │HistoryAct    ││
│  │ 主界面       │  │规则编辑页    │  │提醒弹窗      │  │历史记录      ││
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘│
│         │                 │                 │                 │        │
│  ┌──────┴─────────────────┴─────────────────┴─────────────────┴──────┐ │
│  │                    Compose UI + ViewModel                          │ │
│  │              (StateFlow 驱动 UI 状态，单向数据流)                  │ │
│  └──────────────────────────────┬────────────────────────────────────┘ │
└─────────────────────────────────┼──────────────────────────────────────┘
                                  │
┌─────────────────────────────────┼──────────────────────────────────────┐
│                              Domain 层                                   │
│  ┌──────────────────────────────┴────────────────────────────────────┐ │
│  │                    Service 层（核心业务逻辑）                       │ │
│  │  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌────────────┐ │ │
│  │  │ MonitorSvc │  │ RuleEngine │  │ AlertMgr   │  │ OrderParser│ │ │
│  │  │ 监控服务   │  │ 规则引擎   │  │ 提醒管理   │  │ 订单解析   │ │ │
│  │  └────────────┘  └────────────┘  └────────────┘  └────────────┘ │ │
│  │  ┌────────────┐  ┌────────────┐  ┌────────────┐             │ │
│  │  │ FloatWinMgr│  │ PageDetect │  │ RegionRepo │             │ │
│  │  │ 悬浮窗管理 │  │ 页面检测   │  │ 地区词典   │             │ │
│  │  └────────────┘  └────────────┘  └────────────┘             │ │
│  └───────────────────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────────────┘
                                  │
┌─────────────────────────────────┼──────────────────────────────────────┐
│                              Data 层                                     │
│  ┌──────────────────────────────┴────────────────────────────────────┐ │
│  │  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌────────────┐│ │
│  │  │ RuleDao    │  │ HistoryDao │  │ SettingDao │  │ RegionData ││ │
│  │  │ 规则DAO    │  │ 历史DAO    │  │ 设置DAO    │  │ 地区数据   ││ │
│  │  └────────────┘  └────────────┘  └────────────┘  └────────────┘│ │
│  │  ┌────────────────────────────────────────────────────────────┐  │ │
│  │  │                    Room Database                            │  │ │
│  │  └────────────────────────────────────────────────────────────┘  │ │
│  │  ┌────────────────────────────────────────────────────────────┐  │ │
│  │  │              SharedPreferences (轻量设置)                    │  │ │
│  │  └────────────────────────────────────────────────────────────┘  │ │
│  │  ┌────────────────────────────────────────────────────────────┐  │ │
│  │  │              assets/regions.json (内置地区词典)              │  │ │
│  │  └────────────────────────────────────────────────────────────┘  │ │
│  └───────────────────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────────────┘
```

### 1.2 技术栈

| 层级 | 技术 | 版本/说明 |
|---|---|---|
| 语言 | Kotlin | 1.9+ |
| UI | Jetpack Compose | Material3 |
| 架构 | MVVM + Repository | 单向数据流 |
| DI | Hilt | 2.48+ |
| 异步 | Kotlin Coroutines + Flow | 主线程/IO线程分离 |
| 数据库 | Room | 2.6+ |
| 后台服务 | AccessibilityService + ForegroundService | 系统级服务 |
| 悬浮窗 | WindowManager + ComposeView | API 26+ 用 TYPE_APPLICATION_OVERLAY |

---

## 2. 核心模块设计

### 2.1 数据模型（Entity / Data Class）

#### 2.1.1 规则状态枚举（V3.0 新增）

```kotlin
enum class RuleStatus {
    STANDBY,   // 备战区（待命）
    ACTIVE     // 匹配区（激活）
}
```

#### 2.1.2 订单数据模型（与V2.0一致）

```kotlin
data class ParsedOrder(
    val id: String,
    val fromProvince: String,
    val fromCity: String?,
    val toProvince: String,
    val toCity: String?,
    val cargoName: String?,
    val weightMin: Float?,
    val weightMax: Float?,
    val vehicleLengths: List<String>,
    val vehicleTypes: List<String>,
    val priceAmount: Int?,
    val priceText: String,
    val isDianyi: Boolean,
    val loadTimeText: String,
    val distance: String?,
    val tags: List<String>,
    val rawTexts: List<String>,
    val parseTimestamp: Long = System.currentTimeMillis()
)
```

#### 2.1.3 规则数据模型（V3.0 核心变更）

```kotlin
@Entity(tableName = "match_rules")
data class MatchRuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,
    val ruleStatus: String = "STANDBY",      // STANDBY=备战区, ACTIVE=匹配区
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),

    val fromLocationsJson: String,
    val fromCustomPointsJson: String,
    val toLocationsJson: String,
    val toCustomPointsJson: String,
    val cargoTypesJson: String,
    val vehicleLengthsJson: String,
    val vehicleTypesJson: String,
    val loadTimeOption: String,
    val loadTimeStart: Long? = null,
    val loadTimeEnd: Long? = null,
    val weightRangesJson: String,
    val weightMin: Float? = null,
    val weightMax: Float? = null,
    val minPrice: Int? = null,
    val includeDianyiWithPrice: Boolean = false,
    val includeDianyiWithoutPrice: Boolean = false,
    val requireHighCredit: Boolean = false,
    val requireManyunbao: Boolean = false,
    val requireDepositRefundable: Boolean = false,
    val requirePrivilege: Boolean = false
)

data class LocationRule(
    val province: String,
    val city: String? = null,
    val district: String? = null
) {
    fun matches(orderProvince: String, orderCity: String?): Boolean {
        if (province == "全国") return true
        if (province != orderProvince) return false
        if (city != null && city != orderCity) return false
        return true
    }
}

data class WeightRange(
    val min: Float,
    val max: Float
)
```

#### 2.1.4 历史记录数据模型（与V2.0一致）

```kotlin
@Entity(tableName = "history_records")
data class HistoryRecordEntity(
    @PrimaryKey
    val orderId: String,
    val orderJson: String,
    val alertTime: Long,
    val action: String,
    val date: String,
    val fromDisplay: String,
    val toDisplay: String,
    val cargoDisplay: String,
    val weightDisplay: String,
    val priceDisplay: String
)
```

#### 2.1.5 用户设置数据模型（V3.0 扩展）

```kotlin
data class UserSettings(
    val refreshIntervalSeconds: Int = 5,
    val myVehicleLength: String? = null,
    val popupEnabled: Boolean = true,
    val vibrateEnabled: Boolean = true,
    val vibrateDurationMs: Long = 3000,
    val ringtoneEnabled: Boolean = true,
    val ringtoneUri: String? = null,
    val ringtoneVolume: Float = 1.0f,
    val ttsEnabled: Boolean = true,
    val ttsSpeed: Float = 1.0f,
    val popupDurationSeconds: Int = 0,              // 0=一直显示直到点击
    val multiOrderHandling: String = "QUEUE",       // QUEUE=排队依次, LATEST=只弹最新
    val floatingWindowSizeDp: Int = 56,
    val floatingWindowAlpha: Float = 0.9f,
    val floatingWindowAutoStick: Boolean = true,
    val floatingWindowDefaultX: Int = -1,
    val floatingWindowDefaultY: Int = 400,
    val historyRetentionDays: Int = 7,              // 7/30/0(永久)
    val autoStartOnBoot: Boolean = false
)
```

#### 2.1.6 地区词典数据模型（与V2.0一致）

```kotlin
data class RegionData(val provinces: List<Province>)
data class Province(val code: String, val name: String, val cities: List<City>)
data class City(val code: String, val name: String, val districts: List<District>)
data class District(val code: String, val name: String)
```

---

### 2.2 Repository 层接口定义（V3.0 核心变更）

```kotlin
interface RuleRepository {
    suspend fun getAllRules(): List<MatchRuleEntity>
    suspend fun getStandbyRules(): List<MatchRuleEntity>
    suspend fun getActiveRules(): List<MatchRuleEntity>
    suspend fun saveRule(rule: MatchRuleEntity): Long
    suspend fun deleteRule(ruleId: Long)
    suspend fun activateRule(ruleId: Long)
    suspend fun deactivateRule(ruleId: Long)
    suspend fun activateAll()
    suspend fun deactivateAll()
    suspend fun duplicateRule(ruleId: Long): Long
}

interface HistoryRepository {
    suspend fun getTodayRecords(): List<HistoryRecordEntity>
    suspend fun getTodayAlertedOrderIds(): Set<String>
    suspend fun recordAlert(order: ParsedOrder)
    suspend fun recordAction(orderId: String, action: AlertAction)
    suspend fun cleanupOldRecords(retentionDays: Int)
}

interface SettingsRepository {
    fun getSettings(): UserSettings
    fun saveSettings(settings: UserSettings)
    fun getRefreshInterval(): Int
    fun getAlertSettings(): AlertSettings
    fun getPopupDuration(): Int
    fun getMultiOrderHandling(): String
    fun getHistoryRetentionDays(): Int
}

interface RegionRepository {
    suspend fun loadRegions(): RegionData
    fun searchRegions(keyword: String): List<LocationRule>
    fun getCitiesByProvince(provinceName: String): List<City>
    fun getDistrictsByCity(provinceName: String, cityName: String): List<District>
}
```

---

### 2.3 核心服务设计

#### 2.3.1 AccessibilityService（监控核心）

```kotlin
@AndroidEntryPoint
class YymmMonitorService : AccessibilityService() {

    companion object {
        var instance: YymmMonitorService? = null
            private set
        const val ACTION_START_MONITOR = "action_start_monitor"
        const val ACTION_PAUSE_MONITOR = "action_pause_monitor"
        const val ACTION_RESUME_MONITOR = "action_resume_monitor"
    }

    @Inject lateinit var orderParser: OrderParser
    @Inject lateinit var ruleEngine: RuleEngine
    @Inject lateinit var alertManager: AlertManager
    @Inject lateinit var settingsRepo: SettingsRepository
    @Inject lateinit var pageDetector: PageDetector

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshHandler = Handler(Looper.getMainLooper())
    private var refreshJob: Runnable? = null

    private val _monitorState = MutableStateFlow<MonitorState>(MonitorState.IDLE)
    val monitorState: StateFlow<MonitorState> = _monitorState.asStateFlow()

    private val _stats = MutableStateFlow(MonitorStats())
    val stats: StateFlow<MonitorStats> = _stats.asStateFlow()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_SCROLLED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                serviceScope.launch {
                    val isOrderPage = pageDetector.isOrderListPage(event, rootInActiveWindow)
                    if (!isOrderPage && _monitorState.value == MonitorState.RUNNING) {
                        pauseMonitoring("运满满页面已切换")
                    }
                }
            }
        }
    }

    override fun onInterrupt() {
        pauseMonitoring("无障碍服务被中断")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        serviceScope.cancel()
        return super.onUnbind(intent)
    }

    fun startMonitoring() {
        if (_monitorState.value == MonitorState.RUNNING) return
        _monitorState.value = MonitorState.CHECKING
        serviceScope.launch {
            val rootNode = rootInActiveWindow
            val isOrderPage = pageDetector.isOrderListPage(null, rootNode)
            if (!isOrderPage) {
                _monitorState.value = MonitorState.PAUSED
                alertManager.showPageError("请打开运满满并保持订单列表页")
                return@launch
            }
            _monitorState.value = MonitorState.RUNNING
            startRefreshLoop()
        }
    }

    fun pauseMonitoring(reason: String = "用户手动暂停") {
        _monitorState.value = MonitorState.PAUSED
        stopRefreshLoop()
        FloatingWindowManager.updateState(MonitorState.PAUSED, reason)
    }

    fun resumeMonitoring() {
        startMonitoring()
    }

    private fun startRefreshLoop() {
        val interval = settingsRepo.getRefreshInterval() * 1000L
        refreshJob = object : Runnable {
            override fun run() {
                if (_monitorState.value != MonitorState.RUNNING) return
                serviceScope.launch {
                    performRefresh()
                    delay(1000)
                    parseAndMatch()
                    _stats.value = _stats.value.copy(
                        refreshCount = _stats.value.refreshCount + 1
                    )
                }
                refreshHandler.postDelayed(this, interval)
            }
        }
        refreshHandler.post(refreshJob!!)
    }

    private fun stopRefreshLoop() {
        refreshJob?.let { refreshHandler.removeCallbacks(it) }
        refreshJob = null
    }

    private fun performRefresh() {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val path = Path().apply {
            moveTo(screenWidth / 2f, 200f)
            lineTo(screenWidth / 2f, 800f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {}
            override fun onCancelled(gestureDescription: GestureDescription?) {}
        }, null)
    }

    private suspend fun parseAndMatch() {
        val rootNode = rootInActiveWindow ?: return
        try {
            val orders = orderParser.parse(rootNode)
            var matchCount = 0
            orders.forEach { order ->
                if (ruleEngine.match(order) && !alertManager.isAlertedToday(order.id)) {
                    matchCount++
                    alertManager.triggerAlert(order)
                    _stats.value = _stats.value.copy(
                        matchCount = _stats.value.matchCount + 1
                    )
                }
            }
        } finally {
            rootNode.recycle()
        }
    }
}

enum class MonitorState { IDLE, CHECKING, RUNNING, PAUSED, ERROR }
data class MonitorStats(val refreshCount: Int = 0, val matchCount: Int = 0, val confirmCount: Int = 0)
```

#### 2.3.2 订单解析器（OrderParser）

```kotlin
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
            val arrowPattern = Regex("""([\u4e00-\u9fa5]+)\s*([\u4e00-\u9fa5]+)?\s*[\-\>~]\s*([\u4e00-\u9fa5]+)\s*([\u4e00-\u9fa5]+)?""")
            val match = arrowPattern.find(text)
            if (match != null) {
                val fromProvince = match.groupValues[1]
                val fromCity = match.groupValues[2].ifEmpty { null }
                val toProvince = match.groupValues[3]
                val toCity = match.groupValues[4].ifEmpty { null }
                return Pair(Pair(fromProvince, fromCity), Pair(toProvince, toCity))
            }
        }
        val locationTexts = texts.filter { it.length in 2..10 && it.matches(Regex("""[\u4e00-\u9fa5]+""")) }
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
            text.matches(Regex("""[\u4e00-\u9fa5]+"""))
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
        return emptyList()
    }
}
```

#### 2.3.3 规则引擎（RuleEngine — V3.0 核心重构）

```kotlin
@Singleton
class RuleEngine @Inject constructor(
    private val ruleRepository: RuleRepository
) {

    suspend fun match(order: ParsedOrder): Boolean {
        val activeRules = ruleRepository.getActiveRules()
        if (activeRules.isEmpty()) return false
        return activeRules.any { rule -> matchOrderWithRule(order, rule) }
    }

    fun matchOrderWithRule(order: ParsedOrder, rule: MatchRuleEntity): Boolean {
        if (rule.ruleStatus != "ACTIVE") return false

        val fromLocations = parseLocations(rule.fromLocationsJson)
        val toLocations = parseLocations(rule.toLocationsJson)
        val cargoTypes = parseStringList(rule.cargoTypesJson)
        val lengths = parseStringList(rule.vehicleLengthsJson)
        val types = parseStringList(rule.vehicleTypesJson)
        val weightRanges = parseWeightRanges(rule.weightRangesJson)

        // ========== 第一优先级：出发地与目的地 ==========
        val fromMatch = fromLocations.isEmpty() ||
            fromLocations.any { it.matches(order.fromProvince, order.fromCity) } ||
            parseStringList(rule.fromCustomPointsJson).any { point ->
                order.rawTexts.any { it.contains(point) }
            }
        if (!fromMatch) return false

        val toMatch = toLocations.isEmpty() ||
            toLocations.any { it.matches(order.toProvince, order.toCity) } ||
            parseStringList(rule.toCustomPointsJson).any { point ->
                order.rawTexts.any { it.contains(point) }
            }
        if (!toMatch) return false

        // ========== 次要优先级：其他筛选条件 ==========
        val cargoMatch = cargoTypes.isEmpty() || cargoTypes.any { type ->
            when (type) {
                "FULL_VEHICLE" -> order.tags.contains("整车货")
                "PREMIUM" -> order.tags.contains("优车货")
                "INSTANT" -> order.tags.contains("即时单")
                "CARPOOL" -> order.tags.contains("拼车货")
                else -> false
            }
        }
        if (!cargoMatch) return false

        // 宽松模式：订单"不限车长"时自动通过
        val lengthMatch = lengths.isEmpty() ||
            order.vehicleLengths.isEmpty() ||
            lengths.any { len -> order.vehicleLengths.any { it == len || it.startsWith(len) } }
        if (!lengthMatch) return false

        // 宽松模式：订单"不限车型"时自动通过
        val typeMatch = types.isEmpty() ||
            order.vehicleTypes.isEmpty() ||
            types.any { type -> order.vehicleTypes.contains(type) }
        if (!typeMatch) return false

        // 装货时间匹配（兼容"昨天"）
        val timeMatch = when (rule.loadTimeOption) {
            "ANY" -> true
            "TODAY" -> order.loadTimeText.contains("今天")
            "TOMORROW" -> order.loadTimeText.contains("明天")
            "AFTER_TOMORROW" -> order.loadTimeText.contains("后天") || order.loadTimeText.contains("明天以后")
            "CUSTOM" -> {
                val orderTime = parseOrderTime(order.loadTimeText)
                orderTime != null && rule.loadTimeStart != null && rule.loadTimeEnd != null &&
                orderTime in rule.loadTimeStart..rule.loadTimeEnd
            }
            else -> true
        }
        if (!timeMatch) return false

        // 重量匹配 — 宽松模式：字段缺失时自动通过
        val weightMatch = if (weightRanges.isEmpty() && rule.weightMin == null && rule.weightMax == null) {
            true
        } else if (order.weightMin == null && order.weightMax == null) {
            true
        } else {
            val orderMin = order.weightMin ?: 0f
            val orderMax = order.weightMax ?: Float.MAX_VALUE
            val inPreset = weightRanges.any { range -> orderMin >= range.min && orderMax <= range.max }
            val inCustom = (rule.weightMin == null || orderMin >= rule.weightMin) &&
                           (rule.weightMax == null || orderMax <= rule.weightMax)
            inPreset || inCustom
        }
        if (!weightMatch) return false

        // 价格匹配（宽松模式）
        val priceMatch = matchPrice(order, rule)
        if (!priceMatch) return false

        // 更多筛选（AND）
        val extraMatch = (!rule.requireHighCredit || order.tags.contains("高信用货主")) &&
                         (!rule.requireManyunbao || order.tags.contains("满运宝")) &&
                         (!rule.requireDepositRefundable || order.tags.contains("订金可退")) &&
                         (!rule.requirePrivilege || order.tags.contains("送抢单特权"))
        if (!extraMatch) return false

        return true
    }

    private fun matchPrice(order: ParsedOrder, rule: MatchRuleEntity): Boolean {
        if (order.isDianyi) {
            if (order.priceAmount != null) {
                if (!rule.includeDianyiWithPrice) return false
                if (rule.minPrice != null && rule.minPrice > 0 && order.priceAmount < rule.minPrice) {
                    return false
                }
                return true
            } else {
                return rule.includeDianyiWithoutPrice
            }
        }
        if (order.priceAmount != null) {
            if (rule.minPrice != null && rule.minPrice > 0 && order.priceAmount < rule.minPrice) {
                return false
            }
            return true
        }
        // 字段缺失，宽松模式自动通过
        return true
    }

    private fun parseLocations(json: String): List<LocationRule> {
        return try { Gson().fromJson(json, Array<LocationRule>::class.java).toList() }
        catch (e: Exception) { emptyList() }
    }

    private fun parseStringList(json: String): List<String> {
        return try { Gson().fromJson(json, Array<String>::class.java).toList() }
        catch (e: Exception) { emptyList() }
    }

    private fun parseWeightRanges(json: String): List<WeightRange> {
        return try { Gson().fromJson(json, Array<WeightRange>::class.java).toList() }
        catch (e: Exception) { emptyList() }
    }

    private fun parseOrderTime(loadTimeText: String): Long? {
        val calendar = Calendar.getInstance()
        return when {
            loadTimeText.contains("今天") -> calendar.timeInMillis
            loadTimeText.contains("明天") -> calendar.apply { add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
            loadTimeText.contains("后天") -> calendar.apply { add(Calendar.DAY_OF_YEAR, 2) }.timeInMillis
            loadTimeText.contains("昨天") -> calendar.apply { add(Calendar.DAY_OF_YEAR, -1) }.timeInMillis
            else -> null
        }
    }
}
```

#### 2.3.4 提醒管理器（AlertManager — V3.0 扩展）

```kotlin
@Singleton
class AlertManager @Inject constructor(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val historyRepository: HistoryRepository
) {

    private val todayAlertedOrders = Collections.synchronizedSet<String>(LinkedHashSet())
    private var tts: TextToSpeech? = null
    private var mediaPlayer: MediaPlayer? = null
    private val alertQueue = LinkedBlockingQueue<ParsedOrder>()
    private var isProcessingQueue = false

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.CHINESE
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            todayAlertedOrders.addAll(historyRepository.getTodayAlertedOrderIds())
        }
    }

    fun isAlertedToday(orderId: String): Boolean {
        return todayAlertedOrders.contains(orderId)
    }

    suspend fun triggerAlert(order: ParsedOrder) {
        todayAlertedOrders.add(order.id)
        historyRepository.recordAlert(order)
        val settings = settingsRepository.getSettings()

        when (settings.multiOrderHandling) {
            "QUEUE" -> {
                alertQueue.offer(order)
                processQueue(settings)
            }
            "LATEST" -> {
                showAlertPopup(order)
                triggerEffects(order, settings)
            }
        }
    }

    private fun processQueue(settings: UserSettings) {
        if (isProcessingQueue) return
        isProcessingQueue = true
        CoroutineScope(Dispatchers.Main).launch {
            while (alertQueue.isNotEmpty()) {
                val order = alertQueue.poll() ?: break
                showAlertPopup(order)
                triggerEffects(order, settings)
                delay(settings.popupDurationSeconds * 1000L + 500)
            }
            isProcessingQueue = false
        }
    }

    private fun showAlertPopup(order: ParsedOrder) {
        val intent = Intent(context, AlertPopupActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            putExtra("order", Gson().toJson(order))
        }
        context.startActivity(intent)
    }

    private fun triggerEffects(order: ParsedOrder, settings: UserSettings) {
        if (settings.vibrateEnabled) vibrate(settings.vibrateDurationMs)
        if (settings.ringtoneEnabled) playRingtone(settings)
        if (settings.ttsEnabled) speakOrder(order, settings.ttsSpeed)
    }

    private fun vibrate(durationMs: Long) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
    }

    private fun playRingtone(settings: UserSettings) {
        try {
            mediaPlayer?.release()
            val uri = settings.ringtoneUri?.let { Uri.parse(it) }
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, uri)
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                setVolume(settings.ringtoneVolume, settings.ringtoneVolume)
                prepare()
                start()
                setOnCompletionListener { it.release() }
            }
        } catch (e: Exception) {
            Log.e("AlertManager", "Play ringtone failed", e)
        }
    }

    private fun speakOrder(order: ParsedOrder, speed: Float) {
        tts?.let { engine ->
            engine.setSpeechRate(speed)
            val pricePart = when {
                order.isDianyi && order.priceAmount == null -> "运费电议"
                order.isDianyi -> "运费电议${order.priceAmount}元"
                order.priceAmount != null -> "运费${order.priceAmount}元"
                else -> ""
            }
            val weightPart = order.weightMin?.let { min ->
                order.weightMax?.let { max ->
                    if (min == max) "重量${min}吨" else "重量${min}到${max}吨"
                } ?: "重量${min}吨"
            } ?: ""
            val text = buildString {
                append("发现新订单，")
                append("从${order.fromProvince}${order.fromCity ?: ""}")
                append("到${order.toProvince}${order.toCity ?: ""}，")
                if (pricePart.isNotEmpty()) append("$pricePart，")
                if (weightPart.isNotEmpty()) append("$weightPart，")
                append("请查看。")
            }
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    fun showPageError(message: String) {
        FloatingWindowManager.showError(message)
    }

    fun destroy() {
        tts?.stop()
        tts?.shutdown()
        mediaPlayer?.release()
    }
}
```

#### 2.3.5 悬浮窗管理器（FloatingWindowManager — V3.0 显示匹配区汇总）

```kotlin
object FloatingWindowManager {

    private var windowManager: WindowManager? = null
    private var floatingView: ComposeView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isExpanded = false

    private val _windowState = MutableStateFlow(FloatingWindowState())
    val windowState: StateFlow<FloatingWindowState> = _windowState.asStateFlow()

    fun show(context: Context) {
        if (floatingView != null) return
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        layoutParams = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
            x = context.resources.displayMetrics.widthPixels - 200
            y = 400
        }
        floatingView = ComposeView(context).apply {
            setContent {
                FloatingWindowContent(
                    state = _windowState.collectAsState().value,
                    onExpandToggle = { toggleExpand() },
                    onStartClick = { YymmMonitorService.instance?.startMonitoring() },
                    onPauseClick = { YymmMonitorService.instance?.pauseMonitoring() },
                    onResumeClick = { YymmMonitorService.instance?.resumeMonitoring() },
                    onNavigate = { route -> navigateTo(context, route) }
                )
            }
        }
        windowManager?.addView(floatingView, layoutParams)
        setupDragListener()
    }

    fun updateState(state: MonitorState, message: String = "") {
        _windowState.value = _windowState.value.copy(
            monitorState = state,
            statusMessage = message
        )
    }

    fun updateStats(refresh: Int, match: Int, confirm: Int) {
        _windowState.value = _windowState.value.copy(
            refreshCount = refresh,
            matchCount = match,
            confirmCount = confirm
        )
    }

    fun updateActiveRules(ruleNames: List<String>) {
        _windowState.value = _windowState.value.copy(
            activeRuleCount = ruleNames.size,
            activeRuleNames = ruleNames
        )
    }

    fun showError(message: String) {
        _windowState.value = _windowState.value.copy(
            monitorState = MonitorState.PAUSED,
            statusMessage = message
        )
    }

    fun hide() {
        floatingView?.let {
            windowManager?.removeView(it)
            floatingView = null
        }
    }

    private fun toggleExpand() {
        isExpanded = !isExpanded
        _windowState.value = _windowState.value.copy(isExpanded = isExpanded)
    }

    private fun setupDragListener() {
        // 拖动逻辑：记录初始位置，计算偏移，更新layoutParams，松手贴边动画
    }

    private fun navigateTo(context: Context, route: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra("navigate_to", route)
        }
        context.startActivity(intent)
    }
}

data class FloatingWindowState(
    val monitorState: MonitorState = MonitorState.IDLE,
    val isExpanded: Boolean = false,
    val refreshCount: Int = 0,
    val matchCount: Int = 0,
    val confirmCount: Int = 0,
    val statusMessage: String = "",
    val activeRuleCount: Int = 0,
    val activeRuleNames: List<String> = emptyList()
)
```

---

### 2.4 数据库设计（Room — V3.0 变更）

```kotlin
@Database(entities = [MatchRuleEntity::class, HistoryRecordEntity::class], version = 2)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ruleDao(): RuleDao
    abstract fun historyDao(): HistoryDao
}

@Dao
interface RuleDao {
    @Query("SELECT * FROM match_rules ORDER BY updatedAt DESC")
    suspend fun getAll(): List<MatchRuleEntity>

    @Query("SELECT * FROM match_rules WHERE ruleStatus = 'STANDBY' ORDER BY updatedAt DESC")
    suspend fun getStandby(): List<MatchRuleEntity>

    @Query("SELECT * FROM match_rules WHERE ruleStatus = 'ACTIVE' ORDER BY updatedAt DESC")
    suspend fun getActive(): List<MatchRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: MatchRuleEntity): Long

    @Query("DELETE FROM match_rules WHERE id = :ruleId")
    suspend fun delete(ruleId: Long)

    @Query("UPDATE match_rules SET ruleStatus = 'ACTIVE', updatedAt = :timestamp WHERE id = :ruleId")
    suspend fun activate(ruleId: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE match_rules SET ruleStatus = 'STANDBY', updatedAt = :timestamp WHERE id = :ruleId")
    suspend fun deactivate(ruleId: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE match_rules SET ruleStatus = 'ACTIVE', updatedAt = :timestamp WHERE ruleStatus = 'STANDBY'")
    suspend fun activateAll(timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE match_rules SET ruleStatus = 'STANDBY', updatedAt = :timestamp WHERE ruleStatus = 'ACTIVE'")
    suspend fun deactivateAll(timestamp: Long = System.currentTimeMillis())
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history_records WHERE date = :date ORDER BY alertTime DESC")
    suspend fun getByDate(date: String): List<HistoryRecordEntity>

    @Query("SELECT orderId FROM history_records WHERE date = :date")
    suspend fun getOrderIdsByDate(date: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: HistoryRecordEntity)

    @Query("DELETE FROM history_records WHERE date < :date")
    suspend fun deleteBefore(date: String)
}
```

---

## 3. 关键算法详解

### 3.1 匹配区OR关系算法

```
输入：订单O，匹配区规则列表R[1..n]
输出：是否匹配（Boolean）

1. 如果 R 为空：返回 false
2. 遍历 R 中的每条规则 r：
   a. 调用 matchSingleRule(O, r)
   b. 如果返回 true：立即返回 true（OR短路）
3. 遍历结束仍未命中：返回 false

时间复杂度：O(n * m)
- n = 匹配区规则数量（通常 < 20）
- m = 单条规则匹配耗时（< 5ms）
- 总耗时 < 100ms，完全可接受
```

### 3.2 宽松模式匹配算法

```
输入：订单O，规则R
输出：是否匹配（Boolean）

对于每个筛选字段：
1. 如果用户未设置该字段规则 → 自动通过
2. 如果订单该字段缺失/不完整（如"1-1..."被截断）→ 自动通过
3. 如果订单该字段为"不限"（车长/车型）→ 自动通过
4. 如果订单该字段有明确值 → 正常与用户规则比对
5. 如果不匹配 → 返回 false

全部字段通过 → 返回 true
```

### 3.3 价格匹配算法

```
输入：订单O，规则R
输出：是否匹配（Boolean）

1. 如果 O.isDianyi == true：
   a. 如果 O.priceAmount != null（电议有价格）：
      - 检查 R.includeDianyiWithPrice
        - false → 返回 false
        - true → 继续检查最低运费
      - 如果 R.minPrice > 0 且 O.priceAmount < R.minPrice：
        → 返回 false
      - 否则 → 返回 true

   b. 如果 O.priceAmount == null（电议无价格）：
      - 直接返回 R.includeDianyiWithoutPrice
      - 不受最低运费限制

2. 如果 O.isDianyi == false（非电议订单）：
   - 如果 O.priceAmount == null（字段缺失，宽松模式）：
     → 返回 true
   - 如果 R.minPrice > 0 且 O.priceAmount < R.minPrice：
     → 返回 false
   - 否则 → 返回 true
```

### 3.4 节点解析策略

```
优先级策略（从高到低）：

策略1：RecyclerView/ListView 子项解析
- 查找 className 包含 "RecyclerView" 或 "ListView" 的节点
- 遍历其子节点，每个子节点视为一个订单 item
- 对每个 item 提取文本并解析

策略2：文本密度分组（兜底）
- 当策略1失败时使用
- 遍历所有文本节点，按Y坐标分组
- 每组视为一个订单 item

策略3：关键词触发（最后兜底）
- 查找包含 "去抢单"、"电议"、"元/趟" 等关键词的节点
- 向上回溯到父节点作为订单 item 根节点
```

### 3.5 地区匹配优化

```
问题：出发地/目的地无上限，匹配时需遍历所有已选地区

优化方案：
1. 使用 HashMap 按省份索引
   Map<province, List<LocationRule>>
   先匹配省份，命中后再遍历该省下的规则

2. 若规则只设 province（无 city）：
   直接命中，无需进一步比较

3. 支持"全国"选项：
   province == "全国" 时直接返回 true

4. 时间复杂度：
   最坏 O(n*m)，n=已选地区数，m=订单地区数
   实际中 n 通常 < 50，m = 1（单订单），完全可接受
```

---

## 4. 异常处理矩阵

| 异常场景 | 检测方式 | 处理策略 | 用户感知 |
|---|---|---|---|
| 无障碍服务被关闭 | 定期检查 isEnabled | 弹出全屏遮罩，引导重新开启 | 全屏提示 |
| 悬浮窗权限被回收 | 尝试添加悬浮窗时 catch SecurityException | 引导到设置页重新开启 | Toast提示 |
| 运满满APP未安装 | 包名检测 | 提示用户安装运满满 | 弹窗提示 |
| 运满满不在前台 | pageDetector.isForeground() | 暂停监控，悬浮窗显示异常 | 悬浮窗黄色状态 |
| 运满满页面非订单列表 | 特征文本检测 | 暂停监控，提示切换页面 | 悬浮窗提示 |
| 运满满崩溃/闪退 | AccessibilityEvent 包名消失 | 自动暂停，等待恢复 | 悬浮窗红色状态 |
| 网络断开 | 不影响 | 继续监控，刷新失败记录日志 | 无感知 |
| 节点解析失败 | try-catch + 空值检查 | 跳过该次解析，记录日志 | 无感知 |
| 内存不足 | onTrimMemory() | 清理缓存，降低刷新频率 | 无感知 |
| 运满满版本更新界面变化 | 解析结果为空或异常 | 记录日志，尝试兜底策略 | 无感知 |
| TTS引擎初始化失败 | TextToSpeech 回调 | 禁用语音提醒，记录日志 | Toast提示 |
| 铃声文件不存在 | MediaPlayer 异常 | 使用默认铃声 | 无感知 |

---

## 5. 性能优化策略

| 优化点 | 方案 |
|---|---|
| 刷新频率 | 用户配置，最低3秒，默认5秒 |
| 节点解析 | 使用协程在IO线程执行，不阻塞主线程 |
| 节点回收 | finally { rootNode.recycle() } 确保释放 |
| 匹配缓存 | 规则反序列化结果缓存，避免每次重新解析JSON |
| OR关系短路 | 匹配区规则遍历，命中即返回，不继续后续规则 |
| 优先级优化 | 先检查出发地/目的地，不匹配时快速跳过 |
| 去重查询 | 内存 Set<String> 为主，数据库为辅 |
| 悬浮窗 | 使用 Compose 的 remember 减少重组 |
| 数据库 | 使用 @Transaction 批量操作 |
| 图片资源 | 不使用任何图片，纯Compose绘制，减少APK体积 |
| 日志级别 | Release 包关闭详细日志，仅保留错误日志 |

---

## 6. 文件清单（编码Agent输出结构）

```
app/
├── src/main/
│   ├── java/com/yymm/monitor/
│   │   ├── YymmMonitorApplication.kt
│   │   ├── di/
│   │   │   └── AppModule.kt
│   │   ├── data/
│   │   │   ├── local/
│   │   │   │   ├── AppDatabase.kt
│   │   │   │   ├── dao/
│   │   │   │   │   ├── RuleDao.kt
│   │   │   │   │   └── HistoryDao.kt
│   │   │   │   └── entity/
│   │   │   │       ├── MatchRuleEntity.kt
│   │   │   │       └── HistoryRecordEntity.kt
│   │   │   ├── repository/
│   │   │   │   ├── RuleRepository.kt
│   │   │   │   ├── HistoryRepository.kt
│   │   │   │   ├── SettingsRepository.kt
│   │   │   │   └── RegionRepository.kt
│   │   │   └── model/
│   │   │       ├── ParsedOrder.kt
│   │   │       ├── LocationRule.kt
│   │   │       ├── WeightRange.kt
│   │   │       ├── RegionData.kt
│   │   │       └── UserSettings.kt
│   │   ├── domain/
│   │   │   ├── service/
│   │   │   │   ├── YymmMonitorService.kt
│   │   │   │   ├── OrderParser.kt
│   │   │   │   ├── RuleEngine.kt
│   │   │   │   ├── AlertManager.kt
│   │   │   │   ├── PageDetector.kt
│   │   │   │   └── FloatingWindowManager.kt
│   │   │   └── usecase/
│   │   │       └── ...
│   │   ├── presentation/
│   │   │   ├── MainActivity.kt
│   │   │   ├── AlertPopupActivity.kt
│   │   │   ├── ui/
│   │   │   │   ├── theme/
│   │   │   │   │   ├── Color.kt
│   │   │   │   │   ├── Theme.kt
│   │   │   │   │   └── Type.kt
│   │   │   │   ├── screen/
│   │   │   │   │   ├── MainScreen.kt
│   │   │   │   │   ├── RuleEditScreen.kt
│   │   │   │   │   ├── AlertSettingsScreen.kt
│   │   │   │   │   ├── HistoryScreen.kt
│   │   │   │   │   └── GuideScreen.kt
│   │   │   │   ├── component/
│   │   │   │   │   ├── RegionPicker.kt
│   │   │   │   │   ├── TagSelector.kt
│   │   │   │   │   ├── FloatingWindow.kt
│   │   │   │   │   ├── AlertPopup.kt
│   │   │   │   │   └── ...
│   │   │   │   └── viewmodel/
│   │   │   │       ├── MainViewModel.kt
│   │   │   │       ├── RuleViewModel.kt
│   │   │   │       └── SettingsViewModel.kt
│   │   │   └── navigation/
│   │   │       └── AppNavigation.kt
│   │   └── util/
│   │       ├── JsonUtil.kt
│   │       ├── PermissionUtil.kt
│   │       └── DateUtil.kt
│   ├── res/
│   │   ├── xml/
│   │   │   └── accessibility_service_config.xml
│   │   ├── raw/
│   │   │   └── default_alert.mp3
│   │   └── values/
│   │       └── strings.xml
│   └── assets/
│       └── regions.json
├── build.gradle.kts
└── AndroidManifest.xml
```

---

## 7. AndroidManifest.xml 关键配置

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.BIND_ACCESSIBILITY_SERVICE" />
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <uses-permission android:name="android.permission.VIBRATE" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

    <application
        android:name=".YymmMonitorApplication"
        android:label="浏览助手">

        <activity android:name=".presentation.MainActivity"
            android:exported="true"
            android:launchMode="singleTask">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity android:name=".presentation.AlertPopupActivity"
            android:theme="@android:style/Theme.Translucent.NoTitleBar"
            android:excludeFromRecents="true"
            android:launchMode="singleTop" />

        <service android:name=".domain.service.YymmMonitorService"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
            android:exported="true"
            android:foregroundServiceType="specialUse">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>

        <receiver android:name=".domain.service.BootReceiver"
            android:enabled="true"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
            </intent-filter>
        </receiver>

    </application>
</manifest>
```

---

## 8. 无障碍服务配置

```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:description="@string/accessibility_service_description"
    android:packageNames="com.yymm.driver,com.yunmanman.driver"
    android:accessibilityEventTypes="typeWindowStateChanged|typeViewScrolled"
    android:accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows"
    android:canRetrieveWindowContent="true"
    android:notificationTimeout="100"
    android:settingsActivity="com.yymm.monitor.presentation.MainActivity" />
```

---

## 9. V3.0 核心变更速查表

| 变更项 | V2.0 | V3.0 |
|---|---|---|
| 项目名称 | 运满满司机端订单监控助手 | **浏览助手** |
| 规则状态 | `isEnabled: Boolean`（单条）| `ruleStatus: String`（STANDBY/ACTIVE）|
| 匹配区规则数 | 1套 | **无上限** |
| 规则关系 | 切换（互斥）| **OR关系** |
| 匹配策略 | 严格匹配 | **宽松模式**（字段缺失自动通过）|
| 匹配优先级 | 无 | **出发地/目的地优先** |
| 一键操作 | 无 | **一键全部激活/移除** |
| 悬浮窗显示 | 单条规则状态 | **匹配区规则汇总** |
| 弹窗时长 | 固定 | **用户设置**（0=一直显示）|
| 多订单处理 | 排队依次 | **用户设置**（QUEUE/LATEST）|
| 历史保留 | 7天固定 | **用户设置**（7/30/永久）|
| 开机自启 | 无 | **用户设置** |
| 语音播报 | 完整信息 | **基础模板 only** |
| 装货时间 | 今天/明天/后天 | **兼容"昨天"** |
| 车长车型 | 正常匹配 | **"不限"自动通过** |
| 地区选择 | 省市区三级 | **支持"全国"** |

---

**文档结束**
