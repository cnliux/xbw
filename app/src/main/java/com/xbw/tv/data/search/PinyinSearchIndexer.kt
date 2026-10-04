package com.xbw.tv.data.search

import android.content.Context
import android.util.Log
import com.xbw.tv.data.local.AppDatabase
import com.xbw.tv.data.local.SearchIndexEntity
import com.xbw.tv.data.model.GameCategory
import com.xbw.tv.data.net.HttpFetcher
import com.xbw.tv.data.net.SiteConfig
import com.xbw.tv.data.net.YikmParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 拼音首字母索引构建器（本地方案，站点 /search 实测不支持拼音/首字母）。
 *
 * 策略：
 *  - 只索引五个有原生核心的分类（FC/街机/SFC/GBA/MD），无法玩的分类不进索引；
 *  - 逐分类分页拉列表页（每页 20 条），只取 id/标题/封面/标签算首字母；
 *  - 到底判定：解析出的 currentPage 与请求页不符（服务器越界页返回兜底杂质）、
 *    与上一页 id 大量重复、或解析 0 条，任一命中即换下一分类；
 *  - 串行 + 500ms 间隔，别轰炸 yikm（老服务器，见 docs）；
 *  - 全量约几百页 / 十几分钟，一次性后台跑；7 天后搜索时自动增量重建；
 *  - 不进 GameRepository 的 games 缓存表 —— 索引副本单独存 search_index。
 */
object PinyinSearchIndexer {

    private const val TAG = "PinyinIndexer"
    private const val PREFS = "xbw_prefs"
    private const val KEY_SYNCED_AT = "pinyin_index_synced_at"
    private const val FRESH_MS = 7 * 24 * 3600_000L
    private const val PAGE_INTERVAL_MS = 500L
    private const val MAX_PAGES_PER_CATEGORY = 400
    private const val DUP_RATIO_STOP = 0.6

    /** 只索引能玩到的平台 */
    val INDEXED_CATEGORIES = listOf(
        GameCategory.FC, GameCategory.ARCADE, GameCategory.SFC,
        GameCategory.GBA, GameCategory.MD
    )

    data class Progress(
        val running: Boolean = false,
        val category: String = "",
        val page: Int = 0,
        val total: Int = 0,
        val syncedAt: Long = 0L,
        val error: String = ""
    )

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress

    private val mutex = Mutex()

    @Volatile
    private var cancelRequested = false

    fun cancel() { cancelRequested = true }

    fun isFresh(context: Context, count: Int): Boolean {
        val at = prefs(context).getLong(KEY_SYNCED_AT, 0L)
        return count > 0 && System.currentTimeMillis() - at < FRESH_MS
    }

    suspend fun count(db: AppDatabase): Int =
        withContext(Dispatchers.IO) { db.searchIndexDao().count() }

    /** 索引过期/为空时自动重建；新鲜则直接返回 */
    suspend fun ensureFresh(context: Context, db: AppDatabase) {
        if (isFresh(context, count(db))) return
        rebuild(context, db)
    }

    /** 全量重建（互斥；已在跑则直接返回） */
    suspend fun rebuild(context: Context, db: AppDatabase) {
        if (!mutex.tryLock()) return
        try {
            cancelRequested = false
            val app = context.applicationContext
            val dao = db.searchIndexDao()
            var total = 0
            _progress.value = Progress(running = true, syncedAt = lastSyncedAt(app))
            try {
                for (cat in INDEXED_CATEGORIES) {
                    if (cancelRequested) break
                    Log.i(TAG, "indexing ${cat.key} ...")
                    withContext(Dispatchers.IO) { dao.deleteCategory(cat.key) }
                    val seen = HashSet<String>()
                    var page = 1
                    while (page <= MAX_PAGES_PER_CATEGORY) {
                        if (cancelRequested) break
                        _progress.value = _progress.value.copy(
                            category = cat.title, page = page, total = total
                        )
                        val url = pageUrl(cat, page)
                        val parsed = try {
                            YikmParser.parseList(HttpFetcher.fetchHtml(url), page)
                        } catch (e: Exception) {
                            Log.w(TAG, "${cat.key} p$page fetch failed: ${e.message}")
                            break
                        }
                        val items = parsed.items
                        if (items.isEmpty()) break
                        if (parsed.currentPage != page) break          // 越界页 → 兜底内容
                        if (page > 1 && items.count { it.id in seen } >= items.size * DUP_RATIO_STOP) break
                        val now = System.currentTimeMillis()
                        val rows = items.map {
                            SearchIndexEntity(
                                gameId = it.id,
                                name = it.name,
                                initials = Pinyin.initials(app, it.name),
                                categoryKey = cat.key,
                                coverUrl = it.coverUrl,
                                playUrl = it.playUrl,
                                tags = it.tags.joinToString("|"),
                                indexedAt = now
                            )
                        }
                        withContext(Dispatchers.IO) { dao.upsertAll(rows) }
                        items.forEach { seen.add(it.id) }
                        total += rows.size
                        _progress.value = _progress.value.copy(total = total)
                        if (page % 10 == 0) Log.i(TAG, "${cat.key}: page $page, total $total")
                        page++
                        delay(PAGE_INTERVAL_MS)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "index build aborted", e)
                _progress.value = _progress.value.copy(running = false, error = e.message ?: "error")
                return
            }
            val ts = System.currentTimeMillis()
            prefs(app).edit().putLong(KEY_SYNCED_AT, ts).apply()
            _progress.value = Progress(syncedAt = ts, total = total)
            Log.i(TAG, "index built: $total entries")
        } finally {
            mutex.unlock()
        }
    }

    private fun lastSyncedAt(context: Context): Long =
        prefs(context).getLong(KEY_SYNCED_AT, 0L)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun pageUrl(cat: GameCategory, page: Int): String {
        val abs = SiteConfig.absolutize(cat.listPath ?: return SiteConfig.listUrl(page))
        return if (abs.contains("page=")) {
            abs.replace(Regex("page=\\d+"), "page=$page")
        } else {
            val sep = if (abs.contains("?")) "&" else "?"
            "$abs${sep}page=$page"
        }
    }
}
