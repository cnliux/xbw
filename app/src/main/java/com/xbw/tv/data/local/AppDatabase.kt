package com.xbw.tv.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [GameEntity::class, RecentPlayEntity::class, PhysicalMapEntity::class,
        LogicalKeyEntity::class, FavoriteEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun gameDao(): GameDao
    abstract fun recentPlayDao(): RecentPlayDao
    abstract fun keyMappingDao(): KeyMappingDao
    abstract fun favoriteDao(): FavoriteDao

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

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "xbw.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    // 数据都是可再生的缓存/偏好，无迁移路径时升级直接重建最稳
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
