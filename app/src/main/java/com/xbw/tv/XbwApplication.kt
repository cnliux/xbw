package com.xbw.tv

import android.app.Application
import android.os.Build
import com.xbw.tv.data.local.AppDatabase
import com.xbw.tv.data.net.HttpFetcher
import com.xbw.tv.input.KeySettings

/**
 * 应用入口。启动时做三件一次性初始化：
 *   1. HttpFetcher（OkHttp，含磁盘缓存目录）
 *   2. KeySettings（按键映射：同步加载，游戏页 onKeyDown 要热路径读）
 *   3. AppDatabase（Room）
 *
 * 注意：这里**不预取任何游戏数据**——数据只在进入大厅/搜索页时按需抓取，
 * 保证"不内置数据、实时抓取"。
 */
class XbwApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        if (!isMainProcess()) return

        HttpFetcher.init(cacheDir)
        KeySettings.init(this)
        AppDatabase.get(this)
    }

    private fun isMainProcess(): Boolean = currentProcessName() == packageName

    private fun currentProcessName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName() ?: packageName
        } else {
            packageName
        }

    companion object {
        /** 全局单例仓库：ViewModel 与设置页共享同一份内存缓存 */
        @Volatile
        private var repo: com.xbw.tv.data.repo.GameRepository? = null

        fun repository(app: Application): com.xbw.tv.data.repo.GameRepository =
            repo ?: synchronized(this) {
                repo ?: com.xbw.tv.data.repo.GameRepository(AppDatabase.get(app), app)
                    .also { repo = it }
            }
    }
}
