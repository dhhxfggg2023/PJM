package com.dhhxfggg.pjm.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.data.model.Settings
import com.dhhxfggg.pjm.domain.shizuku.EmbeddedPrivilegedIo
import com.dhhxfggg.pjm.domain.util.AudioSettings
import com.dhhxfggg.pjm.domain.util.FileUtils
import com.dhhxfggg.pjm.ui.component.PjmAeroDialog
import com.dhhxfggg.pjm.ui.component.PjmSlider
import com.dhhxfggg.pjm.ui.theme.PresetAmber
import com.dhhxfggg.pjm.ui.theme.PresetBiliPink
import com.dhhxfggg.pjm.ui.theme.PresetDustyBlue
import com.dhhxfggg.pjm.ui.theme.PresetForest
import com.dhhxfggg.pjm.ui.theme.PresetLavender
import com.dhhxfggg.pjm.ui.theme.PresetRose
import com.dhhxfggg.pjm.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** 目录递归求和：诊断页显示缓存 / 日志占用 */
private fun dirSizeBytes(dir: File?): Long = dir?.listFiles()?.sumOf { if (it.isFile) it.length() else dirSizeBytes(it) } ?: 0L

/** 均衡器面板：5 根纵向推子（拖动时不会被页面滚动抢走手势） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EqualizerPanel(
    gains: MutableList<Float>,
    onDismiss: () -> Unit,
    onCommit: (Int, Float) -> Unit,
    onReset: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.eq_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                // 一键把 5 段推子全部归零（±0 dB），不用一段一段拖回去
                TextButton(onClick = onReset) { Text(stringResource(R.string.eq_reset)) }
            }
            // 推子长度跟着可用空间走：竖向推子太短根本没法微调，所以至少 220dp
            BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                // 面板整体占屏约 0.6，推子只占其中一段：太高反而不方便拇指够到顶
                val travel = (maxHeight * 0.45f).coerceIn(200.dp, 300.dp)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AudioSettings.BAND_FREQS.indices.forEach { index ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = (if (gains[index] >= 0f) "+" else "") + gains[index].toInt().toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Box(
                                modifier = Modifier.height(travel).width(56.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                PjmSlider(
                                    value = gains[index],
                                    valueRange = -12f..12f,
                                    onValueChange = { gains[index] = it },
                                    onValueChangeFinished = { onCommit(index, gains[index]) },
                                    // 必须用 requiredWidth：父 Box 只有 56dp 宽，普通 width(travel)
                                    // 会被父约束夹成 56dp —— 之前只把外框拉高了，里面那根线一直没变长。
                                    modifier = Modifier.requiredWidth(travel).rotate(-90f),
                                )
                            }
                            Text(
                                text = AudioSettings.BAND_LABELS[index],
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Text(
                text = stringResource(R.string.eq_band_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
internal fun MainCategoryList(
    settings: Settings.AppSettings,
    settingsViewModel: SettingsViewModel,
    managedAppCount: Int,
    onNavigate: (SettingsPage) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // 「通用」：日常最常改的两项（外观 + 接管），按大多数软件的层级习惯放在最上面
        SettingsCategory(title = stringResource(R.string.settings_group_general)) {
            SettingsButton(
                icon = Lucide.Palette,
                title = stringResource(R.string.settings_title_appearance),
                description =
                    stringResource(
                        R.string.settings_desc_appearance_summary,
                        settings.theme,
                        "%.2f".format(settings.globalUiScale),
                    ),
                onClick = { onNavigate(SettingsPage.Appearance) },
                accent = PresetLavender,
            )
            SettingsButton(
                icon = Lucide.Speaker,
                title = stringResource(R.string.settings_title_audio_playback),
                description = stringResource(R.string.settings_desc_audio_playback),
                onClick = { onNavigate(SettingsPage.Audio) },
                accent = PresetRose,
            )
            SettingsButton(
                icon = Lucide.Smartphone,
                title = stringResource(R.string.settings_title_app_takeover),
                description =
                    if (managedAppCount > 0) {
                        stringResource(R.string.settings_desc_app_takeover_summary, managedAppCount)
                    } else {
                        stringResource(R.string.settings_desc_app_takeover_off)
                    },
                onClick = { onNavigate(SettingsPage.AppsTakeover) },
                accent = PresetDustyBlue,
            )
            SettingsButton(
                icon = Lucide.MonitorSmartphone,
                title = stringResource(R.string.settings_title_transfer),
                description = stringResource(R.string.settings_desc_transfer),
                onClick = { onNavigate(SettingsPage.Transfer) },
                accent = PresetForest,
            )
        }
        // 「内容与导入」：决定"文件怎么进库"
        SettingsCategory(title = stringResource(R.string.settings_group_content)) {
            SettingsButton(
                icon = Lucide.Database,
                title = stringResource(R.string.settings_title_auto_ingest),
                description =
                    stringResource(
                        R.string.settings_desc_auto_delete_grid,
                        if (settings.autoDeleteOriginal) stringResource(R.string.toggle_on) else stringResource(R.string.toggle_off),
                        settings.gridSpanCount,
                    ),
                onClick = { onNavigate(SettingsPage.Ingestion) },
                accent = PresetForest,
            )
            SettingsButton(
                icon = Lucide.MonitorPlay,
                title = stringResource(R.string.settings_title_bili_scan_strategy),
                description =
                    stringResource(
                        R.string.settings_desc_bili_auto_delete_summary,
                        if (settings.biliAutoDelete) stringResource(R.string.toggle_on) else stringResource(R.string.toggle_off),
                    ),
                onClick = { onNavigate(SettingsPage.Bilibili) },
                accent = PresetBiliPink,
            )
            SettingsButton(
                icon = Lucide.Play,
                title = stringResource(R.string.settings_title_discovery),
                description = stringResource(R.string.settings_desc_discovery),
                onClick = { onNavigate(SettingsPage.Discovery) },
                accent = PresetLavender,
            )
        }
        // 「数据与维护」：诊断、清理、校验
        SettingsCategory(title = stringResource(R.string.settings_group_data)) {
            SettingsButton(
                icon = Lucide.Wrench,
                title = stringResource(R.string.settings_title_diag_maintenance),
                description = stringResource(R.string.settings_desc_diag_maintenance),
                onClick = { onNavigate(SettingsPage.Maintenance) },
                accent = PresetAmber,
            )
        }
        // 列表铺到屏幕底部（底栏是透明的），末尾补出底栏高度，
        // 保证「系统诊断与维护」这类最后一项仍能完整滚出来、点得到
        Spacer(Modifier.height(24.dp))
        SettingsBottomSpacer()
    }
}

/** 音频播放设置：5 段均衡器 + 应用内音量（全局生效，后台助眠也吃这套） */
@Composable
internal fun AudioPlaybackSettings() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(AudioSettings.isEqualizerEnabled(context)) }
    var volume by remember { mutableFloatStateOf(AudioSettings.appVolume(context)) }
    val gains =
        remember {
            mutableStateListOf<Float>().apply {
                AudioSettings.BAND_FREQS.indices.forEach { add(AudioSettings.bandGainDb(context, it)) }
            }
        }
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsCategory(title = stringResource(R.string.settings_cat_audio)) {
            SettingsSwitch(
                icon = Lucide.SlidersHorizontal,
                title = stringResource(R.string.audio_eq_enabled),
                description = stringResource(R.string.audio_eq_hint),
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    AudioSettings.setEqualizerEnabled(context, it)
                },
                accent = PresetLavender,
            )
            // 5 段均衡器改成独立面板里的纵向推子：列表里一长排横向滑块，滚动时手指
            // 滚动时很容易被误触到就把频段改了。面板里没有滚动，推子互不干扰。
            var showEqPanel by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text =
                        AudioSettings.BAND_LABELS.indices.joinToString("  ") { index ->
                            AudioSettings.BAND_LABELS[index] + " " + gains[index].toInt().toString()
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showEqPanel = true }) { Text(stringResource(R.string.eq_adjust)) }
            }
            if (showEqPanel) {
                EqualizerPanel(
                    gains = gains,
                    onDismiss = { showEqPanel = false },
                    onCommit = { index, value -> AudioSettings.setBandGainDb(context, index, value) },
                    onReset = {
                        for (index in gains.indices) {
                            gains[index] = 0f
                            AudioSettings.setBandGainDb(context, index, 0f)
                        }
                    },
                )
            }
        }
        SettingsCategory(title = stringResource(R.string.settings_cat_volume)) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Text(
                    text = stringResource(R.string.audio_app_volume) + "  " + (volume * 100).toInt() + "%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PjmSlider(
                    value = volume,
                    valueRange = 0f..1f,
                    onValueChange = { volume = it },
                    onValueChangeFinished = { AudioSettings.setAppVolume(context, volume) },
                )
            }
        }
        SettingsBottomSpacer()
    }
}

/** 发现页设置：默认分类（图片 / 视频 / B站视频 / 音频），和其它设置页保持同一套层级 */
@Composable
internal fun DiscoverySettings(
    settings: Settings.AppSettings,
    settingsViewModel: SettingsViewModel,
) {
    val options =
        listOf(
            "images" to R.string.cat_short_images,
            "videos" to R.string.cat_short_videos,
            "bili_videos" to R.string.cat_short_bili_videos,
            "audios" to R.string.cat_short_audios,
        )
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsCategory(title = stringResource(R.string.discovery_default_cat)) {
            Text(
                text = stringResource(R.string.discovery_default_cat_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                // 原来四个选项被 weight(1f) 拉满宽度，又宽又扁。改成按内容宽度、居中排布。
                options.chunked(2).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    ) {
                        // 同一行两个选项宽度对半分：四个按钮大小一致，不会一个宽一个窄
                        row.forEach { (value, labelRes) ->
                            FilterChip(
                                selected = settings.discoveryDefaultMode == value,
                                onClick = { settingsViewModel.updateSetting(Settings.KEY_DISCOVERY_DEFAULT_MODE, value) },
                                label = { Text(stringResource(labelRes)) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
        SettingsCategory(title = stringResource(R.string.discovery_random_rules)) {
            Text(
                text = stringResource(R.string.discovery_random_rules_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        SettingsBottomSpacer()
    }
}

@Composable
internal fun AppearanceSettings(
    settings: Settings.AppSettings,
    settingsViewModel: SettingsViewModel,
    onSelectBg: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsCategory(title = stringResource(R.string.settings_cat_interaction)) {
            SettingsSwitch(
                icon = Lucide.ArrowLeft,
                title = stringResource(R.string.settings_title_predictive_back),
                description = stringResource(R.string.settings_desc_predictive_back),
                checked = settings.predictiveBackEnabled,
                onCheckedChange = { settingsViewModel.updateSetting(Settings.KEY_PREDICTIVE_BACK, it) },
            )
        }
        SettingsCategory(title = stringResource(R.string.settings_cat_theme_colors)) {
            SettingsOptionLabel(stringResource(R.string.settings_label_theme))
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(
                    "system" to stringResource(R.string.theme_system),
                    "light" to stringResource(R.string.theme_light),
                    "dark" to stringResource(R.string.theme_dark),
                ).forEach { (key, label) ->
                    FilterChip(selected = settings.theme == key, onClick = {
                        settingsViewModel.updateSetting(Settings.KEY_THEME, key)
                    }, label = { Text(label) })
                }
            }
            // Android 12+ 壁纸动态取色开关：关闭后使用内置海洋蓝莫兰迪配色
            SettingsSwitch(
                icon = Lucide.Palette,
                title = stringResource(R.string.settings_title_dynamic_color),
                description = stringResource(R.string.settings_desc_dynamic_color),
                checked = settings.isDynamicColorEnabled,
                onCheckedChange = { settingsViewModel.updateSetting(Settings.KEY_DYNAMIC_COLOR, it) },
            )
        }
        SettingsCategory(title = stringResource(R.string.settings_cat_frosted_background)) {
            SettingsSwitch(
                icon = Lucide.Image,
                title =
                    stringResource(
                        R.string.settings_title_custom_bg,
                    ),
                checked = settings.isCustomBackgroundEnabled,
                onCheckedChange = {
                    settingsViewModel.updateSetting(Settings.KEY_CUSTOM_BACKGROUND_ENABLED, it)
                },
            )
            if (settings.isCustomBackgroundEnabled) {
                SettingsButton(
                    icon = Lucide.Images,
                    title = stringResource(R.string.settings_title_select_bg),
                    description = stringResource(R.string.settings_desc_select_bg),
                    onClick = onSelectBg,
                )
                SettingsStepper(
                    icon = Lucide.Contrast,
                    title =
                        stringResource(
                            R.string.settings_title_bg_opacity,
                        ),
                    value = settings.backgroundOpacity,
                    step = 0.05f,
                    onValueChange = {
                        settingsViewModel.updateSetting(Settings.KEY_BACKGROUND_OPACITY, it)
                    },
                )
            }
        }
        SettingsCategory(title = stringResource(R.string.settings_cat_canvas_scale)) {
            SettingsStepper(
                icon = Lucide.Maximize,
                title = stringResource(R.string.settings_title_ui_scale),
                value = settings.globalUiScale,
                valueRange =
                    0.8f..1.2f,
                step = 0.01f,
                onValueChange = { settingsViewModel.updateSetting(Settings.KEY_GLOBAL_UI_SCALE, it) },
            )
        }
        // 「库封面」：首页分类卡片上的那张随机封面图
        SettingsCategory(title = stringResource(R.string.settings_cat_vault_cover)) {
            SettingsSwitch(
                icon = Lucide.Image,
                title = stringResource(R.string.settings_title_random_cover),
                description = stringResource(R.string.settings_desc_random_cover),
                checked = settings.coverEnabled,
                onCheckedChange = { settingsViewModel.updateSetting(Settings.KEY_COVER_ENABLED, it) },
            )
            if (settings.coverEnabled) {
                SettingsOptionLabel(stringResource(R.string.settings_label_cover_refresh))
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf("daily" to R.string.cover_refresh_daily, "each" to R.string.cover_refresh_each).forEach { (key, labelRes) ->
                        FilterChip(
                            selected = settings.coverRefresh == key,
                            onClick = { settingsViewModel.updateSetting(Settings.KEY_COVER_REFRESH, key) },
                            label = { Text(stringResource(labelRes)) },
                        )
                    }
                }
            }
        }
        SettingsBottomSpacer()
    }
}

@Composable
internal fun IngestionSettings(
    settings: Settings.AppSettings,
    settingsViewModel: SettingsViewModel,
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsCategory(title = stringResource(R.string.settings_cat_view_config)) {
            SettingsOptionLabel(stringResource(R.string.settings_label_grid_columns))
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(1, 2, 3, 4, 6).forEach { count ->
                    FilterChip(selected = settings.gridSpanCount == count, onClick = {
                        settingsViewModel.updateSetting(Settings.KEY_GRID_SPAN_COUNT, count)
                    }, label = { Text(count.toString()) })
                }
            }
        }
        SettingsCategory(title = stringResource(R.string.settings_cat_auto_rules)) {
            SettingsSwitch(
                icon = Lucide.Trash2,
                title =
                    stringResource(
                        R.string.setting_auto_delete_original,
                    ),
                description =
                    stringResource(
                        R.string.setting_auto_delete_original_desc,
                    ),
                checked = settings.autoDeleteOriginal,
                onCheckedChange = {
                    settingsViewModel.updateSetting(Settings.KEY_AUTO_DELETE_ORIGINAL, it)
                },
            )
            SettingsSwitch(
                icon = Lucide.FileUp,
                title =
                    stringResource(
                        R.string.settings_title_auto_extract,
                    ),
                description =
                    stringResource(
                        R.string.settings_desc_auto_extract,
                    ),
                checked = settings.isArchiveAutoExtractionEnabled,
                onCheckedChange = {
                    settingsViewModel.updateSetting(Settings.KEY_ARCHIVE_AUTO_EXTRACTION, it)
                },
            )
        }
        SettingsCategory(title = stringResource(R.string.settings_cat_split_policy)) {
            SettingsOptionLabel(stringResource(R.string.settings_title_split_size))
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(500 to "500MB", 1024 to "1GB", 2048 to "2GB", 4096 to "4GB").forEach { (size, label) ->
                    FilterChip(selected = settings.exportSplitSize == size, onClick = {
                        settingsViewModel.updateSetting(Settings.KEY_EXPORT_SPLIT_SIZE, size)
                    }, label = { Text(label) })
                }
            }
        }
        SettingsBottomSpacer()
    }
}

@Composable
internal fun BilibiliSettings(
    settings: Settings.AppSettings,
    settingsViewModel: SettingsViewModel,
    onOpenPicker: () -> Unit,
    onOpenMergedPicker: () -> Unit,
) {
    val context = LocalContext.current
    var showPrivilegedAdbDialog by remember { mutableStateOf(false) }
    // 文案在组合期取好：用 LocalContext.getString 读资源不会跟随语言/配置变化
    // （lint 的 LocalContextResources）；下面的回调里直接用这几个值。
    val privilegedReadyMsg = stringResource(R.string.toast_privileged_ready_all)
    val privilegedNotRunningMsg = stringResource(R.string.toast_privileged_not_running)
    val authRecordsDestroyedMsg = stringResource(R.string.toast_auth_records_destroyed)
    val copiedMsg = stringResource(R.string.toast_copied_to_clipboard)

    // 检测 PJM 内置特权服务（shell 身份）是否在线
    val privilegedReady by produceState(initialValue = false) {
        while (true) {
            value = EmbeddedPrivilegedIo.isAvailable(context)
            delay(3000)
        }
    }
    // 状态变化时用横幅提示（就绪/未运行），不做成按钮
    var lastPrivilegedReady by remember { mutableStateOf(false) }
    LaunchedEffect(privilegedReady) {
        if (privilegedReady != lastPrivilegedReady) {
            lastPrivilegedReady = privilegedReady
            val msg = if (privilegedReady) privilegedReadyMsg else privilegedNotRunningMsg
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsCategory(title = stringResource(R.string.settings_cat_privileged_access)) {
            // 状态卡（非按钮，仅展示）+ 横幅提示
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor =
                            if (privilegedReady) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                    ),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Lucide.ShieldCheck,
                        null,
                        tint = if (privilegedReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            if (privilegedReady) {
                                stringResource(R.string.settings_status_privileged_ready)
                            } else {
                                stringResource(R.string.settings_status_privileged_not_running)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            if (privilegedReady) {
                                stringResource(R.string.settings_desc_privileged_ready_detail)
                            } else {
                                stringResource(R.string.settings_desc_privileged_not_running_detail)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // 电脑 adb 启动命令（带设备序列号，避免开着模拟器时发错设备）
            SettingsButton(
                icon = Lucide.Terminal,
                title = stringResource(R.string.settings_title_adb_start_service),
                description = stringResource(R.string.settings_desc_adb_start_service),
                onClick = {
                    EmbeddedPrivilegedIo.prepareStartScript(context)
                    showPrivilegedAdbDialog = true
                },
            )
        }
        SettingsCategory(title = stringResource(R.string.settings_cat_data_access)) {
            // 导入未合并视频（选择目录，识别并合并 B站 分离的 m4s 音视频）
            SettingsButton(
                icon = Lucide.FolderDown,
                title = stringResource(R.string.settings_title_import_unmerged),
                description = stringResource(R.string.settings_desc_import_unmerged),
                onClick = onOpenPicker,
                onLongClick = {
                    context.contentResolver.persistedUriPermissions.forEach {
                        try {
                            context.contentResolver.releasePersistableUriPermission(
                                it.uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                            )
                        } catch (_: Exception) {
                        }
                    }
                    settingsViewModel.updateSetting(Settings.KEY_BILI_ROOT_URI, null)
                    Toast.makeText(context, authRecordsDestroyedMsg, Toast.LENGTH_SHORT).show()
                },
            )
            SettingsButton(
                icon = Lucide.Clapperboard,
                title = stringResource(R.string.settings_title_bili_merged_import),
                description = stringResource(R.string.settings_desc_bili_merged_import),
                onClick = onOpenMergedPicker,
            )
        }
        SettingsCategory(title = stringResource(R.string.settings_cat_post_process)) {
            // 核心修复：两个自动删除开关合并为一个 —— 未合并/已合并视频导入后统一删除源文件
            SettingsSwitch(
                icon = Lucide.Eraser,
                title = stringResource(R.string.settings_title_bili_auto_delete),
                description = stringResource(R.string.settings_desc_bili_auto_delete),
                checked = settings.biliAutoDelete,
                onCheckedChange = { checked ->
                    // 同时同步两个键，保证未合并/已合并两条导入路径行为一致
                    settingsViewModel.updateSetting(Settings.KEY_BILI_AUTO_DELETE, checked)
                    settingsViewModel.updateSetting(Settings.KEY_BILI_MERGED_AUTO_DELETE, checked)
                },
            )
        }
        SettingsBottomSpacer()
    }

    // 电脑 adb 启动 PJM 内置特权服务命令对话框（命令带设备序列号，避免模拟器混淆）
    if (showPrivilegedAdbDialog) {
        // 先弹窗、再算命令：取设备序列号要走系统调用，不能挡在弹窗第一帧前面
        val adbCommand by produceState(initialValue = "") {
            value = withContext(Dispatchers.IO) { EmbeddedPrivilegedIo.getStartCommand(context) }
        }
        PjmAeroDialog(
            onDismissRequest = { showPrivilegedAdbDialog = false },
            title = stringResource(R.string.settings_title_adb_start_service_dialog),
            confirmButton = {
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("pjm_start", adbCommand))
                        showPrivilegedAdbDialog = false
                        Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                    },
                    enabled = adbCommand.isNotEmpty(),
                ) { Text(stringResource(R.string.action_copy_command)) }
            },
            dismissButton = { TextButton(onClick = { showPrivilegedAdbDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) {
            Column {
                Text(stringResource(R.string.dialog_label_steps), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.dialog_msg_adb_steps), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(
                                MaterialTheme.shapes.medium,
                            ).background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(12.dp),
                ) {
                    Text(adbCommand, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.dialog_msg_adb_multi_device_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun MaintenanceSettings(
    settingsViewModel: SettingsViewModel,
    onCheckUpdate: () -> Unit,
    onCheckIntegrity: () -> Unit,
    onExportLogs: () -> Unit,
    onClearCache: () -> Unit,
    onClearLogs: () -> Unit,
    onFactoryReset: () -> Unit,
    onRandomShare: () -> Unit,
    refreshToken: Int = 0,
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsCategory(title = stringResource(R.string.settings_cat_asset_maintenance)) {
            SettingsButton(
                icon = Lucide.SquareCheck,
                title =
                    stringResource(
                        R.string.settings_title_clean_duplicates,
                    ),
                description = stringResource(R.string.settings_desc_clean_duplicates),
                onClick = {
                    settingsViewModel.scanForDuplicates()
                },
            )
            SettingsButton(
                icon = Lucide.ShieldCheck,
                title = stringResource(R.string.settings_title_integrity_check),
                description = stringResource(R.string.settings_desc_integrity_check),
                onClick = onCheckIntegrity,
            )
        }
        SettingsCategory(title = stringResource(R.string.settings_cat_update)) {
            SettingsButton(
                icon = Lucide.Download,
                title = stringResource(R.string.settings_title_check_update),
                description = stringResource(R.string.settings_desc_check_update),
                onClick = onCheckUpdate,
            )
        }
        SettingsCategory(title = stringResource(R.string.settings_cat_random_share)) {
            SettingsButton(
                icon = Lucide.Shuffle,
                title = stringResource(R.string.settings_title_random_share),
                description = stringResource(R.string.settings_desc_random_share),
                onClick = onRandomShare,
            )
        }
        SettingsCategory(title = stringResource(R.string.settings_cat_diagnostics)) {
            val ctx = LocalContext.current
            // 显示各自现在占多大：缓存 = 缩略图 + 临时文件 + 阅读器分页缓存；日志 = files/logs
            // 显示的只是「点一下能被清掉」的部分：cacheDir 里的临时文件与阅读器分页缓存。
            // 缩略图（filesDir/thumbnails、filesDir/pjm_thumbnail_cache）不在这里，也不会被「清除缓存」删除：
            // 它们由 ThumbnailCache 跟着源文件走，源文件删掉时同步删除，不需要用户手动清。
            val cacheBytes by
                produceState(0L, refreshToken) {
                    while (true) {
                        value = withContext(Dispatchers.IO) { dirSizeBytes(ctx.cacheDir) }
                        delay(2000)
                    }
                }
            val logBytes by
                produceState(0L, refreshToken) {
                    while (true) {
                        value = withContext(Dispatchers.IO) { dirSizeBytes(File(ctx.filesDir, "logs")) }
                        delay(2000)
                    }
                }
            // 核心修复：清除缓存与清除日志分离
            SettingsButton(
                icon = Lucide.Brush,
                title = stringResource(R.string.settings_title_clear_cache_all),
                description = stringResource(R.string.settings_size_now, stringResource(R.string.settings_desc_clear_cache_only_tmp), FileUtils.formatFileSize(cacheBytes)),
                onClick = onClearCache,
            )
            SettingsButton(
                icon = Lucide.FileX,
                title = stringResource(R.string.settings_title_clear_logs),
                description = stringResource(R.string.settings_size_now, stringResource(R.string.settings_desc_clear_logs), FileUtils.formatFileSize(logBytes)),
                onClick = onClearLogs,
            )
            SettingsButton(icon = Lucide.Bug, title = stringResource(R.string.settings_title_export_logs), onClick = onExportLogs)
            SettingsButton(icon = Lucide.RotateCcw, title = stringResource(R.string.settings_title_factory_reset), onClick = onFactoryReset)
        }
        SettingsBottomSpacer()
    }
}
