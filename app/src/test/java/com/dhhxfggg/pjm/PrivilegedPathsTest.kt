package com.dhhxfggg.pjm

import com.dhhxfggg.pjm.domain.shizuku.PrivilegedPaths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 特权文件路径白名单回归测试（纯 JVM）。 */
class PrivilegedPathsTest {
    @Test
    fun sharedStorage_isAccepted() {
        assertTrue(PrivilegedPaths.isSharedStorage("/storage/emulated/0/DCIM/Camera/a.jpg"))
        assertTrue(PrivilegedPaths.isSharedStorage("/storage/emulated/0/Android/data/tv.danmaku.bili/x"))
    }

    @Test
    fun dataAndTraversal_areRejected() {
        assertFalse(PrivilegedPaths.isSharedStorage("/data/data/com.x/databases/x"))
        assertFalse(PrivilegedPaths.isSharedStorage("/system/etc/hosts"))
        assertFalse(PrivilegedPaths.isSharedStorage("/storage/emulated/0/../../data/x"))
    }

    @Test
    fun deleteRejectsRootAndTopLevelDirs() {
        assertFalse("共享存储根不可删", PrivilegedPaths.isSafeDeleteTarget("/storage/emulated/0"))
        assertFalse("DCIM 整目录不可删", PrivilegedPaths.isSafeDeleteTarget("/storage/emulated/0/DCIM"))
        assertFalse("Download 整目录不可删", PrivilegedPaths.isSafeDeleteTarget("/storage/emulated/0/Download"))
        assertTrue("应用缓存子目录可删", PrivilegedPaths.isSafeDeleteTarget("/storage/emulated/0/Android/data/tv.danmaku.bili/download/123"))
    }

    @Test
    fun copyDestinationMustBeInsideAppExternalFiles() {
        val root = File("/storage/emulated/0/Android/data/com.dhhxfggg.pjm/files")
        assertTrue(PrivilegedPaths.isInside(root.absolutePath + "/cache/a.m4s", root))
        assertFalse(PrivilegedPaths.isInside("/storage/emulated/0/DCIM/a.m4s", root))
        assertFalse(PrivilegedPaths.isInside("/data/local/tmp/a.m4s", root))
    }
}
