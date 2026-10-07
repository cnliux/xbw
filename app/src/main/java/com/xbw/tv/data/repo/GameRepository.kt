package com.xbw.tv.data.repo

import android.app.Application
import android.util.Log
import android.util.LruCache
import com.xbw.tv.core.CoreRouter
import com.xbw.tv.core.RomProvider
import com.xbw.tv.data.local.AppDatabase
import com.xbw.tv.data.local.FavoriteEntity
import com.xbw.tv.data.local.GameEntity
import com.xbw.tv.data.local.GamePlatformEntity
import com.xbw.tv.data.local.RecentPlayEntity
import com.xbw.tv.data.local.SearchIndexEntity
import com.xbw.tv.data.model.GameCategory
import com.xbw.tv.data.model.GameItem
import com.xbw.tv.data.net.CheatParser
import com.xbw.tv.data.net.HttpFetcher
import com.xbw.tv.data.net.SiteConfig
import com.xbw.tv.data.net.YikmParser
import com.xbw.tv.data.plugin.PluginRepository
import com.xbw.tv.data.search.PinyinSearchIndexer
import com.xbw.tv.data.usb.UsbScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
class GameRepository(private val db: AppDatabase, private val app: Application? = null) {

    companion object {
        private const val TAG = "GameRepository"

        /** 内存缓存里同一列表最多留这么多条 */
        private const val MEM_LIMIT = 240

        /** 单次搜索最多现查多少个未知的 play 页（再多是"过滤掉"不如"先显示"） */
        private const val MAX_PLATFORM_PROBE = 24

/** play 页并发探测数（站点会限流，不能一把梭） */
        private const val PROBE_CONCURRENCY = 4

        /** 第三方源在"伪装分页"时每页给多少条（与站点每页 20 条一致） */
        private const val PAGE_SIZE = 20
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
            // FAVORITE / RECENT 分类：本地数据；THIRD：第三方插件源；USB：本地扫描
            if (category == GameCategory.THIRD) return loadThirdParty(page, force, arcade = false)
            if (category == GameCategory.THIRD_ARCADE) return loadThirdParty(page, force, arcade = true)
            if (category == GameCategory.USB) return loadUsb(page, force)
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

    /**
     * 第三方插件源的列表（[GameCategory.THIRD] / [GameCategory.THIRD_ARCADE]）。
     *
     * 第三方源是**一次性全量清单**（gamelist.xml 几百 KB、几千条），没有分页概念，
     * 所以这里在内存里切片，把 [PluginRepository] 的结果伪装成普通的分页列表，
     * 让大厅的翻页/下拉刷新逻辑一行都不用改。
     *
     * @param arcade false=THIRD（非街机平台的源聚合）；true=THIRD_ARCADE（仅街机源）。
     *   街机源和 FC 源分开成独立分类，标题才能标成"第三方街机"。
     * 切完页后顺手把这一页的封面预取到本地（GBK 网盘封面要带 cookie 下，
     * Glide 没法带自定义头），失败静默回落占位图。
     */
    private suspend fun loadThirdParty(page: Int, force: Boolean, arcade: Boolean): LoadResult {
        val ctx = app ?: throw IllegalStateException("第三方源需要 Application 上下文")
        val category = if (arcade) GameCategory.THIRD_ARCADE else GameCategory.THIRD
        val key = memKey(category.key, page)
        if (!force) memory.get(key)?.let { return LoadResult(it, true, page, page + 1) }
        return lockFor(key).withLock {
            if (!force) memory.get(key)?.let { return@withLock LoadResult(it, true, page, page + 1) }
            val all = if (arcade) {
                PluginRepository.loadByPlatform(ctx, GameCategory.ARCADE.key)
            } else {
                PluginRepository.loadMany(ctx, PluginRepository.sources(ctx).filter { it.platform != GameCategory.ARCADE.key })
            }
            if (all.isEmpty()) throw EmptySiteException("没有配置第三方源，去设置里添加")
            val maxPage = (all.size + PAGE_SIZE - 1) / PAGE_SIZE
            if (page > maxPage) throw EmptySiteException("第 $page 页超出范围")
            val items = all.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE)
            // 封面预取：失败也不影响本页展示（占位图兜底）
            val withCovers = runCatching { PluginRepository.prefetchCovers(ctx, items) }
                .getOrElse { Log.w(TAG, "cover prefetch skipped: ${it.message}"); items }
            memory.put(key, withCovers)
            LoadResult(withCovers, false, page, maxPage, emptyList(), System.currentTimeMillis())
        }
    }

    /**
     * U盘本地游戏（[GameCategory.USB]）：扫描出来的全量列表在内存里切片伪装分页，
     * 和 [loadThirdParty]/普通分类同一套分页逻辑。挂载卷变更时重新扫描；
     * 没有可用文件时按"空站点"处理，让 UI 显示可重试的错误并带排查提示。
     */
    private suspend fun loadUsb(page: Int, force: Boolean): LoadResult {
        val ctx = app ?: throw IllegalStateException("U盘游戏需要 Application 上下文")
        val category = GameCategory.USB
        val key = memKey(category.key, page)
        if (!force) memory.get(key)?.let { return LoadResult(it, true, page, page + 1) }
        return lockFor(key).withLock {
            if (!force) memory.get(key)?.let { return@withLock LoadResult(it, true, page, page + 1) }
            val all = runCatching { UsbScanner.items(ctx) }.getOrElse {
                Log.w(TAG, "usb scan failed: ${it.message}")
                emptyList()
            }
            if (all.isEmpty()) {
                throw EmptySiteException("未发现 U盘中的游戏文件（支持 nes/gba/sfc/md/zip 等；请插好 U盘并在系统的存储权限里授权）")
            }
            val maxPage = (all.size + PAGE_SIZE - 1) / PAGE_SIZE
            if (page > maxPage) throw EmptySiteException("第 $page 页超出范围")
            val items = all.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE)
            memory.put(key, items)
            LoadResult(items, false, page, maxPage, emptyList(), System.currentTimeMillis())
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
     * 搜索（本地索引增强 + 可玩性过滤）：**本地索引 + 服务器**双路合并 ——
     * 本地前缀命中排最前，服务器结果其次，本地包含/标签命中垫底，按 id 去重。
     * 服务器挂了但本地有结果时返回本地（离线也能搜）。
     *
     * 本地索引（[PinyinSearchIndexer]）是主力：站点 `/search` 不支持拼音/首字母，
     * 搜"街机"只回 7 个合集标题，搜不到任何一个街机游戏。索引里 9500+ 条覆盖
     * FC/街机/SFC/GBA/MD 全部分页，所以中文查询也必须查它 —— 盒子没软键盘，
     * 用户就是打中文，纯 ASCII 才查索引等于索引形同虚设。
     *
     * 查询分派：
     *   - 纯字母（`hdl`）→ initials 前缀 + initials 包含
     *   - 含中文（`魂斗罗`/`街机`）→ 标题前缀 + 标题包含 + 标签命中
     *   两种都再补一条标题包含，混合输入（`魂dolo`）也能命中。
     *
     * 远程搜索是**全站**检索，会带回 Java / NDS / DOS / Flash 这类本 App
     * 没有原生核心的平台，点进去只能看到"暂无原生核心"。合并后统一过一遍
     * [filterPlayable]：索引命中的直接放行，其余按平台缓存/play 页判定。
     */
    suspend fun searchEx(keyword: String): LoadResult {
        val kw = keyword.trim()
        if (kw.isEmpty()) return LoadResult(emptyList(), false, 1, 1)
        val ascii = kw.all { it in 'a'..'z' || it in 'A'..'Z' }
        val prefix = mutableListOf<GameItem>()
        val contains = mutableListOf<GameItem>()
        withContext(Dispatchers.IO) {
            val dao = db.searchIndexDao()
            val q = kw.lowercase()
            if (ascii) {
                prefix += dao.queryPrefix(q).map { it.toItem() }
                contains += dao.queryContains(q).map { it.toItem() }
                contains += dao.queryNameContains(kw).map { it.toItem() }
            } else {
                prefix += dao.queryNamePrefix(kw).map { it.toItem() }
                contains += dao.queryNameContains(kw).map { it.toItem() }
                // 搜"街机"/"GBA"这类平台名：直接出该分类的游戏（站点卡片标签里没有平台名）
                GameCategory.fromLabel(kw)
                    ?.takeIf { it.playable }
                    ?.let { cat -> contains += dao.queryByCategory(cat.key).map { it.toItem() } }
                // 标签命中排在标题包含之后：搜"射击"应先给出标题带"射击"的
                contains += dao.queryByTag(kw).map { it.toItem() }
            }
        }
        val server = runCatching { search(kw) }
        if (server.isFailure && prefix.isEmpty() && contains.isEmpty()) {
            throw server.exceptionOrNull() ?: IllegalStateException("search failed")
        }
val serverItems = server.getOrNull()?.items ?: emptyList()
        val used = prefix.mapTo(HashSet()) { it.id }
        // 第三方源放在服务器结果之后、本地包含之前：用户自己加的源不该被官方站压住，
        // 但也不该抢走"标题精确匹配"的第一位
        val pluginItems = pluginMatches(kw)
        val merged = prefix +
                serverItems.filterNot { it.id in used }.onEach { used.add(it.id) } +
                pluginItems.filterNot { it.id in used } +
                contains.filterNot { it.id in used }
        val playable = filterPlayable(merged)
        return LoadResult(playable, false, 1, 1,
            fetchedAt = System.currentTimeMillis())
    }

    /**
     * 只保留有原生核心的结果。判定顺序（命中即止，避免多余请求）：
     *   1. **卡片标签**：明确写着 NDS/Java/DOS/Flash/H5 的直接淘汰（零请求，最稳）；
     *   2. 本地拼音索引 —— 索引只建 FC/街机/SFC/GBA/MD，命中即可玩；
     *   3. `game_platform` 平台缓存 —— 之前解析过 play 页，结论直接复用；
     *   4. 现查 play 页（并发 [PROBE_CONCURRENCY]，单次搜索最多 [MAX_PLATFORM_PROBE] 个），
     *      结果写缓存。
     *
     * 抓不到 play 页（离线/限流/改版）时**保守放行**，宁可多显示也不误伤 —— 但这会让
     * "搜不出来"变成常态，所以 [MAX_PLATFORM_PROBE] 必须覆盖单次搜索的全部未知条目
     * （实测街机等关键词会带来几十条无标签结果），否则街机这类有核心的内容会被误杀。
     */
    private suspend fun filterPlayable(items: List<GameItem>): List<GameItem> {
        if (items.isEmpty()) return items
        // ① 标签就能判死的，先摘掉，不占后面的探测名额
        val byTag = items.filterNot { tagSaysUnplayable(it) }
        if (byTag.size != items.size) {
            Log.i(TAG, "search dropped ${items.size - byTag.size} game(s) by platform tag")
        }
        val ids = byTag.map { it.id }.distinct()
        val playable = HashMap<String, Boolean>(ids.size)
        withContext(Dispatchers.IO) {
            db.searchIndexDao().categoriesOf(ids).forEach {
                playable[it.gameId] = CoreRouter.isPlayableCategory(it.categoryKey)
            }
            db.gamePlatformDao().byIds(ids).forEach {
                playable[it.gameId] = CoreRouter.isPlayableCategory(it.categoryKey)
            }
        }
        val unknown = ids.filter { it !in playable }.take(MAX_PLATFORM_PROBE)
        if (unknown.isNotEmpty()) {
            val rows = coroutineScope {
                unknown.chunked(PROBE_CONCURRENCY).flatMap { chunk ->
                    chunk.map { id ->
                        async(Dispatchers.IO) {
                            when (val p = RomProvider.probePlatform(id)) {
                                // 抓不到就当可玩（放行），不写缓存，下次再试
                                is CoreRouter.Platform.Unknown -> null
                                is CoreRouter.Platform.Native -> id to GamePlatformEntity(
                                    id, p.categoryKey, p.coreName, System.currentTimeMillis())
                                CoreRouter.Platform.Unsupported -> id to
                                        GamePlatformEntity(id, "", "", System.currentTimeMillis())
                            }
                        }
                    }.awaitAll()
                }.filterNotNull()
            }
            if (rows.isNotEmpty()) {
                rows.forEach { (id, row) ->
                    playable[id] = CoreRouter.isPlayableCategory(row.categoryKey)
                }
                withContext(Dispatchers.IO) { db.gamePlatformDao().upsertAll(rows.map { it.second }) }
            }
        }
        val kept = byTag.filter { playable[it.id] != false }
        if (kept.size != byTag.size) {
            Log.i(TAG, "search filtered out ${byTag.size - kept.size} game(s) without native core")
        }
        return kept
    }

    /**
     * 卡片标签明确写着没核心的平台（NDS / Java / DOS / Flash / H5）→ 直接淘汰。
     * 题材类标签（"运动比赛"）返回 null，走后面的索引/平台缓存/play 页判定。
     */
    private fun tagSaysUnplayable(item: GameItem): Boolean {
        val cat = item.tags.firstNotNullOfOrNull { GameCategory.fromLabel(it) }
        return cat != null && !cat.playable
    }

    /**
 * 第三方源里的匹配项：标题包含关键词，或标签命中（题材/拼音首字母）。
     * 第三方清单没有索引表，直接在内存里线性扫几百条，成本可以忽略。
     */
    private suspend fun pluginMatches(kw: String): List<GameItem> {
        val ctx = app ?: return emptyList()
        val q = kw.lowercase()
        return runCatching {
            PluginRepository.loadAll(ctx)
        }.getOrElse { emptyList() }.filter { item ->
            item.name.lowercase().contains(q) ||
                    item.tags.any { it.lowercase().contains(q) }
        }.take(60)
    }

    /** 拼音索引条目数（UI 展示用） */
    suspend fun searchIndexCount(): Int =
        withContext(Dispatchers.IO) { db.searchIndexDao().count() }

    /** 索引过期/为空则后台重建（幂等，重复调用无副作用） */
    suspend fun ensureSearchIndex(context: android.content.Context) =
        PinyinSearchIndexer.ensureFresh(context, db)

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

    /** 是否已收藏（游戏内工具条按钮文案用，不依赖内存镜像加载时机） */
    suspend fun isFavorite(gameId: String): Boolean =
        withContext(Dispatchers.IO) { db.favoriteDao().isFavorite(gameId) }

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

    private fun SearchIndexEntity.toItem() = GameItem(
        id = gameId,
        name = name,
        coverUrl = coverUrl,
        playUrl = playUrl,
        tags = if (tags.isBlank()) emptyList() else tags.split("|"),
        source = GameItem.SOURCE_SEARCH
    )
}
