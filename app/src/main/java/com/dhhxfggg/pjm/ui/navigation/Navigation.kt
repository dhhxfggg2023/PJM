package com.dhhxfggg.pjm.ui.navigation

import androidx.activity.compose.LocalActivity
import androidx.activity.ComponentActivity
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dhhxfggg.pjm.ui.screen.MainScreen
import com.dhhxfggg.pjm.ui.screen.SettingsScreen
import com.dhhxfggg.pjm.ui.screen.FileViewerScreen
import com.dhhxfggg.pjm.ui.screen.DiscoveryScreen
import com.dhhxfggg.pjm.ui.screen.MediaDetailScreen
import com.dhhxfggg.pjm.domain.util.VaultManager
import androidx.hilt.navigation.compose.hiltViewModel
import com.dhhxfggg.pjm.ui.component.BottomNavBar
import com.dhhxfggg.pjm.ui.viewmodel.CryptoViewModel

sealed class Screen(val route: String) {
    object Main : Screen("main")
    object Discovery : Screen("discovery")
    object Settings : Screen("settings")
    object FileViewer : Screen("file_viewer/{category}") {
        fun createRoute(category: String) = "file_viewer/${android.net.Uri.encode(category)}"
    }
    object MediaDetail : Screen("media_detail/{relativePath}") {
        fun createRoute(relativePath: String) = "media_detail/${android.net.Uri.encode(relativePath)}"
    }
}

@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Screen.Main.route
) {
    val viewModel: CryptoViewModel = hiltViewModel(LocalActivity.current as ComponentActivity)
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    
    // 全屏状态提升到 Navigation 层级
    var isDiscoveryFullScreen by remember { mutableStateOf(false) }
    
    val showBottomBar = remember(currentRoute, isDiscoveryFullScreen) {
        currentRoute in listOf(Screen.Main.route, Screen.Discovery.route, Screen.Settings.route) && 
        !(currentRoute == Screen.Discovery.route && isDiscoveryFullScreen)
    }
    
    val animDuration = 350

    Scaffold(
        bottomBar = { if (showBottomBar) BottomNavBar(navController = navController) },
        containerColor = Color.Transparent 
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.fillMaxSize(),
            enterTransition = {
                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(animDuration, easing = FastOutSlowInEasing)) + fadeIn(tween(animDuration))
            },
            exitTransition = {
                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(animDuration, easing = FastOutSlowInEasing)) + fadeOut(tween(animDuration))
            },
            popEnterTransition = {
                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(animDuration, easing = FastOutSlowInEasing)) + fadeIn(tween(animDuration))
            },
            popExitTransition = {
                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(animDuration, easing = FastOutSlowInEasing)) + fadeOut(tween(animDuration))
            }
        ) {
            composable(Screen.Main.route) {
                MainScreen(
                    navController = navController,
                    bottomPadding = innerPadding.calculateBottomPadding(),
                    onNavigateToCategory = { category -> navController.navigate(Screen.FileViewer.createRoute(category)) }
                )
            }
            composable(Screen.Discovery.route) {
                DiscoveryScreen(
                    navController = navController,
                    bottomPadding = innerPadding.calculateBottomPadding(),
                    isFullScreen = isDiscoveryFullScreen,
                    onFullScreenChange = { isDiscoveryFullScreen = it }
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    bottomPadding = innerPadding.calculateBottomPadding()
                )
            }
            composable(
                route = Screen.FileViewer.route,
                arguments = listOf(navArgument("category") { type = NavType.StringType })
            ) { backStackEntry ->
                val category = backStackEntry.arguments?.getString("category") ?: VaultManager.CAT_OTHERS
                FileViewerScreen(
                    category = category, 
                    onBack = { navController.popBackStack() },
                    bottomPadding = innerPadding.calculateBottomPadding(),
                    onNavigateToMediaDetail = { path -> navController.navigate(Screen.MediaDetail.createRoute(path)) }
                )
            }
            composable(
                route = Screen.MediaDetail.route,
                arguments = listOf(navArgument("relativePath") { type = NavType.StringType })
            ) { backStackEntry ->
                val relativePath = backStackEntry.arguments?.getString("relativePath") ?: ""
                MediaDetailScreen(
                    relativePath = relativePath,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
