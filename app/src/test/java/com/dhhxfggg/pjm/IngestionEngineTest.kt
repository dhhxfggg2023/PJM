package com.dhhxfggg.pjm

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dhhxfggg.pjm.data.db.AppDatabase
import com.dhhxfggg.pjm.domain.util.IngestionEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 入库失败的回归测试。
 *
 * 旧实现无条件把源 URI 加进 deletableOriginals：当 openInputStream 返回 null
 * （content provider 打不开）时，保险库没有任何副本，源文件却会被删除 —— 数据永久丢失。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IngestionEngineTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun unreadableSource_isNotOfferedForDeletion() =
        runBlocking {
            // 没有注册 provider 的 content:// URI：openInputStream 返回 null，本次不会产出任何实体。
            val uri = Uri.parse("content://no.such.provider/missing.jpg")
            val summary =
                IngestionEngine.store(
                    context = context,
                    uris = listOf(uri),
                    fileDao = db.fileDao(),
                )
            assertTrue("源流打不开时绝不能把 URI 列入可删除原件", summary.deletableUris.isEmpty())
            assertEquals("应计为一次失败", 1, summary.failed)
            assertEquals("不应有任何文件入库", 0, summary.imported)
        }
}
