package com.xbw.tv.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * 平台缓存：`gameId → 站点分类 key / 核心名`。
 *
 * 为什么需要：站点搜索是**全站**检索，会返回 Java / NDS / DOS / Flash 这些
 * 本 App 没有原生核心的平台（远程结果不带分类信息，GameItem 也没有 category
 * 字段）。搜索结果要"只显示玩得动的"，就只能去问 play 页；而 play 页结论
 * 几乎不变，所以解析一次就长期缓存，避免每次搜索都打十几个请求。
 *
 * [categoryKey] 为空串 = 已确认无原生核心（解析过但不支持），据此过滤掉。
 */
@androidx.room.Entity(
    tableName = "game_platform",
    primaryKeys = ["gameId"],
    indices = [androidx.room.Index("categoryKey")]
)
data class GamePlatformEntity(
    val gameId: String,
    /** GameCategory.key；空串表示"有这款游戏但无原生核心" */
    val categoryKey: String,
    /** 核心名（fceumm/fbneo/snes9x/mgba/genesis_plus_gx）；无核心时为空串 */
    val coreName: String,
    val updatedAt: Long
)

@Dao
interface GamePlatformDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<GamePlatformEntity>)

    @Query("SELECT * FROM game_platform WHERE gameId IN (:ids)")
    suspend fun byIds(ids: List<String>): List<GamePlatformEntity>

    @Query("SELECT COUNT(*) FROM game_platform WHERE categoryKey != ''")
    suspend fun playableCount(): Int

    @Query("DELETE FROM game_platform")
    suspend fun clearAll()
}
