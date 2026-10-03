package com.xbw.tv.ui.common

import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator

/**
 * TV 焦点动效工具。方案 4.1 的「焦点卡片放大 1.1 + 高亮 + 阴影」在这里实现。
 *
 * 注意事项（TV 开发的经典坑）：
 *  1. 放大后卡片会被 RecyclerView 裁剪 → 必须让父容器裁剪关闭（调用处已设 clipChildren=false）；
 *  2. 复用（ViewHolder 回收）时必须复位缩放，否则滚动后一堆卡片停在放大态；
 *  3. 用 elevation 制造阴影（API 21+ 即可），别用外包 large bitmap 阴影。
 */
object TvFocusAnimator {

    const val SCALE_FOCUSED = 1.08f
    const val SCALE_NORMAL = 1.0f
    private const val DURATION = 170L

    /**
     * 给任意 View 装上标准 TV 焦点动效。
     * @param scaleFocused 焦点时缩放比
     * @param extra 焦点变化附加回调（如记住 focusedId），在同一监听里链式执行，
     *              避免调用方再 setOnFocusChangeListener 把动效监听覆盖掉。
     */
    fun attach(
        view: View,
        scaleFocused: Float = SCALE_FOCUSED,
        extra: ((Boolean) -> Unit)? = null
    ) {
        view.onFocusChangeListener = View.OnFocusChangeListener { v2, hasFocus ->
            val target = if (hasFocus) scaleFocused else SCALE_NORMAL
            v2.animate()
                .scaleX(target)
                .scaleY(target)
                .setDuration(DURATION)
                .setInterpolator(DecelerateInterpolator())
                .withLayer()
                .start()
            v2.elevation = if (hasFocus) 22f else 3f
            // 抬到兄弟之上，放大不被裁
            (v2.parent as? ViewGroup)?.let { p ->
                p.bringChildToFront(v2)
                p.clipChildren = false
                p.clipToPadding = false
            }
            extra?.invoke(hasFocus)
        }
    }

    /** 复用时强制复位（ onBindViewHolder 里调用） */
    fun reset(view: View) {
        view.animate().cancel()
        view.scaleX = SCALE_NORMAL
        view.scaleY = SCALE_NORMAL
        view.elevation = 3f
    }

    /**
     * 递归打开裁剪开关：RecyclerView 的每一层都要关，否则放大动画会被裁一半。
     * 在 Adapter#onViewAttachedToWindow 里对 itemView 调用一次即可。
     */
    fun enableOverflow(view: View) {
        var p = view.parent
        while (p is ViewGroup) {
            p.clipChildren = false
            p.clipToPadding = false
            p = p.parent
        }
    }
}
