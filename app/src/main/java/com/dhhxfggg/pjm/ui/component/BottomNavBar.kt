package com.dhhxfggg.pjm.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.ui.navigation.Screen
import com.dhhxfggg.pjm.ui.theme.IconPack
import com.dhhxfggg.pjm.ui.theme.PrimaryDark

/** 底栏胶囊本身的高度（不含上下留白与系统导航条） */
val BottomNavBarContentHeight: Dp = 60.dp

/** 胶囊上下的留白 */
val BottomNavBarVerticalPadding: Dp = 10.dp

/**
 * 浮动胶囊式底部导航。
 *
 * 相比裸 [androidx.compose.material3.NavigationBar] 的改动：
 *  1. **悬浮**在内容之上（圆角胶囊 + 半透明表面 + 投影），而不是贴底的一条实心色带；
 *  2. **选中项展开**成「图标 + 文字」的渐变胶囊，未选中只留图标与细字 ——
 *     层次靠形状而非仅靠颜色，弱视用户也能分辨；
 *  3. 保留原有的触感反馈与 `saveState/restoreState` 导航语义（切 Tab 不丢滚动位置）。
 *
 * 无障碍：图标带 `contentDescription`；选中项额外用 [Modifier.semantics] 标注 selected。
 */
@Composable
fun BottomNavBar(
    navController: NavHostController,
    iconPack: IconPack,
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val haptic = LocalHapticFeedback.current

    data class NavItem(
        val route: String,
        val icon: ImageVector,
        val labelRes: Int,
    )

    val items =
        listOf(
            NavItem(Screen.Main.route, iconPack.home, R.string.nav_home),
            NavItem(Screen.Discovery.route, iconPack.discovery, R.string.nav_discovery),
            NavItem(Screen.Settings.route, iconPack.settings, R.string.nav_settings),
        )

    val onNavigate =
        remember(navController, currentRoute) {
            { route: String ->
                if (currentRoute != route) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    navController.navigate(route) {
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = BottomNavBarVerticalPadding),
    ) {
        // 底栏不再做成「浮在大背景上的一张卡片」：底色直接取页面背景、去掉描边与投影，
        // 只有选中项的高亮胶囊浮出来，不会出现「底栏一层、背景一层」的割裂感。
        Surface(
            modifier = Modifier.fillMaxWidth().height(BottomNavBarContentHeight),
            shape = CircleShape,
            color = Color.Transparent,
            border = null,
            shadowElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEach { item ->
                    NavBarItem(
                        modifier = Modifier.weight(1f),
                        icon = item.icon,
                        label = stringResource(item.labelRes),
                        selected = currentRoute == item.route,
                        onClick = { onNavigate(item.route) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NavBarItem(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // 注意用 scheme.primary 作 remember 的 key：开启动态取色后主色会变，
    // 不重新计算的话渐变会停留在旧配色上。
    val gradient =
        remember(scheme.primary) {
            Brush.linearGradient(listOf(PrimaryDark, scheme.primary))
        }
    val interaction = remember { MutableInteractionSource() }

    Box(
        // 整格仍然是点击区域，但不再画背景、不再有内边距分割 ——
        // 三个 Tab 共用底栏这一张背景，视觉上是一整条，而不是三块独立按钮。
        modifier =
            modifier
                .fillMaxHeight()
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                // 把整格合成一个语义节点并暴露选中态，否则读屏用户听不出当前在哪个 Tab
                .semantics(mergeDescendants = true) {
                    role = Role.Tab
                    this.selected = selected
                    contentDescription = label
                },
        contentAlignment = Alignment.Center,
    ) {
        // 关键：选中与未选中**使用完全相同的布局**（图标在上、文字在下），
        // 只切换背景胶囊的显隐。
        //
        // 此前选中态把文字挪到图标右侧变成「横排胶囊」—— 切换 Tab 时，
        // 那个 Tab 的文字会从图标下方跳到右侧，切换时有明显的横向跳动。
        // 让文字位置恒定后，视觉变化只发生在背景，不再有位移。
        val pill =
            if (selected) {
                Modifier.background(gradient)
            } else {
                Modifier
            }
        Column(
            modifier =
                Modifier
                    .clip(CircleShape)
                    .then(pill)
                    .padding(horizontal = 14.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                // 下方已有同文案的 Text，图标再带 contentDescription 会被 TalkBack 念两遍
                contentDescription = null,
                tint = if (selected) Color.White else scheme.onSurfaceVariant,
                modifier = Modifier.size(21.dp),
            )
            Text(
                text = label,
                color = if (selected) Color.White else scheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
