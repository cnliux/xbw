package com.xbw.tv.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [GameEntity::class, RecentPlayEntity::class, PhysicalMapEntity::class,
        LogicalKeyEntity::class, FavoriteEntity::class, SearchIndexEntity::class,
        GamePlatformEntity::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun gameDao(): GameDao
    abstract fun recentPlayDao(): RecentPlayDao
    abstract fun keyMappingDao(): KeyMappingDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun searchIndexDao(): SearchIndexDao
    abstract fun gamePlatformDao(): GamePlatformDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v1 → v2：新增 favorites 表（收藏是用户数据，不能用 destructive
         * fallback 重建，否则升级会把最近玩过/按键映射一起清掉）。
         * 建表 SQL 必须与 Room 对 FavoriteEntity 的期望 schema 完全一致。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `favorites` (" +
                            "`gameId` TEXT NOT NULL, " +
                            "`name` TEXT NOT NULL, " +
                            "`coverUrl` TEXT, " +
                            "`playUrl` TEXT NOT NULL, " +
                            "`tags` TEXT NOT NULL, " +
                            "`addedAt` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`gameId`))"
                )
            }
        }

        /** v2 → v3：拼音首字母检索索引（可再生缓存，但同样写显式迁移保平安） */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `search_index` (" +
                            "`gameId` TEXT NOT NULL, " +
                            "`name` TEXT NOT NULL, " +
                            "`initials` TEXT NOT NULL, " +
                            "`categoryKey` TEXT NOT NULL, " +
                            "`coverUrl` TEXT, " +
                            "`playUrl` TEXT NOT NULL, " +
                            "`tags` TEXT NOT NULL, " +
                            "`indexedAt` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`gameId`))"
                )
            }
        }

        /** v3 → v4：游戏平台缓存（搜索只放行有原生核心的平台，见 GamePlatformDao） */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `game_platform` (" +
                            "`gameId` TEXT NOT NULL, " +
                            "`categoryKey` TEXT NOT NULL, " +
                            "`coreName` TEXT NOT NULL, " +
                            "`updatedAt` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`gameId`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_game_platform_categoryKey` " +
                        "ON `game_platform` (`categoryKey`)")
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "xbw.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    // 数据都是可再生的缓存/偏好，无迁移路径时升级直接重建最稳
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
