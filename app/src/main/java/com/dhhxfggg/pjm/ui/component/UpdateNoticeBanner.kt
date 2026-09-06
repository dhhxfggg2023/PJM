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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.domain.util.UpdateChecker
import kotlinx.coroutines.delay

/**
 * 顶部「版本更新」提示横幅（自包含）。
 *
 * 行为：
 *  - App 启动后延迟数秒静默检查 GitHub 最新 Release（失败/无更新不打扰）；
 *  - 发现新版本 → 从屏幕最上方滑入横幅，约 8 秒后自动淡出，也可点 × 立即关闭；
 *  - 点击横幅 → 应用内直接下载安装（带进度）；完成后自动拉起系统安装器；
 *  - 下载失败给出 Toast 提示。
 *
 * 用法：放在全局最外层 Box 中即可（建议靠后声明以覆盖在内容层之上）。
 */
@Composable
fun UpdateNoticeBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // null = 不显示；非 null = 待展示的更新信息
    var available by remember { mutableStateOf<UpdateChecker.CheckResult.UpdateAvailable?>(null) }
    // 是否已开始下载（true 后进入下载流程）
    var downloadStarted by remember { mutableStateOf(false) }
    // 下载进度：null=未开始；0f..1f=进行中
    var downloadProgress by remember { mutableStateOf<Float?>(null) }
    // 用户手动关闭
    var dismissed by remember { mutableStateOf(false) }

    // 1) 启动后静默检查一次
    LaunchedEffect(Unit) {
        // 让首屏先稳定，稍后检查，避免抢占冷启动资源
        delay(2500)
        val result = UpdateChecker.checkForUpdate(context)
        if (result is UpdateChecker.CheckResult.UpdateAvailable) {
            available = result
        }
    }

    // 2) 自动消失：出现后约 8 秒淡出（下载中不消失）
    LaunchedEffect(available, downloadStarted) {
        if (available != null && !downloadStarted) {
            delay(8000)
            dismissed = true
        }
    }

    // 3) 下载安装流程（点击横幅触发后执行）
    LaunchedEffect(downloadStarted) {
        val update = available ?: return@LaunchedEffect
        if (!downloadStarted) return@LaunchedEffect
        when (
            val result =
                UpdateChecker.downloadApk(context, update.apkUrl) { p ->
                    downloadProgress = p
                }
        ) {
            is UpdateChecker.DownloadResult.Success -> {
                val ok = UpdateChecker.installApk(context, result.apkFile)
                dismissed = true
                if (!ok) {
                    Toast
                        .makeText(
                            context,
                            context.getString(R.string.msg_update_install_fallback),
                            Toast.LENGTH_LONG,
                        ).show()
                }
            }
            is UpdateChecker.DownloadResult.Error -> {
                dismissed = true
                Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    val show = available != null && !dismissed

    AnimatedVisibility(
        visible = show,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier,
    ) {
        val update = available ?: return@AnimatedVisibility
        val progress = downloadProgress

        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .statusBarsPadding()
                    .clickable(enabled = progress == null) { downloadStarted = true },
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
                        stringResource(R.string.update_banner_text, update.latestVersion)
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
                    onClick = { dismissed = true },
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
