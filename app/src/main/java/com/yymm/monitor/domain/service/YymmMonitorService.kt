package com.yymm.monitor.domain.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.yymm.monitor.data.repository.SettingsRepository
import com.yymm.monitor.domain.model.MonitorState
import com.yymm.monitor.domain.model.MonitorStats
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 无障碍监控服务（TECH_SPEC 2.3.1）。
 *
 * 观察运满满订单列表页，定时下拉刷新 + 解析 + 匹配，匹配成功提醒用户。
 * 严格不执行任何自动点击/抢单操作。
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
            else -> {
                // 其他事件类型（TYPE_VIEW_SCROLLED 等）暂不处理
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
            orders.forEach { order ->
                if (ruleEngine.match(order) && !alertManager.isAlertedToday(order.id)) {
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
