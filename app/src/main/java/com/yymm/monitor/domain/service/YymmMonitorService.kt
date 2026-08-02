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
    @Inject lateinit var pageDetector: PageDetector

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshHandler = Handler(Looper.getMainLooper())
    private var refreshJob: Runnable? = null

    /** 防重叠：上一轮周期未结束时跳过本轮工作（保持定时节奏） */
    @Volatile
    private var isCycleRunning = false

    /** 事件驱动解析节流时间戳 */
    private var lastEventParseTs = 0L

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
        // 启动即就绪：无障碍服务连接后自动进入 READY（悬浮窗显示"开始监控"），
        // 无需额外等待。用户点击"开始"即进入 RUNNING 开始轮询匹配。
        if (_monitorState.value == MonitorState.IDLE) {
            _monitorState.value = MonitorState.READY
            FloatingWindowManager.updateState(MonitorState.READY)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                serviceScope.launch {
                    val rootNode = captureRootOnMainThread()
                    val isOrderPage = pageDetector.isOrderListPage(event, rootNode)
                    rootNode?.recycle()
                    if (!isOrderPage && _monitorState.value == MonitorState.RUNNING) {
                        pauseMonitoring("运满满页面已切换")
                    }
                }
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // 事件驱动解析：用户在运满满内滚动列表时立即抓取，与定时轮询互补（节流 800ms 防刷屏）
                if (_monitorState.value == MonitorState.RUNNING) {
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

    fun startMonitoring() {
        if (_monitorState.value == MonitorState.RUNNING) return
        _monitorState.value = MonitorState.CHECKING
        serviceScope.launch {
            val rootNode = captureRootOnMainThread()
            val isOrderPage = pageDetector.isOrderListPage(null, rootNode)
            rootNode?.recycle()
            if (!isOrderPage) {
                _monitorState.value = MonitorState.PAUSED
                alertManager.showPageError("请打开运满满并保持订单列表页")
                return@launch
            }
            _monitorState.value = MonitorState.RUNNING
            FloatingWindowManager.updateState(MonitorState.RUNNING)
            syncActiveRulesToFloat()
            startRefreshLoop()
        }
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
     * 1. 取当前窗口 root，校验仍在运满满订单页（不依赖事件流，切出 App 立即暂停）
     * 2. 下拉刷新（阻塞等待手势结果，失败跳过本轮解析）
     * 3. 解析 + 匹配 + 提醒
     */
    private suspend fun doRefreshCycle() {
        // 1. 页面校验
        val preRoot = captureRootOnMainThread()
        val isOrderPage = pageDetector.isOrderListPage(null, preRoot)
        preRoot?.recycle()
        if (!isOrderPage) {
            if (_monitorState.value == MonitorState.RUNNING) {
                pauseMonitoring("运满满页面已切换")
            }
            return
        }

        // 2. 下拉刷新
        val refreshOk = performRefresh()
        _stats.value = _stats.value.copy(refreshCount = _stats.value.refreshCount + 1)
        if (!refreshOk) return
        delay(1000)

        // 3. 解析匹配
        parseAndMatch()
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
        // 从屏高 25% 滑到 85%（约 60% 屏高的下拉幅度），居中横坐标
        val startY = (screenHeight * 0.25f).toInt().coerceAtLeast(0)
        val endY = (screenHeight * 0.85f).toInt().coerceAtMost(screenHeight - 1)
        val path = Path().apply {
            moveTo(screenWidth / 2f, startY.toFloat())
            lineTo(screenWidth / 2f, endY.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
            .build()
        val deferred = CompletableDeferred<Boolean>()
        refreshHandler.post {
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    deferred.complete(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    deferred.complete(false)
                }
            }, refreshHandler)
        }
        return withTimeoutOrNull(3000) { deferred.await() } ?: false
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
        val rootNode = captureRootOnMainThread() ?: return
        try {
            val orders = orderParser.parse(rootNode)
            orders.forEach { order ->
                if (ruleEngine.match(order) && !alertManager.isAlertedToday(order.id)) {
                    alertManager.triggerAlert(order)
                    _stats.value = _stats.value.copy(
                        matchCount = _stats.value.matchCount + 1
                    )
                }
            }
            // 悬浮窗统计联动
            val s = _stats.value
            FloatingWindowManager.updateStats(s.refreshCount, s.matchCount, s.confirmCount)
        } finally {
            rootNode.recycle()
        }
    }
}
