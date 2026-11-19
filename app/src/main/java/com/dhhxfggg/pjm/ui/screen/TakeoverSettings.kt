package com.dhhxfggg.pjm.ui.screen

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.*
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.data.model.ManagedAppEntity
import com.dhhxfggg.pjm.domain.service.TakeoverBackgroundPermission
import com.dhhxfggg.pjm.ui.component.PjmAeroDialog
import com.dhhxfggg.pjm.ui.component.PjmButtonCaption
import com.dhhxfggg.pjm.ui.theme.PresetDustyBlue
import com.dhhxfggg.pjm.ui.viewmodel.InstalledApp
import com.dhhxfggg.pjm.ui.viewmodel.TakeoverViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「应用接管」设置页。
 *
 * 只做三件事：
 * 1. 告诉用户这个功能到底会把文件怎么处理（删除原文件 + 失败会通知）；
 * 2. 列出已接管的应用（图标 + 名称），每行可以移除；
 * 3. 把「已入库但原文件没删掉」的失败数量摆出来，不让它悄悄发生。
 *
 * 列表默认是空的：接管是用户逐个点着加进来的，不是默认替谁做主。
 */
@Composable
internal fun AppsTakeoverSettings(takeoverViewModel: TakeoverViewModel) {
    val context = LocalContext.current
    val enabled by takeoverViewModel.enabled.collectAsStateWithLifecycle()
    val managedApps by takeoverViewModel.managedApps.collectAsStateWithLifecycle()
    val deleteFailures by takeoverViewModel.deleteFailures.collectAsStateWithLifecycle()
    val importFailures by takeoverViewModel.importFailures.collectAsStateWithLifecycle()

    var showPicker by remember { mutableStateOf(false) }
    val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsCategory(title = stringResource(R.string.takeover_section_intro)) {
            Text(
                text =
                    if (supported) {
                        stringResource(R.string.takeover_explanation)
                    } else {
                        stringResource(R.string.takeover_requires_android10)
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }

        SettingsCategory(title = stringResource(R.string.takeover_section_switch)) {
            SettingsSwitch(
                icon = Lucide.Power,
                title = stringResource(R.string.takeover_switch_title),
                description = stringResource(R.string.takeover_switch_desc),
                checked = enabled,
                onCheckedChange = { takeoverViewModel.setEnabled(it) },
            )
            if (!enabled) {
                Text(
                    text = stringResource(R.string.takeover_switch_off_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }

        SettingsCategory(title = stringResource(R.string.takeover_section_apps)) {
            // 「添加应用」放在最上面：应用一多，按钮会被压到列表底部，点不到也容易误触删除
            SettingsButton(
                icon = Lucide.Plus,
                title = stringResource(R.string.takeover_add_app),
                description = stringResource(R.string.takeover_add_app_desc),
                onClick = { showPicker = true },
                accent = PresetDustyBlue,
            )
            if (managedApps.isEmpty()) {
                Text(
                    text = stringResource(R.string.takeover_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            } else {
                managedApps.forEach { app ->
                    ManagedAppRow(
                        app = app,
                        active = enabled,
                        onRemove = { takeoverViewModel.removeApp(app.packageName) },
                    )
                }
            }
        }

        // 后台运行白名单：接管一旦启用就默认需要它 —— 国产 ROM 会在锁屏后冻结后台进程，
        // 不在白名单里时前台服务记录还在、通知还挂着，但协程一行都不跑（只有打开 App 才补扫）。
        // 进页面时如果还没授权就直接调系统弹窗；被拒绝也不反复弹（间隔由 request 限流）。
        // 返回页面时用 ON_RESUME 重查一次：用户点完「允许」，这一块就自动消失。
        var backgroundExempt by remember { mutableStateOf(TakeoverBackgroundPermission.isExempt(context)) }
        var backgroundStatus by remember { mutableStateOf(takeoverViewModel.backgroundStatus()) }
        LifecycleResumeEffect(Unit) {
            backgroundExempt = TakeoverBackgroundPermission.isExempt(context)
            backgroundStatus = takeoverViewModel.backgroundStatus()
            onPauseOrDispose { }
        }
        LaunchedEffect(managedApps.isNotEmpty()) {
            if (managedApps.isNotEmpty() && !backgroundExempt) {
                TakeoverBackgroundPermission.request(context)
            }
        }

        if (enabled && managedApps.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val lastScanAt = backgroundStatus.lastScanAt
            val scanAgeMin = if (lastScanAt > 0L) ((now - lastScanAt) / 60_000L).toInt() else -1
            // 5 分钟内有过扫描就算「在跑」：定时兜底 3 分钟一轮，留两分钟抖动余量
            val running = scanAgeMin in 0..5
            val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
            SettingsCategory(title = stringResource(R.string.takeover_background_title)) {
                StatusLine(
                    text =
                        stringResource(
                            if (backgroundExempt) R.string.takeover_status_battery_ok else R.string.takeover_status_battery_off,
                        ),
                    ok = backgroundExempt,
                )
                StatusLine(
                    text =
                        if (lastScanAt <= 0L) {
                            stringResource(R.string.takeover_status_no_scan)
                        } else {
                            stringResource(
                                R.string.takeover_status_last_scan,
                                timeFormat.format(Date(lastScanAt)),
                                scanAgeMin.coerceAtLeast(0),
                            )
                        },
                    ok = running,
                )
                StatusLine(
                    text =
                        if (backgroundStatus.gapCount <= 0) {
                            stringResource(R.string.takeover_status_no_gap)
                        } else {
                            stringResource(
                                R.string.takeover_status_gap,
                                backgroundStatus.gapCount,
                                if (backgroundStatus.lastGapAt > 0L) {
                                    timeFormat.format(Date(backgroundStatus.lastGapAt))
                                } else {
                                    "—"
                                },
                                (backgroundStatus.lastGapMs / 60_000L).toInt().coerceAtLeast(1),
                            )
                        },
                    ok = backgroundStatus.gapCount <= 0,
                )
                Text(
                    text =
                        when {
                            running -> stringResource(R.string.takeover_status_verdict_ok)
                            lastScanAt <= 0L -> stringResource(R.string.takeover_status_verdict_none)
                            else -> stringResource(R.string.takeover_status_verdict_bad, scanAgeMin)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (running) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
                Text(
                    text = stringResource(R.string.takeover_status_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                if (!backgroundExempt) {
                    SettingsButton(
                        icon = Lucide.ShieldAlert,
                        title = stringResource(R.string.takeover_background_allow),
                        description = stringResource(R.string.takeover_background_allow_desc),
                        onClick = { TakeoverBackgroundPermission.request(context, force = true) },
                    )
                }
                SettingsButton(
                    icon = Lucide.Settings,
                    title = stringResource(R.string.takeover_background_battery_page),
                    description = stringResource(R.string.takeover_background_battery_page_desc),
                    onClick = { TakeoverBackgroundPermission.openBatteryPage(context) },
                )
            }
        }

        if (deleteFailures > 0 || importFailures > 0) {
            SettingsCategory(title = stringResource(R.string.takeover_section_failures)) {
                if (deleteFailures > 0) {
                    FailureLine(
                        icon = Lucide.ShieldAlert,
                        text = stringResource(R.string.takeover_failure_delete_count, deleteFailures),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (importFailures > 0) {
                    FailureLine(
                        icon = Lucide.ShieldAlert,
                        text = stringResource(R.string.takeover_failure_import_count, importFailures),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SettingsButton(
                    icon = Lucide.Eraser,
                    title = stringResource(R.string.takeover_clear_failures),
                    onClick = { takeoverViewModel.clearFailureRecords() },
                )
            }
        }

        // 末尾留白：底栏是浮在内容之上的透明胶囊，没有这段垫高时
        // 最后一块（失败记录/清除按钮）会被底栏挡住，点不到也看不到。
        // 设置页其它子页都有，唯独这一页漏了 —— 症状就是"最下面点不到"。
        SettingsBottomSpacer()
    }

    if (showPicker) {
        TakeoverAppPickerDialog(
            managedApps = managedApps,
            takeoverViewModel = takeoverViewModel,
            onDismiss = { showPicker = false },
        )
    }
}

/** 状态行：对勾/警告 + 一行说明 */
@Composable
private fun StatusLine(
    text: String,
    ok: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (ok) Lucide.CircleCheck else Lucide.ShieldAlert,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 已接管的一行：图标 + 应用名 + 包名 + 移除按钮（不存在"开关"，移除就是停用） */
@Composable
private fun ManagedAppRow(
    app: ManagedAppEntity,
    active: Boolean,
    onRemove: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                // 总开关关掉后这些应用不再被接管，整行置灰提示状态
                .alpha(if (active) 1f else 0.38f)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(
            packageName = app.packageName,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 一个「✕」看不出是移除接管还是关闭弹窗，补一行小字
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Lucide.X,
                    contentDescription = stringResource(R.string.takeover_remove_app),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            PjmButtonCaption(
                caption = stringResource(R.string.hint_remove),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            )
        }
    }
}

@Composable
private fun FailureLine(
    icon: ImageVector,
    text: String,
    color: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/**
 * 选择应用弹窗。
 *
 * 先显示、再加载：弹窗立刻出现，列表在后台线程读 PackageManager
 * （上百个应用时同步读会明显卡住第一帧）；读取期间显示加载状态。
 * 已接管的包名不重复出现在候选里，勾选后一次性添加。
 */
@Composable
private fun TakeoverAppPickerDialog(
    managedApps: List<ManagedAppEntity>,
    takeoverViewModel: TakeoverViewModel,
    onDismiss: () -> Unit,
) {
    var installed by remember { mutableStateOf<List<InstalledApp>?>(null) }
    LaunchedEffect(Unit) {
        installed = takeoverViewModel.loadInstalledUserApps()
    }
    val managedPackages = remember(managedApps) { managedApps.mapTo(HashSet()) { it.packageName } }
    val available =
        remember(installed, managedPackages) {
            installed?.filter { it.packageName !in managedPackages }.orEmpty()
        }
    var selected by remember { mutableStateOf(emptySet<String>()) }

    PjmAeroDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.takeover_picker_title),
        confirmButton = {
            Button(
                onClick = {
                    selected.forEach { packageName ->
                        available
                            .firstOrNull { it.packageName == packageName }
                            ?.let { takeoverViewModel.addApp(it.packageName, it.label) }
                    }
                    onDismiss()
                },
                enabled = selected.isNotEmpty(),
            ) { Text(stringResource(R.string.takeover_picker_add, selected.size)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            when {
                installed == null ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.takeover_picker_loading), style = MaterialTheme.typography.bodySmall)
                    }

                available.isEmpty() ->
                    Text(
                        stringResource(R.string.takeover_picker_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                else -> {
                    Text(
                        stringResource(R.string.takeover_picker_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(available, key = { it.packageName }) { app ->
                            val checked = app.packageName in selected
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(MaterialTheme.shapes.medium)
                                        .clickable {
                                            selected =
                                                if (checked) {
                                                    selected - app.packageName
                                                } else {
                                                    selected + app.packageName
                                                }
                                        }.padding(vertical = 6.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Spacer(Modifier.width(8.dp))
                                AppIcon(
                                    packageName = app.packageName,
                                    modifier = Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = app.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 应用图标：按行懒加载。
 *
 * 百来个应用的图标如果一次性全解码，会白占几十 MB 内存；这里每行只解自己的，
 * 且只在 IO 线程解码（getApplicationIcon 会读 APK 资源，不是免费操作）。
 * 解码失败（应用已卸载）时留一个同尺寸占位块，行高不跳。
 */
@Composable
private fun AppIcon(
    packageName: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(initialValue = null, packageName) {
        value =
            withContext(Dispatchers.IO) {
                runCatching {
                    val drawable = context.packageManager.getApplicationIcon(packageName)
                    drawable.toBitmap(width = 96, height = 96, config = Bitmap.Config.ARGB_8888).asImageBitmap()
                }.getOrNull()
            }
    }
    val currentIcon = icon
    if (currentIcon != null) {
        Image(bitmap = currentIcon, contentDescription = null, modifier = modifier)
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        ) {}
    }
}
