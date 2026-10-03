package com.xbw.tv.ui.game

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.xbw.tv.R

/**
 * 金手指面板列表：整行点击切换/环切。
 *
 * 行模型 [Row] 对两类金手指通用：
 *  - FC（FCEUmm）：title=名称，subtitle=原始码（ADDR-TYPE-VAL），on=开启；
 *  - 街机（FBNeo）：title=金手指名，subtitle=当前取值（"N - 选项名"），
 *    on=当前取值非 "0 - Disabled"。
 *
 * 状态由 Activity 持有并负责下发核心；adapter 只渲染。
 * 切换单行用 update（notifyItemChanged），整刷用 submit。
 */
class CheatAdapter(
    private val onToggle: (Int) -> Unit
) : RecyclerView.Adapter<CheatAdapter.VH>() {

    data class Row(val title: String, val subtitle: String, val on: Boolean)

    private var rows: List<Row> = emptyList()

    fun submit(list: List<Row>) {
        rows = list
        notifyDataSetChanged()
    }

    fun update(position: Int, row: Row) {
        if (position !in rows.indices) return
        rows = rows.toMutableList().also { it[position] = row }
        notifyItemChanged(position)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_cheat, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(rows[position])
    }

    override fun getItemCount() = rows.size

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.cheat_name)
        private val code: TextView = view.findViewById(R.id.cheat_code)
        private val state: TextView = view.findViewById(R.id.cheat_state)

        init {
            // 注意：这里刻意不用 TvFocusAnimator——它的 bringChildToFront 会打乱
            // RecyclerView 的子视图顺序，配合 notifyItemChanged 动画会把行挪丢（实机复现：
            // 开启一条金手指后相邻行消失）。面板行数少、不会互相裁切，纯缩放足够。
            view.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                val target = if (hasFocus) 1.03f else 1f
                v.animate().scaleX(target).scaleY(target).setDuration(120L).withLayer().start()
            }
            view.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onToggle(pos)
            }
        }

        fun bind(row: Row) {
            name.text = row.title
            if (row.subtitle.isBlank()) {
                code.visibility = View.GONE
            } else {
                code.visibility = View.VISIBLE
                code.text = row.subtitle
            }
            state.text = if (row.on) "开启" else "关闭"
            state.setTextColor(if (row.on) 0xFF7CE38B.toInt() else 0x66FFFFFF.toInt())
        }
    }
}
