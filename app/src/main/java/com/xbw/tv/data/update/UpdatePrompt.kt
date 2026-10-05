package com.xbw.tv.data.update

import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.xbw.tv.R
import kotlinx.coroutines.launch

/**
 * 升级提示的 UI 入口。设置页"立即检查"与进游戏时的静默检查共用同一套弹窗，
 * 避免两处各写一遍（用户看到两种不同的升级弹窗会以为装了两个 App）。
 */
object UpdatePrompt {

    private const val TAG = "UpdatePrompt"

    /** 设置页：主动检查，一定给结果反馈（检查中 / 已是最新 / 检查失败 / 下载） */
    fun checkNow(activity: androidx.activity.ComponentActivity) {
        val toast = Toast.makeText(activity, R.string.update_checking, Toast.LENGTH_SHORT)
        toast.show()
        activity.lifecycleScope.launch {
            // 网络不通要明说，否则用户会以为"刚出的版本我这儿看不到"
            val info = try {
                UpdateChecker.check()
            } catch (e: Exception) {
                Log.w(TAG, "check failed", e)
                null
            }
            if (info == null) {
                if (UpdateChecker.reachable()) {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.update_latest, UpdateChecker.currentVersion),
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(activity, R.string.update_check_failed, Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            showUpdateDialog(activity, info, markDismissed = false)
        }
    }

    /**
     * 进游戏时的静默检查：默认开、6 小时内只查一次、同一版本只提示一次。
     * 有新版本才弹窗；网络不通静默跳过，绝不影响进入游戏。
     */
    fun autoCheckOnGameStart(activity: androidx.activity.ComponentActivity) {
        if (!UpdateSettings.isAutoCheck(activity)) return
        val last = UpdateSettings.lastCheckAt(activity)
        if (last > 0 && System.currentTimeMillis() - last < UpdateSettings.CHECK_INTERVAL_MS) return
        activity.lifecycleScope.launch {
            val info = try {
                UpdateChecker.check()
            } catch (e: Exception) {
                Log.w(TAG, "auto check failed", e)
                null
            }
            UpdateSettings.markChecked(activity)
            if (info == null) return@launch
            if (UpdateSettings.dismissedTag(activity) == info.tag) return@launch
            showUpdateDialog(activity, info, markDismissed = true)
        }
    }

    private fun showUpdateDialog(activity: androidx.activity.ComponentActivity, info: UpdateInfo, markDismissed: Boolean) {
        if (activity.isFinishing) return
        val notes = info.notes.ifBlank { activity.getString(R.string.update_notes_empty) }
        val size = if (info.sizeBytes > 0) formatSize(info.sizeBytes) else "—"
        val body = activity.getString(
            R.string.update_available_body, info.versionCode,
            UpdateChecker.currentVersion, notes, size
        )
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_available_title, info.versionCode))
            .setMessage(body)
            .setPositiveButton(R.string.update_now) { _, _ ->
                UpdateChecker.downloadAndInstall(activity, info)
            }
            .setNegativeButton(R.string.update_later) { _, _ ->
                // 用户选择稍后：这一版不再打扰，下次有新版本才会再提示
                if (markDismissed) UpdateSettings.markDismissed(activity, info.tag)
            }
            .setCancelable(false)
            .show()
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> String.format("%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
