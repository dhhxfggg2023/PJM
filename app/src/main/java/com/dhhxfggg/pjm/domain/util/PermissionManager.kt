package com.dhhxfggg.pjm.domain.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
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
        // 1. 基础权限检查
        val basicGranted = REQUIRED_PERMISSIONS.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
        
        // 2. 高级权限检查 (Android 11+ 的所有文件访问)
        val manageGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else true
        
        return basicGranted && manageGranted
    }

    fun openPermissionSettings(activity: Activity) {
        // 如果是 Android 11+ 且未授予全盘访问权限，优先引导至对应的设置页
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
            } catch (e: Exception) {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
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
}
