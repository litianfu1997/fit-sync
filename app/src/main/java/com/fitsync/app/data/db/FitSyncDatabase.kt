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
    version = 7,
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
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                )
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
                    "CREATE TEMP TABLE platform_account_remap " +
                        "(oldId INTEGER PRIMARY KEY NOT NULL, newId INTEGER NOT NULL)",
                )
                db.execSQL(
                    "INSERT INTO platform_account_remap(oldId, newId) " +
                        "SELECT account.id, keepers.keepId FROM platform_account account " +
                        "JOIN (SELECT platform, MAX(id) AS keepId FROM platform_account GROUP BY platform) keepers " +
                        "ON keepers.platform = account.platform WHERE account.id != keepers.keepId",
                )

                // 先按账号映射后的唯一键去重，再重写所有引用，避免删除重复账号后留下悬空记录。
                db.execSQL(
                    "DELETE FROM platform_copy WHERE id NOT IN (" +
                        "SELECT MAX(copy.id) FROM platform_copy copy " +
                        "LEFT JOIN platform_account_remap remap ON remap.oldId = copy.platformAccountId " +
                        "GROUP BY copy.canonicalActivityId, COALESCE(remap.newId, copy.platformAccountId))",
                )
                db.execSQL(
                    "DELETE FROM platform_copy WHERE id NOT IN (" +
                        "SELECT MAX(copy.id) FROM platform_copy copy " +
                        "LEFT JOIN platform_account_remap remap ON remap.oldId = copy.platformAccountId " +
                        "GROUP BY COALESCE(remap.newId, copy.platformAccountId), copy.remoteActivityId)",
                )
                db.execSQL(
                    "UPDATE platform_copy SET platformAccountId = (" +
                        "SELECT newId FROM platform_account_remap WHERE oldId = platform_copy.platformAccountId) " +
                        "WHERE platformAccountId IN (SELECT oldId FROM platform_account_remap)",
                )
                db.execSQL(
                    "DELETE FROM delivery_job WHERE id NOT IN (" +
                        "SELECT MAX(job.id) FROM delivery_job job " +
                        "LEFT JOIN platform_account_remap remap ON remap.oldId = job.destinationAccountId " +
                        "GROUP BY job.canonicalActivityId, COALESCE(remap.newId, job.destinationAccountId))",
                )
                db.execSQL(
                    "UPDATE delivery_job SET destinationAccountId = (" +
                        "SELECT newId FROM platform_account_remap WHERE oldId = delivery_job.destinationAccountId) " +
                        "WHERE destinationAccountId IN (SELECT oldId FROM platform_account_remap)",
                )
                db.execSQL(
                    "DELETE FROM sync_rule WHERE id NOT IN (" +
                        "SELECT MAX(rule.id) FROM sync_rule rule " +
                        "LEFT JOIN platform_account_remap remap ON remap.oldId = rule.destinationAccountId " +
                        "GROUP BY rule.sourcePlatform, COALESCE(remap.newId, rule.destinationAccountId))",
                )
                db.execSQL(
                    "UPDATE sync_rule SET destinationAccountId = (" +
                        "SELECT newId FROM platform_account_remap WHERE oldId = sync_rule.destinationAccountId) " +
                        "WHERE destinationAccountId IN (SELECT oldId FROM platform_account_remap)",
                )
                db.execSQL(
                    "DELETE FROM platform_account WHERE id NOT IN " +
                        "(SELECT MAX(id) FROM platform_account GROUP BY platform)",
                )
                db.execSQL("DROP TABLE platform_account_remap")
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

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE activity_variant ADD COLUMN parserVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "UPDATE activity_variant SET parserVersion = " +
                        "COALESCE((SELECT fitParserVersion FROM app_settings WHERE id = 1), 0)",
                )

                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_canonical_activity_sportType_startTimeEpochMs " +
                        "ON canonical_activity(sportType, startTimeEpochMs)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_canonical_activity_dedupStatus_startTimeEpochMs " +
                        "ON canonical_activity(dedupStatus, startTimeEpochMs)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_activity_variant_parserVersion_id " +
                        "ON activity_variant(parserVersion, id)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_platform_copy_platformAccountId_fileSha256 " +
                        "ON platform_copy(platformAccountId, fileSha256)",
                )
                db.execSQL("DROP INDEX IF EXISTS index_delivery_job_status")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "index_delivery_job_status_nextRetryAtEpochMs_createdAtEpochMs " +
                        "ON delivery_job(status, nextRetryAtEpochMs, createdAtEpochMs)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_delivery_job_sourceVariantId " +
                        "ON delivery_job(sourceVariantId)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_import_job_createdAtEpochMs " +
                        "ON import_job(createdAtEpochMs)",
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "index_delivery_job_destinationAccountId_status_nextRetryAtEpochMs_createdAtEpochMs " +
                        "ON delivery_job(destinationAccountId, status, nextRetryAtEpochMs, createdAtEpochMs)",
                )
            }
        }
    }
}
