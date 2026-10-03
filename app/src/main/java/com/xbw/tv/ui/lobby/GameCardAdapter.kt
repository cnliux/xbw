package com.xbw.tv.ui.lobby

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.xbw.tv.R
import com.xbw.tv.data.model.GameItem
import com.xbw.tv.ui.common.TvFocusAnimator

/**
 * 游戏卡片网格适配器。
 *
 * - 焦点动效：TvFocusAnimator（放大 1.08 + 白描边 + elevation 阴影）
 * - 封面：Glide 带占位图；站点懒加载属性在 Parser 已处理绝对地址
 * - 分页：由大厅底部的分页按钮整页切换，这里不做触底自动翻页（避免与按钮抢跑）
 * - 收藏：长按卡片（或在大厅按菜单键）切换，★ 角标由 favoriteIds 驱动
 */
class GameCardAdapter(
    private val onClick: (GameItem) -> Unit,
    private val onToggleFavorite: (GameItem) -> Unit = {}
) : ListAdapter<GameItem, GameCardAdapter.VH>(DIFF) {

    /** 焦点恢复用：切分类/刷新后把焦点还给上次的 id */
    var focusedId: String? = null

    /** 收藏 id 集合：大厅观察 Repository.favoriteIds 赋值，全量刷新角标 */
    var favoriteIds: Set<String> = emptySet()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    /** 供 Activity 按焦点位置取条目（ListAdapter.getItem 是 protected） */
    fun itemAt(position: Int): GameItem? =
        if (position in 0 until itemCount) getItem(position) else null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_game_card, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val cover: ImageView = view.findViewById(R.id.cover)
        private val title: TextView = view.findViewById(R.id.title)
        private val tag: TextView = view.findViewById(R.id.tag)
        private val star: TextView = view.findViewById(R.id.fav_star)
        private val root: View = view.findViewById(R.id.card_root)

        init {
            // 焦点动效 + 记住焦点 id 一次性挂好（attach 内部是单一监听器）
            TvFocusAnimator.attach(root, extra = { hasFocus ->
                if (hasFocus && bindingAdapterPosition != RecyclerView.NO_POSITION) {
                    focusedId = getItem(bindingAdapterPosition).id
                }
            })
            root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(getItem(pos))
            }
            // 长按（遥控器长按 OK 也走这里）= 收藏切换
            root.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onToggleFavorite(getItem(pos))
                    true
                } else false
            }
        }

        fun bind(item: GameItem) {
            title.text = item.name
            if (item.primaryTag.isNotEmpty()) {
                tag.visibility = View.VISIBLE
                tag.text = item.primaryTag
            } else {
                tag.visibility = View.GONE
            }
            star.visibility = if (item.id in favoriteIds) View.VISIBLE else View.GONE
            // 封面：站点 https 直链；失败/为空回落到占位手柄图
            if (item.coverUrl != null) {
                Glide.with(cover)
                    .load(item.coverUrl)
                    .placeholder(R.drawable.placeholder_cover)
                    .error(R.drawable.placeholder_cover)
                    // 封面缓存一份足够：站点改版换图概率低，ALL 策略省流量
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .centerCrop()
                    .into(cover)
            } else {
                cover.setImageResource(R.drawable.placeholder_cover)
            }
            TvFocusAnimator.reset(root)
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<GameItem>() {
            override fun areItemsTheSame(a: GameItem, b: GameItem) = a.id == b.id
            override fun areContentsTheSame(a: GameItem, b: GameItem) = a == b
        }
    }
}
