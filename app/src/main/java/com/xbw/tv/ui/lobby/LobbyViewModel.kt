package com.xbw.tv.ui.lobby

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.xbw.tv.XbwApplication
import com.xbw.tv.data.model.GameCategory
import com.xbw.tv.data.model.GameItem
import com.xbw.tv.data.net.HttpFetcher
import com.xbw.tv.data.net.YikmParser
import com.xbw.tv.data.plugin.PluginRepository
import com.xbw.tv.data.repo.GameRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 大厅 ViewModel（MVVM 的 M↔V 中间层）。
 *
 * 加载时序（方案 3.4 的 stale-while-revalidate）：
 *   enter(category)
 *     ├─ 立刻 emit 缓存（若有）→ UI 秒开，状态 CacheFirst
 *     └─ 后台强制抓取 → 成功 emit 新数据 RemoteFresh
 *                        失败且无缓存 → Error（可重试）
 *                        失败有缓存  → 保留缓存 + warning
 *
 * 分类不是写死的：内置分类（[GameCategory.lobbyChips]）+ 每个用户自建第三方源
 * 都是大厅顶部的独立芯片（[LobbyCategory]），源删了芯片就消失。
 */
class LobbyViewModel(
    private val app: Application,
    private val repo: GameRepository
) : ViewModel() {

    /** UI 状态：一个 sealed class 覆盖方案第九步的 Loading/Error/Empty */
    sealed class State {
        object Idle : State()
        /** 正在首次加载（无内容可展示）*/
        object Loading : State()
        /** 已有内容由展示，后台在刷新（顶部小进度提示）*/
        data class Refreshing(val items: List<GameItem>) : State()
        /** 已展示：fromCache=true 时右上角显示"更新于 x 前 · 点击刷新" */
        data class Content(
            val items: List<GameItem>,
            val fromCache: Boolean,
            val page: Int,
            val maxPage: Int,
            val warnings: List<String> = emptyList(),
            val fetchedAt: Long = 0L
        ) : State() {
            val hasNextPage: Boolean get() = maxPage > page
        }
        data class Error(val message: String, val kind: Kind) : State()
        enum class Kind { NETWORK, SITE_CHANGED, EMPTY, UNKNOWN }

        val itemsOrNull: List<GameItem>?
            get() = when (this) {
                is Content -> items
                is Refreshing -> items
                else -> null
            }
    }

    private val _state = MutableLiveData<State>(State.Idle)
    val state: LiveData<State> = _state

    /** 顶部芯片：内置分类 + 用户自建源（顺序：内置可玩 → 用户源 → U盘/收藏/最近） */
    private val _categories = MutableLiveData<List<LobbyCategory>>(emptyList())
    val categories: LiveData<List<LobbyCategory>> = _categories

    var currentCategory: LobbyCategory = LobbyCategory.BuiltIn(GameCategory.ALL)
        private set

    /** 当前页：进游戏退出后 onResume 会重新 enter，必须回到本页而不是第 1 页 */
    private var currentPage = 1

    /** 首页热门区块（大厅顶部横向卡片行用） */
    private val _homeSections = MutableLiveData<List<YikmParser.HomeSection>>()
    val homeSections: LiveData<List<YikmParser.HomeSection>> = _homeSections

    private var loadJob: Job? = null
    private var pageJob: Job? = null

    init {
        reloadCategories()
    }

    /**
     * 重新装配分类芯片（进大厅/编辑源返回后调用）：
     * 内置分类固定 + 每个用户源一个芯片（标题=用户写的显示名，位置在 MD 后）。
     * 当前选中是已删源的话落回「全部游戏」。
     */
    fun reloadCategories() {
        val chips = GameCategory.lobbyChips
        val mdAt = chips.indexOf(GameCategory.MD) + 1
        val head = chips.take(mdAt).map { LobbyCategory.BuiltIn(it) }
        val tail = chips.drop(mdAt).map { LobbyCategory.BuiltIn(it) }
        val plugins = PluginRepository.sources(app).map { LobbyCategory.Plugin(it) }
        val next = head + plugins + tail
        _categories.value = next
        val cur = currentCategory
        if (cur is LobbyCategory.Plugin && next.none { it.key == cur.key }) {
            currentCategory = LobbyCategory.BuiltIn(GameCategory.ALL)
        }
    }

    private suspend fun cachedFor(category: LobbyCategory, page: Int): GameRepository.LoadResult? =
        when (category) {
            is LobbyCategory.BuiltIn ->
                repo.peekCache(category.category, page)
                    ?: runCatching { repo.cachedList(category.category, page) }.getOrNull()
            is LobbyCategory.Plugin -> repo.peekPluginCache(category.source.id, page)
        }

    private suspend fun fetchFor(category: LobbyCategory, page: Int): GameRepository.LoadResult =
        when (category) {
            is LobbyCategory.BuiltIn -> repo.fetchList(category.category, page, force = true)
            is LobbyCategory.Plugin -> repo.fetchPlugin(category.source, page, force = true)
        }

    /** 首次进入分类：缓存先行 + 后台刷新 */
    fun enter(category: LobbyCategory, forceRefresh: Boolean = true) {
        val switched = currentCategory.key != category.key
        if (loadJob?.isActive == true && !switched && !forceRefresh) return
        if (switched) currentPage = 1   // 换分类从第 1 页开始
        val target = currentPage
        currentCategory = category
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = State.Loading
            // 1) 先给内存/磁盘缓存
            val cached = cachedFor(category, target)
            if (!cached?.items.isNullOrEmpty()) {
                _state.value = State.Content(
                    cached!!.items, true, cached.page, cached.maxPage.coerceAtLeast(cached.page),
                    fetchedAt = cached.fetchedAt
                )
            }
            // 2) 强制联网刷新（RECENT 分类走本地）
            _state.value = if (_state.value is State.Content)
                State.Refreshing((_state.value as State.Content).items)
            else State.Loading

            runCatching { fetchFor(category, target) }
                .onSuccess { r ->
                    _state.value = State.Content(r.items, false, r.page, r.maxPage, r.warnings, r.fetchedAt)
                }
                .onFailure { e ->
                    val keep = _state.value?.itemsOrNull
                    when {
                        e is YikmParser.ParseException ->
                            _state.value = if (keep != null)
                                State.Content(keep, true, target, target,
                                    listOf("站点结构可能已变更：${e.message}（当前展示的是缓存）"))
                            else State.Error("站点结构可能已变更：${e.message}", State.Kind.SITE_CHANGED)
                        e is HttpFetcher.FetchException || e is java.io.IOException ->
                            _state.value = if (keep != null)
                                State.Content(keep, true, target, target, listOf("网络异常，展示缓存：${e.message}"))
                            else State.Error("网络请求失败：${e.message}", State.Kind.NETWORK)
                        e is GameRepository.EmptySiteException ->
                            _state.value = if (keep != null)
                                State.Content(keep, true, target, target, listOf("站点返回空列表，展示缓存"))
                            else State.Error("这个分类暂时没有可玩的游戏", State.Kind.EMPTY)
                        else ->
                            _state.value = State.Error(e.message ?: "未知错误", State.Kind.UNKNOWN)
                    }
                }
        }
    }

    /**
     * 翻页：整页替换当前列表（不再追加合并）。
     *
     * 失败时**停留在当页**：已展示的内容不动，只把提示写进 warnings
     * （状态行渲染 warnings.firstOrNull()），避免用户翻页失败后看到空白。
     */
    fun goToPage(target: Int) {
        val cur = _state.value as? State.Content ?: return
        if (target < 1 || target > cur.maxPage || target == cur.page) return
        if (pageJob?.isActive == true) return
        val cat = currentCategory
        pageJob = viewModelScope.launch {
            // 1) 缓存先行：该页有缓存就先整页切换，避免翻页白屏
            val cached = cachedFor(cat, target)
            val hitCache = !cached?.items.isNullOrEmpty()
            currentPage = target   // 立刻记住目标页：加载中途退到游戏再回来也回这里
            _state.value = if (hitCache) {
                State.Content(
                    cached!!.items, true, target,
                    cur.maxPage.coerceAtLeast(target), fetchedAt = cached.fetchedAt
                )
            } else {
                cur.copy(warnings = listOf("正在加载第 $target 页…"))
            }

            // 2) 联网抓该页并整页替换
            runCatching { fetchFor(cat, target) }
                .onSuccess { r ->
                    _state.value = State.Content(r.items, false, r.page, r.maxPage, r.warnings, r.fetchedAt)
                }
                .onFailure { e ->
                    val shown = _state.value as? State.Content ?: cur
                    val stayPage = if (hitCache) target else cur.page
                    _state.value = shown.copy(
                        warnings = listOf("第 $target 页加载失败，仍显示第 $stayPage 页：${e.message ?: "未知错误"}")
                    )
                }
        }
    }

    fun prevPage() {
        val cur = _state.value as? State.Content ?: return
        goToPage(cur.page - 1)
    }

    fun nextPage() {
        val cur = _state.value as? State.Content ?: return
        goToPage(cur.page + 1)
    }

    fun refresh() = enter(currentCategory, forceRefresh = true)

    /** 首页区块（顶部"最近在玩/热门"横向行） */
    fun loadHomeSections() {
        if (_homeSections.value != null) return
        viewModelScope.launch {
            runCatching { repo.fetchHomeSections() }
                .onSuccess { _homeSections.value = it }
                .onFailure { android.util.Log.w("LobbyViewModel", "home sections failed", it) }
        }
    }

    companion object {
        /** 在 Activity/AppFragment 里：ViewModelProvider(this, LobbyViewModel.factory(app)) */
        fun factory(app: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    LobbyViewModel(app, XbwApplication.repository(app)) as T
            }
    }
}