package com.xbw.tv.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** "最近玩过"记录：进入游戏页时写入，大厅 RECENT 分类读取 */
@Entity(
    tableName = "recent_plays",
    indices = [Index("gameId", unique = true)]
)
data class RecentPlayEntity(
    @PrimaryKey val gameId: String,
    val name: String,
    val coverUrl: String?,
    val playUrl: String,
    val tags: String = "",
    val lastPlayedAt: Long = System.currentTimeMillis(),
    val playCount: Int = 1
)

@Dao
interface RecentPlayDao {

    @Query("SELECT * FROM recent_plays ORDER BY lastPlayedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 60): List<RecentPlayEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(e: RecentPlayEntity)

    @Query(
        "UPDATE recent_plays SET playCount = playCount + 1, lastPlayedAt = :now " +
                "WHERE gameId = :gameId"
    )
    suspend fun bump(gameId: String, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM recent_plays")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM recent_plays")
    suspend fun count(): Int
}
