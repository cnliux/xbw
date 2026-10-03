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

    @Query("SELECT MAX(fetchedAt) FROM games WHERE categoryKey = :categoryKey AND page = :page")
    suspend fun lastFetchedAt(categoryKey: String, page: Int): Long?
}
