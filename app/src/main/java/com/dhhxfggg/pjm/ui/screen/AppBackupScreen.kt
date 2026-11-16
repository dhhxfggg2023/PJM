package com.dhhxfggg.pjm.ui.screen

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.SearchX
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.domain.util.FileUtils
import com.dhhxfggg.pjm.ui.component.PjmAeroDialog
import com.dhhxfggg.pjm.ui.viewmodel.AppBackupViewModel
import com.dhhxfggg.pjm.ui.viewmodel.BackupAppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 应用图标内存缓存。
 *
 * 为什么不用 Coil：这里要的是「已安装应用的 Icon」，来源是 PackageManager
 * （读 APK 资源），不是图片 URL；走一轮 ImageLoader 反而更绕。
 * LruCache 上限按条数算（128 条 ≈ 5 MB 左右），滚动回来时不必重新解码。
 */
private object AppIconCache {
    // android.util.LruCache 的默认 sizeOf 按条数计，这里显式写出来以免被误当成「按字节」
    private val cache =
        object : LruCache<String, ImageBitmap>(128) {
            override fun sizeOf(
                key: String,
                value: ImageBitmap,
            ): Int = 1
        }

    fun get(packageName: String): ImageBitmap? = cache.get(packageName)

    fun put(
        packageName: String,
        icon: ImageBitmap,
    ) {
        cache.put(packageName, icon)
    }
}

/**
 * 「备份已安装应用」页面。
 *
 * 布局：顶部返回 + 标题 + 搜索框；中间是应用列表（复选框多选）；底部固定一个
 * 「备份选中（N）」按钮 —— 与文件柜的批量操作保持一致，用户不用滚到底部才能提交。
 *
 * 备份只针对安装包（APK），不含应用数据；文案里刻意写成「安装包」以免误导。
 *
 * @param bottomPadding 底栏高度（本页是独立路由，通常传 0）
 * @param onBack 返回上一页
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBackupScreen(
    bottomPadding: Dp = 0.dp,
    onBack: () -> Unit = {},
    viewModel: AppBackupViewModel = hiltViewModel(),
) {
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val outcome by viewModel.outcome.collectAsStateWithLifecycle()

    // 搜索框的即时文本和防抖后的过滤词分开：输入框必须立刻显示用户敲的字，
    // 而列表过滤走 300ms 防抖（见 AppBackupViewModel.onQueryChange）。
    var searchText by remember { mutableStateOf("") }
    val filtered =
        remember(apps, query) {
            if (query.isEmpty()) {
                apps
            } else {
                val needle = query.lowercase()
                apps.filter { it.searchKey.contains(needle) }
            }
        }

    Scaffold(
        topBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.app_backup_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Lucide.ArrowLeft, stringResource(R.string.action_back))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
                OutlinedTextField(
                    value = searchText,
                    onValueChange = {
                        searchText = it
                        viewModel.onQueryChange(it)
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    placeholder = { Text(stringResource(R.string.app_backup_search_hint)) },
                    leadingIcon = { Icon(Lucide.Search, null, modifier = Modifier.size(18.dp)) },
                    shape = MaterialTheme.shapes.medium,
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Color.Transparent),
                )
            }
        },
        bottomBar = {
            AppBackupBottomBar(
                selectedCount = selected.size,
                isRunning = progress != null,
                onBackup = { viewModel.startBackup() },
            )
        },
        containerColor = Color.Transparent,
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding())
                    .padding(bottom = padding.calculateBottomPadding())
                    .padding(bottom = bottomPadding),
        ) {
            when {
                isLoading -> AppBackupLoading()
                filtered.isEmpty() -> AppBackupEmptyState(isSearchEmpty = query.isNotEmpty())
                else ->
                    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
                        items(filtered, key = { it.packageName }) { app ->
                            AppBackupRow(
                                app = app,
                                checked = selected.contains(app.packageName),
                                enabled = progress == null,
                                onToggle = { viewModel.toggleSelection(app.packageName) },
                            )
                        }
                        item(key = "app_backup_bottom_spacer") { Spacer(Modifier.height(12.dp)) }
                    }
            }
        }
    }

    val currentProgress = progress
    if (currentProgress != null) {
        AppBackupProgressDialog(progress = currentProgress)
    }

    outcome?.let { result ->
        PjmAeroDialog(
            onDismissRequest = { viewModel.dismissOutcome() },
            title = stringResource(R.string.app_backup_result_title),
            confirmButton = {
                Button(onClick = { viewModel.dismissOutcome() }) { Text(stringResource(R.string.action_confirm)) }
            },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.app_backup_result_summary, result.successCount, result.failureCount),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (result.apkCount > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.app_backup_result_apks, result.apkCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (result.failureCount > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.app_backup_result_reason, result.failureReasons.joinToString("；")),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppBackupBottomBar(
    selectedCount: Int,
    isRunning: Boolean,
    onBackup: () -> Unit,
) {
    Surface(color = Color.Transparent) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Button(
                onClick = onBackup,
                enabled = selectedCount > 0 && !isRunning,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.app_backup_action_backup, selectedCount))
            }
        }
    }
}

@Composable
private fun AppBackupLoading() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.app_backup_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AppBackupEmptyState(isSearchEmpty: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Lucide.SearchX,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(if (isSearchEmpty) R.string.app_backup_search_empty else R.string.app_backup_empty),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (isSearchEmpty) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.app_backup_search_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AppBackupRow(
    app: BackupAppInfo,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Checkbox,
                    onValueChange = { onToggle() },
                ).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIconBox(packageName = app.packageName)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (app.isSystem) {
                    Spacer(Modifier.width(6.dp))
                    AppBackupTag(text = stringResource(R.string.app_backup_system_tag))
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                    (
                        if (app.versionName.isNullOrBlank()) {
                            stringResource(R.string.app_backup_version_unknown)
                        } else {
                            stringResource(R.string.app_backup_version, app.versionName, app.versionCode)
                        }
                    ) + " · " + FileUtils.formatFileSize(app.apkBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** 小号标签：系统应用 / 分片数。用弱底色而不是描边，避免和列表里的复选框抢注意力。 */
@Composable
private fun AppBackupTag(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier =
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/**
 * 应用图标：按行懒加载。
 *
 * 三个必须遵守的约束：
 * 1. 不在主线程解码 —— loadIcon 会去读 APK 资源，上百个应用一次性解会明显掉帧；
 * 2. 解码结果进 [AppIconCache]，滚动回来不重复解码；
 * 3. 失败（应用刚被卸载）时留同尺寸占位，行高不跳。
 */
@Composable
private fun AppIconBox(
    packageName: String,
    modifier: Modifier = Modifier.size(44.dp),
) {
    val context = LocalContext.current
    val cached = remember(packageName) { AppIconCache.get(packageName) }
    val icon by
        produceState<ImageBitmap?>(initialValue = cached, packageName) {
            if (value != null) return@produceState
            value =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val drawable = context.packageManager.getApplicationIcon(packageName)
                        drawable.toBitmap(width = 132, height = 132, config = Bitmap.Config.ARGB_8888).asImageBitmap()
                    }.getOrNull()?.also { AppIconCache.put(packageName, it) }
                }
        }

    val current = icon
    val shape = RoundedCornerShape(12.dp)
    if (current != null) {
        Image(
            bitmap = current,
            contentDescription = null,
            modifier = modifier.clip(shape),
        )
    } else {
        Box(modifier = modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

/** 备份中弹窗：不可取消（中途退出会留下半截文件），只显示进度与当前应用。 */
@Composable
private fun AppBackupProgressDialog(progress: com.dhhxfggg.pjm.ui.viewmodel.AppBackupProgress) {
    val fraction =
        if (progress.totalBytes <= 0L) {
            0f
        } else {
            (progress.loadedBytes.toFloat() / progress.totalBytes.toFloat()).coerceIn(0f, 1f)
        }
    PjmAeroDialog(
        onDismissRequest = {},
        title = stringResource(R.string.app_backup_backing_up, progress.currentLabel),
        confirmButton = {},
        dismissButton = {},
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text =
                    stringResource(
                        R.string.app_backup_progress,
                        progress.currentIndex,
                        progress.totalApps,
                        FileUtils.formatFileSize(progress.loadedBytes),
                        FileUtils.formatFileSize(progress.totalBytes),
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 进度条按字节走，应用序号按个走：两者都显示，用户才知道「还剩几个应用」
            Spacer(Modifier.height(4.dp))
            Text(
                text = String.format(Locale.US, "%d%%", (fraction * 100).toInt()),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
