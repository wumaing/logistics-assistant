package com.yymm.monitor.domain.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.yymm.monitor.data.local.entity.MatchRuleEntity
import com.yymm.monitor.data.model.ParsedOrder
import com.yymm.monitor.data.repository.SettingsRepository
import com.yymm.monitor.domain.model.MonitorState
import com.yymm.monitor.domain.model.MonitorStats
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 无障碍监控服务（TECH_SPEC 2.3.1）。
 *
 * 观察运满满订单列表页：轮询 + 事件驱动双通道，定时下拉刷新 + 解析 + 匹配，匹配成功提醒用户。
 * 严格不执行任何自动点击/抢单操作（唯一 dispatchGesture 是下拉刷新，向下滑动非点击）。
 *
 * 对照开源（Auto.js）落地的可靠性改进：
 * - [getRootInActiveWindow] 官方标注仅主线程可调，统一用主线程取根，避免 IO 线程访问风险
 * - 下拉手势：屏幕比例坐标 + 阻塞等待 GestureResultCallback 结果 + 失败跳过本轮解析
 * - 轮询循环内自校验页面（不依赖被 packageNames 过滤的事件流），切出运满满立即暂停
 * - TYPE_VIEW_SCROLLED 事件驱动解析（节流），与轮询互补
 */
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
    @Inject lateinit var ocrEngine: OcrEngine
    @Inject lateinit var ocrOrderParser: OcrOrderParser

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshHandler = Handler(Looper.getMainLooper())
    private var refreshJob: Runnable? = null

    /** 防重叠：上一轮周期未结束时跳过本轮工作（保持定时节奏） */
    @Volatile
    private var isCycleRunning = false

    /** 事件驱动解析节流时间戳 */
    private var lastEventParseTs = 0L

    /** 当前前台包名（从事件更新；司机端 root 拿不到时，事件是可靠来源） */
    @Volatile
    private var currentFrontPkg: String? = null

    private val _monitorState = MutableStateFlow<MonitorState>(MonitorState.IDLE)
    val monitorState: StateFlow<MonitorState> = _monitorState.asStateFlow()

    private val _stats = MutableStateFlow(MonitorStats())
    val stats: StateFlow<MonitorStats> = _stats.asStateFlow()

    /**
     * 获取当前前台窗口包名（不依赖 root/事件）。
     *
     * 司机端屏蔽 root 和事件时，用 AccessibilityWindowInfo 查活动窗口。
     * root 在司机端可能为 null，改用窗口 title（如 "com.xiwei.logistics/...CarrierMainActivity"）解析包名。
     */
    private fun getFrontPkgFromWindows(): String? {
        return try {
            val win = windows?.firstOrNull { it.isActive }
                ?: windows?.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
            if (win == null) return null
            // 优先 root 包名；root 为 null 时从窗口 title 解析
            win.root?.packageName?.toString()?.takeIf { it.isNotBlank() }
                ?: win.title?.toString()?.substringBefore("/")?.substringAfter(".")?.let { full ->
                    full.substringBefore("/")
                }
                ?: win.title?.toString()?.substringBefore("/")
        } catch (_: Exception) {
            null
        }
    }

    /** 写诊断日志到 files/monitor_log.txt（ColorOS 过滤 logcat，用文件绕开） */
    private fun diagLog(msg: String) {
        try {
            val f = java.io.File(filesDir, "monitor_log.txt")
            f.appendText("[${System.currentTimeMillis()}] $msg\n")
        } catch (_: Exception) {}
    }

    /** 递归收集节点树全部文本（诊断司机端可访问性） */
    private fun collectAllTexts(node: android.view.accessibility.AccessibilityNodeInfo): List<String> {
        val out = mutableListOf<String>()
        try {
            node.text?.toString()?.let { if (it.isNotBlank()) out.add(it) }
            node.contentDescription?.toString()?.let { if (it.isNotBlank()) out.add(it) }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    out.addAll(collectAllTexts(child))
                }
            }
        } catch (_: Exception) {}
        return out
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        diagLog("onServiceConnected 无障碍服务已连接")
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            // 手势能力由 accessibility_service_config.xml 的 canPerformGestures="true" 声明
            // （系统绑定服务时解析 XML 授予 CAPABILITY_CAN_PERFORM_GESTURES）
        }
        // 启动即就绪：无障碍服务连接后自动进入 READY（悬浮窗显示"开始监控"），
        // 无需额外等待。用户点击"开始"即进入 RUNNING 开始轮询匹配。
        if (_monitorState.value == MonitorState.IDLE) {
            _monitorState.value = MonitorState.READY
            FloatingWindowManager.updateState(MonitorState.READY)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // 更新当前前台包名（事件比 root 可靠：司机端 root 拿不到但事件能到）
        event.packageName?.toString()?.let { currentFrontPkg = it }
        val eventPkg = event.packageName?.toString() ?: ""
        val isDriver = eventPkg.contains("xiwei")
        // 诊断：事件是否带文本（司机端 root 拿不到时，事件文本是唯一来源）
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            val evTexts = (event.text?.filter { it.isNotBlank() } ?: emptyList()) +
                (event.contentDescription?.let { listOf(it) } ?: emptyList())
            if (evTexts.isNotEmpty()) {
                diagLog("事件文本(${event.packageName}): ${evTexts.take(5).joinToString(" | ")}")
            }
        }
        // 降噪：仅记录司机端或窗口切换事件，内容变化刷屏不记（避免淹没关键日志）
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || isDriver) {
            diagLog("onAccessibilityEvent type=${event.eventType} pkg=${event.packageName}")
        }
        // 仅处理司机端的事件（其他应用的滚动/内容变化不触发解析，避免误匹配无关内容）
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // 司机端窗口切换：更新前台包名
                if (isDriver) currentFrontPkg = eventPkg
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 事件驱动解析：仅司机端的滚动/内容变化触发（节流 800ms 防刷屏）
                if (_monitorState.value == MonitorState.RUNNING && isDriver) {
                    currentFrontPkg = eventPkg
                    val now = SystemClock.uptimeMillis()
                    if (now - lastEventParseTs > 800) {
                        lastEventParseTs = now
                        serviceScope.launch { parseAndMatch() }
                    }
                }
            }
        }
    }

    override fun onInterrupt() {
        pauseMonitoring("无障碍服务被中断")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 支持通过 startService 触发监控控制（悬浮窗/测试用）
        when (intent?.action) {
            ACTION_START_MONITOR -> startMonitoring()
            ACTION_PAUSE_MONITOR -> pauseMonitoring("用户暂停")
            ACTION_RESUME_MONITOR -> startMonitoring()
        }
        return START_STICKY
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // 不 cancel serviceScope：无障碍服务被系统暂时 unbind（如切走/回收）后，
        // 会再次 onServiceConnected 重新绑定。scope 一旦取消，后续 launch 抛异常，
        // 监控链路无法恢复（用户反馈"退出软件后无障碍被关闭"即源于此）。
        stopRefreshLoop()
        alertManager.destroy()
        instance = null
        _monitorState.value = MonitorState.IDLE
        FloatingWindowManager.updateState(MonitorState.IDLE)
        return super.onUnbind(intent)
    }

    /**
     * 持续运行：服务被系统杀死后自动重启。
     *
     * OPPO/ColorOS 会回收后台服务进程。若服务在监控运行中被杀，
     * onDestroy 里启动一个普通的 startService（非 bind），让服务进程
     * 重新拉起（AccessibilityService 由系统 bind，重启后 onServiceConnected
     * 会再次触发，instance 恢复，监控可继续）。
     */
    override fun onDestroy() {
        super.onDestroy()
        // 服务被杀但用户仍希望监控（RUNNING/PAUSED 非 IDLE）→ 重启
        val shouldRestart = _monitorState.value == MonitorState.RUNNING ||
            _monitorState.value == MonitorState.PAUSED
        instance = null
        if (shouldRestart) {
            try {
                val intent = Intent(this, YymmMonitorService::class.java)
                startService(intent)
            } catch (_: Exception) {
                // 后台启动受限（OPPO 限制），忽略
            }
        }
    }

    fun startMonitoring() {
        diagLog("startMonitoring 调用，当前状态=${_monitorState.value}")
        if (_monitorState.value == MonitorState.RUNNING) return
        // 不校验当前页面：真人主动打开运满满司机页后点"开始监控"，
        // 助手直接进入 RUNNING 开始轮询刷新+解析+匹配，由真人判断页面是否正确。
        _monitorState.value = MonitorState.RUNNING
        FloatingWindowManager.updateState(MonitorState.RUNNING)
        serviceScope.launch { syncActiveRulesToFloat() }
        startRefreshLoop()
        diagLog("startMonitoring 完成，已启动轮询")
    }

    fun pauseMonitoring(reason: String = "用户手动暂停") {
        if (_monitorState.value == MonitorState.PAUSED) return
        _monitorState.value = MonitorState.PAUSED
        stopRefreshLoop()
        FloatingWindowManager.updateState(MonitorState.PAUSED, reason)
    }

    fun resumeMonitoring() {
        startMonitoring()
    }

    /**
     * 导出当前窗口节点树（真机调试用）：写入 files/node_dumps/。
     * 返回导出文件路径；root 不可用/失败时返回 null。
     */
    fun dumpNodeTree(): String? {
        val root = captureRootOnMainThread() ?: return null
        return try {
            NodeDumper.dump(this, root)?.absolutePath
        } finally {
            root.recycle()
        }
    }

    /**
     * 把当前激活规则同步到悬浮窗面板（启动时调用一次；规则名列表用于"当前匹配规则"展示）。
     */
    private suspend fun syncActiveRulesToFloat() {
        val rules = ruleEngine.activeRules()
        FloatingWindowManager.updateActiveRules(rules.map { it.name.ifBlank { "未命名规则" } })
    }

    private fun startRefreshLoop() {
        if (refreshJob != null) return
        refreshJob = object : Runnable {
            override fun run() {
                if (_monitorState.value != MonitorState.RUNNING) return
                // 动态读取刷新间隔：运行中修改设置即时生效
                refreshHandler.postDelayed(this, settingsRepo.getRefreshInterval() * 1000L)
                if (!isCycleRunning) {
                    isCycleRunning = true
                    serviceScope.launch {
                        try {
                            doRefreshCycle()
                        } finally {
                            isCycleRunning = false
                        }
                    }
                }
            }
        }
        refreshHandler.post(refreshJob!!)
    }

    private fun stopRefreshLoop() {
        refreshJob?.let { refreshHandler.removeCallbacks(it) }
        refreshJob = null
    }

    /**
     * 单个刷新周期：
     * 1. 下拉刷新（阻塞等待手势结果，失败跳过本轮解析）
     * 2. 解析 + 匹配 + 提醒
     *
     * 不做页面校验：真人主动判断页面是否正确，助手只负责刷新与匹配。
     */
    private suspend fun doRefreshCycle() {
        diagLog("doRefreshCycle 开始")
        // 截屏方案不依赖司机端事件判断：截屏是全局的，OCR 解析不到订单就自然无匹配。
        // 司机端屏蔽事件/节点，无法用包名判断前台，直接截屏+OCR。
        // 1. 下拉刷新：手势不依赖 root（dispatchGesture 仅需手势能力）
        val refreshOk = performRefresh()
        diagLog("performRefresh 结果=$refreshOk")
        _stats.value = _stats.value.copy(refreshCount = _stats.value.refreshCount + 1)
        if (!refreshOk) return
        delay(1000)

        // 2. 截屏 OCR 解析（主方案：司机端屏蔽节点树）
        parseFromScreenCapture()
    }

    /**
     * 下拉刷新：屏幕比例坐标（避免小屏滑出/大屏拉不动），阻塞等待手势结果。
     *
     * @return true=手势成功完成；false=被取消/失败/超时（跳过本轮解析）
     */
    private suspend fun performRefresh(): Boolean {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        // 从屏高 20% 滑到 65%（约 45% 屏高的下拉幅度，接近真人下拉；过长的路径
        // 在部分机型会被系统判定无效导致 onCancelled）
        val startY = (screenHeight * 0.20f).toInt().coerceAtLeast(0)
        val endY = (screenHeight * 0.65f).toInt().coerceAtMost(screenHeight - 1)
        val path = Path().apply {
            moveTo(screenWidth / 2f, startY.toFloat())
            lineTo(screenWidth / 2f, endY.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 400))
            .build()
        val deferred = CompletableDeferred<Boolean>()
        refreshHandler.post {
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    deferred.complete(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    diagLog("performRefresh 手势被取消")
                    deferred.complete(false)
                }
            }, refreshHandler)
        }
        return withTimeoutOrNull(5000) { deferred.await() } ?: false
    }

    /**
     * 在主线程取当前窗口根节点（getRootInActiveWindow 仅主线程安全）。
     * 已在主线程则直接返回；否则投递主线程并阻塞等待。
     */
    private fun captureRootOnMainThread(): AccessibilityNodeInfo? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return rootInActiveWindow
        }
        val latch = CountDownLatch(1)
        var result: AccessibilityNodeInfo? = null
        refreshHandler.post {
            try {
                result = rootInActiveWindow
            } finally {
                latch.countDown()
            }
        }
        try {
            latch.await(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            return null
        }
        return result
    }

    private suspend fun parseAndMatch() {
        // 1. 优先无障碍节点树（root 可获取且是司机端时）
        var node: AccessibilityNodeInfo? = null
        repeat(3) { attempt ->
            node = captureRootOnMainThread()
            if (node != null) return@repeat
            diagLog("parseAndMatch root=null 第${attempt + 1}次重试")
            delay(500)
        }
        val rootNode = node
        if (rootNode != null) {
            val rootPkg = rootNode.packageName?.toString() ?: ""
            if (rootPkg.contains("xiwei")) {
                parseFromRoot(rootNode)
                return
            }
            diagLog("parseAndMatch 非司机端 root($rootPkg)，改走截屏 OCR")
            rootNode.recycle()
        }

        // 2. 截屏 OCR 兜底（司机端屏蔽节点树时的主方案）
        parseFromScreenCapture()
    }

    /** 从无障碍节点树解析（司机端暴露节点时） */
    private suspend fun parseFromRoot(rootNode: AccessibilityNodeInfo) {
        try {
            val texts = collectAllTexts(rootNode)
            diagLog("parseFromRoot root: pkg=${rootNode.packageName} 文本数=${texts.size}")
            val orders = orderParser.parse(rootNode)
            diagLog("parseFromRoot 解析到 ${orders.size} 个订单")
            matchAndAlert(orders)
        } finally {
            rootNode.recycle()
        }
    }

    /** 从截屏 OCR 解析（司机端屏蔽节点树时的主方案） */
    private suspend fun parseFromScreenCapture() {
        // 截屏服务未运行则尝试启动（需已授权）
        if (!ScreenCaptureService.isRunning) {
            diagLog("parseFromScreenCapture 截屏服务未运行")
            return
        }
        // 每次轮询触发截一帧（异步，稍等后取）
        ScreenCaptureService.captureInstance?.requestCapture()
        delay(500)
        val bmp = ScreenCaptureService.lastBitmap ?: run {
            diagLog("parseFromScreenCapture 无截屏数据")
            return
        }
        val ocrText = ocrEngine.recognize(bmp)
        if (ocrText.isBlank()) {
            diagLog("parseFromScreenCapture OCR 无文本")
            return
        }
        diagLog("parseFromScreenCapture OCR 文本(${ocrText.length}字符): ${ocrText.take(80)}")
        val orders = ocrOrderParser.parse(ocrText)
        diagLog("parseFromScreenCapture 解析到 ${orders.size} 个订单")
        matchAndAlert(orders)
    }

    /** 匹配 + 提醒（共享，suspend：内部调用 suspend 的 match/triggerAlert） */
    private suspend fun matchAndAlert(orders: List<ParsedOrder>) {
        orders.forEach { order ->
            if (ruleEngine.match(order) && !alertManager.isAlertedToday(order.id)) {
                diagLog("匹配成功并提醒: ${order.fromProvince}${order.fromCity} → ${order.toProvince}${order.toCity}")
                alertManager.triggerAlert(order)
                _stats.value = _stats.value.copy(
                    matchCount = _stats.value.matchCount + 1
                )
            }
        }
        val s = _stats.value
        FloatingWindowManager.updateStats(s.refreshCount, s.matchCount, s.confirmCount)
    }
}
