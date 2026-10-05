package com.xbw.tv.data.update

import android.content.Context
import android.content.SharedPreferences

/**
 * 升级相关的本地开关与节流状态。独立 prefs 文件（不进 KeySettings，避免和按键映射互相干扰）。
 */
object UpdateSettings {

    private const val PREF = "xbw_update"
    private const val K_AUTO = "auto_check"
    private const val K_LAST_CHECK = "last_check_at"
    private const val K_DISMISSED = "dismissed_tag"

    /** 同一台机器最多间隔这么久才复查一次（进游戏时静默检查，不做无意义的请求） */
    const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    @Volatile
    private var cached: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: context.applicationContext
                .getSharedPreferences(PREF, Context.MODE_PRIVATE).also { cached = it }
        }
    }

    /** 进游戏时自动检测是否需要升级（默认开） */
    fun isAutoCheck(context: Context): Boolean = prefs(context).getBoolean(K_AUTO, true)

    fun setAutoCheck(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(K_AUTO, on).apply()
    }

    fun lastCheckAt(context: Context): Long = prefs(context).getLong(K_LAST_CHECK, 0L)

    fun markChecked(context: Context, at: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(K_LAST_CHECK, at).apply()
    }

    /** 已经提示过"有新版本"的那一版，避免每次进游戏都重复弹窗打扰 */
    fun dismissedTag(context: Context): String = prefs(context).getString(K_DISMISSED, "") ?: ""

    fun markDismissed(context: Context, tag: String) {
        prefs(context).edit().putString(K_DISMISSED, tag).apply()
    }
}
