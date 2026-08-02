package com.yymm.monitor.domain.service

import android.animation.ValueAnimator
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
        if (floatingView != null) return // 已显示则不再添加
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
        windowManager?.addView(floatingView, layoutParams)
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
        setupDragListener(floatingView!!, context)
    }

    fun hide() {
        floatingView?.let {
            windowManager?.removeView(it)
            floatingView = null
        }
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
            floatingView?.let { view ->
                windowManager?.updateViewLayout(view, lp)
            }
        }
    }

    private fun setupDragListener(view: View, context: Context) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams?.x?.toFloat() ?: 0f
                    initialY = layoutParams?.y?.toFloat() ?: 0f
                    touchX = event.rawX
                    touchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (kotlin.math.abs(dx) > dpToPx(context, 10).toFloat() ||
                        kotlin.math.abs(dy) > dpToPx(context, 10).toFloat()
                    ) {
                        isDragging = true
                    }
                    if (isDragging) {
                        layoutParams?.let { lp ->
                            lp.x = (initialX + dx).toInt()
                            lp.y = (initialY + dy).toInt()
                            windowManager?.updateViewLayout(v, lp)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    val moved = kotlin.math.abs(dx) > dpToPx(context, 10).toFloat() ||
                        kotlin.math.abs(dy) > dpToPx(context, 10).toFloat()
                    if (!moved) {
                        // 点击（移动距离 < 10dp）→ 展开/收起
                        v.performClick()
                        toggleExpand(context)
                    } else {
                        // 拖动结束 → 贴边动画
                        snapToEdge(context, v)
                    }
                    true
                }
                else -> false
            }
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
}
