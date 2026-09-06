package com.dhhxfggg.pjm.ui.component

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/**
 * PJM Aero Glass Dialog Container.
 * Replaces standard AlertDialog to ensure consistent frosted-glass visual style.
 */
@Composable
fun PjmAeroDialog(
    onDismissRequest: () -> Unit,
    title: String? = null,
    icon: (@Composable () -> Unit)? = null,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // 核心修复：在 Dialog 窗口级别设置磨砂效果，而不是在 Activity 级别
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        if (window != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SideEffect {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes.blurBehindRadius = 80
                window.setAttributes(window.attributes)
            }
        }

        Box(
            modifier =
                Modifier
                    .wrapContentWidth()
                    .widthIn(max = 340.dp)
                    .fillMaxWidth(0.85f)
                    .wrapContentHeight()
                    .clip(MaterialTheme.shapes.extraLarge),
        ) {
            // 背景层：保持轻微透明度以配合窗口磨砂
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.62f)),
            )

            // 内容层：增加滚动支持，防止大字体/大缩放时 UI 溢出
            Column(
                modifier =
                    Modifier
                        .padding(20.dp) // 略微减少内边距以使布局更紧凑
                        .fillMaxWidth()
                        .heightIn(max = 620.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (icon != null) {
                    Box(modifier = Modifier.padding(bottom = 16.dp)) { icon() }
                }

                if (title != null) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                }

                // 核心修复：添加垂直滚动条支持大缩放
                Box(modifier = Modifier.weight(1f, fill = false)) {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState()).fillMaxWidth()) {
                        content()
                    }
                }

                Spacer(Modifier.height(24.dp))

                // 核心修复：MT 风格全宽纵向按钮组，彻底解决 UI 缩放下的文字溢出与错乱
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // 确认按钮 (全宽)
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color.Transparent,
                    ) {
                        confirmButton()
                    }

                    if (dismissButton != null) {
                        // 取消/次要按钮 (全宽)
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color.Transparent,
                        ) {
                            dismissButton()
                        }
                    }
                }
            }
        }
    }
}
