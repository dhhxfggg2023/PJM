package com.dhhxfggg.pjm.ui.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.dhhxfggg.pjm.ui.navigation.Screen

/**
 * A bottom navigation bar component for switching between main app screens.
 *
 * @param navController The navigation controller used for switching destinations.
 */
@Composable
fun BottomNavBar(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val haptic = LocalHapticFeedback.current

    val onNavigate = remember(navController) {
        { route: String ->
            if (currentRoute != route) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                navController.navigate(route) {
                    popUpTo(navController.graph.startDestinationId) { 
                        saveState = true 
                    }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
    }

    NavigationBar {
        NavigationBarItem(
            selected = currentRoute == Screen.Main.route,
            onClick = { onNavigate(Screen.Main.route) },
            icon = { Icon(Icons.Default.Home, contentDescription = "主页") },
            label = { Text("主页") }
        )
        NavigationBarItem(
            selected = currentRoute == Screen.Discovery.route,
            onClick = { onNavigate(Screen.Discovery.route) },
            icon = { Icon(Icons.Default.AutoAwesome, contentDescription = "发现") },
            label = { Text("发现") }
        )
        NavigationBarItem(
            selected = currentRoute == Screen.Settings.route,
            onClick = { onNavigate(Screen.Settings.route) },
            icon = { Icon(Icons.Default.Settings, contentDescription = "设置") },
            label = { Text("设置") }
        )
    }
}
