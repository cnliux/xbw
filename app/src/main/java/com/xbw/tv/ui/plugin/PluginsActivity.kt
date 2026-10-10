package com.xbw.tv.ui.plugin

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.xbw.tv.data.plugin.PluginRepository
import com.xbw.tv.data.plugin.PluginSource
import com.xbw.tv.databinding.ItemPluginBinding

/** 第三方源列表的行：名称、地址、内置标记、编辑/删除 */
class PluginAdapter(
    private val onEdit: (PluginSource) -> Unit,
    private val onDelete: (PluginSource) -> Unit
) : RecyclerView.Adapter<PluginAdapter.VH>() {

    private var items: List<PluginSource> = emptyList()

    fun submit(list: List<PluginSource>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(val b: ItemPluginBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemPluginBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val src = items[position]
        h.b.tvName.text = src.title
        h.b.tvUrl.text = src.listUrl
        h.b.tvBadge.visibility = if (src.builtin) android.view.View.VISIBLE
        else android.view.View.GONE
        if (src.builtin) h.b.tvBadge.setText(com.xbw.tv.R.string.plugins_builtin)
        // 内置源也能编辑/删除：删除 = 本机隐藏，编辑 = 隐藏原内置并另存为用户副本
        h.b.btnEdit.visibility = android.view.View.VISIBLE
        h.b.btnDelete.visibility = android.view.View.VISIBLE
        h.b.btnDelete.setOnClickListener { onDelete(src) }
        h.b.btnEdit.setOnClickListener { onEdit(src) }
        h.b.root.setOnClickListener { onEdit(src) }
    }
}

/**
 * 第三方游戏源管理页。设置页里点"第三方游戏源"进来。
 *
 * 只做增删改，读取逻辑全在 [PluginRepository]；这里不碰网络，
 * 想验证一个地址能不能用，去编辑页点"保存"，那里会真读一次并报条数。
 */
class PluginsActivity : androidx.appcompat.app.AppCompatActivity() {

    private lateinit var adapter: PluginAdapter
    private lateinit var list: androidx.recyclerview.widget.RecyclerView

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            com.xbw.tv.databinding.ActivityPluginsBinding.inflate(layoutInflater).also {
                list = it.rvPlugins
            }.root
        )
        adapter = PluginAdapter(
            onEdit = { src -> startActivity(PluginEditActivity.intent(this, src)) },
            onDelete = { src -> confirmDelete(src) }
        )
        list.adapter = adapter
        list.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        findViewById<android.view.View>(com.xbw.tv.R.id.btn_add_plugin).setOnClickListener {
            startActivity(PluginEditActivity.intent(this, null))
        }
    }

    override fun onResume() {
        super.onResume()
        adapter.submit(PluginRepository.sources(this))
    }

    private fun confirmDelete(src: PluginSource) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(src.title)
            .setMessage(com.xbw.tv.R.string.plugins_del_confirm)
            .setPositiveButton(com.xbw.tv.R.string.plugins_del) { _, _ ->
                PluginRepository.remove(this, src.id)
                adapter.submit(PluginRepository.sources(this))
            }
            .setNegativeButton(com.xbw.tv.R.string.plugins_cancel, null)
            .show()
    }
}