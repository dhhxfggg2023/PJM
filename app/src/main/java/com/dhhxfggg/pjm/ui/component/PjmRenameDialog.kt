package com.dhhxfggg.pjm.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.dhhxfggg.pjm.R

/**
 * 库内「显示名」重命名弹窗。
 *
 * 只改数据库里的 name；磁盘上的文件名永远是 UUID，物理命名规则不因为改名被动到。
 * 打开时默认选中主文件名（不含扩展名）那一段，直接打字就能覆盖。
 */
@Composable
fun PjmRenameDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val baseLength = initialName.substringBeforeLast('.', initialName).length
    var value by remember(initialName) {
        mutableStateOf(TextFieldValue(initialName, selection = TextRange(0, baseLength)))
    }
    PjmAeroDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.action_rename),
        confirmButton = {
            Button(
                enabled = value.text.isNotBlank(),
                onClick = { onConfirm(value.text) },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.rename_dialog_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
