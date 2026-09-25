package com.dhhxfggg.pjm.ui.component

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.domain.util.UpdateController
import kotlinx.coroutines.delay

/**
 * 顶部「版本更新」提示横幅。
 *
 * 行为：
 *  - App 启动后延迟数秒静默检查 GitHub 最新 Release（失败/无更新不打扰）；
 *  - 发现新版本 → 从屏幕最上方滑入横幅，约 8 秒后自动淡出，也可点 × 立即关闭；
 *  - 点击横幅 → 应用内直接下载安装（带进度）；完成后自动拉起系统安装器；
 *  - 下载失败给出 Toast 提示。
 *
 * 核心修复：检查与下载的协程都上移到 [UpdateController]（进程级作用域）。
 * 本组件在 MainActivity 中是条件组合的（`if (opTasks.isEmpty())`），旋转屏幕、
 * 或任何后台任务进度出现都会让它离开组合 —— 原来挂在它 `LaunchedEffect` 上的
 * 下载协程会被**静默取消**，用户看不到任何提示。现在组件只是观察者。
 *
 * 用法：放在全局最外层 Box 中即可（建议靠后声明以覆盖在内容层之上）。
 */
@Composable
fun UpdateNoticeBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val update by UpdateController.available.collectAsState()
    val downloadProgress by UpdateController.downloadProgress.collectAsState()
    val dismissed by UpdateController.dismissed.collectAsState()
    val message by UpdateController.message.collectAsState()

    // 1) 启动后静默检查一次（controller 内部幂等，重组不会重复触发）
    LaunchedEffect(Unit) {
        UpdateController.checkOnce(context)
    }

    // 2) 自动消失：出现后约 8 秒淡出（下载中不消失）
    LaunchedEffect(update, downloadProgress) {
        if (update != null && downloadProgress == null && !dismissed) {
            delay(8000)
            UpdateController.dismiss()
        }
    }

    // 3) 一次性提示（下载失败 / 安装回退）
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        UpdateController.consumeMessage()
    }

    val show = update != null && !dismissed
    val progress = downloadProgress

    AnimatedVisibility(
        visible = show,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier,
    ) {
        val current = update ?: return@AnimatedVisibility

        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .statusBarsPadding()
                    .clickable(enabled = progress == null) { UpdateController.startDownload(context) },
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.98f),
                ),
            elevation = CardDefaults.cardElevation(8.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (progress != null) {
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 3.dp,
                    )
                } else {
                    Icon(
                        imageVector = Lucide.Download,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.width(12.dp))
                val text =
                    if (progress != null) {
                        stringResource(R.string.update_banner_downloading, (progress * 100).toInt())
                    } else {
                        stringResource(R.string.update_banner_text, current.latestVersion)
                    }
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = { UpdateController.dismiss() },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Lucide.X,
                        contentDescription = stringResource(R.string.update_banner_close),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}
