package com.xbw.tv.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 收藏的独立表。
 *
 * 为什么不放进 games 表加 favorite 字段：games 是「可再生的站点缓存」，
 * 每次刷新都 deletePage + 重插，收藏标记会被整页抹掉；收藏是用户数据，
 * 定位与 recent_plays 一样，必须独立成表并在 DB 升级时保留（AppDatabase 有显式迁移）。
 */
@Entity(tableName = "favorites")
data class FavoriteEntity(
    /** 游戏 id（yikm play?id=），收藏集合的主键 */
    @PrimaryKey val gameId: String,
    val name: String,
    val coverUrl: String?,
    val playUrl: String,
    /** 标签用 | 连接存储（与 games/recent_plays 一致） */
    val tags: String = "",
    val addedAt: Long = System.currentTimeMillis()
)
