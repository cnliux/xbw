package com.xbw.tv.ui.game

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.xbw.tv.core.RetroCore
import com.xbw.tv.core.RomProvider
import com.xbw.tv.data.model.GameItem
import com.xbw.tv.data.net.CheatParser
import com.xbw.tv.data.update.UpdatePrompt
import com.xbw.tv.databinding.ActivityNativeGameBinding
import com.xbw.tv.input.GameButton
import com.xbw.tv.input.KeySettings
import com.xbw.tv.ui.common.Nav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ▶ 原生核心游戏页：ROM 直接喂 libretro 核心（FC=FCEUmm，街机=FBNeo）。
 *
 * 存在的理由（用户实测反馈驱动）：
 *  - 老盒子（Mali-450/Android7）上 WebView+JS 模拟器帧率崩坏（98% jank），
 *    原生核心绕开浏览器合成栈，CPU 直出 RGB 帧到 SurfaceView；
 *  - 手柄按键不再经过"合成 KeyboardEvent → 站点混淆 JS"链路，USB 手柄
 *    任何未映射按键都被本类吞掉，不会掉进系统焦点黑洞。
 *
 * 输入模型：物理 keyCode →（KeySettings.logicalOf）→ GameButton → retro 位图。
 * 摇杆 AXIS 运动事件也折算成方向位（很多 USB 手柄方向是轴不是十字键）。
 *
 * 帧率铁律：仿真线程绝不能碰 ANativeWindow_lock（会等 SurfaceFlinger 放行，
 * 把循环压到 ~50Hz，音频喂不饱 → 游戏慢 17%、音调偏低）。视频走 3 槽交接池
 * 交给独立渲染线程，主时钟是 AudioTrack 的 WRITE_BLOCKING。详见 xbw_core.c。
 */
class NativeGameActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "extra_id"
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_COVER = "extra_cover"
        const val EXTRA_PLAY_URL = "extra_play_url"
        const val EXTRA_TAGS = "extra_tags"
        /** 第三方插件源的直链 ROM（非空时跳过 play 页直接下载） */
        const val EXTRA_ROM_URL = "extra_rom_url"
        /** 第三方插件源的候选 ROM 直链（按优先级排，下载逐个试，兜底用） */
        const val EXTRA_ROM_URLS = "extra_rom_urls"
        /** 第三方插件源条目在清单里的原始文件名（FBNeo 要按 zip 原名落盘） */
        const val EXTRA_ROM_NAME = "extra_rom_name"
        /** 第三方插件源声明的平台（GameCategory.key） */
        const val EXTRA_PLATFORM = "extra_platform"
        /** 第三方插件源解出的会话 cookie 值（源是 cookie 网盘时 ROM 也要带） */
        const val EXTRA_COOKIE = "extra_cookie"
        /** U盘本地 ROM 的绝对路径（有值时跳过下载与 play 页，直接进核心） */
        const val EXTRA_LOCAL_PATH = "extra_local_path"

        /** GameButton → libretro JOYPAD 位（键名以 retro.h 1.10 为准） */
        private val RETRO_BIT: Map<GameButton, Int> = mapOf(
            GameButton.B to RetroCore.ID_B,          // NES B 键
            GameButton.Y to RetroCore.ID_Y,
            GameButton.SELECT to RetroCore.ID_SELECT,
            GameButton.START to RetroCore.ID_START,
            GameButton.UP to RetroCore.ID_UP,
            GameButton.DOWN to RetroCore.ID_DOWN,
            GameButton.LEFT to RetroCore.ID_LEFT,
            GameButton.RIGHT to RetroCore.ID_RIGHT,
            GameButton.A to RetroCore.ID_A,
            GameButton.X to RetroCore.ID_X,
            GameButton.L to RetroCore.ID_L,
            GameButton.R to RetroCore.ID_R,
            GameButton.L3 to RetroCore.ID_L3,
            GameButton.R3 to RetroCore.ID_R3,
        )
    }

    private lateinit var binding: ActivityNativeGameBinding
    private val core = RetroCore()
    private val handler = Handler(Looper.getMainLooper())

    private var gameId = ""
    private var gameName = ""
    private var coverUrl: String? = null
    private var playUrl = ""
    private var tags: List<String> = emptyList()
    private var directRomUrl: String? = null
    private var directRomUrls: List<String> = emptyList()
    private var directRomName: String? = null
    private var directPlatform: String? = null
    private var directCookie: String? = null
    private var localRomPath: String? = null

    private var toolbarVisible = false
    private var paused = false
    private var stopped = false
    /** ROM/核心加载失败：loading 层保持可见，此时 BACK/手柄任意退出键直接 finish（不弹确认框，避免被困） */
    private var loadFailed = false

    // 金手指状态：FC（fceumm）走站点 RAM 码（retro_cheat_set），
    // 街机（fbneo）走 cheat ini 转成的 core options（retro_cheat_set 是空函数）
    private var coreName = ""
    private var cheatPanelVisible = false
    private var cheatsLoaded = false
    private var cheats: List<CheatParser.Cheat> = emptyList()
    private val enabledCheats = HashSet<Int>()
    private var arcadeOptions: List<RetroCore.CoreOption> = emptyList()
    private var arcadeOptionsLoaded = false
    private val cheatAdapter = CheatAdapter { pos -> toggleCheat(pos) }

    /** 当前按住位图（含摇杆折算位），每次变更推给 native */
    private var padBits = 0
    private val heldButtons = HashSet<GameButton>()
    private var axisBits = 0

    private val stateFile: File by lazy {
        File(filesDir, "roms/$gameId/quick.state")
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNativeGameBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        gameId = intent.getStringExtra(EXTRA_ID).orEmpty()
        gameName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        coverUrl = intent.getStringExtra(EXTRA_COVER)
        playUrl = intent.getStringExtra(EXTRA_PLAY_URL)
            .takeUnless { it.isNullOrBlank() }
            ?: "https://www.yikm.net/play?id=$gameId"
        tags = intent.getStringArrayExtra(EXTRA_TAGS)?.toList() ?: emptyList()
        directRomUrl = intent.getStringExtra(EXTRA_ROM_URL)?.takeIf { it.isNotBlank() }
        directRomUrls = intent.getStringArrayListExtra(EXTRA_ROM_URLS)?.filter { it.isNotBlank() }
            ?: emptyList()
        directRomName = intent.getStringExtra(EXTRA_ROM_NAME)?.takeIf { it.isNotBlank() }
        directPlatform = intent.getStringExtra(EXTRA_PLATFORM)
        directCookie = intent.getStringExtra(EXTRA_COOKIE)
        localRomPath = intent.getStringExtra(EXTRA_LOCAL_PATH)?.takeIf { it.isNotBlank() }

        if (gameId.isNotEmpty()) {
            lifecycleScope.launch {
                com.xbw.tv.XbwApplication.repository(application).markPlayed(
                    GameItem(id = gameId, name = gameName, coverUrl = coverUrl, playUrl = playUrl)
                )
            }
        }

        binding.gameSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { core.setSurface(holder.surface) }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { core.setSurface(null) }
        })

        wireToolbar()
        binding.hintBar.postDelayed({ binding.hintBar.visibility = View.GONE }, 6000)

        // 进游戏时静默检测是否需要升级（设置里可关；只有真有新版本才弹窗）
        UpdatePrompt.autoCheckOnGameStart(this)

        loadAndRun()
    }

    // ── 启动流水线：ROM 下载(缓存) → 核心加载 → 音频 → 仿真线程 ──────────
    private fun loadAndRun() {
        lifecycleScope.launch {
            binding.loadingText.text = "正在准备 ROM…"
            val result = withContext(Dispatchers.IO) {
                // ROM 下载进度 → 主线程刷进度条（IO 线程回调，节流 ~100ms/次）
                val onProgress: (Long, Long) -> Unit = { done, total ->
                    runOnUiThread { showRomProgress(done, total) }
                }
                // U盘本地 ROM：直接进核心，不下载也不抓 play 页；
                // 第三方插件源带直链 ROM：走 prepareDirect，别去抓 yikm play 页；
                // 候选列表优先（逐个试到真文件），老的单个直链兜底
                when {
                    localRomPath != null -> RomProvider.prepareLocal(
                        this@NativeGameActivity, gameId, File(localRomPath!!), directPlatform
                    )
                    directRomUrls.isNotEmpty() -> RomProvider.prepareDirect(
                        this@NativeGameActivity, gameId, directRomUrls, directPlatform, directCookie, directRomName, onProgress
                    )
                    directRomUrl != null -> RomProvider.prepareDirect(
                        this@NativeGameActivity, gameId, listOf(directRomUrl!!), directPlatform, directCookie, null, onProgress
                    )
                    else -> RomProvider.prepare(this@NativeGameActivity, gameId, onProgress)
                }
            }
            // 分三类提示：没核心 / 下载或站点失败（可重试）/ 真加载失败
            val spec = when (result) {
                is RomProvider.RomResult.Ready -> result.spec
                is RomProvider.RomResult.Unsupported -> {
                    failLoad("${result.platform} 暂无原生核心（可在设置里查看已支持平台）")
                    return@launch
                }
                is RomProvider.RomResult.Failed -> {
                    failLoad(result.reason)
                    return@launch
                }
            }
            coreName = spec.coreName
            binding.loadingBar.visibility = View.GONE
            binding.loadingText.text = if (spec.fromCache) "ROM 已就绪，正在启动 ${spec.coreName} 核心…"
                else "正在启动 ${spec.coreName} 核心…"
            val ok = withContext(Dispatchers.IO) {
                var ok = core.loadCore(this@NativeGameActivity, spec.coreName, spec.systemDir, spec.romFile)
                // 修改版 zip 的驱动名可能不在本版 FBNeo 数据表里 → 逐段兜底基础版
                if (!ok && spec.fallbacks.isNotEmpty()) {
                    for (fb in spec.fallbacks) {
                        Log.i("NativeGame", "primary rom failed, fallback: ${fb.name}")
                        ok = core.loadAlternativeRom(fb)
                        if (ok) break
                    }
                }
                if (!ok) return@withContext false
                val av = core.avInfo()   // [w, h, fps*100, rate]
                core.startAudio(if (av[3] > 0) av[3] else 44100)
                core.setSurface(binding.gameSurface.holder.surface)
                core.start()
            }
            if (stopped) return@launch
            if (!ok) {
                failLoad("核心加载失败")
                return@launch
            }
            binding.loadingOverlay.visibility = View.GONE
            binding.metaBadge.text = "$gameName · ${spec.coreName}"
            binding.metaBadge.visibility = View.VISIBLE
        }
    }

    private fun failLoad(msg: String) {
        // 加载失败：loading 层保持，BACK/手柄退出键直接 finish（见 dispatchKeyEvent 的 loadFailed 分支）
        loadFailed = true
        binding.loadingBar.visibility = View.GONE
        binding.loadingText.text = "$msg\n按返回键退出"
        toast(msg)
    }

    /** ROM 下载进度（主线程调用）：total>0 显示百分比进度条，否则只显示已下大小 */
    private var lastProgressAt = 0L
    private fun showRomProgress(done: Long, total: Long) {
        if (loadFailed) return
        val now = System.currentTimeMillis()
        val finished = total > 0 && done >= total
        if (!finished && now - lastProgressAt < 100) return
        lastProgressAt = now
        val mb = done / 1048576.0
        if (total > 0) {
            val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
            binding.loadingBar.visibility = View.VISIBLE
            binding.loadingBar.progress = pct
            binding.loadingText.text = "正在下载 ROM… %d%%（%.1f/%.1f MB）".format(pct, mb, total / 1048576.0)
        } else {
            binding.loadingText.text = "正在下载 ROM… %.1f MB".format(mb)
        }
    }

    // ── 工具条 ─────────────────────────────────────────────────────────
    private fun wireToolbar() {
        binding.btnPause.setOnClickListener { togglePause() }
        binding.btnReset.setOnClickListener { core.reset(); toast("已重置") }
        binding.btnSaveState.setOnClickListener {
            val bytes = core.saveState()
            if (bytes != null) {
                stateFile.writeBytes(bytes)
                toast("已存档（${bytes.size / 1024} KB）")
            } else toast("存档失败：核心未运行")
        }
        binding.btnLoadState.setOnClickListener {
            if (stateFile.exists() && core.loadState(stateFile.readBytes())) toast("已读档")
            else toast("没有即时存档")
        }
        binding.btnCheat.setOnClickListener { openCheatPanel() }
        binding.btnCheatOffAll.setOnClickListener { clearCheats() }
        binding.btnFavorite.setOnClickListener {
            lifecycleScope.launch {
                val repo = com.xbw.tv.XbwApplication.repository(application)
                val added = repo.toggleFavorite(favoriteItem())
                binding.btnFavorite.text = if (added) "已收藏" else "收藏"
                toast(if (added) "已加入收藏" else "已取消收藏")
            }
        }
        binding.btnExit.setOnClickListener { confirmExit() }

        binding.cheatList.layoutManager = LinearLayoutManager(this)
        // 同大厅：item 动画与焦点缩放冲突会把行弄丢，直接关掉
        binding.cheatList.itemAnimator = null
        binding.cheatList.adapter = cheatAdapter
    }

    private fun togglePause() {
        if (!core.running) return
        paused = !paused
        core.setPaused(paused)
        binding.btnPause.text = if (paused) "继续" else "暂停"
    }

    private fun showToolbar() {
        toolbarVisible = true
        clearPad()
        binding.toolbar.visibility = View.VISIBLE
        refreshFavoriteLabel()
        binding.toolbar.post { binding.btnPause.requestFocus() }
    }

    private fun favoriteItem() = com.xbw.tv.data.model.GameItem(
        id = gameId,
        name = gameName,
        coverUrl = coverUrl,
        playUrl = playUrl,
        tags = tags,
        source = com.xbw.tv.data.model.GameItem.SOURCE_FAVORITE
    )

    private fun refreshFavoriteLabel() {
        if (gameId.isEmpty()) return
        lifecycleScope.launch {
            val repo = com.xbw.tv.XbwApplication.repository(application)
            val fav = withContext(kotlinx.coroutines.Dispatchers.IO) { repo.isFavorite(gameId) }
            binding.btnFavorite.text = if (fav) "已收藏" else "收藏"
        }
    }

    private fun hideToolbar() {
        toolbarVisible = false
        binding.toolbar.visibility = View.GONE
        binding.gameSurface.requestFocus()
    }

    // ── 金手指面板（FC=RAM 码开关，街机=选项环切）──────────────────────
    private fun openCheatPanel() {
        if (!core.loaded || !core.running) {
            toast("核心未运行")
            return
        }
        if (coreName != "fceumm" && coreName != "fbneo") {
            toast("该平台暂不支持金手指（当前支持 FC / 街机）")
            return
        }
        clearPad()
        toolbarVisible = false
        binding.toolbar.visibility = View.GONE
        cheatPanelVisible = true
        binding.cheatPanel.visibility = View.VISIBLE
        binding.cheatTitle.text = "金手指 · $gameName"
        if (coreName == "fbneo") loadArcadeOptions() else loadFcCheats()
    }

    /** 街机：选项在 loadCore 时已由核心下发，读出来渲染即可 */
    private fun loadArcadeOptions() {
        if (!arcadeOptionsLoaded) {
            arcadeOptions = core.coreOptions()
            arcadeOptionsLoaded = true
            if (arcadeOptions.isEmpty()) Log.i("NativeGame", "no arcade cheat options id=$gameId")
        }
        renderArcadeRows()
        focusFirstCheat()
    }

    private fun loadFcCheats() {
        if (!cheatsLoaded) {
            cheatsLoaded = true
            lifecycleScope.launch {
                val list = try {
                    withContext(Dispatchers.IO) {
                        com.xbw.tv.XbwApplication.repository(application).fetchCheats(gameId)
                    }
                } catch (e: Exception) {
                    Log.w("NativeGame", "fetch cheats failed id=$gameId", e)
                    cheatsLoaded = false   // 失败后下次打开重试
                    if (cheatPanelVisible) {
                        hideCheatPanel()
                        toast("金手指获取失败")
                    }
                    return@launch
                }
                if (!cheatPanelVisible) return@launch   // 等待期间面板已被关掉
                cheats = list
                renderFcCheats()
                focusFirstCheat()
            }
        } else {
            renderFcCheats()
            focusFirstCheat()
        }
    }

    private fun showEmptyOrList(empty: Boolean) {
        binding.cheatEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.cheatList.visibility = if (empty) View.GONE else View.VISIBLE
        binding.btnCheatOffAll.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun fcRow(i: Int) = CheatAdapter.Row(cheats[i].name, cheats[i].raw, i in enabledCheats)

    private fun arcadeRow(o: RetroCore.CoreOption) = CheatAdapter.Row(
        title = o.desc.substringAfter("] ").ifBlank { o.desc },
        subtitle = o.currentValue.orEmpty(),
        on = o.currentIndex != 0,   // "0 - Disabled" 视为关闭
    )

    private fun renderFcCheats() {
        showEmptyOrList(cheats.isEmpty())
        cheatAdapter.submit(cheats.indices.map { fcRow(it) })
    }

    private fun renderArcadeRows() {
        showEmptyOrList(arcadeOptions.isEmpty())
        cheatAdapter.submit(arcadeOptions.map { arcadeRow(it) })
    }

    /** notifyDataSetChanged 后子 View 要等下一帧布局才存在，重试直到首行可聚焦 */
    private fun focusFirstCheat(attempt: Int = 0) {
        if (attempt >= 6) return
        if (attempt == 0) binding.cheatList.scrollToPosition(0)   // 可能已滚到列表底部
        binding.cheatList.postDelayed({
            if (!cheatPanelVisible) return@postDelayed
            val vh = binding.cheatList.findViewHolderForAdapterPosition(0)
            // requestFocus 可能因面板尚未完成布局而失败（返回 false），要和"行还没生成"一样重试
            if (vh == null || !vh.itemView.requestFocus()) focusFirstCheat(attempt + 1)
        }, 40L)
    }

    /** 焦点落在第几行；焦点不在列表上返回 -1 */
    private fun focusedCheatPosition(): Int {
        val child = binding.cheatList.focusedChild ?: return -1
        return binding.cheatList.getChildAdapterPosition(child)
    }

    private fun focusIsLastCheatRow(): Boolean {
        val pos = focusedCheatPosition()
        return pos >= 0 && pos == cheatAdapter.itemCount - 1
    }

    private fun focusIsFirstCheatRow(): Boolean = focusedCheatPosition() == 0

    /** 从"全部关闭"回到列表末行：先滚到底，再聚焦最后一个已绑定行，滚完没到底就重试 */
    private fun focusLastCheatRow(attempt: Int = 0) {
        if (!cheatPanelVisible || cheatAdapter.itemCount == 0) return
        binding.cheatList.scrollToPosition(cheatAdapter.itemCount - 1)
        binding.cheatList.postDelayed({
            if (!cheatPanelVisible) return@postDelayed
            val vh = binding.cheatList.findViewHolderForAdapterPosition(cheatAdapter.itemCount - 1)
            if (vh != null) {
                if (!vh.itemView.requestFocus() && attempt < 6) focusLastCheatRow(attempt + 1)
                return@postDelayed
            }
            // scrollToPosition 还没滚到底：重试；实在等不到就退而聚焦最后一个可见行
            if (attempt < 6) focusLastCheatRow(attempt + 1)
            else (0 until binding.cheatList.childCount)
                .mapNotNull { binding.cheatList.getChildAt(it) }
                .lastOrNull()?.requestFocus()
        }, 40L)
    }

    private fun toggleCheat(pos: Int) {
        if (coreName == "fbneo") {
            if (pos !in arcadeOptions.indices) return
            val o = arcadeOptions[pos]
            if (o.values.isEmpty()) return
            // 环切取值：native 标 dirty，下一帧核心重读并打补丁
            core.setCoreOption(o, (o.currentIndex + 1) % o.values.size)
            cheatAdapter.update(pos, arcadeRow(o))
            return
        }
        if (pos !in cheats.indices) return
        val on = pos !in enabledCheats
        if (on) enabledCheats.add(pos) else enabledCheats.remove(pos)
        cheatAdapter.update(pos, fcRow(pos))
        applyCheats()
    }

    private fun clearCheats() {
        if (coreName == "fbneo") {
            if (arcadeOptions.all { it.currentIndex == 0 }) {
                toast("当前没有开启的金手指")
                return
            }
            arcadeOptions.forEach { core.setCoreOption(it, 0) }   // 0 = Disabled
            renderArcadeRows()
            // submit=notifyDataSetChanged 会销毁持有焦点的行，必须把焦点请回来
            focusFirstCheat()
            toast("已全部关闭")
            return
        }
        if (enabledCheats.isEmpty()) {
            toast("当前没有开启的金手指")
            return
        }
        enabledCheats.clear()
        renderFcCheats()
        applyCheats()
        focusFirstCheat()
        toast("已全部关闭")
    }

    /** 整表下发：RetroCore 只排队，真正的 retro_cheat_reset/set 在仿真线程执行 */
    private fun applyCheats() {
        core.setCheats(cheats.filterIndexed { i, _ -> i in enabledCheats }.map { it.code })
    }

    private fun hideCheatPanel() {
        cheatPanelVisible = false
        binding.cheatPanel.visibility = View.GONE
        binding.gameSurface.requestFocus()
    }

    private fun confirmExit() {
        Nav.confirm(this, "退出游戏", "要退出《$gameName》吗？", "退出", "继续玩") { finish() }
    }

    private fun toast(t: String) = Nav.toast(this, t)

    // ── 输入：物理键 → GameButton → retro 位图 ─────────────────────────
    private fun pushBits() {
        var bits = axisBits
        for (b in heldButtons) RETRO_BIT[b]?.let { bits = bits or (1 shl it) }
        if (bits != padBits) {
            padBits = bits
            core.setInput(bits)
            Log.d("NativeInput", "bits=0x%x".format(bits))
        }
    }

    private fun clearPad() {
        heldButtons.clear()
        axisBits = 0
        pushBits()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isFinishing) return super.dispatchKeyEvent(event)
        val isGamepad = event.source and (android.view.InputDevice.SOURCE_GAMEPAD or
                android.view.InputDevice.SOURCE_JOYSTICK) != 0

        // ★ 加载失败被困修复：游戏还没跑起来（ROM 下载失败/无核心），
        //   任何退出意图（BACK / 手柄 B / START / MENU）直接 finish，
        //   不弹确认框、不走工具条/金手指路由——loading 层全屏时这些
        //   路由要么没焦点要么被吞，就是"退不出去"的根因
        if (loadFailed) {
            if (event.action == KeyEvent.ACTION_UP && (
                    event.keyCode == KeyEvent.KEYCODE_BACK ||
                    event.keyCode == KeyEvent.KEYCODE_BUTTON_B ||
                    event.keyCode == KeyEvent.KEYCODE_BUTTON_START ||
                    event.keyCode == KeyEvent.KEYCODE_MENU)) {
                finish()
                return true
            }
            return true   // 其余按键一律吞掉，别让未映射键乱焦点
        }

        // 金手指面板模式：焦点交给列表/按钮，只保留关闭键与 BACK
        if (cheatPanelVisible) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MENU -> {
                    if (event.action == KeyEvent.ACTION_UP) hideCheatPanel()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (event.action == KeyEvent.ACTION_UP) hideCheatPanel()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    // 末行再按下：RecyclerView 会把搜索吞回自己肚子里，显式交给按钮
                    if (event.action == KeyEvent.ACTION_UP && focusIsLastCheatRow()) {
                        binding.btnCheatOffAll.requestFocus(); return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (event.action != KeyEvent.ACTION_UP) return super.dispatchKeyEvent(event)
                    when {
                        // 首行再按上：标题不可聚焦，吞掉免得焦点流落到面板外
                        focusIsFirstCheatRow() -> return true
                        // "全部关闭"按上回列表：按钮的 nextFocusUp 指向 RV 本身，
                        // 自然搜索找不到内部行，显式落回末行
                        binding.btnCheatOffAll.isFocused -> { focusLastCheatRow(0); return true }
                    }
                }
            }
            // 菜单热键（默认 SELECT）也能关面板
            if (event.keyCode == KeySettings.menuHotkey && event.action == KeyEvent.ACTION_UP) {
                hideCheatPanel(); return true
            }
            return super.dispatchKeyEvent(event)
        }

        // 工具条模式：交系统焦点走按钮，只保留呼出键与 BACK
        if (toolbarVisible) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MENU -> {
                    if (event.action == KeyEvent.ACTION_UP) hideToolbar()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (event.action == KeyEvent.ACTION_UP) hideToolbar()
                    return true
                }
            }
            // 菜单热键（默认 SELECT）收起工具条
            if (event.keyCode == KeySettings.menuHotkey && event.action == KeyEvent.ACTION_UP) {
                hideToolbar(); return true
            }
            return super.dispatchKeyEvent(event)
        }

        // 游戏模式
        if (event.keyCode == KeySettings.menuHotkey ||
            event.keyCode == KeyEvent.KEYCODE_MENU ||
            (event.keyCode == KeyEvent.KEYCODE_BUTTON_START && event.repeatCount >= 8)
        ) {
            if (event.action == KeyEvent.ACTION_UP && event.repeatCount < 8) {
                if (event.keyCode != KeyEvent.KEYCODE_BUTTON_START) showToolbar()
            } else if (event.action == KeyEvent.ACTION_UP && event.repeatCount >= 8) {
                showToolbar()
            }
            return true
        }

        // HOME 家族键（遥控器 KEYCODE_HOME 多数 ROM 被系统截走，能到达就一并处理；
        // 手柄 Guide/Home 键标准上报为 BUTTON_MODE）→ 呼出工具条：先存档/读档再退出才安全
        if (event.keyCode == KeyEvent.KEYCODE_HOME ||
            event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE
        ) {
            if (event.action == KeyEvent.ACTION_UP) showToolbar()
            return true
        }

        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) confirmExit()
            return true
        }

        val btn = KeySettings.logicalOf(event.keyCode)
        if (btn != null) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> heldButtons.add(btn)
                KeyEvent.ACTION_UP -> heldButtons.remove(btn)
            }
            pushBits()
            return true
        }

        // ★ USB 手柄键兜底：任何来自手柄/摇杆、未被映射的键码一律吞掉，
        //   绝不放行给系统（放行 = 焦点乱跳/黑屏，就是"部分按键失灵"的根源）
        return if (isGamepad && event.keyCode != KeyEvent.KEYCODE_UNKNOWN) true
        else super.dispatchKeyEvent(event)
    }

    /** 摇杆/方向轴 → 方向位（很多 USB 手柄的"方向"是 AXIS 不是 DPAD 键码） */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val src = event.source and (android.view.InputDevice.SOURCE_JOYSTICK or
                android.view.InputDevice.SOURCE_GAMEPAD or
                android.view.InputDevice.SOURCE_DPAD)
        // 工具条/金手指面板打开时方向输入归焦点系统：这里必须放行给 super，
        // 系统才会把摇杆/十字键(hat 轴)合成 DPAD 按键驱动列表换行；
        // 若在此吞成游戏输入，面板里方向键完全失灵（街机金手指"无法聚焦"的根因）
        if (src == 0 || toolbarVisible || cheatPanelVisible) return super.onGenericMotionEvent(event)

        var bits = 0
        val x = event.getAxisValue(MotionEvent.AXIS_X)
        val y = event.getAxisValue(MotionEvent.AXIS_Y)
        val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val dead = 0.45f
        if (x < -dead || hx < -dead) bits = bits or (1 shl RetroCore.ID_LEFT)
        if (x > dead || hx > dead) bits = bits or (1 shl RetroCore.ID_RIGHT)
        if (y < -dead || hy < -dead) bits = bits or (1 shl RetroCore.ID_UP)
        if (y > dead || hy > dead) bits = bits or (1 shl RetroCore.ID_DOWN)
        if (bits != axisBits) {
            axisBits = bits
            pushBits()
        }
        return true
    }

    // ── 生命周期 ───────────────────────────────────────────────────────
    override fun onPause() {
        super.onPause()
        clearPad()
        if (core.running) {
            paused = true
            core.setPaused(true)
            binding.btnPause.text = "继续"
        }
    }

    override fun onDestroy() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        // 后台线程里拆核心：nativeStop 内部先落 SRAM，再 dlclose
        val c = core
        Thread { c.stop() }.start()
        super.onDestroy()
    }
}
