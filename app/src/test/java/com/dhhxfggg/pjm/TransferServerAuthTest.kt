package com.dhhxfggg.pjm

import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.domain.transfer.VaultTransferServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 传输服务令牌鉴权回归测试。
 *
 * 背景：回环端口不按 UID 隔离，同机任意应用都能连 127.0.0.1:8787。
 * 加令牌后必须满足三件事，否则要么裸奔、要么浏览器自己都打不开：
 * 1. 不带令牌的数据接口一律 401（且响应体标明 auth，旧客户端可据 upgrade 提示更新）；
 * 2. 带 `X-PJM-Token` 或 `?token=` 可正常访问；
 * 3. 索引页把令牌透传进文件/分页链接，否则点第一个文件就 401。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransferServerAuthTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var vaultRoot: File
    private lateinit var server: VaultTransferServer

    private val token = "0123456789abcdef0123456789abcdef"
    private val entity =
        FileEntity(
            relativePath = "images/20260101_0000.jpg",
            name = "20260101_0000.jpg",
            size = 3L,
            category = "images",
            lastModified = 1_700_000_000_000L,
            isImage = true,
            extension = "jpg",
            contentHash = null,
            importedAt = 1_700_000_000_000L,
        )

    @Before
    fun setUp() {
        vaultRoot = tmp.newFolder("vault")
        File(vaultRoot, entity.relativePath).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        server =
            VaultTransferServer(
                vaultRoot = vaultRoot,
                filesProvider = { listOf(entity) },
                displayName = { it.name },
                preferredPort = 0,
                authTokenProvider = { token },
            )
        assertTrue("服务应能在临时端口启动", server.start())
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private fun request(
        path: String,
        headerToken: String? = null,
    ): Triple<Int, String, String> {
        val conn = URL("http://127.0.0.1:${server.port}$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        if (headerToken != null) conn.setRequestProperty("X-PJM-Token", headerToken)
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().decodeToString() } ?: ""
        conn.disconnect()
        return Triple(code, body, path)
    }

    @Test
    fun filesJson_withoutToken_is401() {
        val (code, body, _) = request("/api/files")
        assertEquals("无令牌必须拒绝", 401, code)
        assertTrue("响应体要能让旧客户端识别出是鉴权问题：$body", body.contains("\"auth\""))
    }

    @Test
    fun filesJson_withHeaderToken_is200() {
        val (code, body, _) = request("/api/files", headerToken = token)
        assertEquals("带正确令牌应放行", 200, code)
        assertTrue("清单里应有相对路径：$body", body.contains(entity.relativePath))
    }

    @Test
    fun filesJson_withWrongToken_is401() {
        val (code, _, _) = request("/api/files", headerToken = "wrong")
        assertEquals(401, code)
    }

    @Test
    fun hello_staysOpenAndAdvertisesAuth() {
        // /api/hello 必须保持开放：PC 端要在拿到令牌前先探测版本与端口
        val (code, body, _) = request("/api/hello")
        assertEquals(200, code)
        assertTrue("hello 应声明需要鉴权：$body", body.contains("\"auth\":1"))
    }

    @Test
    fun indexPage_passesTokenIntoFileLinks() {
        val (code, body, _) = request("/?token=$token")
        assertEquals(200, code)
        assertTrue("文件链接必须带上令牌，否则点一下就 401：$body", body.contains("/file?path=") && body.contains("token=$token"))
    }

    @Test
    fun indexPage_withoutToken_is401() {
        val (code, _, _) = request("/")
        assertEquals(401, code)
    }

    @Test
    fun noTokenProvider_keepsLegacyOpenBehaviour() {
        // authTokenProvider 返回 null 表示不鉴权（单测/旧行为）
        val open =
            VaultTransferServer(
                vaultRoot = vaultRoot,
                filesProvider = { listOf(entity) },
                displayName = { it.name },
                preferredPort = 0,
                authTokenProvider = { null },
            )
        assertTrue(open.start())
        try {
            val conn = URL("http://127.0.0.1:${open.port}/api/files").openConnection() as HttpURLConnection
            assertEquals("未配置令牌时不应拦截", 200, conn.responseCode)
            conn.disconnect()
        } finally {
            open.stop()
        }
        assertFalse("服务应已停止", open.isRunning)
    }
}
