package com.dhhxfggg.pjm.ui.screen

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import com.composables.icons.lucide.HardDrive
import com.composables.icons.lucide.Lucide
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.domain.util.*
import com.dhhxfggg.pjm.ui.theme.PresetAmber
import com.dhhxfggg.pjm.ui.theme.PresetBiliPink
import com.dhhxfggg.pjm.ui.theme.PresetDustyBlue
import com.dhhxfggg.pjm.ui.theme.PresetForest
import com.dhhxfggg.pjm.ui.theme.PresetLavender
import com.dhhxfggg.pjm.ui.theme.rememberIconPack
import com.dhhxfggg.pjm.ui.viewmodel.MainViewModel
import java.io.File

/**
 * The main entry screen of the application, displaying a summary of the vault's contents.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    mainViewModel: MainViewModel = hiltViewModel(),
    bottomPadding: Dp = 0.dp,
    onNavigateToCategory: (String) -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val uiState by mainViewModel.uiState.collectAsState()

    val iconPack = rememberIconPack()

    // 可读性修复：原为 alpha 0.7，在浅色花纹壁纸下卡片与背景糊在一起。
    // 提到 0.82 后既保留玻璃质感，内容区又足够清晰。
    val glassColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
    val glassBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)

    Scaffold(
        containerColor = Color.Transparent,
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.main_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(16.dp))

            StorageUsageModule(
                totalSize = uiState.totalVaultSize,
                categorySizes = uiState.categorySizes,
                glassColor = glassColor,
                glassBorderColor = glassBorderColor,
                iconPack = iconPack,
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                VaultManager.triggerRefresh()
                mainViewModel.refreshCovers()
            }

            Spacer(modifier = Modifier.height(16.dp))

            VaultManager.CATEGORIES.forEach { category ->
                val categoryInfo = getCategoryInfo(category, iconPack)
                val (displayName, icon, color) = categoryInfo

                val count = uiState.categoryCounts[category] ?: 0
                val coverFile =
                    remember(uiState.categoryCovers[category]) {
                        uiState.categoryCovers[category]?.let { VaultManager.getFileFromEntity(context, it) }
                    }

                VaultCategoryCard(
                    name = displayName,
                    icon = icon,
                    count = count,
                    sizeText = uiState.categorySizes[category]?.let { FileUtils.formatFileSize(it) }.orEmpty(),
                    latestFile = coverFile,
                    glassColor = glassColor,
                    glassBorderColor = glassBorderColor,
                    accentColor = color,
                    chevron = iconPack.actionChevron,
                    onClick = { onNavigateToCategory(category) },
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            Spacer(modifier = Modifier.height(bottomPadding + 32.dp))
        }
    }
}

@Composable
fun getCategoryInfo(
    category: String,
    iconPack: com.dhhxfggg.pjm.ui.theme.IconPack,
): Triple<String, ImageVector, Color> {
    val primaryColor = MaterialTheme.colorScheme.primary
    return when (category) {
        VaultManager.CAT_PJM -> Triple(stringResource(R.string.cat_pjm_display), iconPack.catPjm, primaryColor)
        VaultManager.CAT_BILI_VIDEOS -> Triple("B站视频", iconPack.catBiliVideos, PresetBiliPink)
        VaultManager.CAT_IMAGES -> Triple(stringResource(R.string.cat_images_display), iconPack.catImages, PresetForest)
        VaultManager.CAT_VIDEOS -> Triple(stringResource(R.string.cat_videos_display), iconPack.catVideos, PresetAmber)
        // 核心修复：音频库原先用 PresetRose(#FF6B9C)，与 B站视频的 PresetBiliPink(#FB7299)
        // 几乎同色 —— 两张分类卡并排时看起来像重复的分类。改用淡紫，六个分类颜色即可完全区分。
        VaultManager.CAT_AUDIOS -> Triple(stringResource(R.string.cat_audios_display), iconPack.catAudios, PresetLavender)
        else -> Triple(stringResource(R.string.cat_others_display), iconPack.catOthers, PresetDustyBlue)
    }
}

/**
 * Displays an overview of storage usage by category.
 */
@Composable
fun StorageUsageModule(
    totalSize: Long,
    categorySizes: Map<String, Long>,
    glassColor: Color,
    glassBorderColor: Color,
    iconPack: com.dhhxfggg.pjm.ui.theme.IconPack,
    onRefresh: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onRefresh,
        colors = CardDefaults.cardColors(containerColor = glassColor),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, glassBorderColor),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.HardDrive, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.label_vault_usage), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(FileUtils.formatFileSize(totalSize), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
            }

            Spacer(Modifier.height(12.dp))

            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)),
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    VaultManager.CATEGORIES.forEach { category ->
                        val size = categorySizes[category] ?: 0L
                        if (size > 0 && totalSize > 0) {
                            val weight = size.toFloat() / totalSize
                            val categoryInfo = getCategoryInfo(category, iconPack)
                            val color = categoryInfo.third

                            val animatedWeight by animateFloatAsState(
                                targetValue = weight,
                                animationSpec = spring(stiffness = Spring.StiffnessLow),
                                label = "weight_$category",
                            )

                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxHeight()
                                        .weight(animatedWeight.coerceAtLeast(0.001f))
                                        .background(color),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                VaultManager.CATEGORIES.forEach { category ->
                    val size = categorySizes[category] ?: 0L
                    if (size > 0) {
                        val categoryInfo = getCategoryInfo(category, iconPack)
                        val name = categoryInfo.first
                        val color = categoryInfo.third
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "$name ${FileUtils.formatFileSize(size)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable () -> Unit,
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier,
        horizontalArrangement = horizontalArrangement,
        verticalArrangement = verticalArrangement,
        content = { content() },
    )
}

/**
 * A card representing a category in the vault.
 *
 * 视觉改版：图标底从「主色 15% 淡色块」改为「主色渐变实心块 + 白色图标」，
 * 右侧补一个箭头，副标题同时给出文件数与占用体积 —— 三处改动让卡片有明确的
 * 「可点击 → 进入分类」的视觉引导，而不是一块静态色块。
 */
@Composable
fun VaultCategoryCard(
    name: String,
    icon: ImageVector,
    count: Int,
    sizeText: String,
    latestFile: File?,
    glassColor: Color,
    glassBorderColor: Color,
    accentColor: Color,
    chevron: ImageVector,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().height(84.dp),
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = glassColor),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, glassBorderColor),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (latestFile != null &&
                latestFile.exists() &&
                (FileUtils.isImageFile(latestFile.name) || FileUtils.isVideoFile(latestFile.name))
            ) {
                // 核心优化：用 remember 缓存 ImageRequest 对象，避免每次重组重建；
                // 并结合 .size(400x400) 限制解码尺寸，避免封面解码整张原图（OOM 风险）。
                val context = LocalContext.current
                val coverRequest =
                    remember(latestFile, context) {
                        ImageRequest
                            .Builder(context)
                            .data(latestFile)
                            .decoderFactory(VideoFrameDecoder.Factory())
                            .size(400, 400)
                            .crossfade(enable = true)
                            .build()
                    }
                AsyncImage(
                    model = coverRequest,
                    contentDescription = null,
                    modifier = Modifier.matchParentSize().graphicsLayer(alpha = 0.5f),
                    contentScale = ContentScale.Crop,
                )
            }
            Row(
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 彩色渐变图标底（白图标）：比淡色底更有分量，也让六个分类一眼可辨
                Box(
                    modifier =
                        Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(accentColor, accentColor.copy(alpha = 0.70f)),
                                ),
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    val countLabel = stringResource(R.string.label_category_count, count)
                    Text(
                        text = if (sizeText.isNotEmpty()) "$countLabel · $sizeText" else countLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = chevron,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
