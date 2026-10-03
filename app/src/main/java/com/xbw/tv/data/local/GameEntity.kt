package com.xbw.tv.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 游戏列表缓存（Room）。
 *
 * 定位：仅用于"冷启动秒开"，不违反"不内置数据"要求 ——
 *   - APK 内不含任何游戏数据（本表初始为空）；
 *   - 每次进入大厅都会先展示缓存，然后**强制后台重新抓取**并覆盖（见 GameRepository）。
 */
@Entity(
    tableName = "games",
    indices = [Index("id", unique = true), Index("source"), Index("categoryKey")]
)
data class GameEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val id: String,
    val name: String,
    val coverUrl: String?,
    val playUrl: String,
    /** 标签用 | 连接存储 */
    val tags: String = "",
    /** GameItem.SOURCE_* */
    val source: String = "",
    /** GameCategory.key，ALL 表示通用列表 */
    @ColumnInfo(name = "categoryKey") val categoryKey: String = "all",
    val page: Int = 1,
    /** 抓取时间戳，UI 显示"更新于 x 分钟前" */
    val fetchedAt: Long = System.currentTimeMillis()
)
