package com.dhhxfggg.pjm.ui.component

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.ui.theme.IconPack
import com.dhhxfggg.pjm.ui.navigation.Screen

/**
 * A bottom navigation bar component for switching between main app screens.
 *
 * @param navController The navigation controller used for switching destinations.
 * @param iconPack The icon pack to use for rendering icons.
 */
@Composable
fun BottomNavBar(navController: NavHostController, iconPack: IconPack) {
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
            icon = { Icon(iconPack.home, contentDescription = stringResource(R.string.nav_home)) },
            label = { Text(stringResource(R.string.nav_home)) }
        )
        NavigationBarItem(
            selected = currentRoute == Screen.Discovery.route,
            onClick = { onNavigate(Screen.Discovery.route) },
            icon = { Icon(iconPack.discovery, contentDescription = stringResource(R.string.nav_discovery)) },
            label = { Text(stringResource(R.string.nav_discovery)) }
        )
        NavigationBarItem(
            selected = currentRoute == Screen.Settings.route,
            onClick = { onNavigate(Screen.Settings.route) },
            icon = { Icon(iconPack.settings, contentDescription = stringResource(R.string.nav_settings)) },
            label = { Text(stringResource(R.string.nav_settings)) }
        )
    }
}
