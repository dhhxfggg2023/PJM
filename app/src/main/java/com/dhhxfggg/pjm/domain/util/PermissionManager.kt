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
     *
     * 注意：Android 13+ 开启「所有文件访问」(MANAGE_EXTERNAL_STORAGE) 后，
     * 系统已放行对全部共享存储的读取，此时 checkSelfPermission(READ_MEDIA_*)
     * 仍会返回 DENIED —— 因此 [checkPermissions] 对 R+ 只以全盘访问为门槛，
     * 本常量仅用于需要单独按需请求媒体权限的场景。
     */
    val REQUIRED_PERMISSIONS: Array<String> =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                // Android 13+：通常不需要全局存储权限，按需申请媒体权限
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
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
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                )
            }
        }

    /**
     * 检查核心权限是否已授予。
     *
     * 核心修复（首次安装被拦死）：
     * Android 11+ 的硬性门槛是「所有文件访问」(MANAGE_EXTERNAL_STORAGE)：
     *   - 一旦授予，系统即允许读写全部共享存储（含媒体），READ_MEDIA_* / READ_EXTERNAL_STORAGE
     *     的 checkSelfPermission 仍返回 DENIED，但实际访问不受限；
     *   - 因此 R+ 上【只】以 isExternalStorageManager() 为放行条件，
     *     避免用户开了全盘访问却因 READ_MEDIA 未“授予”而被永远拦在权限页。
     * Android 10 及以下无全盘概念，退化为运行时读写权限检查。
     */
    fun checkPermissions(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager()
        }
        // Android 10 及以下：运行时读写权限
        return REQUIRED_PERMISSIONS.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun openPermissionSettings(activity: Activity) {
        // 如果是 Android 11+ 且未授予全盘访问权限，优先引导至对应的设置页
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                val intent =
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:${activity.packageName}")
                    }
                activity.startActivity(intent)
            } catch (e: Exception) {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            try {
                val intent =
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", activity.packageName, null)
                    }
                activity.startActivity(intent)
            } catch (e: Exception) {
                activity.startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }
}
