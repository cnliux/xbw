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

/** 批量取 id → 分类 key（搜索过滤时用来跳过 play 页请求） */
data class IdCategory(
    val gameId: String,
    val categoryKey: String
)

@Dao
interface SearchIndexDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<SearchIndexEntity>)

    /** 索引里只建了有原生核心的平台，所以"命中索引 = 可玩"，无需再问站点 */
    @Query("SELECT gameId, categoryKey FROM search_index WHERE gameId IN (:ids)")
    suspend fun categoriesOf(ids: List<String>): List<IdCategory>

    /** 前缀命中优先（sld 优先出"圣斗士…"而不是中间含 sld 的） */
    @Query("SELECT * FROM search_index WHERE initials LIKE :q || '%' ORDER BY name ASC LIMIT :lim")
    suspend fun queryPrefix(q: String, lim: Int = 80): List<SearchIndexEntity>

    @Query(
        "SELECT * FROM search_index WHERE initials LIKE '%' || :q || '%' " +
                "AND initials NOT LIKE :q || '%' ORDER BY name ASC LIMIT :lim"
    )
    suspend fun queryContains(q: String, lim: Int = 80): List<SearchIndexEntity>

    /**
     * 标题前缀命中。中文查询只能走这个：首字母索引对中文没有意义，
     * 而用户是用屏幕键盘打中文的（盒子没软键盘），只查 initials 等于本地索引全程用不上。
     */
    @Query("SELECT * FROM search_index WHERE name LIKE :q || '%' ORDER BY name ASC LIMIT :lim")
    suspend fun queryNamePrefix(q: String, lim: Int = 80): List<SearchIndexEntity>

    /** 标题中间命中（"斗罗" 命中"魂斗罗"），排在前缀命中之后 */
    @Query(
        "SELECT * FROM search_index WHERE name LIKE '%' || :q || '%' " +
                "AND name NOT LIKE :q || '%' ORDER BY name ASC LIMIT :lim"
    )
    suspend fun queryNameContains(q: String, lim: Int = 80): List<SearchIndexEntity>

    /**
     * 标签命中：搜"街机"要能列出街机的游戏，而不只是标题里带"街机"两个字。
     * tags 存的是 `a|b|c`，用 `('|' || tags || '|') LIKE '%|q|%'` 保证整词匹配，
     * 免得搜"机"把"飞机大战"之类全捞出来。
     */
    @Query(
        "SELECT * FROM search_index WHERE ('|' || tags || '|') LIKE '%|' || :q || '|%' " +
                "ORDER BY name ASC LIMIT :lim"
    )
    suspend fun queryByTag(q: String, lim: Int = 150): List<SearchIndexEntity>

    /**
     * 按分类取全部游戏：搜"街机"要出**街机的游戏**，而不是标题里带"街机"两个字的合集。
     * 站点街机列表页的卡片只挂题材标签（射击/格斗），平台名并不在 tags 里，
     * 所以关键词命中分类名时得直接按 [com.xbw.tv.data.model.GameCategory.key] 取。
     */
    @Query("SELECT * FROM search_index WHERE categoryKey = :key ORDER BY name ASC LIMIT :lim")
    suspend fun queryByCategory(key: String, lim: Int = 150): List<SearchIndexEntity>

    @Query("SELECT COUNT(*) FROM search_index")
    suspend fun count(): Int

    @Query("DELETE FROM search_index WHERE categoryKey = :categoryKey")
    suspend fun deleteCategory(categoryKey: String)

    @Query("DELETE FROM search_index")
    suspend fun clearAll()
}
