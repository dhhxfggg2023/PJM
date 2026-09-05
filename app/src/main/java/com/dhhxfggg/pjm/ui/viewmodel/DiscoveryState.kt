package com.dhhxfggg.pjm.ui.viewmodel

import androidx.compose.runtime.Immutable
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.domain.util.VaultManager
import java.io.File

/**
 * Sealed class representing items that can be discovered in the vault.
 */
@Immutable
sealed class DiscoveryItem {
    abstract val displayId: Long
    abstract val entity: FileEntity
    abstract val file: File

    data class Image(
        override val displayId: Long,
        override val file: File,
        override val entity: FileEntity
    ) : DiscoveryItem()

    data class Video(
        override val displayId: Long,
        override val file: File,
        override val entity: FileEntity
    ) : DiscoveryItem()
}

/**
 * Enum for discovery modes.
 */
enum class DiscoveryMode(val value: String) {
    BILI_VIDEOS(VaultManager.CAT_BILI_VIDEOS),
    IMAGES(VaultManager.CAT_IMAGES),
    VIDEOS(VaultManager.CAT_VIDEOS)
}

/**
 * UI State for the Discovery Screen.
 */
@Immutable
data class DiscoveryUiState(
    val items: List<DiscoveryItem> = emptyList(),
    val mode: DiscoveryMode = DiscoveryMode.BILI_VIDEOS,
    val isLoading: Boolean = false
)
