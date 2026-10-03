package com.xbw.tv.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FavoriteDao {

    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    suspend fun all(): List<FavoriteEntity>

    @Query("SELECT gameId FROM favorites")
    suspend fun allIds(): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE gameId = :gameId)")
    suspend fun isFavorite(gameId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE gameId = :gameId")
    suspend fun delete(gameId: String)
}
