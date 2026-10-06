package com.xbw.tv.ui.plugin

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
        platformAdapter = PlatformAdapter(GameCategory.lobbyChips.filter { it.fetchable }) { cat ->
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
            val count = runCatching { PluginRepository.load(this@PluginEditActivity, probe).size }
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
            PluginRepository.upsert(
                this@PluginEditActivity,
                probe.copy(
                    title = name,
                    coverBase = b.etCoverBase.text.toString().trim(),
                    romBase = b.etRomBase.text.toString().trim(),
                    builtin = false
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
            getColor(if (isError) R.color.xbw_error else R.color.xbw_text_secondary)
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

/** 平台选择：横向一排 chip，模拟按键上下左右切换（复用 lobby chip 的观感） */
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
        h.b.tvName.text = cat.title
        val on = cat == current
        h.b.tvBadge.visibility = View.GONE
        h.b.root.setBackgroundColor(
            h.itemView.context.getColor(if (on) R.color.xbw_card_pressed else R.color.xbw_surface)
        )
        h.b.root.setOnClickListener { onPick(cat) }
    }
}