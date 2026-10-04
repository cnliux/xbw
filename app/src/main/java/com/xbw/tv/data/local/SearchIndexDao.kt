package com.xbw.tv.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** 拼音首字母检索索引条目（全站游戏的轻量副本） */
@androidx.room.Entity(tableName = "search_index", primaryKeys = ["gameId"])
data class SearchIndexEntity(
    val gameId: String,
    val name: String,
    /** 标题拼音首字母，小写，例 "hld" */
    val initials: String,
    val categoryKey: String,
    val coverUrl: String?,
    val playUrl: String,
    /** '|' 连接的标签串，回组成 GameItem 用 */
    val tags: String,
    val indexedAt: Long
)

@Dao
interface SearchIndexDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<SearchIndexEntity>)

    /** 前缀命中优先（sld 优先出"圣斗士…"而不是中间含 sld 的） */
    @Query("SELECT * FROM search_index WHERE initials LIKE :q || '%' ORDER BY name ASC LIMIT :lim")
    suspend fun queryPrefix(q: String, lim: Int = 80): List<SearchIndexEntity>

    @Query(
        "SELECT * FROM search_index WHERE initials LIKE '%' || :q || '%' " +
                "AND initials NOT LIKE :q || '%' ORDER BY name ASC LIMIT :lim"
    )
    suspend fun queryContains(q: String, lim: Int = 80): List<SearchIndexEntity>

    @Query("SELECT COUNT(*) FROM search_index")
    suspend fun count(): Int

    @Query("DELETE FROM search_index WHERE categoryKey = :categoryKey")
    suspend fun deleteCategory(categoryKey: String)

    @Query("DELETE FROM search_index")
    suspend fun clearAll()
}
