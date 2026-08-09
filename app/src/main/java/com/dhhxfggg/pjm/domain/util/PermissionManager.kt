package com.dhhxfggg.pjm.domain.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

object PermissionManager {

    /**
     * 获取全应用运行所需的最基础权限。
     * 现代 Android (13+) 推荐使用系统 Picker，因此这里仅保留兼容性权限。
     */
    val REQUIRED_PERMISSIONS: Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
            // Android 13+：通常不需要全局存储权限，按需申请媒体权限
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
            // Android 11-12：仅需读取权限
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        else -> {
            // Android 10 及以下：需要读写权限
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }
    }

    /**
     * 检查核心权限是否已授予
     */
    fun checkPermissions(context: Context): Boolean {
        // 如果是 Android 13+ 且只打算用系统 Picker 处理文件，这里甚至可以返回 true
        return REQUIRED_PERMISSIONS.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun openPermissionSettings(activity: Activity) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", activity.packageName, null)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            activity.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
}
