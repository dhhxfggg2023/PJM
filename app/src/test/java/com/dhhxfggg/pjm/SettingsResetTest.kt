package com.dhhxfggg.pjm

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dhhxfggg.pjm.domain.util.SettingsManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「恢复出厂设置」清空 SharedPreferences 的回归测试。
 *
 * 旧实现直接删 shared_prefs 下的 xml，但进程内已持有的 SharedPreferences 实例
 * 缓存了 mMap，不会因文件消失而重载 —— 用户恢复出厂后读到的仍是旧值，
 * 直到杀进程才生效。新实现按文件名逐个 edit().clear().commit()，
 * 同步清掉内存态与磁盘态。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsResetTest {
    @Test
    fun resetAllSettings_clearsAlreadyHeldSharedPreferencesInstance() =
        runBlocking {
            val context: Context = ApplicationProvider.getApplicationContext()
            val prefs = context.getSharedPreferences("pjm_audio", Context.MODE_PRIVATE)
            prefs.edit().putString("stale_key", "stale_value").commit()

            SettingsManager(context).resetAllSettings()

            // 关键：断言发生在【同一个实例】上 —— 删 xml 不会让它重载，
            // 只有 clear().commit() 才能让这里立刻读到清空后的值。
            assertNull(
                "恢复出厂后，已持有的 SP 实例必须立刻读到清空后的值",
                prefs.getString("stale_key", null),
            )
        }
}
