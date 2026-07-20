package com.fitsync.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.fitsync.app.data.db.dao.ActivityVariantDao
import com.fitsync.app.data.db.dao.AppSettingsDao
import com.fitsync.app.data.db.dao.CanonicalActivityDao
import com.fitsync.app.data.db.dao.DeliveryJobDao
import com.fitsync.app.data.db.dao.ImportJobDao
import com.fitsync.app.data.db.dao.PlatformAccountDao
import com.fitsync.app.data.db.dao.PlatformCopyDao
import com.fitsync.app.data.db.dao.RemoteActivitySkipDao
import com.fitsync.app.data.db.dao.SyncRuleDao
import com.fitsync.app.data.db.entity.ActivityVariantEntity
import com.fitsync.app.data.db.entity.AppSettingsEntity
import com.fitsync.app.data.db.entity.CanonicalActivityEntity
import com.fitsync.app.data.db.entity.DeliveryJobEntity
import com.fitsync.app.data.db.entity.ImportJobEntity
import com.fitsync.app.data.db.entity.PlatformAccountEntity
import com.fitsync.app.data.db.entity.PlatformCopyEntity
import com.fitsync.app.data.db.entity.RemoteActivitySkipEntity
import com.fitsync.app.data.db.entity.SyncRuleEntity
import com.fitsync.app.domain.model.FitStoragePolicy
@Database(
    entities = [
        PlatformAccountEntity::class,
        CanonicalActivityEntity::class,
        ActivityVariantEntity::class,
        PlatformCopyEntity::class,
        RemoteActivitySkipEntity::class,
        DeliveryJobEntity::class,
        SyncRuleEntity::class,
        ImportJobEntity::class,
        AppSettingsEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class FitSyncDatabase : RoomDatabase() {
    abstract fun platformAccountDao(): PlatformAccountDao
    abstract fun canonicalActivityDao(): CanonicalActivityDao
    abstract fun activityVariantDao(): ActivityVariantDao
    abstract fun platformCopyDao(): PlatformCopyDao
    abstract fun remoteActivitySkipDao(): RemoteActivitySkipDao
    abstract fun deliveryJobDao(): DeliveryJobDao
    abstract fun syncRuleDao(): SyncRuleDao
    abstract fun importJobDao(): ImportJobDao
    abstract fun appSettingsDao(): AppSettingsDao

    companion object {
        @Volatile
        private var instance: FitSyncDatabase? = null

        fun getInstance(context: Context): FitSyncDatabase {
            return instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }
        }

        private fun build(context: Context): FitSyncDatabase {
            return Room.databaseBuilder(context, FitSyncDatabase::class.java, "fitsync.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .addCallback(
                    object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            db.execSQL(
                                """
                                INSERT OR IGNORE INTO app_settings (id, storagePolicy, autoSyncEnabled)
                                VALUES (1, '${FitStoragePolicy.DELETE_AFTER_7_DAYS.name}', 1)
                                """.trimIndent(),
                            )
                        }
                    },
                )
                .build()
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE platform_account ADD COLUMN capabilitySnapshot TEXT")
                db.execSQL("ALTER TABLE platform_account ADD COLUMN lastValidatedAtEpochMs INTEGER")
                db.execSQL("ALTER TABLE platform_account ADD COLUMN lastSyncAtEpochMs INTEGER")
                db.execSQL("ALTER TABLE platform_account ADD COLUMN needsUserAction INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "DELETE FROM platform_account WHERE id NOT IN " +
                        "(SELECT MAX(id) FROM platform_account GROUP BY platform)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_platform_account_platform " +
                        "ON platform_account(platform)",
                )

                db.execSQL("ALTER TABLE platform_copy ADD COLUMN remoteUrl TEXT")
                db.execSQL("ALTER TABLE platform_copy ADD COLUMN fileSha256 TEXT")

                db.execSQL(
                    "ALTER TABLE delivery_job ADD COLUMN executionMode TEXT NOT NULL DEFAULT 'BACKGROUND'",
                )
                db.execSQL("ALTER TABLE delivery_job ADD COLUMN userActionUrl TEXT")
                db.execSQL("ALTER TABLE delivery_job ADD COLUMN requestedByRuleId INTEGER")

                db.execSQL("ALTER TABLE sync_rule ADD COLUMN includeHistory INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN initialHistoryDays INTEGER NOT NULL DEFAULT 90")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN syncIntervalMinutes INTEGER NOT NULL DEFAULT 15")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN notificationsEnabled INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE canonical_activity ADD COLUMN avgHeartRate INTEGER")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN fitParserVersion INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE canonical_activity ADD COLUMN reviewCandidateCanonicalId INTEGER")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS remote_activity_skip (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        platformAccountId INTEGER NOT NULL,
                        remoteActivityId TEXT NOT NULL,
                        reasonCode TEXT NOT NULL,
                        errorMessage TEXT,
                        createdAtEpochMs INTEGER NOT NULL,
                        updatedAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "index_remote_activity_skip_platformAccountId_remoteActivityId " +
                        "ON remote_activity_skip(platformAccountId, remoteActivityId)",
                )
            }
        }
    }
}
