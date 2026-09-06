package com.dhhxfggg.pjm.ui.screen

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.dhhxfggg.pjm.ui.theme.PresetRose
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

    val glassColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
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
                    latestFile = coverFile,
                    glassColor = glassColor,
                    glassBorderColor = glassBorderColor,
                    accentColor = color,
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
        VaultManager.CAT_AUDIOS -> Triple(stringResource(R.string.cat_audios_display), iconPack.catAudios, PresetRose)
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
 */
@Composable
fun VaultCategoryCard(
    name: String,
    icon: ImageVector,
    count: Int,
    latestFile: File?,
    glassColor: Color,
    glassBorderColor: Color,
    accentColor: Color,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().height(90.dp),
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
                    modifier = Modifier.matchParentSize().graphicsLayer(alpha = 0.55f),
                    contentScale = ContentScale.Crop,
                )
            }
            Row(
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    color = accentColor.copy(alpha = 0.15f),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = accentColor)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(text = name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(text = stringResource(R.string.label_category_count, count), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
