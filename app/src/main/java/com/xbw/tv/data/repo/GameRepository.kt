package com.xbw.tv.data.repo

import android.util.Log
import android.util.LruCache
import com.xbw.tv.data.local.AppDatabase
import com.xbw.tv.data.local.FavoriteEntity
import com.xbw.tv.data.local.GameEntity
import com.xbw.tv.data.local.RecentPlayEntity
import com.xbw.tv.data.model.GameCategory
import com.xbw.tv.data.model.GameItem
import com.xbw.tv.data.net.CheatParser
import com.xbw.tv.data.net.HttpFetcher
import com.xbw.tv.data.net.SiteConfig
import com.xbw.tv.data.net.YikmParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 数据仓库：抓取 → 解析 → 缓存 的唯一入口（方案 3.4 的实现）。
 *
 * 缓存策略（"不内置数据"的关键）：
 *   1. 进页面先给缓存（秒开，可能为空）；
 *   2. **无条件**在后台重新抓取并覆盖缓存（stale-while-revalidate）；
 *   3. 抓取失败且无缓存 → 上抛错误，UI 显示重试；抓取失败但有缓存 → 用缓存 + 提示。
 *
 * 内存缓存用 LruCache（5 个 key），磁盘用 Room；同一 (分类,页码) 的并发抓取用 Mutex 合并。
 */
class GameRepository(private val db: AppDatabase) {

    companion object {
        private const val TAG = "GameRepository"

        /** 内存缓存里同一列表最多留这么多条 */
        private const val MEM_LIMIT = 240
    }

    private val memory = LruCache<String, List<GameItem>>(12)
    private val cheatMem = LruCache<String, List<CheatParser.Cheat>>(24)
    private val locks = mutableMapOf<String, Mutex>()

    data class LoadResult(
        val items: List<GameItem>,
        val fromCache: Boolean,
        val page: Int,
        val maxPage: Int,
        val warnings: List<String> = emptyList(),
        val fetchedAt: Long = 0L
    ) {
        val hasNextPage: Boolean get() = maxPage > page
    }

    class EmptySiteException(msg: String, cause: Throwable? = null) : Exception(msg, cause)

    private fun lockFor(key: String): Mutex = synchronized(locks) {
        locks.getOrPut(key) { Mutex() }
    }

    private fun memKey(categoryKey: String, page: Int) = "$categoryKey#$page"

    // ------------------------------------------------------------------
    // 读取：缓存优先（UI 用）
    // ------------------------------------------------------------------

    /** 同步读内存缓存，用于 Fragment 重建瞬间避免闪屏 */
    fun peekCache(category: GameCategory, page: Int): LoadResult? {
        val key = memKey(category.key, page)
        val mem = memory.get(key)
        if (mem != null) return LoadResult(mem, true, page, page + 1)
        return null
    }

    /** Room 缓存读取（异步） */
    suspend fun cachedList(category: GameCategory, page: Int): LoadResult =
        withContext(Dispatchers.IO) {
            val rows = db.gameDao().query(category.key, page)
            val items = rows.map { it.toItem() }
            val ts = db.gameDao().lastFetchedAt(category.key, page) ?: 0L
            // 缓存里没有总页数信息，maxPage=page：避免误触 loadMore 抓到不存在的页
            LoadResult(items, true, page, page, fetchedAt = ts)
        }

    // ------------------------------------------------------------------
    // 抓取：始终联网
    // ------------------------------------------------------------------

    /**
     * 抓取指定分类第 page 页。
     *
     * @param force 忽略内存缓存（页面重新可见时传 true，保证"实时"）
     * @throws YikmParser.ParseException 站点改版
     * @throws HttpFetcher.FetchException 网络失败
     */
    suspend fun fetchList(
        category: GameCategory,
        page: Int,
        force: Boolean = true
    ): LoadResult {
        if (!category.fetchable) {
            // FAVORITE / RECENT 分类：本地数据
            val items = if (category == GameCategory.FAVORITE) favoriteGames() else recentGames()
            return LoadResult(items, false, 1, 1)
        }
        val key = memKey(category.key, page)
        if (!force) {
            memory.get(key)?.let { return LoadResult(it, true, page, page + 1) }
        }
        return lockFor(key).withLock {
            if (!force) {
                memory.get(key)?.let { return@withLock LoadResult(it, true, page, page + 1) }
            }
            val url = category.listPath?.let { expand(it, page) } ?: SiteConfig.listUrl(page)
            Log.d(TAG, "fetch $url")
            val html = HttpFetcher.fetchHtml(url)
            val parsed = YikmParser.parseList(html, page)
            if (parsed.isEmpty) {
                throw EmptySiteException("第 $page 页解析到 0 条数据")
            }
            val items = parsed.items.map { it.copy(source = category.sourceOf()) }
            persist(category.key, page, items)
            if (items.size <= MEM_LIMIT) memory.put(key, items) else memory.remove(key)
            LoadResult(items, false, parsed.currentPage, parsed.maxPage, parsed.warnings, System.currentTimeMillis())
        }
    }

    /** 抓取首页"热门区块"；首页结构特殊，单独解析 */
    suspend fun fetchHomeSections(): List<YikmParser.HomeSection> = lockFor("home").withLock {
        val html = HttpFetcher.fetchHtml(SiteConfig.HOME_URL)
        val sections = YikmParser.parseHomeSections(html)
        if (sections.isEmpty()) throw EmptySiteException("首页未解析到任何区块")
        // 同时把"所有游戏"区块写进 ALL 分类缓存，大厅首屏可直接复用
        sections.firstOrNull { it.title.contains("所有") || it.title.contains("全部") }?.let { sec ->
            persist(GameCategory.ALL.key, 1, sec.items)
            memory.put(memKey(GameCategory.ALL.key, 1), sec.items)
        }
        sections
    }

    /**
     * 搜索。站点 /search?name= 返回整页结果、无分页（实测），
     * 因此 maxPage 恒为 1。
     */
    suspend fun search(keyword: String): LoadResult {
        val kw = keyword.trim()
        if (kw.isEmpty()) return LoadResult(emptyList(), false, 1, 1)
        return lockFor("search:$kw").withLock {
            val html = HttpFetcher.fetchHtml(SiteConfig.searchUrl(kw))
            val parsed = YikmParser.parseList(html, 1)
            val items = parsed.items.map { it.copy(source = GameItem.SOURCE_SEARCH) }
            LoadResult(items, false, 1, 1, parsed.warnings, System.currentTimeMillis())
        }
    }

    /**
     * 抓取某游戏的金手指（站点 /cheat?id=）。
     *
     * 空响应 = 该游戏没有金手指，返回空列表而不是抛错。
     * 缓存：会话内内存 LRU（金手指体积小、极少变动），磁盘层面由 OkHttp 的 HTTP 缓存兜底；
     * 刻意不动 Room —— 加表要升 DB 版本，而 fallbackToDestructiveMigration 会把「最近玩过」清掉。
     */
    suspend fun fetchCheats(gameId: String, force: Boolean = false): List<CheatParser.Cheat> {
        val id = gameId.trim()
        if (id.isEmpty()) return emptyList()
        if (!force) cheatMem.get(id)?.let { return it }
        return lockFor("cheat:$id").withLock {
            if (!force) cheatMem.get(id)?.let { return@withLock it }
            val text = HttpFetcher.fetchHtml(SiteConfig.cheatUrl(id), allowEmpty = true)
            val list = CheatParser.parse(text)
            cheatMem.put(id, list)
            list
        }
    }

    /** URL 模板里带 page= 的，替换页码；否则拼页码参数 */
    private fun expand(pathTemplate: String, page: Int): String {
        val abs = SiteConfig.absolutize(pathTemplate)
        return if (abs.contains("page=")) {
            abs.replace(Regex("page=\\d+"), "page=$page")
        } else {
            val sep = if (abs.contains("?")) "&" else "?"
            "$abs${sep}page=$page"
        }
    }

    private fun GameCategory.sourceOf(): String = when (this) {
        GameCategory.ALL -> GameItem.SOURCE_HOME
        GameCategory.RECENT -> GameItem.SOURCE_RECENT
        GameCategory.FAVORITE -> GameItem.SOURCE_FAVORITE
        else -> GameItem.SOURCE_CATEGORY
    }

    private suspend fun persist(categoryKey: String, page: Int, items: List<GameItem>) {
        withContext(Dispatchers.IO) {
            val dao = db.gameDao()
            dao.deletePage(categoryKey, page)
            dao.insertAll(items.map { it.toEntity(categoryKey, page) })
        }
    }

    // ------------------------------------------------------------------
    // 最近玩过
    // ------------------------------------------------------------------

    suspend fun markPlayed(item: GameItem) = withContext(Dispatchers.IO) {
        val dao = db.recentPlayDao()
        dao.insertIfAbsent(
            RecentPlayEntity(
                gameId = item.id,
                name = item.name,
                coverUrl = item.coverUrl,
                playUrl = item.playUrl,
                tags = item.tags.joinToString("|")
            )
        )
        dao.bump(item.id)
    }

    suspend fun recentGames(limit: Int = 60): List<GameItem> = withContext(Dispatchers.IO) {
        db.recentPlayDao().recent(limit).map {
            GameItem(
                id = it.gameId,
                name = it.name,
                coverUrl = it.coverUrl,
                playUrl = it.playUrl,
                tags = it.tags.split("|").filter { t -> t.isNotBlank() },
                source = GameItem.SOURCE_RECENT
            )
        }
    }

    // ------------------------------------------------------------------
    // 收藏（用户数据，独立 favorites 表，不随 games 缓存重建丢失）
    // ------------------------------------------------------------------

    /** 收藏 id 集合的内存镜像：卡片渲染 ★ / 同步判定用，变更时整体刷新 */
    private val _favoriteIds = MutableStateFlow<Set<String>>(emptySet())
    val favoriteIds: StateFlow<Set<String>> = _favoriteIds

    /** 进大厅时加载一次，之后 toggle 增量刷新 */
    suspend fun loadFavorites() {
        _favoriteIds.value = withContext(Dispatchers.IO) { db.favoriteDao().allIds().toSet() }
    }

    suspend fun favoriteGames(limit: Int = 200): List<GameItem> = withContext(Dispatchers.IO) {
        db.favoriteDao().all().take(limit).map { it.toItem() }
    }

    /** 切换收藏状态，@return 切换后是否已收藏 */
    suspend fun toggleFavorite(item: GameItem): Boolean {
        val added = withContext(Dispatchers.IO) {
            if (db.favoriteDao().isFavorite(item.id)) {
                db.favoriteDao().delete(item.id)
                false
            } else {
                db.favoriteDao().insert(
                    FavoriteEntity(
                        gameId = item.id,
                        name = item.name,
                        coverUrl = item.coverUrl,
                        playUrl = item.playUrl,
                        tags = item.tags.joinToString("|")
                    )
                )
                true
            }
        }
        _favoriteIds.value = withContext(Dispatchers.IO) { db.favoriteDao().allIds().toSet() }
        return added
    }

    // ------------------------------------------------------------------
    // 缓存维护
    // ------------------------------------------------------------------

    suspend fun cacheStats(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        db.gameDao().count() to db.recentPlayDao().count()
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        db.gameDao().clearAll()
        memory.evictAll()
        // 注意：不清 recent_plays，那是用户行为数据；也不清按键映射
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        clearCache()
        db.recentPlayDao().clear()
    }

    // ------------------------------------------------------------------
    // 映射
    // ------------------------------------------------------------------

    private fun GameEntity.toItem() = GameItem(
        id = id,
        name = name,
        coverUrl = coverUrl,
        playUrl = playUrl,
        tags = tags.split("|").filter { it.isNotBlank() },
        source = source.ifBlank { GameCategory.fromKey(categoryKey).sourceOf() }
    )

    private fun FavoriteEntity.toItem() = GameItem(
        id = gameId,
        name = name,
        coverUrl = coverUrl,
        playUrl = playUrl,
        tags = tags.split("|").filter { it.isNotBlank() },
        source = GameItem.SOURCE_FAVORITE
    )

    private fun GameItem.toEntity(categoryKey: String, page: Int) = GameEntity(
        id = id,
        name = name,
        coverUrl = coverUrl,
        playUrl = playUrl,
        tags = tags.joinToString("|"),
        source = source,
        categoryKey = categoryKey,
        page = page
    )
}
