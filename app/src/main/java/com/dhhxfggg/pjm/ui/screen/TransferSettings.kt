package com.dhhxfggg.pjm.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MonitorSmartphone
import com.dhhxfggg.pjm.domain.util.FileUtils
import com.dhhxfggg.pjm.ui.viewmodel.TransferViewModel

/**
 * 「传输到电脑」设置页。
 *
 * 手机自己就是服务器：电脑用浏览器打开地址即可按分类/时间段筛选并下载，
 * 每个文件都支持 Range 请求 —— 下载器能断点续传、也能多连接分段拉同一份文件。
 */
@Composable
internal fun TransferSettings(
    transferViewModel: TransferViewModel = hiltViewModel(),
) {
    val state by transferViewModel.state.collectAsState()
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsCategory(title = "传到电脑") {
            SettingsSwitch(
                icon = Lucide.MonitorSmartphone,
                title = "开启传输服务",
                description = "手机开一个 HTTP 下载服务，电脑用浏览器直接取文件",
                checked = state.running,
                onCheckedChange = { transferViewModel.toggle() },
            )
        }
        if (state.running) {
            SettingsCategory(title = "连接地址") {
                if (state.addresses.isEmpty()) {
                    Text(
                        text = "没检测到局域网地址；USB 方式请用下面的 127.0.0.1 地址",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
                state.addresses.forEach { ip ->
                    Text(
                        text = "http://" + ip + ":" + state.port + "/",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                Text(
                    text =
                        "USB 连接：电脑执行 adb forward tcp:" + state.port + " tcp:" + state.port +
                            "，再打开 http://127.0.0.1:" + state.port + "/",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                Text(
                    text = "可下载 " + state.fileCount + " 个文件 · " + FileUtils.formatFileSize(state.totalSize),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                Spacer(Modifier.height(8.dp))
            }
            SettingsCategory(title = "说明") {
                Text(
                    text = "电脑那页可以按分类和日期筛选；单个文件支持多线程分段下载与断点续传。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        state.error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}
