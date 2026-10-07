package com.xbw.tv.ui.common

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.xbw.tv.R
import com.xbw.tv.data.model.GameItem
import com.xbw.tv.data.plugin.PluginRepository
import com.xbw.tv.ui.game.NativeGameActivity
import com.xbw.tv.ui.game.VideoGameActivity
import kotlinx.coroutines.launch
import java.io.File

/** 导航与通用交互小工具 */
object Nav {

    /** 是否是第三方插件源条目（browse 直接出的 source 是 plugin；最近玩过/收藏里存的是插件 id） */
    private fun isPluginItem(item: GameItem): Boolean =
        item.source == GameItem.SOURCE_PLUGIN || item.id.startsWith("plug-")

    /**
     * 打开游戏：
     *  - 第三方插件源：有 ROM 直链 → 走既有原生核心；只有视频 → 下载后本地播放；
     *    都没有 → 提示该源没配可播放资源。
     *  - 官方站：原生 libretro 核心。封面/链接/标签一并带上，游戏内收藏要用。
     */
    fun openGame(activity: androidx.appcompat.app.AppCompatActivity, item: GameItem) {
        // U盘本地游戏：文件在盒子上，直接进核心（平台由扩展名推断的 platformKey 决定）
        if (item.source == GameItem.SOURCE_USB || item.id.startsWith("usb-")) {
            openUsbGame(activity, item)
            return
        }
        if (!isPluginItem(item)) {
            nativeGame(activity, item, null, null, null, null)
            return
        }
        activity.lifecycleScope.launch {
            val src = PluginRepository.sourceById(activity.applicationContext, item.id)
            android.util.Log.i("Nav", "plugin id=${item.id} sourceById=${src?.id} title=${src?.title}")
            if (src == null) {
                toast(activity, activity.getString(R.string.plugin_no_rom))
                return@launch
            }
            val cookie = PluginRepository.cookieFor(activity.applicationContext, src)
            android.util.Log.i("Nav", "plugin id=${item.id} cookie=${cookie != null}")
            // 下载候选地址（GBK 直链 → 通用直链 → down.php 网关）逐个试
            val candidates = PluginRepository.romCandidates(src, item)
            android.util.Log.i("Nav", "plugin id=${item.id} candidates=${candidates.size} first=${candidates.firstOrNull()}")
            if (candidates.isNotEmpty()) {
                // FBNeo（街机）必须按 zip 原名落盘才能匹配 DRV_NAME，把原始文件名传下去
                val romFileName = item.rawPath.substringAfterLast('/').takeIf { it.isNotBlank() }
                // 平台优先用条目扩展名派生的 platformKey（防目录源里混着别的平台
                // 文件被全塞成源平台核心），没有才回源平台
                val platform = item.platformKey.ifBlank { src.platform }
                nativeGame(activity, item, candidates, platform, cookie, romFileName)
            } else if (item.videoUrl != null) {
                playPluginVideo(activity, src, item)
            } else {
                toast(activity, activity.getString(R.string.plugin_no_rom))
            }
        }
    }

    /** U盘条目/收藏重进：有 [GameItem.localPath] 直接用；只有 id 就先从扫描结果找回 */
    private fun openUsbGame(activity: androidx.appcompat.app.AppCompatActivity, item: GameItem) {
        if (item.localPath.isNotBlank()) {
            startUsbGame(activity, item)
            return
        }
        activity.lifecycleScope.launch {
            val entry = runCatching {
                com.xbw.tv.data.usb.UsbScanner.items(activity.applicationContext)
                    .firstOrNull { it.id == item.id }
            }.getOrNull()
            if (entry == null) {
                toast(activity, "未找到该游戏（U盘可能已拔出，重新挂载后再试）")
            } else {
                startUsbGame(activity, entry)
            }
        }
    }

    private fun startUsbGame(activity: androidx.appcompat.app.AppCompatActivity, entry: GameItem) {
        val local = File(entry.localPath)
        if (!local.isFile || local.length() <= 16) {
            toast(activity, "U盘文件不可用或已被删除")
            return
        }
        activity.startActivity(
            Intent(activity, NativeGameActivity::class.java).apply {
                putExtra(NativeGameActivity.EXTRA_ID, entry.id)
                putExtra(NativeGameActivity.EXTRA_NAME, entry.name)
                putExtra(NativeGameActivity.EXTRA_COVER, entry.coverUrl)
                putExtra(NativeGameActivity.EXTRA_TAGS, entry.tags.toTypedArray())
                if (entry.platformKey.isNotBlank()) {
                    putExtra(NativeGameActivity.EXTRA_PLATFORM, entry.platformKey)
                }
                putExtra(NativeGameActivity.EXTRA_LOCAL_PATH, entry.localPath)
            }
        )
    }

    private fun nativeGame(
        activity: Activity,
        item: GameItem,
        directRomans: List<String>?,
        platform: String?,
        cookie: String?,
        romFileName: String?
    ) {
        activity.startActivity(
            Intent(activity, NativeGameActivity::class.java).apply {
                putExtra(NativeGameActivity.EXTRA_ID, item.id)
                putExtra(NativeGameActivity.EXTRA_NAME, item.name)
                putExtra(NativeGameActivity.EXTRA_COVER, item.coverUrl)
                putExtra(NativeGameActivity.EXTRA_TAGS, item.tags.toTypedArray())
                // 插件直链：告诉游戏页直接下载这些 URL（逐个试），平台决定用哪个核心
                if (!directRomans.isNullOrEmpty()) {
                    putExtra(NativeGameActivity.EXTRA_PLAY_URL, directRomans.first())
                    putExtra(NativeGameActivity.EXTRA_ROM_URLS, ArrayList(directRomans))
                    platform?.let { putExtra(NativeGameActivity.EXTRA_PLATFORM, it) }
                    cookie?.let { putExtra(NativeGameActivity.EXTRA_COOKIE, it) }
                    romFileName?.let { putExtra(NativeGameActivity.EXTRA_ROM_NAME, it) }
                }
            }
        )
    }

    /** 插件只有视频没有 ROM：带 cookie 下载到本地再播（VideoView 没法带自定义 header） */
    private suspend fun playPluginVideo(
        activity: Activity,
        src: com.xbw.tv.data.plugin.PluginSource,
        item: GameItem
    ) {
        val videoUrl = item.videoUrl ?: return
        val dir = File(activity.cacheDir, "plugins-videos/${src.id}").also { it.mkdirs() }
        val stem = videoUrl.substringAfterLast('/').substringBefore('?').ifBlank { "video.mp4" }
        val dest = File(dir, stem)
        if (!dest.isFile || dest.length() <= 16) {
            try {
                PluginRepository.downloadAsset(activity, src, videoUrl, dest)
            } catch (e: Exception) {
                toast(activity, "视频获取失败：${e.message ?: "网络异常"}")
                return
            }
        }
        activity.startActivity(
            Intent(activity, VideoGameActivity::class.java).apply {
                putExtra(VideoGameActivity.EXTRA_VIDEO_PATH, dest.absolutePath)
                putExtra(VideoGameActivity.EXTRA_TITLE, item.name)
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
