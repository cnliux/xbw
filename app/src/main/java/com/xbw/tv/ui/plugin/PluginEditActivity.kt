package com.xbw.tv.ui.plugin

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.xbw.tv.R
import com.xbw.tv.data.model.GameCategory
import com.xbw.tv.data.plugin.PluginRepository
import com.xbw.tv.data.plugin.PluginSource
import com.xbw.tv.databinding.ActivityPluginEditBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

/**
 * 添加 / 编辑一个第三方源。
 *
 * 保存时会**真读一次**清单并显示条数：gamelist.xml 站点五花八门，
 * 光存地址不验证等于把坏源留给用户在大厅里踩。读不通就不给保存。
 */
class PluginEditActivity : androidx.appcompat.app.AppCompatActivity() {

    private lateinit var b: ActivityPluginEditBinding
    private lateinit var platformAdapter: PlatformAdapter
    private var platform: GameCategory = GameCategory.FC

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPluginEditBinding.inflate(layoutInflater)
        setContentView(b.root)
        // 运行核心：从 5 个有原生核心的可玩平台里选（XML 不声明平台，必须用户指定）
        val platforms = GameCategory.playableCategories
        platformAdapter = PlatformAdapter(platforms) { cat ->
            platform = cat
            platformAdapter.selected(cat)
        }
        b.rvPlatform.layoutManager =
            androidx.recyclerview.widget.LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        b.rvPlatform.adapter = platformAdapter
        b.btnCancel.setOnClickListener { finish() }
        b.btnSave.setOnClickListener { save() }

        // 编辑模式：把旧值填进去
        intent.getStringExtra(EXTRA_ID)?.let { id ->
            PluginRepository.sources(this).firstOrNull { it.id == id }?.let { src ->
                b.etName.setText(src.title)
                b.etUrl.setText(src.listUrl)
                b.etCoverBase.setText(src.coverBase)
                b.etRomBase.setText(src.romBase)
                platform = src.category
                platformAdapter.selected(platform)
            }
        }
    }

    private fun save() {
        val name = b.etName.text.toString().trim()
        val url = b.etUrl.text.toString().trim()
        if (name.isEmpty()) return status(getString(R.string.plugins_need_name), true)
        if (url.isEmpty()) return status(getString(R.string.plugins_need_url), true)
        b.btnSave.isEnabled = false
        status(getString(R.string.plugins_testing_fmt, url), false)
        lifecycleScope.launch {
            val probe = PluginSource(
                id = PluginSource.deriveId(url),
                title = name, platform = platform.key, listUrl = url
            )
            val count = runCatching {
                PluginRepository.load(this@PluginEditActivity, probe, force = true).size
            }
                .getOrElse {
                    b.btnSave.isEnabled = true
                    status(getString(R.string.plugins_test_fail_fmt, it.message ?: "?"), true)
                    return@launch
                }
            if (count == 0) {
                b.btnSave.isEnabled = true
                status(getString(R.string.plugins_test_fail_fmt, "0 条"), true)
                return@launch
            }
            // 编辑已有源时保留它的高级选项（GBK 编码 / 目录引导 / 合并清单过滤），
            // 别因为这次只改了「显示名称 / 运行核心」就把配置清成默认值
            val base = intent.getStringExtra(EXTRA_ID)
                ?.let { id -> PluginRepository.sources(this@PluginEditActivity).firstOrNull { it.id == id } }
                ?: probe
            // 编辑的是内置源：先隐藏原内置（改了地址 → id 变了，upsert 认不出来，
            // 不隐藏的话大厅里会同时留着旧内置芯片和新副本）
            if (base.builtin) PluginRepository.remove(this@PluginEditActivity, base.id)
            PluginRepository.upsert(
                this@PluginEditActivity,
                probe.copy(
                    title = name,
                    coverBase = b.etCoverBase.text.toString().trim(),
                    romBase = b.etRomBase.text.toString().trim(),
                    builtin = false,
                    gbkUris = base.gbkUris,
                    dirBootstrap = base.dirBootstrap,
                    dirMatch = base.dirMatch,
                    filterByPlatform = base.filterByPlatform,
                    phpDir = base.phpDir
                )
            )
            b.tvStatus.visibility = View.GONE
            com.xbw.tv.ui.common.Nav.toast(
                this@PluginEditActivity,
                getString(R.string.plugins_saved) + " ($count)"
            )
            finish()
        }
    }

    private fun status(text: String, isError: Boolean) {
        b.tvStatus.visibility = View.VISIBLE
        b.tvStatus.text = text
        b.tvStatus.setTextColor(
            ContextCompat.getColor(this, if (isError) R.color.xbw_error else R.color.xbw_text_secondary)
        )
    }

    companion object {
        private const val EXTRA_ID = "plugin_id"

        fun intent(ctx: Context, src: PluginSource?): Intent =
            Intent(ctx, PluginEditActivity::class.java).apply {
                src?.let { putExtra(EXTRA_ID, it.id) }
            }
    }
}

/** 核心选择：横向一排 chip，每个是一类可玩平台的 libretro 核心（复用 lobby chip 的观感） */
class PlatformAdapter(
    private val items: List<GameCategory>,
    private val onPick: (GameCategory) -> Unit
) : RecyclerView.Adapter<PlatformAdapter.VH>() {

    private var current: GameCategory? = null

    fun selected(cat: GameCategory) {
        val old = items.indexOf(current)
        current = cat
        val now = items.indexOf(cat)
        if (old >= 0) notifyItemChanged(old)
        if (now >= 0) notifyItemChanged(now)
    }

    class VH(val b: com.xbw.tv.databinding.ItemPluginBinding) :
        RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        com.xbw.tv.databinding.ItemPluginBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val cat = items[position]
        val on = cat == current
        // 主标题 = 分类名；角标 = 对应 libretro 核心名（一眼看清选了哪个核心）
        h.b.tvName.text = cat.title
        h.b.tvBadge.visibility = View.VISIBLE
        h.b.tvBadge.text = com.xbw.tv.core.CoreRouter.coreForPlatform(cat.key) ?: ""
        h.b.root.setBackgroundColor(
            ContextCompat.getColor(h.itemView.context, if (on) R.color.xbw_card_pressed else R.color.xbw_surface)
        )
        h.b.root.setOnClickListener { onPick(cat) }
    }
}