package com.xbw.tv.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface GameDao {

    @Query(
        "SELECT * FROM games WHERE categoryKey = :categoryKey AND page = :page " +
                "ORDER BY seq ASC LIMIT :limit"
    )
    suspend fun query(categoryKey: String, page: Int, limit: Int = 200): List<GameEntity>

    @Query("SELECT * FROM games WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): GameEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<GameEntity>)

    @Query("DELETE FROM games WHERE categoryKey = :categoryKey AND page = :page")
    suspend fun deletePage(categoryKey: String, page: Int)

    @Query("DELETE FROM games")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM games")
    suspend fun count(): Int

    /** 全量官方缓存条目（拼音首字母映射从它现算，不入库） */
    @Query("SELECT * FROM games")
    suspend fun all(): List<GameEntity>

    /** 缓存最后写入时间（首字母映射的失效水位） */
    @Query("SELECT MAX(fetchedAt) FROM games")
    suspend fun maxFetchedAt(): Long?

    @Query("SELECT MAX(fetchedAt) FROM games WHERE categoryKey = :categoryKey AND page = :page")
    suspend fun lastFetchedAt(categoryKey: String, page: Int): Long?
}
