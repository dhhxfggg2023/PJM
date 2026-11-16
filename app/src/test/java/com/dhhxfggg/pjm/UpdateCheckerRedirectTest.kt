package com.dhhxfggg.pjm

import com.dhhxfggg.pjm.domain.util.UpdateChecker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新下载地址白名单的回归测试（纯 JVM，不触网）。
 *
 * 重定向逐跳校验依赖 isTrustedDownloadUrl：被 302 到白名单外的主机必须被拒绝。
 */
class UpdateCheckerRedirectTest {
    @Test
    fun trustedGithubHosts_areAccepted() {
        assertTrue(UpdateChecker.isTrustedDownloadUrl("https://github.com/a/b.apk"))
        assertTrue(UpdateChecker.isTrustedDownloadUrl("https://objects.githubusercontent.com/x"))
    }

    @Test
    fun redirectTargetOutsideAllowlist_isRejected() {
        assertFalse(UpdateChecker.isTrustedDownloadUrl("https://evil.example.com/pjm.apk"))
        assertFalse(UpdateChecker.isTrustedDownloadUrl("http://github.com/pjm.apk"))
    }
}
