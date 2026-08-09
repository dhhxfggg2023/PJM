package com.dhhxfggg.pjm.ui.screen

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.CloudUpload
import com.composables.icons.lucide.ShieldCheck
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Video
import com.composables.icons.lucide.Music
import com.composables.icons.lucide.FileArchive
import com.composables.icons.lucide.HardDrive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import com.dhhxfggg.pjm.domain.util.*
import com.dhhxfggg.pjm.ui.viewmodel.MainViewModel
import com.dhhxfggg.pjm.ui.viewmodel.CryptoViewModel
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.video.VideoFrameDecoder
import coil3.request.crossfade
import java.io.File

/**
 * The main entry screen of the application, displaying a summary of the vault's contents.
 *
 * @param mainViewModel The ViewModel for the main screen.
 * @param cryptoViewModel The ViewModel for cryptographic operations, shared across components.
 * @param navController The navigation controller.
 * @param bottomPadding Padding to apply at the bottom, e.g., for navigation bars.
 * @param onNavigateToCategory Callback invoked to navigate to a specific file category.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    mainViewModel: MainViewModel = hiltViewModel(),
    cryptoViewModel: CryptoViewModel = hiltViewModel(LocalActivity.current as ComponentActivity),
    navController: NavHostController,
    bottomPadding: Dp = 0.dp,
    onNavigateToCategory: (String) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val uiState by mainViewModel.uiState.collectAsState()

    var showExportConfirm by remember { mutableStateOf(false) }

    val glassColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
    val glassBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)

    Scaffold(
        containerColor = Color.Transparent
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "PJM 资源柜",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))

            StorageUsageModule(
                totalSize = uiState.totalVaultSize,
                categorySizes = uiState.categorySizes,
                glassColor = glassColor,
                glassBorderColor = glassBorderColor
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                VaultManager.triggerRefresh()
                mainViewModel.refreshCovers()
            }
            
            Spacer(modifier = Modifier.height(16.dp))

            VaultManager.CATEGORIES.forEach { category ->
                val categoryInfo = getCategoryInfo(category)
                val (displayName, icon, color) = categoryInfo
                
                val count = uiState.categoryCounts[category] ?: 0
                val coverFile = remember(uiState.categoryCovers[category]) {
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
                    onClick = { onNavigateToCategory(category) }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = { showExportConfirm = true },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Lucide.CloudUpload, null)
                    Spacer(Modifier.width(12.dp))
                    Text("一键全库加密导出", style = MaterialTheme.typography.titleSmall)
                }
            }
            
            Spacer(modifier = Modifier.height(bottomPadding + 32.dp)) 
        }
    }

    if (showExportConfirm) {
        AlertDialog(
            onDismissRequest = { showExportConfirm = false },
            title = { Text("加密导出") },
            text = { Text("确定要将整个资源柜的所有内容加密导出吗？该操作将生成 PJM 加密分卷文件。") },
            confirmButton = {
                Button(onClick = {
                    showExportConfirm = false
                    mainViewModel.startVaultExport { success ->
                        if (success) {
                            Toast.makeText(context, "全库已加密导出", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("确认导出") }
            },
            dismissButton = {
                TextButton(onClick = { showExportConfirm = false }) { Text("取消") }
            }
        )
    }
}

/**
 * Utility function to retrieve metadata for a given vault category.
 */
@Composable
fun getCategoryInfo(category: String): Triple<String, ImageVector, Color> {
    val primaryColor = MaterialTheme.colorScheme.primary
    return remember(category, primaryColor) {
        when (category) {
            "pjm" -> Triple("PJM 归档", Lucide.ShieldCheck, primaryColor)
            "images" -> Triple("相册照片", Lucide.Image, Color(0xFF4CAF50))
            "videos" -> Triple("视频影像", Lucide.Video, Color(0xFFFF9800))
            "audios" -> Triple("音乐音频", Lucide.Music, Color(0xFFE91E63))
            else -> Triple("其它杂项", Lucide.FileArchive, Color(0xFF607D8B))
        }
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
    onRefresh: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onRefresh,
        colors = CardDefaults.cardColors(containerColor = glassColor),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, glassBorderColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.HardDrive, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("保险库占用详情", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(FileUtils.formatFileSize(totalSize), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
            }
            
            Spacer(Modifier.height(12.dp))
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    VaultManager.CATEGORIES.forEach { category ->
                        val size = categorySizes[category] ?: 0L
                        if (size > 0 && totalSize > 0) {
                            val weight = size.toFloat() / totalSize
                            val categoryInfo = getCategoryInfo(category)
                            val color = categoryInfo.third
                            
                            val animatedWeight by animateFloatAsState(
                                targetValue = weight,
                                animationSpec = spring(stiffness = Spring.StiffnessLow),
                                label = "weight_$category"
                            )
                            
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .weight(animatedWeight.coerceAtLeast(0.001f))
                                    .background(color)
                            )
                        }
                    }
                }
            }
            
            Spacer(Modifier.height(12.dp))
            
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                VaultManager.CATEGORIES.forEach { category ->
                    val size = categorySizes[category] ?: 0L
                    if (size > 0) {
                        val categoryInfo = getCategoryInfo(category)
                        val name = categoryInfo.first
                        val color = categoryInfo.third
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "$name ${FileUtils.formatFileSize(size)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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
    content: @Composable () -> Unit
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier,
        horizontalArrangement = horizontalArrangement,
        verticalArrangement = verticalArrangement,
        content = { content() }
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
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().height(90.dp),
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = glassColor),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, glassBorderColor)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (latestFile != null && latestFile.exists() && (FileUtils.isImageFile(latestFile.name) || FileUtils.isVideoFile(latestFile.name))) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(latestFile)
                        .decoderFactory(VideoFrameDecoder.Factory())
                        .size(400, 400) 
                        .crossfade(true)
                        .build(),
                    contentDescription = null, 
                    modifier = Modifier.matchParentSize().graphicsLayer(alpha = 0.55f), 
                    contentScale = ContentScale.Crop
                )
            }
            Row(
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = accentColor.copy(alpha = 0.15f),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) { 
                        Icon(icon, null, tint = accentColor) 
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(text = name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(text = "共 $count 个加密项", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
