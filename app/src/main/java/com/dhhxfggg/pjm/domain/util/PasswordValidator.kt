package com.dhhxfggg.pjm.domain.util

import androidx.compose.ui.graphics.Color
import com.dhhxfggg.pjm.R

enum class PasswordStrength {
    WEAK, MEDIUM, STRONG, VERY_STRONG
}

object PasswordValidator {

    fun calculateStrength(password: String): PasswordStrength {
        if (password.length < 6) return PasswordStrength.WEAK

        var score = 0

        // 长度分数
        when {
            password.length >= 12 -> score += 2
            password.length >= 8 -> score += 1
        }

        // 字符类型分数
        if (password.any { it.isUpperCase() }) score += 1
        if (password.any { it.isLowerCase() }) score += 1
        if (password.any { it.isDigit() }) score += 1
        if (password.any { !it.isLetterOrDigit() }) score += 1

        // 重复字符检查
        val hasRepeat = password.windowed(3).any { window ->
            window.toSet().size == 1
        }
        if (!hasRepeat) score += 1

        // 常见弱密码检查
        val weakPasswords = setOf(
            "123456", "password", "12345678", "qwerty", "123456789",
            "12345", "1234", "111111", "1234567", "dragon"
        )
        if (password.lowercase() in weakPasswords) score = 0

        return when (score) {
            in 0..2 -> PasswordStrength.WEAK
            in 3..4 -> PasswordStrength.MEDIUM
            5 -> PasswordStrength.STRONG
            else -> PasswordStrength.VERY_STRONG
        }
    }

    fun getStrengthDescriptionResId(strength: PasswordStrength): Int {
        return when (strength) {
            PasswordStrength.WEAK -> R.string.strength_weak
            PasswordStrength.MEDIUM -> R.string.strength_medium
            PasswordStrength.STRONG -> R.string.strength_strong
            PasswordStrength.VERY_STRONG -> R.string.strength_very_strong
        }
    }

    fun getStrengthColor(strength: PasswordStrength): Color {
        return when (strength) {
            PasswordStrength.WEAK -> Color(0xFFF44336) // 红色
            PasswordStrength.MEDIUM -> Color(0xFFFF9800) // 橙色
            PasswordStrength.STRONG -> Color(0xFF4CAF50) // 绿色
            PasswordStrength.VERY_STRONG -> Color(0xFF2196F3) // 蓝色
        }
    }

    fun validatePassword(password: String): ValidationResult {
        return when {
            password.isEmpty() -> ValidationResult(
                isValid = false,
                messageResId = R.string.error_password_empty
            )
            password.length < 6 -> ValidationResult(
                isValid = false,
                messageResId = R.string.password_too_short
            )
            calculateStrength(password) == PasswordStrength.WEAK -> ValidationResult(
                isValid = false,
                messageResId = R.string.password_too_weak
            )
            else -> ValidationResult(
                isValid = true,
                messageResId = R.string.password_valid, // 注意：这个通常需要格式化参数，我们将在UI层处理
                strengthResId = getStrengthDescriptionResId(calculateStrength(password))
            )
        }
    }

    data class ValidationResult(
        val isValid: Boolean,
        val messageResId: Int,
        val strengthResId: Int? = null
    )
}