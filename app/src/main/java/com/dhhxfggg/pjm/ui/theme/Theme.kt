package com.dhhxfggg.pjm.ui.theme

import android.app.Activity
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.request.crossfade
import coil3.request.bitmapConfig
import android.graphics.Bitmap
import com.dhhxfggg.pjm.data.model.Settings
import com.dhhxfggg.pjm.domain.util.PjmLogger
import java.io.File
import androidx.compose.ui.text.font.Font

// 定义颜色方案
private val DarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    background = BackgroundDark,
    surface = SurfaceDark,
    onBackground = Color.White,
    onSurface = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryLight,
    background = BackgroundLight,
    surface = SurfaceLight,
    onBackground = Color.Black,
    onSurface = Color.Black
)

@Composable
fun PJMTheme(
    settings: Settings.AppSettings,
    content: @Composable () -> Unit
) {
    val darkTheme = when (settings.theme) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !darkTheme -> dynamicLightColorScheme(context)
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val customFontFamily = remember(settings.customFontUri) {
        settings.customFontUri?.let { path ->
            val fontFile = File(path)
            if (fontFile.exists()) {
                try { FontFamily(Font(fontFile)) } catch (e: Exception) { FontFamily.Default }
            } else FontFamily.Default
        } ?: FontFamily.Default
    }

    val textColor = colorScheme.onBackground
    val typography = Typography(
        headlineLarge = TextStyle(fontFamily = customFontFamily, fontWeight = FontWeight.Bold, fontSize = 28.sp, color = textColor),
        titleLarge = TextStyle(fontFamily = customFontFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = textColor),
        bodyLarge = TextStyle(fontFamily = customFontFamily, fontSize = 16.sp, color = textColor),
        bodyMedium = TextStyle(fontFamily = customFontFamily, fontSize = 14.sp, color = textColor),
        labelSmall = TextStyle(fontFamily = customFontFamily, fontSize = 11.sp, color = textColor)
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    val customDensity = Density(
        density = LocalDensity.current.density * settings.globalUiScale,
        fontScale = LocalDensity.current.fontScale
    )

    CompositionLocalProvider(LocalDensity provides customDensity) {
        MaterialTheme(colorScheme = colorScheme, typography = typography, content = content)
    }
}

@Composable
fun AppTheme(settings: Settings.AppSettings, content: @Composable () -> Unit) {
    val context = LocalContext.current
    PJMTheme(settings = settings) {
        val isDark = when (settings.theme) {
            "dark" -> true; "light" -> false; else -> isSystemInDarkTheme()
        }
        val baseColor = if (isDark) Color.Black else Color.White

        Box(modifier = Modifier.fillMaxSize().background(baseColor)) {
            val bgRequest = remember(settings.customBackgroundUri, settings.isCustomBackgroundEnabled) {
                if (settings.isCustomBackgroundEnabled && !settings.customBackgroundUri.isNullOrEmpty()) {
                    ImageRequest.Builder(context)
                        .data(settings.customBackgroundUri)
                        .crossfade(true)
                        .build()
                } else null
            }

            if (bgRequest != null) {
                AsyncImage(
                    model = bgRequest,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    onState = { state ->
                        if (state is coil3.compose.AsyncImagePainter.State.Error) {
                            PjmLogger.e("AppTheme", "Background image load failed: ${state.result.throwable.message}")
                        }
                    }
                )
                
                // 背景遮罩层：受 backgroundOpacity 控制
                // opacity 1.0 = 不透明（完全看到原图），opacity 0.0 = 全遮挡（看到 baseColor）
                val overlayAlpha = (1f - settings.backgroundOpacity.coerceIn(0f, 1f))
                if (overlayAlpha > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(baseColor.copy(alpha = overlayAlpha))
                    )
                }
            }
            content()
        }
    }
}
