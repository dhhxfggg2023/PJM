package com.dhhxfggg.pjm

import com.dhhxfggg.pjm.domain.shizuku.PrivilegedPaths
import org.junit.Assert.assertFalse
import org.junit.Test

/** copy 落点白名单回归测试。 */
class AidlCopyPathTest {
    @Test
    fun copyDestinationOutsideAppExternalFiles_isRejected() {
        assertFalse(PrivilegedPaths.isAllowedCopyDestination("/storage/emulated/0/DCIM/a.m4s"))
        assertFalse(PrivilegedPaths.isAllowedCopyDestination("/data/local/tmp/a.m4s"))
        assertFalse(PrivilegedPaths.isAllowedCopyDestination("/storage/emulated/0/Android/data/other.app/files/a"))
    }
}
