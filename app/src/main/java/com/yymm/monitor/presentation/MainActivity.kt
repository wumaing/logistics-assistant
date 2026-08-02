package com.yymm.monitor.presentation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.yymm.monitor.domain.service.FloatingWindowManager
import com.yymm.monitor.presentation.navigation.AppNavigation
import com.yymm.monitor.presentation.ui.screen.GuideScreen
import com.yymm.monitor.presentation.ui.theme.YymmMonitorTheme
import com.yymm.monitor.util.PermissionUtil
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/** 权限遮罩显示状态（跨 Activity 生命周期传递，Compose 观察） */
object PermissionMaskState {
    val shouldShow = MutableStateFlow(false)
}

/** 悬浮窗快捷入口导航事件：singleTask 复用 MainActivity 时，用事件驱动 NavController 跳转 */
object NavigationEvent {
    val route = MutableStateFlow<String?>(null)
}

/**
 * 主界面入口。
 *
 * 流程：
 * 1. 首次启动显示引导页（guide_completed 标志）
 * 2. 引导完成后进入 AppNavigation
 * 3. 回前台时检测权限回收，弹出全屏遮罩（PRD A.2）
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val prefs by lazy {
        getSharedPreferences("yymm_settings", Context.MODE_PRIVATE)
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val guideCompleted = prefs.getBoolean("guide_completed", false)

        setContent {
            YymmMonitorTheme {
                var showGuide by remember { mutableStateOf(!guideCompleted) }

                if (showGuide) {
                    GuideScreen(onGuideComplete = {
                        prefs.edit().putBoolean("guide_completed", true).apply()
                        showGuide = false
                    })
                } else {
                    val navController = rememberNavController()
                    val startDestination = when (intent.getStringExtra("navigate_to")) {
                        "alert", "settings" -> "alert_settings"
                        "history" -> "history"
                        "filter" -> "filter"
                        else -> "main"
                    }
                    AppNavigation(
                        navController = navController,
                        startDestination = startDestination
                    )
                    // 悬浮窗快捷入口：singleTask 复用 Activity 时 onCreate 不会重跑，
                    // 用事件驱动 NavController 跳转（悬浮窗再次点击也能生效）
                    val navRoute by NavigationEvent.route.collectAsStateWithLifecycle()
                    LaunchedEffect(navRoute) {
                        navRoute?.let { route ->
                            NavigationEvent.route.value = null // 消费事件
                            try {
                                navController.navigate(route) {
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            } catch (_: Exception) {
                                // 路由不存在时忽略，不打断用户
                            }
                        }
                    }
                }

                // 前台权限回收检测（PRD A.2）
                val showPermissionMask by PermissionMaskState.shouldShow.collectAsStateWithLifecycle()
                if (showPermissionMask) {
                    PermissionMask(onDismiss = {
                        PermissionMaskState.shouldShow.value = false
                    })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // singleTask：悬浮窗快捷入口再次启动时走这里，转发导航事件
        when (intent.getStringExtra("navigate_to")) {
            "alert", "settings" -> NavigationEvent.route.value = "alert_settings"
            "history" -> NavigationEvent.route.value = "history"
            "filter" -> NavigationEvent.route.value = "filter"
            else -> NavigationEvent.route.value = "main"
        }
    }

    override fun onResume() {
        super.onResume()
        // 每次回前台检测权限是否被回收
        val guideCompleted = prefs.getBoolean("guide_completed", false)
        if (guideCompleted) {
            val overlayOk = PermissionUtil.isFloatingWindowAllowed(this)
            // 悬浮窗是无障碍/订单提醒的常驻入口：只要悬浮窗权限在就常驻显示，
            // 不因无障碍暂缺而消失（缺失时由遮罩引导 + 悬浮窗状态提示）
            if (overlayOk) {
                FloatingWindowManager.show(this)
            }
            // 延迟检测无障碍：OPPO/ColorOS 上服务绑定有延迟（用户刚开启时
            // onServiceConnected 可能未触发），立即检测会误报"已关闭"。延迟 800ms
            // 让服务完成绑定，instance 非空即判定已开启。
            mainHandler.postDelayed({
                if (isFinishing || isDestroyed) return@postDelayed
                val accessibilityOk = PermissionUtil.isAccessibilityServiceEnabled(this)
                if (!accessibilityOk || !overlayOk) {
                    PermissionMaskState.shouldShow.value = true
                }
            }, 800)
        }
    }
}

/**
 * 全屏权限回收遮罩（PRD A.2）：
 * "检测到无障碍服务已关闭，监控功能无法使用" + 去开启按钮
 */
@Composable
private fun PermissionMask(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // 初始隐藏，延迟后显示（避免与 onResume 竞态）
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(300)
        visible = true
    }

    if (!visible) return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "检测到无障碍服务已关闭",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF44336)
                )
                Spacer(Modifier.height(8.dp))
                Text("监控功能无法使用", fontSize = 14.sp, color = Color.Gray)
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = {
                        PermissionUtil.openAccessibilitySettings(context)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
                ) {
                    Text("去开启")
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("稍后再说")
                }
            }
        }
    }
}
