package com.yymm.monitor.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.yymm.monitor.presentation.ui.screen.AlertSettingsScreen
import com.yymm.monitor.presentation.ui.screen.FilterScreen
import com.yymm.monitor.presentation.ui.screen.HistoryScreen
import com.yymm.monitor.presentation.ui.screen.MainScreen
import com.yymm.monitor.presentation.ui.screen.RuleEditScreen

/**
 * 应用导航图。
 *
 * 路由：
 * - "main" → MainScreen
 * - "rule_edit" → RuleEditScreen（新建）
 * - "rule_edit/{ruleId}" → RuleEditScreen（编辑）
 * - "alert_settings" → AlertSettingsScreen
 * - "history" → HistoryScreen
 * - "guide" → GuideScreen（后续步骤启用）
 */
@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController(),
    startDestination: String = "main"
) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable("main") {
            MainScreen(navController = navController)
        }
        composable("rule_edit") {
            RuleEditScreen(ruleId = null, navController = navController)
        }
        composable(
            route = "rule_edit/{ruleId}",
            arguments = listOf(navArgument("ruleId") { type = NavType.LongType })
        ) { backStackEntry ->
            val ruleId = backStackEntry.arguments?.getLong("ruleId")
            RuleEditScreen(ruleId = ruleId, navController = navController)
        }
        composable("alert_settings") {
            AlertSettingsScreen(navController = navController)
        }
        composable("history") {
            HistoryScreen(navController = navController)
        }
        composable("filter") {
            FilterScreen(navController = navController)
        }
        composable("guide") {
            // GuideScreen 由 MainActivity 控制（首次启动引导），此路由保留占位
            MainScreen(navController = navController)
        }
    }
}
