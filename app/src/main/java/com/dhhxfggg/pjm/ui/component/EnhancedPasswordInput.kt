package com.dhhxfggg.pjm.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.Lucide
import com.dhhxfggg.pjm.R

/**
 * An enhanced password input field with visibility toggle and a password strength indicator.
 *
 * @param password The current text value of the password field.
 * @param onPasswordChange Callback invoked when the password text changes.
 */
@Composable
fun EnhancedPasswordInput(
    password: String,
    onPasswordChange: (String) -> Unit,
) {
    var passwordVisible by remember { mutableStateOf(false) }

    val strength =
        remember(password) {
            when {
                password.isEmpty() -> 0f
                password.length < 6 -> 0.3f
                password.any { it.isDigit() } && password.any { it.isLetter() } -> 0.8f
                password.length >= 10 -> 1f
                else -> 0.5f
            }
        }

    val strengthColor by animateColorAsState(
        targetValue =
            when {
                strength <= 0.3f -> Color.Red
                strength <= 0.6f -> Color.Yellow
                else -> Color.Green
            },
        label = "strength_color",
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.label_enter_password)) },
            singleLine = true,
            visualTransformation =
                if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector =
                            if (passwordVisible) {
                                Lucide.Eye
                            } else {
                                Lucide.EyeOff
                            },
                        contentDescription = null,
                    )
                }
            },
        )

        Spacer(modifier = Modifier.height(8.dp))

        LinearProgressIndicator(
            progress = { strength },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(4.dp),
            color = strengthColor,
            trackColor = strengthColor.copy(alpha = 0.2f),
        )

        Text(
            text =
                when {
                    strength == 0f -> ""
                    strength <= 0.3f -> stringResource(R.string.strength_weak_desc)
                    strength <= 0.6f -> stringResource(R.string.strength_medium_desc)
                    else -> stringResource(R.string.strength_strong_desc)
                },
            style = MaterialTheme.typography.labelSmall,
            color = strengthColor,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
