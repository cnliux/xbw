package com.xbw.tv.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 按键映射的两层存储：
 *
 *  1. PhysicalMapEntity —— 物理按键（手柄 A/B/…）→ 逻辑按钮（GameButton.id）。
 *     解决"不同手柄键位错乱"（方案 7.3 的按键自定义）。
 *  2. LogicalKeyEntity —— 逻辑按钮 → 注入网页的键盘描述（code / key / keyCode）。
 *     站点按键表比较的是 KeyboardEvent.code（KeyZ/KeyX/…），详见 docs/YIKM_SITE_STRUCTURE.md。
 */

@Entity(tableName = "physical_map")
data class PhysicalMapEntity(
    /** Android KeyEvent.KEYCODE_* */
    @PrimaryKey val androidKeyCode: Int,
    /** GameButton.id，例如 "a" / "b" / "start" */
    val gameButton: String
)

@Entity(tableName = "logical_key")
data class LogicalKeyEntity(
    /** GameButton.id */
    @PrimaryKey val gameButton: String,
    /** KeyboardEvent.code，主判据（站点按 code 比较），例如 "KeyX" */
    val webCode: String,
    /** KeyboardEvent.key，例如 "x" */
    val webKey: String,
    /** 旧版 keyCode，兜底给只监听 keyCode 的老页面 */
    val webKeyCode: Int
)

@Dao
interface KeyMappingDao {

    @Query("SELECT * FROM physical_map")
    suspend fun allPhysical(): List<PhysicalMapEntity>

    @Query("SELECT * FROM physical_map WHERE androidKeyCode = :keyCode LIMIT 1")
    suspend fun physicalOf(keyCode: Int): PhysicalMapEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPhysical(e: PhysicalMapEntity)

    @Query("DELETE FROM physical_map WHERE androidKeyCode = :keyCode")
    suspend fun deletePhysical(keyCode: Int)

    @Query("DELETE FROM physical_map")
    suspend fun clearPhysical()

    @Query("SELECT * FROM logical_key")
    suspend fun allLogical(): List<LogicalKeyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLogical(e: LogicalKeyEntity)

    @Query("DELETE FROM logical_key")
    suspend fun clearLogical()

    @Query("SELECT COUNT(*) FROM physical_map")
    suspend fun physicalCount(): Int

    @Query("SELECT COUNT(*) FROM logical_key")
    suspend fun logicalCount(): Int
}
