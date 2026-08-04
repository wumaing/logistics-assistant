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
import android.widget.Toast
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
        const val ACTION_SETTINGS_CHANGED = "action_settings_changed"
        const val ACTION_TEST_REFRESH = "action_test_refresh"
        /** 刷新事件广播 action（校准页监听，重播轨迹动画） */
        const val ACTION_REFRESH_TICK = "com.yymm.monitor.REFRESH_TICK"
    }

    @Inject lateinit var orderParser: OrderParser
    @Inject lateinit var ruleEngine: RuleEngine
    @Inject lateinit var alertManager: AlertManager
    @Inject lateinit var settingsRepo: SettingsRepository
    @Inject lateinit var ocrEngine: OcrEngine
    @Inject lateinit var ocrOrderParser: OcrOrderParser

    /** 持久化监控运行状态（服务被杀后重连恢复 RUNNING） */
    private val prefsMonitor by lazy {
        getSharedPreferences("monitor_state", MODE_PRIVATE)
    }

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
                ?: win.title?.toString()?.let { title ->
                    // title 形如 "com.xiwei.logistics/com.xiwei.logistics.carrier.ui.CarrierMainActivity"
                    val slashIdx = title.indexOf('/')
                    if (slashIdx > 0) title.substring(0, slashIdx) else title
                }
        } catch (_: Exception) {
            null
        }
    }

    /** 写诊断日志到 files/monitor_log.txt（ColorOS 过滤 logcat，用文件绕开） */
    private fun diagLog(msg: String) {
        try {
            val f = java.io.File(filesDir, "monitor_log.txt")
            // 自动清理：超过 300KB 只保留最近半段（防日志无限增长占满内存）
            if (f.length() > 300 * 1024) {
                val lines = f.readLines()
                val keep = lines.takeLast((lines.size / 2).coerceAtLeast(1))
                f.writeText(keep.joinToString("\n") + "\n")
            }
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
        // 启动原则（真人手动优先，后台被杀自动恢复）：
        // - 首次连接：只进入 READY（悬浮窗显示"开始监控"），必须真人手动点开始才 RUNNING
        // - 运行中服务被杀重连：检测到 monitor_running 标志（真人启动时写入）→ 自动恢复 RUNNING
        // - 真人暂停后重连：标志已清除 → 回到 READY 等真人重新启动
        val wasRunning = prefsMonitor.getBoolean("monitor_running", false)
        if (_monitorState.value == MonitorState.IDLE && wasRunning) {
            diagLog("onServiceConnected 服务被杀后重连，自动恢复 RUNNING")
            _monitorState.value = MonitorState.RUNNING
            FloatingWindowManager.updateState(MonitorState.RUNNING)
            startRefreshLoop()
        } else if (_monitorState.value == MonitorState.IDLE) {
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
            ACTION_SETTINGS_CHANGED -> {
                // 设置已变更（校准页保存后通知）：立即重读一次设置，
                // 确保刷新手势用最新坐标。日志确认重读值。
                val s = settingsRepo.getSettings()
                diagLog("设置已变更，重新读取: 起点(${s.refreshStartX},${s.refreshStartY}) 终点(${s.refreshEndX},${s.refreshEndY}) 时长=${s.refreshDurationMs}ms")
                if (_monitorState.value == MonitorState.RUNNING) {
                    // 运行中：无需重启轮询，下一轮 doRefreshCycle 会读新设置
                }
            }
            ACTION_TEST_REFRESH -> {
                // 校准页"试刷一次"：用传入坐标立刻执行一次真实下拉手势，肉眼验证刷新手势位置。
                // 传入 0 表示"不覆盖"该字段，用已保存设置值；其余用试刷参数。
                val x = intent.getIntExtra("start_x", -1)
                val y = intent.getIntExtra("start_y", -1)
                val ex = intent.getIntExtra("end_x", -1)
                val ey = intent.getIntExtra("end_y", -1)
                val dur = intent.getIntExtra("duration_ms", 0)
                diagLog("收到试刷指令: 起点($x,$y) 终点($ex,$ey) 时长=${dur}ms")
                // 延迟 3 秒：给用户时间切到运满满页面再执行手势
                refreshHandler.postDelayed({
                    // canPerformGestures 是 API 33+ 属性，用 capabilities 检查兼容所有版本
                    val gestureCapable = (serviceInfo?.capabilities
                        ?.and(AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES)) != 0
                    if (!gestureCapable) {
                        diagLog("试刷失败：当前无法执行手势（可能不在可交互窗口）")
                        toastOnMain("试刷失败：无法执行手势，请确认无障碍已开启且当前在运满满页面")
                        return@postDelayed
                    }
                    serviceScope.launch {
                        val ok = performTestRefresh(x, y, ex, ey, dur)
                        diagLog("试刷完成 结果=$ok")
                        toastOnMain(if (ok) "✅ 试刷完成：已从当前起点下拉到终点" else "❌ 试刷被取消/失败")
                    }
                }, 3000L)
            }
        }
        return START_STICKY
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // 不 cancel serviceScope：无障碍服务被系统暂时 unbind（如切走/回收）后，
        // 会再次 onServiceConnected 重新绑定。scope 一旦取消，后续 launch 抛异常，
        // 监控链路无法恢复（用户反馈"退出软件后无障碍被关闭"即源于此）。
        // monitor_running 持久化标志不清理：若用户在监控中，重连后自动恢复 RUNNING。
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
        // 服务被系统杀死后自动重启（拉回进程），配合 onServiceConnected 的
        // monitor_running 标志自动恢复 RUNNING——保证"真人启动后被杀不丢监控"。
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
        // 持久化运行标志：真人手动启动后，服务被杀重连时自动恢复 RUNNING（不丢监控）
        prefsMonitor.edit().putBoolean("monitor_running", true).apply()
        FloatingWindowManager.updateState(MonitorState.RUNNING)
        serviceScope.launch { syncActiveRulesToFloat() }
        startRefreshLoop()
        diagLog("startMonitoring 完成，已启动轮询")
    }

    fun pauseMonitoring(reason: String = "用户手动暂停") {
        if (_monitorState.value == MonitorState.PAUSED) return
        _monitorState.value = MonitorState.PAUSED
        // 用户手动暂停：清除运行标志（重连后不自动恢复，等真人重新启动）
        prefsMonitor.edit().remove("monitor_running").apply()
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

    /**
     * 发送刷新事件广播（校准页监听后重播轨迹动画，直观显示"正在这里刷新"）。
     */
    private fun sendRefreshTickBroadcast() {
        try {
            sendBroadcast(Intent(ACTION_REFRESH_TICK))
        } catch (_: Exception) {
        }
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
        // 司机端屏蔽事件/root/窗口信息，无法可靠判断前台。
        // 策略：全局下拉刷新 + 截屏 OCR，OCR 识别出订单结构（X→Y）才解析匹配，
        // 非订单页（桌面等）OCR 识别不出订单结构自然跳过，不会误匹配。
        // 1. 下拉刷新：手势不依赖 root（dispatchGesture 仅需手势能力）
        val refreshOk = performRefresh()
        diagLog("performRefresh 结果=$refreshOk")
        _stats.value = _stats.value.copy(refreshCount = _stats.value.refreshCount + 1)
        // 刷新计数实时反馈到悬浮窗（用户能看到"刷新: N次"在增长）
        val s = _stats.value
        FloatingWindowManager.updateStats(s.refreshCount, s.matchCount, s.confirmCount)
        // 广播刷新事件：校准页监听后重播轨迹动画，直观显示"正在这里刷新"
        sendRefreshTickBroadcast()
        if (!refreshOk) return
        delay(1000)

        // 2. 截屏 OCR 解析（主方案：司机端屏蔽节点树）
        parseFromScreenCapture()
    }

    /**
     * 下拉刷新：模拟真人下拉手势，触发运满满下拉刷新。
     *
     * 运满满 flutter 页面：快速滑动被当作滚动列表，慢速"按住→下拉→松手"才识别为下拉刷新。
     * 真人下拉分三段：
     *  1. 按住起点（短暂停留 120ms，进入下拉模式）
     *  2. 缓慢下拉（800-1200ms，触发下拉刷新动画）
     *  3. 松手（触发刷新）
     *
     * 借鉴开源 AutoAction：手势加随机扰动（坐标/时长），更接近真人，
     * 避免被司机端识别为自动化操作而拦截手势。
     *
     * @return true=手势成功完成；false=被取消/失败/超时（跳过本轮解析）
     */
    private suspend fun performRefresh(): Boolean {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        // 用户校准的起点/终点坐标（优先）；未校准用默认 屏高25%→87%
        val settings = settingsRepo.getSettings()
        val startX = if (settings.refreshStartX in 0..screenWidth) settings.refreshStartX else screenWidth / 2
        val startY = if (settings.refreshStartY in 0..screenHeight) settings.refreshStartY
        else (screenHeight * 0.33f).toInt().coerceAtLeast(0)
        val endX = if (settings.refreshEndX in 0..screenWidth) settings.refreshEndX else startX
        val endY = if (settings.refreshEndY in 0..screenHeight) settings.refreshEndY
        else (screenHeight * 0.87f).toInt().coerceAtMost(screenHeight - 1)
        val durationMs = settings.refreshDurationMs
        // 诊断：打印实际刷新手势坐标，验证校准是否生效
        diagLog("performRefresh 轨迹: 起点($startX,$startY) 终点($endX,$endY) 时长=$durationMs")
        return dispatchPullGesture(startX, startY, endX, endY, durationMs)
    }

    /**
     * 校准页"试刷一次"：用传入坐标执行真实下拉手势（传 -1/0 的字段回退到已保存设置）。
     *
     * 与 performRefresh 共用 [dispatchPullGesture]，保证试刷和真实监控刷新手感一致。
     */
    private suspend fun performTestRefresh(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int): Boolean {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val settings = settingsRepo.getSettings()
        val sX = if (startX in 0..screenWidth) startX else settings.refreshStartX.takeIf { it in 0..screenWidth } ?: (screenWidth / 2)
        val sY = if (startY in 0..screenHeight) startY else settings.refreshStartY.takeIf { it in 0..screenHeight } ?: (screenHeight * 0.33f).toInt().coerceAtLeast(0)
        val eX = if (endX in 0..screenWidth) endX else settings.refreshEndX.takeIf { it in 0..screenWidth } ?: sX
        val eY = if (endY in 0..screenHeight) endY else settings.refreshEndY.takeIf { it in 0..screenHeight } ?: (screenHeight * 0.87f).toInt().coerceAtMost(screenHeight - 1)
        val dur = durationMs.coerceIn(200, 2000)
        diagLog("performTestRefresh 轨迹: 起点($sX,$sY) 终点($eX,$eY) 时长=$dur")
        return dispatchPullGesture(sX, sY, eX, eY, dur)
    }

    /**
     * 派发下拉手势（监控刷新与校准页试刷共用）。
     *
     * 优先用系统级 `input swipe`（等同真人手指，运满满 flutter 能识别下拉刷新——
     * 实测 dispatchGesture 无障碍注入的手势运满满不识别，input swipe 系统注入可以）。
     * input swipe 失败时回退到 dispatchGesture。
     *
     * @return true=手势成功完成；false=被取消/失败/超时（跳过本轮解析）
     */
    private suspend fun dispatchPullGesture(
        startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int
    ): Boolean {
        // 随机扰动（±10px）：接近真人，防检测
        val randomX = startX + (kotlin.random.Random.nextInt(-10, 10))
        val randomStartY = startY + (kotlin.random.Random.nextInt(-10, 10))
        val randomEndX = endX + (kotlin.random.Random.nextInt(-10, 10))
        val randomEndY = endY + (kotlin.random.Random.nextInt(-10, 10))
        val pullMs: Int = durationMs.coerceIn(200, 2000)

        // 方案0：UiAutomation 注入真实触摸事件（不带无障碍标志，运满满无法区分真人手指）
        // 运满满检测 dispatchGesture 的 FLAG_FROM_ACCESSIBILITY 标志拒绝刷新；
        // UiAutomation.injectInputEvent 注入系统级 InputEvent（等同真人触摸）。
        if (injectRealTouchSwipe(randomX, randomStartY, randomEndX, randomEndY, pullMs)) {
            diagLog("UiAutomation 真实触摸注入成功: ($randomX,$randomStartY)->($randomEndX,$randomEndY)")
            return true
        }
        diagLog("UiAutomation 注入失败，回退其他方案")

        // 方案1：系统级 input swipe（等同真人手指，运满满能识别下拉刷新）
        try {
            val cmd = "input swipe $randomX $randomStartY $randomEndX $randomEndY $pullMs"
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                diagLog("input swipe 成功: $cmd")
                return true
            }
            diagLog("input swipe 失败 exit=$exitCode: $cmd")
        } catch (_: Exception) {
        }

        // 方案2：回退到无障碍 dispatchGesture（双指下拉）
        // 运满满屏蔽单指无障碍手势（FLAG_IS_ACCESSIBILITY_EVENT 检测）；
        // 双指平行下拉（Auto.js 社区防检测技巧）可能绕过屏蔽，尝试之。
        val gestureDuration = 1200L
        val path1 = Path().apply {
            moveTo((randomX - 60).toFloat(), randomStartY.toFloat())
            lineTo((randomEndX - 60).toFloat(), randomEndY.toFloat())
        }
        val path2 = Path().apply {
            moveTo((randomX + 60).toFloat(), randomStartY.toFloat())
            lineTo((randomEndX + 60).toFloat(), randomEndY.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path1, 0, gestureDuration, false))
            .addStroke(GestureDescription.StrokeDescription(path2, 0, gestureDuration, false))
            .build()
        val deferred = CompletableDeferred<Boolean>()
        refreshHandler.post {
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    deferred.complete(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    diagLog("下拉手势被取消")
                    deferred.complete(false)
                }
            }, refreshHandler)
        }
        return withTimeoutOrNull(5000) { deferred.await() } ?: false
    }

    /**
     * 用 UiAutomation 注入真实触摸事件（等效真人手指，不带无障碍标志）。
     *
     * 运满满检测 dispatchGesture 的 FLAG_FROM_ACCESSIBILITY 标志拒绝下拉刷新；
     * UiAutomation.injectInputEvent 注入的 InputEvent 不带该标志，运满满无法区分真人。
     *
     * 通过反射获取 AccessibilityService 内部的 UiAutomation（API 24+ 稳定存在）。
     * 构造 MotionEvent 序列：DOWN → 多段 MOVE（模拟滑动）→ UP，时间递增。
     */
    private fun injectRealTouchSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean {
        return try {
            // 反射获取 UiAutomation（不同系统版本字段名不同，尝试多个）
            var automationField: java.lang.reflect.Field? = null
            val fieldNames = listOf("mUiAutomation", "automation", "mAccessibilityServiceConnection")
            for (name in fieldNames) {
                try {
                    val f = android.accessibilityservice.AccessibilityService::class.java.getDeclaredField(name)
                    f.isAccessible = true
                    automationField = f
                    diagLog("找到字段: $name, 类型=${f.type.simpleName}")
                    break
                } catch (e: NoSuchFieldException) {
                    diagLog("无字段: $name")
                } catch (e: Exception) {
                    diagLog("字段访问异常: $name → ${e.message}")
                }
            }
            if (automationField == null) {
                // 列出所有字段名帮助定位
                val names = android.accessibilityservice.AccessibilityService::class.java
                    .declaredFields.map { it.name }.joinToString(",")
                diagLog("AccessibilityService 字段: $names")
                return false
            }
            val automation = automationField?.get(this) as? android.app.UiAutomation
            if (automation == null) {
                diagLog("UiAutomation 反射获取为 null (字段=${automationField?.name})")
                return false
            }
            val injectMethod = android.app.UiAutomation::class.java.getMethod(
                "injectInputEvent", android.view.InputEvent::class.java, Boolean::class.javaPrimitiveType
            )

            val now = SystemClock.uptimeMillis()
            val steps = 10
            val stepTime = (durationMs / steps).coerceAtLeast(10)
            // DOWN 事件
            val down = android.view.MotionEvent.obtain(
                now, now,
                android.view.MotionEvent.ACTION_DOWN, x1.toFloat(), y1.toFloat(), 0
            )
            if (!(injectMethod.invoke(automation, down, false) as Boolean)) return false
            down.recycle()

            // 分段 MOVE（模拟缓慢滑动）
            for (i in 1..steps) {
                val t = now + i * stepTime
                val cx = x1 + (x2 - x1) * i / steps
                val cy = y1 + (y2 - y1) * i / steps
                val move = android.view.MotionEvent.obtain(
                    now, t,
                    android.view.MotionEvent.ACTION_MOVE, cx.toFloat(), cy.toFloat(), 0
                )
                injectMethod.invoke(automation, move, false)
                move.recycle()
                Thread.sleep(stepTime.toLong())
            }

            // UP 事件
            val up = android.view.MotionEvent.obtain(
                now, now + (steps + 1) * stepTime,
                android.view.MotionEvent.ACTION_UP, x2.toFloat(), y2.toFloat(), 0
            )
            val ok = injectMethod.invoke(automation, up, false) as Boolean
            up.recycle()
            ok
        } catch (e: Exception) {
            diagLog("UiAutomation 注入异常: ${e.message}")
            false
        }
    }

    /** 在主线程 Toast（后台线程调用安全） */
    private fun toastOnMain(msg: String) {
        refreshHandler.post {
            try {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
        }
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
        // 完整 OCR 文本落盘（诊断用，看识别质量）
        try {
            java.io.File(filesDir, "ocr_debug.txt").writeText(ocrText)
        } catch (_: Exception) {}
        diagLog("parseFromScreenCapture OCR 文本(${ocrText.length}字符): ${ocrText.take(80)}")
        val orders = ocrOrderParser.parse(ocrText)
        diagLog("parseFromScreenCapture 解析到 ${orders.size} 个订单，各字段: " +
            orders.take(3).joinToString(" || ") { o ->
                "出发=${o.fromProvince}/${o.fromCity}/${o.fromDistrict} 目的=${o.toProvince}/${o.toCity}/${o.toDistrict} " +
                "重=${o.weightMin}吨 车型=${o.vehicleTypes} 车长=${o.vehicleLengths} 价=${o.priceText}"
            }
        )
        matchAndAlert(orders)
    }

    /** 匹配 + 提醒（共享，suspend：内部调用 suspend 的 match/triggerAlert） */
    private suspend fun matchAndAlert(orders: List<ParsedOrder>) {
        orders.forEach { order ->
            val matchResult = ruleEngine.match(order)
            if (matchResult != null && !alertManager.isAlertedToday(order.id)) {
                val missing = matchResult.missingFields
                diagLog(
                    "匹配成功并提醒: ${order.fromProvince}/${order.fromCity} → ${order.toProvince}/${order.toCity}" +
                        if (missing.isNotEmpty()) " ⚠字段不全:${missing.joinToString(",")}" else ""
                )
                alertManager.triggerAlert(order, missing)
                _stats.value = _stats.value.copy(
                    matchCount = _stats.value.matchCount + 1
                )
            }
        }
        val s = _stats.value
        FloatingWindowManager.updateStats(s.refreshCount, s.matchCount, s.confirmCount)
    }
}
