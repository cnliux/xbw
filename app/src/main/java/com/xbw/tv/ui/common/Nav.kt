package com.xbw.tv.ui.common

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.xbw.tv.R
import com.xbw.tv.data.model.GameItem
import com.xbw.tv.ui.game.NativeGameActivity

/** 导航与通用交互小工具 */
object Nav {

    /** 打开游戏：原生 libretro 核心。 */
    fun openGame(activity: Activity, item: GameItem) {
        activity.startActivity(
            Intent(activity, NativeGameActivity::class.java).apply {
                putExtra(NativeGameActivity.EXTRA_ID, item.id)
                putExtra(NativeGameActivity.EXTRA_NAME, item.name)
            }
        )
    }

    /**
     * TV 确认对话框：
     *  - 默认焦点落在「取消/继续玩」，防止误触 A 直接退出；
     *  - setOnKeyListener 兜底处理遥控器 BACK。
     */
    fun confirm(
        activity: Activity,
        title: String,
        message: String,
        confirmText: String,
        cancelText: String,
        onConfirm: () -> Unit
    ) {
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(confirmText) { d, _ -> d.dismiss(); /* 焦点默认在此按钮 */ }
            .setNegativeButton(cancelText) { d, _ -> d.dismiss() }
            .create()
        dialog.setOnShowListener {
            // 反转焦点顺序：左键(确认)与右键(取消)，默认焦点放「取消」更安全
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
        }
        // 对话框弹出期间把模式切到 NATIVE_DIALOG 的逻辑由调用方的 Activity#dispatchKeyEvent 兜底
        dialog.show()
        dialog.setOnKeyListener { d, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BUTTON_B &&
                event.action == android.view.KeyEvent.ACTION_UP
            ) {
                d.dismiss(); true
            } else false
        }
        // 确认后回调：包在 positive 里，上面的 lambda 里没触发 → 重新绑定
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }
    }

    /** 轻提示（TV 上 Toast 显示慢，用短暂 overlay 更好；这里保留 Toast 简单可靠） */
    fun toast(context: Context, text: String) {
        android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** 给 TextView 设置「更新于 x 分钟前」 */
    fun relativeTime(ts: Long): String {
        if (ts <= 0) return "刚刚"
        val diff = System.currentTimeMillis() - ts
        return when {
            diff < 60_000 -> "刚刚"
            diff < 3_600_000 -> "${diff / 60_000} 分钟前"
            diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
            else -> "${diff / 86_400_000} 天前"
        }
    }

    /** 隐藏焦点框时用的通用淡入淡出 */
    fun fade(view: View, show: Boolean, endAction: (() -> Unit)? = null) {
        view.animate().cancel()
        view.alpha = if (show) 0f else 1f
        view.visibility = View.VISIBLE
        view.animate().alpha(if (show) 1f else 0f).setDuration(160)
            .withEndAction {
                if (!show) view.visibility = View.GONE
                endAction?.invoke()
            }.start()
    }
}
