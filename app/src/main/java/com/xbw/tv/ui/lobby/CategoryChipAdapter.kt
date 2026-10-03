package com.xbw.tv.ui.lobby

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.xbw.tv.R
import com.xbw.tv.data.model.GameCategory
import com.xbw.tv.databinding.ItemCategoryChipBinding
import com.xbw.tv.ui.common.TvFocusAnimator

/**
 * 顶部分类芯片行（FC/街机/GBA/…）。
 * 焦点进入即轻微放大高亮，A 键切换分类（方案 4.1 焦点设计）。
 */
class CategoryChipAdapter(
    private val onPick: (GameCategory) -> Unit
) : RecyclerView.Adapter<CategoryChipAdapter.VH>() {

    @SuppressLint("NotifyDataSetChanged")
    private val items = mutableListOf<GameCategory>()

    /** 当前选中的分类 key（选中的芯片高亮为品牌色） */
    var selectedKey: String = GameCategory.ALL.key
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    @SuppressLint("NotifyDataSetChanged")
    fun submitList(list: List<GameCategory>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemCategoryChipBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(private val binding: ItemCategoryChipBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            TvFocusAnimator.attach(binding.root, scaleFocused = 1.12f)
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    selectedKey = items[pos].key
                    onPick(items[pos])
                }
            }
        }

        fun bind(category: GameCategory) {
            binding.chip.text = category.title
            // 选中的分类：文字用品牌色（bg 由 drawable selector 控制焦点态）
            binding.chip.setTextColor(
                if (category.key == selectedKey) 0xFFFF5A5F.toInt() else 0xFFFFFFFF.toInt()
            )
        }
    }
}
