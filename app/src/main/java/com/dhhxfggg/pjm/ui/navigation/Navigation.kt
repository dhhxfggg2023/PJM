package com.dhhxfggg.pjm.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dhhxfggg.pjm.domain.util.FrameMonitor
import com.dhhxfggg.pjm.domain.util.VaultManager
import com.dhhxfggg.pjm.ui.component.BottomNavBar
import com.dhhxfggg.pjm.ui.screen.DiscoveryScreen
import com.dhhxfggg.pjm.ui.screen.FileViewerScreen
import com.dhhxfggg.pjm.ui.screen.MainScreen
import com.dhhxfggg.pjm.ui.screen.MediaDetailScreen
import com.dhhxfggg.pjm.ui.screen.SettingsScreen
import com.dhhxfggg.pjm.ui.screen.VaultHeatmapScreen
import com.dhhxfggg.pjm.ui.theme.rememberIconPack

sealed class Screen(
    val route: String,
) {
    object Main : Screen("main")

    object Discovery : Screen("discovery")

    object Settings : Screen("settings")

    object FileViewer : Screen("file_viewer/{category}") {
        fun createRoute(category: String) = "file_viewer/${android.net.Uri.encode(category)}"
    }

    object MediaDetail : Screen("media_detail/{relativePath}") {
        fun createRoute(relativePath: String) = "media_detail/${android.net.Uri.encode(relativePath)}"
    }

    /** 入库热力图（首页保险库占用卡片长按进入） */
    object VaultHeatmap : Screen("vault_heatmap")
}

@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Screen.Main.route,
    // 核心修复：singleTask 下 onNewIntent 只会更新 State，而 NavHost 的 startDestination
    // 仅在首次建图时生效。快捷方式/二次分享需要一条「一次性导航事件」通道。
    pendingRoute: kotlinx.coroutines.flow.StateFlow<String?> =
        remember { kotlinx.coroutines.flow.MutableStateFlow(null) },
) {
    val pending by pendingRoute.collectAsStateWithLifecycle()
    LaunchedEffect(pending) {
        val route = pending ?: return@LaunchedEffect
        navController.navigate(route) { launchSingleTop = true }
        @Suppress("UNCHECKED_CAST")
        (pendingRoute as? kotlinx.coroutines.flow.MutableStateFlow<String?>)?.value = null
    }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    // 给调试版慢帧统计标注当前页面，日志里能直接看到是哪一屏在掉帧
    LaunchedEffect(currentRoute) { FrameMonitor.currentScreen = currentRoute.orEmpty() }

    val iconPack = rememberIconPack()

    var isDiscoveryFullScreen by remember { mutableStateOf(false) }

    val showBottomBar =
        remember(currentRoute, isDiscoveryFullScreen) {
            (currentRoute in listOf(Screen.Main.route, Screen.Discovery.route, Screen.Settings.route)) &&
                !(currentRoute == Screen.Discovery.route && isDiscoveryFullScreen)
        }

    val animDuration = 220

    Scaffold(
        bottomBar = { if (showBottomBar) BottomNavBar(navController = navController, iconPack = iconPack) },
        containerColor = Color.Transparent,
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.fillMaxSize(),
            // 只做位移，不再叠加整屏淡入淡出：两层全屏内容同时做 alpha + 位移，
            // 每次跳页都要多渲染一整屏，手感发飘也更容易掉帧。
            enterTransition = {
                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(animDuration, easing = FastOutSlowInEasing))
            },
            exitTransition = {
                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(animDuration, easing = FastOutSlowInEasing))
            },
            popEnterTransition = {
                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(animDuration, easing = FastOutSlowInEasing))
            },
            popExitTransition = {
                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(animDuration, easing = FastOutSlowInEasing))
            },
        ) {
            composable(Screen.Main.route) {
                MainScreen(
                    bottomPadding = innerPadding.calculateBottomPadding(),
                    onNavigateToCategory = { category ->
                        navController.navigate(Screen.FileViewer.createRoute(category))
                    },
                    onOpenHeatmap = { navController.navigate(Screen.VaultHeatmap.route) },
                )
            }
            composable(Screen.VaultHeatmap.route) {
                VaultHeatmapScreen(
                    onBack = { navController.popBackStack() },
                    bottomPadding = innerPadding.calculateBottomPadding(),
                )
            }
            composable(Screen.Discovery.route) {
                DiscoveryScreen(
                    navController = navController,
                    bottomPadding = innerPadding.calculateBottomPadding(),
                    isFullScreen = isDiscoveryFullScreen,
                    onFullScreenChange = { isDiscoveryFullScreen = it },
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    bottomPadding = innerPadding.calculateBottomPadding(),
                )
            }
            composable(
                route = Screen.FileViewer.route,
                arguments = listOf(navArgument("category") { type = NavType.StringType }),
            ) { backStackEntry ->
                val category = backStackEntry.arguments?.getString("category") ?: VaultManager.CAT_OTHERS
                FileViewerScreen(
                    category = category,
                    onBack = { navController.popBackStack() },
                    bottomPadding = innerPadding.calculateBottomPadding(),
                    onNavigateToMediaDetail = { path -> navController.navigate(Screen.MediaDetail.createRoute(path)) },
                )
            }
            composable(
                route = Screen.MediaDetail.route,
                arguments = listOf(navArgument("relativePath") { type = NavType.StringType }),
            ) { backStackEntry ->
                val relativePath = backStackEntry.arguments?.getString("relativePath") ?: ""
                MediaDetailScreen(
                    relativePath = relativePath,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
