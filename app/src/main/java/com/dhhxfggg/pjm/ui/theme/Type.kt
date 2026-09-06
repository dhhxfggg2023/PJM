package com.dhhxfggg.pjm.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 全局排版基线（占位）。
 * 实际字阶由 Theme.kt 的 [pjmTypography] 依据当前配色动态生成，
 * 以保证文字颜色始终与主题一致。如需替换全局字体，在此处统一调整即可。
 */
val Typography =
    Typography(
        bodyLarge =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.5.sp,
            ),
    )
