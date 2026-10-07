package com.xbw.tv.ui.lobby

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xbw.tv.R
import com.xbw.tv.XbwApplication
import com.xbw.tv.data.model.GameCategory
import com.xbw.tv.data.model.GameItem
import com.xbw.tv.databinding.ActivityLobbyBinding
import com.xbw.tv.ui.common.Nav
import com.xbw.tv.ui.search.SearchActivity
import com.xbw.tv.ui.settings.SettingsActivity
import kotlinx.coroutines.launch

/**
 * 大厅（TV 启动入口，方案第二节 UI 层 + 第六节流程 1/2）。
 *
 * 焦点策略：
 *  - 首次进入：焦点自动落到第一张游戏卡片（requestFocus 延迟到布局完成）；
 *  - 顶栏按钮（搜索/设置）可被上方向键聚焦；
 *  - 错误态：焦点强制到「重试」按钮（方案第六节：重试按 A）；
 *  - 分类切换：列表刷新后焦点回第一张卡；
 *  - B 键（BACK）：在顶层 = 系统退出，符合 TV 习惯，不劫持。
 */
class LobbyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLobbyBinding
    private lateinit var viewModel: LobbyViewModel
    private lateinit var cardAdapter: GameCardAdapter
    private lateinit var chipAdapter: CategoryChipAdapter

    private var pendingFocusPosition: Int? = null

    private companion object {
        const val PERM_REQUEST_USB = 1001
    }

    /** Android 6~9：扫 U盘需要读写外部存储权限，授权后立刻重扫当前分类 */
    private fun ensureUsbAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        ) return
        val perm = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(perm), PERM_REQUEST_USB)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQUEST_USB && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            if (viewModel.currentCategory == GameCategory.USB) {
                pendingFocusPosition = 0
                viewModel.enter(GameCategory.USB, forceRefresh = true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLobbyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this, LobbyViewModel.factory(application)).get(LobbyViewModel::class.java)

        setupTopBar()
        setupCategoryChips()
        setupGrid()
        setupPager()
        observe()
        observeFavorites()

        viewModel.enter(GameCategory.ALL)
    }

    private fun setupTopBar() {
        binding.btnSearch.setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        binding.btnPlugins.setOnClickListener {
            startActivity(Intent(this, com.xbw.tv.ui.plugin.PluginsActivity::class.java))
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnRetry.setOnClickListener { viewModel.refresh() }
    }

    private fun setupCategoryChips() {
        chipAdapter = CategoryChipAdapter { category ->
            // 进 U盘分类前先把存储权限要到（Android 6~9 老盒子可直接读挂载卷）
            if (category == GameCategory.USB) ensureUsbAccess()
            viewModel.enter(category)
            pendingFocusPosition = 0
        }
        binding.rvCategories.apply {
            layoutManager = LinearLayoutManager(this@LobbyActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = chipAdapter
            // 横向列表焦点进出不要抢跑：保留焦点恢复默认行为
            descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        chipAdapter.submitList(GameCategory.lobbyChips)
    }

    private fun setupGrid() {
        cardAdapter = GameCardAdapter(
            onClick = { item -> Nav.openGame(this, item) },
            onToggleFavorite = { item -> toggleFavorite(item) }
        )
        binding.rvGames.apply {
            adapter = cardAdapter
            // 禁用增删/移动动画：DefaultItemAnimator 的 move 动画与 TvFocusAnimator
            // 的 bringChildToFront 冲突，会在动画结束回收时报
            // "Scrapped or attached views may not be recycled" 直接崩（实机复现）
            itemAnimator = null
            // TV 网格：列数随屏幕宽度自适应（卡片固定 148dp + 16dp 外边距）
            layoutManager = object : GridLayoutManager(this@LobbyActivity, spanCount()) {
                override fun onRequestChildFocus(parent: RecyclerView, state: RecyclerView.State, child: View, focused: View?): Boolean {
                    val r = super.onRequestChildFocus(parent, state, child, focused)
                    scrollToChildRow(child)
                    return r
                }
            }
            // 焦点离开视口时自动滚动：GridLayoutManager 默认按行滚，这里补一个居中偏移
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                pendingFocusPosition?.let { pos ->
                    pendingFocusPosition = null
                    if (pos < cardAdapter.itemCount) {
                        scrollToPosition(pos)
                        post { (layoutManager as? GridLayoutManager)?.findViewByPosition(pos)?.requestFocus() }
                    }
                }
            }
        }
    }

    private fun spanCount(): Int {
        val width = resources.displayMetrics.widthPixels
        val cardW = (148 + 16) * resources.displayMetrics.density.toInt() // 卡片+左右 margin
        return (width / cardW).coerceIn(4, 6)
    }

    /**
     * 分页行：整页切换（替代旧的无滚动触底自动加载）。
     * 首页禁用「上一页」，末页禁用「下一页」；翻页后焦点回第一张卡片。
     */
    private fun setupPager() {
        binding.btnPrevPage.setOnClickListener {
            pendingFocusPosition = 0
            viewModel.prevPage()
        }
        binding.btnNextPage.setOnClickListener {
            pendingFocusPosition = 0
            viewModel.nextPage()
        }
        // 页码标签即下拉入口
        binding.pagerLabel.setOnClickListener { showPagePicker() }
    }

    private fun scrollToChildRow(child: View) {
        // 底部半行被遮时抬升：让聚焦卡片完整可见（配合放大 1.08 不裁切）
        val rv = binding.rvGames
        val lm = rv.layoutManager as? GridLayoutManager ?: return
        val pos = lm.getPosition(child)
        if (pos == RecyclerView.NO_POSITION) return
        val bottomVisible = rv.childViewBottomVisible(child)
        if (!bottomVisible) rv.smoothScrollBy(0, child.height / 2)
    }

    private fun observe() {
        viewModel.state.observe(this) { state -> render(state) }
        viewModel.categories.observe(this) { chipAdapter.submitList(it) }
    }

    /** 收藏：加载一次 + 订阅变更（卡片 ★ 与「我的收藏」页签共用同一份数据） */
    private fun observeFavorites() {
        val repo = XbwApplication.repository(application)
        lifecycleScope.launch { repo.loadFavorites() }
        lifecycleScope.launch {
            repo.favoriteIds.collect { ids -> cardAdapter.favoriteIds = ids }
        }
    }

    private fun toggleFavorite(item: GameItem) {
        lifecycleScope.launch {
            val added = XbwApplication.repository(application).toggleFavorite(item)
            Nav.toast(this@LobbyActivity, getString(if (added) R.string.fav_added else R.string.fav_removed, item.name))
        }
    }

    /** 按状态切可见性（Loading / Content / Refreshing / Error） */
    private fun render(state: LobbyViewModel.State) {
        when (state) {
            is LobbyViewModel.State.Idle -> Unit
            is LobbyViewModel.State.Loading -> {
                binding.loadingPanel.visibility = View.VISIBLE
                binding.errorPanel.visibility = View.GONE
                binding.rvGames.visibility = View.GONE
                binding.statusLine.visibility = View.GONE
                binding.pagerRow.visibility = View.GONE
            }
            is LobbyViewModel.State.Refreshing -> {
                binding.loadingPanel.visibility = View.GONE
                binding.errorPanel.visibility = View.GONE
                binding.rvGames.visibility = View.VISIBLE
                binding.statusLine.visibility = View.VISIBLE
                binding.statusLine.text = getString(R.string.refreshing)
                cardAdapter.submitList(state.items)
            }
            is LobbyViewModel.State.Content -> {
                binding.loadingPanel.visibility = View.GONE
                binding.errorPanel.visibility = View.GONE
                binding.rvGames.visibility = View.VISIBLE
                binding.statusLine.visibility = View.VISIBLE
                val warn = state.warnings.firstOrNull()
                binding.statusLine.text = when {
                    warn != null -> warn
                    state.fromCache -> getString(R.string.cache_hint, Nav.relativeTime(state.fetchedAt))
                    else -> "共 ${state.items.size} 款"
                }
                updatePager(state)
                // 网格铺平：整页数据（非末页）裁到列数的整数倍，避免最后一行剩 2 张的锯齿排布；
                // 末页/单页列表（最近玩过/收藏/搜索）不裁，防止吞掉真实条目
                val span = (binding.rvGames.layoutManager as? GridLayoutManager)?.spanCount ?: 1
                val shown = if (state.maxPage > 1 && state.page < state.maxPage && span > 1)
                    state.items.take(state.items.size / span * span) else state.items
                cardAdapter.submitList(shown) {
                    // 数据替换完成后再恢复焦点，避免定位到旧位置
                    pendingFocusPosition?.let { pos ->
                        pendingFocusPosition = null
                        if (pos < state.items.size) {
                            binding.rvGames.scrollToPosition(pos)
                            binding.rvGames.post {
                                binding.rvGames.layoutManager?.findViewByPosition(pos)?.requestFocus()
                            }
                        }
                    }
                }
            }
            is LobbyViewModel.State.Error -> {
                binding.loadingPanel.visibility = View.GONE
                binding.rvGames.visibility = View.GONE
                binding.errorPanel.visibility = View.VISIBLE
                binding.errorTitle.text = when (state.kind) {
                    LobbyViewModel.State.Kind.NETWORK -> getString(R.string.error_network)
                    LobbyViewModel.State.Kind.SITE_CHANGED -> getString(R.string.error_site_changed)
                    LobbyViewModel.State.Kind.EMPTY -> getString(R.string.error_empty)
                    else -> getString(R.string.error_network)
                }
                binding.errorDetail.text = state.message
                binding.statusLine.visibility = View.GONE
                binding.pagerRow.visibility = View.GONE
                // 错误态：焦点交给重试按钮（方案第六节）
                binding.btnRetry.requestFocus()
            }
        }
    }

    /** 同步分页行：单页（如「最近玩过」/搜索）整行隐藏，其余按页禁用两端 */
    private fun updatePager(state: LobbyViewModel.State.Content) {
        if (state.maxPage <= 1) {
            binding.pagerRow.visibility = View.GONE
            return
        }
        binding.pagerRow.visibility = View.VISIBLE
        binding.pagerLabel.text = getString(R.string.lobby_page_fmt, state.page, state.maxPage) + " ▾"
        binding.btnPrevPage.isEnabled = state.page > 1
        binding.btnNextPage.isEnabled = state.page < state.maxPage
    }

    /** 页码下拉：点「第 X/Y 页 ▾」弹出全部页码列表，遥控器上下选页直达 */
    private fun showPagePicker() {
        val st = viewModel.state.value as? LobbyViewModel.State.Content ?: return
        val pages = Array(st.maxPage) { "第 ${it + 1} 页" }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.lobby_page_picker_title)
            .setItems(pages) { _, which ->
                pendingFocusPosition = 0
                viewModel.goToPage(which + 1)
            }
            .show()
    }

    /** 从游戏页返回时强制后台刷新（方案 3.4：缓存只是加速，回到大厅看最新） */
    override fun onResume() {
        super.onResume()
        if (viewModel.state.value != null &&
            viewModel.state.value !is LobbyViewModel.State.Loading
        ) {
            viewModel.enter(viewModel.currentCategory, forceRefresh = true)
        }
    }

    /**
     * 遥控器 BACK 在顶层 = 退出；如果焦点不在列表顶部，
     * 先回到第一张卡片（防止遥控器在分类行误按退出）。
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            val lm = binding.rvGames.layoutManager as? GridLayoutManager
            val firstVisible = (lm?.findFirstVisibleItemPosition() ?: 0)
            if (firstVisible > 0) {
                binding.rvGames.scrollToPosition(0)
                binding.rvGames.post { lm?.findViewByPosition(0)?.requestFocus() }
                return true
            }
        }
        // 菜单键 = 收藏当前聚焦的卡片（遥控器好按；手柄用长按 A/OK 也行）
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            var focused: View? = binding.rvGames.findFocus()
            while (focused != null && focused.parent !== binding.rvGames) {
                focused = focused.parent as? View
            }
            if (focused != null) {
                val pos = (binding.rvGames.layoutManager as? GridLayoutManager)?.getPosition(focused)
                    ?: RecyclerView.NO_POSITION
                if (pos != RecyclerView.NO_POSITION) {
                    cardAdapter.itemAt(pos)?.let { toggleFavorite(it) }
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}

/**
 * 子 View 底部是否完整可见（rv 的 childViewBottomVisible 在旧版 RecyclerView 才有；
 * 这里自己实现避免版本差异）。
 */
private fun RecyclerView.childViewBottomVisible(child: View): Boolean {
    val rvBottom = bottom - paddingBottom
    return child.bottom <= rvBottom
}
