package com.yymm.monitor.domain.service

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.yymm.monitor.domain.model.FloatingWindowState
import com.yymm.monitor.domain.model.MonitorState
import com.yymm.monitor.presentation.MainActivity
import com.yymm.monitor.presentation.ui.component.FloatingWindowContent
import com.yymm.monitor.presentation.ui.component.FloatingWindowHideHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 悬浮窗管理器（TECH_SPEC 2.3.5 / PRD 3.4）。
 *
 * object 单例（非 Hilt 注入），由 Service 直接调用。
 * - 迷你球（56dp）+ 可展开控制面板（200dp）
 * - 拖动、松手贴边动画（ValueAnimator 300ms）
 * - 状态联动：监控状态/统计/匹配规则汇总实时更新
 * - 快捷入口跳转 MainActivity（navigate_to extra）
 */
object FloatingWindowManager {

    private var windowManager: WindowManager? = null
    private var floatingView: ComposeView? = null
    private var windowView: View? = null  // 实际 add 到 WindowManager 的容器（FrameLayout）
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isExpanded = false

    private val _windowState = MutableStateFlow(FloatingWindowState())
    val windowState: StateFlow<FloatingWindowState> = _windowState.asStateFlow()

    // 拖动相关状态
    private var initialX = 0f
    private var initialY = 0f
    private var touchX = 0f
    private var touchY = 0f
    private var isDragging = false

    private val expandedWidthDp = 200
    private val dragThresholdPx = 40 // 10dp @ density 4.0 的近似值，实际用 dpToPx(10)

    fun show(context: Context) {
        if (windowView != null) return // 已显示则不再添加
        // 悬浮窗是系统窗口（WindowManager 层），不属于任何 Activity 视图树，
        // Compose 需要 LifecycleOwner/SavedStateRegistryOwner，否则 setContent 崩溃
        // （ViewTreeLifecycleOwner not found）。从 context 解出 Activity 显式绑定。
        val activity = context.findActivity()
        val lifecycleOwner: LifecycleOwner? = activity as? LifecycleOwner
        val savedStateOwner: SavedStateRegistryOwner? = activity as? SavedStateRegistryOwner
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
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
            // 默认位置：屏幕右侧，Y=400（留边距）
            x = context.resources.displayMetrics.widthPixels - dpToPx(context, 72)
            y = 400
        }
        floatingView = ComposeView(context).apply {
            setContent {
                val state by _windowState.collectAsState()
                FloatingWindowContent(
                    state = state,
                    onExpandToggle = { toggleExpand(context) },
                    onStartClick = { YymmMonitorService.instance?.startMonitoring() },
                    onPauseClick = { YymmMonitorService.instance?.pauseMonitoring() },
                    onNavigate = { route -> navigateTo(context, route) },
                    onClose = { toggleExpand(context) }
                )
            }
        }
        // 容器是 WindowManager 窗口的根，LifecycleOwner/SavedStateRegistryOwner 必须设在
        // 容器上（ComposeView 是子 view，往父链找 owner 时从窗口根开始，设在子节点找不到）。
        // 拖动用 DragInterceptFrameLayout 的 onInterceptTouchEvent 在 dispatch 阶段拦截：
        // Compose 子视图会消费触摸，普通 OnTouchListener（在 onTouchEvent 阶段）收不到事件，
        // 只有 onInterceptTouchEvent 能在 Compose 之前抢到拖动。
        val container = DragInterceptFrameLayout(context, this).apply {
            lifecycleOwner?.let { setViewTreeLifecycleOwner(it) }
            savedStateOwner?.let { setViewTreeSavedStateRegistryOwner(it) }
            addView(floatingView)
        }
        windowView = container
        try {
            windowManager?.addView(container, layoutParams)
        } catch (t: Throwable) {
            // 悬浮窗创建失败（如权限时序/系统限制）时清掉残留，避免下次 show 被 skip
            android.util.Log.e("FloatWin", "addView FAILED: ${t}")
            floatingView = null
            windowView = null
            layoutParams = null
        }
        // 长按迷你球"隐藏悬浮窗"菜单回调
        FloatingWindowHideHandler.hideCallback = { hide() }
        // "导出节点树"调试回调
        FloatingWindowHideHandler.dumpNodeTreeCallback = {
            val path = YymmMonitorService.instance?.dumpNodeTree()
            val message = if (path != null) "节点树已导出：\n$path"
            else "导出失败：无障碍服务未连接或当前无窗口"
            try {
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                // 后台无 Activity 上下文时 Toast 可能抛异常，忽略
            }
        }
    }

    fun hide() {
        windowView?.let {
            windowManager?.removeView(it)
            windowView = null
        }
        floatingView = null
        FloatingWindowHideHandler.hideCallback = null
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

    private fun toggleExpand(context: Context) {
        isExpanded = !isExpanded
        _windowState.value = _windowState.value.copy(isExpanded = isExpanded)
        // 展开时固定宽度 200dp，收起时恢复自适应
        layoutParams?.let { lp ->
            lp.width = if (isExpanded) dpToPx(context, expandedWidthDp)
            else WindowManager.LayoutParams.WRAP_CONTENT
            windowView?.let { view ->
                windowManager?.updateViewLayout(view, lp)
            }
        }
    }

    /**
     * 拖动拦截容器。
     *
     * 悬浮窗内是 ComposeView，Compose 的 AndroidComposeView 会消费大部分触摸事件
     * （点击/滑动都进 Compose 手势），普通 View.setOnTouchListener 设在容器上时，
     * 事件已被子 ComposeView 消费，父层 onTouchEvent 收不到 → 无法拖动。
     * 用 onInterceptTouchEvent 在 dispatch 阶段拦截：一旦检测到拖动意图
     * （移动距离超过阈值），拦截该事件序列，后续 MOVE 直接由本容器处理并更新窗口位置，
     * Compose 完全感知不到；未触发拖动阈值的 DOWN/MOVE 原样放行给 Compose（正常点击展开）。
     */
    private class DragInterceptFrameLayout(
        context: Context,
        private val owner: FloatingWindowManager
    ) : android.widget.FrameLayout(context) {

        private var intercepted = false

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            return when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    owner.initialX = owner.layoutParams?.x?.toFloat() ?: 0f
                    owner.initialY = owner.layoutParams?.y?.toFloat() ?: 0f
                    owner.touchX = ev.rawX
                    owner.touchY = ev.rawY
                    owner.isDragging = false
                    intercepted = false
                    false // 不拦截 DOWN，先让 Compose 有机会处理点击
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - owner.touchX
                    val dy = ev.rawY - owner.touchY
                    val threshold = (context.resources.displayMetrics.density * 10).toFloat()
                    if (!intercepted && (kotlin.math.abs(dx) > threshold || kotlin.math.abs(dy) > threshold)) {
                        // 超过拖动阈值 → 拦截，进入拖动模式
                        intercepted = true
                        owner.isDragging = true
                    }
                    intercepted
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    owner.isDragging = false
                    intercepted
                }
                else -> false
            }
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (intercepted) {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_MOVE -> {
                        val lp = owner.layoutParams ?: return true
                        lp.x = (owner.initialX + (ev.rawX - owner.touchX)).toInt()
                        lp.y = (owner.initialY + (ev.rawY - owner.touchY)).toInt()
                        owner.windowManager?.updateViewLayout(this, lp)
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        intercepted = false
                        owner.snapToEdge(context, this)
                        return true
                    }
                    else -> return false
                }
            }
            return super.onTouchEvent(ev)
        }
    }

    /**
     * 贴边动画：根据当前位置判断贴左还是贴右，300ms 动画滑到边缘。
     */
    private fun snapToEdge(context: Context, view: View) {
        val lp = layoutParams ?: return
        val screenWidth = context.resources.displayMetrics.widthPixels
        val viewWidth = view.width.coerceAtLeast(1)
        val targetX = if (lp.x + viewWidth / 2 < screenWidth / 2) {
            0 // 贴左
        } else {
            screenWidth - viewWidth // 贴右
        }
        val animator = ValueAnimator.ofInt(lp.x, targetX).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                lp.x = animation.animatedValue as Int
                windowManager?.updateViewLayout(view, lp)
            }
        }
        animator.start()
    }

    private fun navigateTo(context: Context, route: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra("navigate_to", route) // "rules", "alert", "history", "settings"
        }
        context.startActivity(intent)
    }

    private fun dpToPx(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()

    /** 从 Context 向上解出 Activity（ContextWrapper 可能多层包一层 ServiceContext） */
    private fun Context.findActivity(): Activity? {
        var ctx = this
        while (ctx is android.content.ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }
}
