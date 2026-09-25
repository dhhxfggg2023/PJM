package com.dhhxfggg.pjm.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * 当前 Activity 是否处于 STARTED 及以上（即"用户看得见"）。
 *
 * 为什么需要它：工程里所有播放控制都只看「页面是否激活」（`isActive`），
 * 而 App 退到后台、熄屏时这个条件**依然成立** —— 结果是按 Home 键或熄屏后
 * 视频音频继续播放（耗电，且在隐私场景下很尴尬）。
 *
 * 用法：把返回值并入播放条件，例如
 * ```
 * LaunchedEffect(isActive, isScrolling, isVisible) {
 *     if (isActive && !isScrolling && isVisible) player.play() else player.pause()
 * }
 * ```
 */
@Composable
fun rememberIsAppVisible(): Boolean {
    val lifecycleOwner = LocalLifecycleOwner.current
    var visible by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> visible = true
                    Lifecycle.Event.ON_STOP -> visible = false
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return visible
}
