package com.fitsync.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration1To2Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration-1-2-test.db"
    private var database: FitSyncDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun migrationPreservesSettingsAndAddsSyncDefaults() = runBlocking {
        context.deleteDatabase(dbName)
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) = createVersion1Schema(db)
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).use { helper ->
            helper.writableDatabase.execSQL(
                "INSERT INTO app_settings(id, storagePolicy, autoSyncEnabled) VALUES(1, 'KEEP_FOREVER', 1)",
            )
        }

        database = Room.databaseBuilder(context, FitSyncDatabase::class.java, dbName)
            .addMigrations(
                FitSyncDatabase.MIGRATION_1_2,
                FitSyncDatabase.MIGRATION_2_3,
                FitSyncDatabase.MIGRATION_3_4,
                FitSyncDatabase.MIGRATION_4_5,
                FitSyncDatabase.MIGRATION_5_6,
                FitSyncDatabase.MIGRATION_6_7,
            )
            .build()
        val settings = database!!.appSettingsDao().get()

        assertThat(settings).isNotNull()
        assertThat(settings!!.initialHistoryDays).isEqualTo(90)
        assertThat(settings.syncIntervalMinutes).isEqualTo(15)
        assertThat(settings.notificationsEnabled).isTrue()
        assertThat(settings.fitParserVersion).isEqualTo(0)
        assertThat(settings.storagePolicy.name).isEqualTo("KEEP_FOREVER")
    }

    @Test
    fun migrationRemapsReferencesFromDuplicatePlatformAccounts() = runBlocking {
        context.deleteDatabase(dbName)
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) = createVersion1Schema(db)
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).use { helper ->
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO platform_account(id, platform, status, createdAtEpochMs, updatedAtEpochMs) VALUES(1, 'ONELAP', 'ACTIVE', 1, 1)")
            db.execSQL("INSERT INTO platform_account(id, platform, status, createdAtEpochMs, updatedAtEpochMs) VALUES(2, 'ONELAP', 'ACTIVE', 2, 2)")
            db.execSQL("INSERT INTO canonical_activity(id, sportType, startTimeEpochMs, dedupStatus, createdAtEpochMs) VALUES(10, 'CYCLING', 1, 'UNIQUE', 1)")
            db.execSQL("INSERT INTO activity_variant(id, canonicalActivityId, collectorType, localFilePath, fileSha256, fileSize, validationStatus, hasGps, hasHeartRate, hasCadence, hasPower, createdAtEpochMs) VALUES(20, 10, 'LOCAL', 'x.fit', 'sha', 1, 'VALID', 1, 0, 0, 0, 1)")
            db.execSQL("INSERT INTO platform_copy(id, canonicalActivityId, platformAccountId, remoteActivityId, createdBySync, sourceVariantId, createdAtEpochMs) VALUES(30, 10, 1, 'remote-1', 0, 20, 1)")
            db.execSQL("INSERT INTO delivery_job(id, canonicalActivityId, destinationAccountId, sourceVariantId, idempotencyKey, status, attemptCount, createdAtEpochMs, updatedAtEpochMs) VALUES(40, 10, 1, 20, 'key-1', 'PENDING', 0, 1, 1)")
            db.execSQL("INSERT INTO sync_rule(id, sourcePlatform, destinationAccountId, enabled, createdAtEpochMs) VALUES(50, 'LOCAL_IMPORT', 1, 1, 1)")
        }

        database = Room.databaseBuilder(context, FitSyncDatabase::class.java, dbName)
            .addMigrations(
                FitSyncDatabase.MIGRATION_1_2,
                FitSyncDatabase.MIGRATION_2_3,
                FitSyncDatabase.MIGRATION_3_4,
                FitSyncDatabase.MIGRATION_4_5,
                FitSyncDatabase.MIGRATION_5_6,
                FitSyncDatabase.MIGRATION_6_7,
            )
            .build()
        val db = database!!.openHelper.writableDatabase

        assertThat(singleLong(db, "SELECT COUNT(*) FROM platform_account WHERE platform = 'ONELAP'")).isEqualTo(1)
        assertThat(singleLong(db, "SELECT platformAccountId FROM platform_copy WHERE id = 30")).isEqualTo(2)
        assertThat(singleLong(db, "SELECT destinationAccountId FROM delivery_job WHERE id = 40")).isEqualTo(2)
        assertThat(singleLong(db, "SELECT destinationAccountId FROM sync_rule WHERE id = 50")).isEqualTo(2)
    }

    private fun singleLong(db: SupportSQLiteDatabase, sql: String): Long =
        db.query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun createVersion1Schema(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `platform_account` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `platform` TEXT NOT NULL, `externalUserId` TEXT, `displayName` TEXT, `encryptedToken` TEXT, `encryptedRefreshToken` TEXT, `tokenExpiresAtEpochMs` INTEGER, `syncCursor` TEXT, `status` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `canonical_activity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sportType` TEXT NOT NULL, `startTimeEpochMs` INTEGER NOT NULL, `endTimeEpochMs` INTEGER, `elapsedSeconds` INTEGER, `movingSeconds` INTEGER, `distanceMeters` REAL, `elevationGain` REAL, `title` TEXT, `activityFingerprint` TEXT, `dedupStatus` TEXT NOT NULL, `primaryVariantId` INTEGER, `createdAtEpochMs` INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_canonical_activity_startTimeEpochMs` ON `canonical_activity` (`startTimeEpochMs`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_canonical_activity_activityFingerprint` ON `canonical_activity` (`activityFingerprint`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `activity_variant` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `canonicalActivityId` INTEGER NOT NULL, `collectorType` TEXT NOT NULL, `sourcePlatform` TEXT, `remoteActivityId` TEXT, `localFilePath` TEXT NOT NULL, `fileSha256` TEXT NOT NULL, `fileSize` INTEGER NOT NULL, `recordingManufacturer` INTEGER, `recordingProduct` INTEGER, `deviceSerial` TEXT, `qualityScore` INTEGER, `validationStatus` TEXT NOT NULL, `hasGps` INTEGER NOT NULL, `hasHeartRate` INTEGER NOT NULL, `hasCadence` INTEGER NOT NULL, `hasPower` INTEGER NOT NULL, `recordCount` INTEGER, `trackSummaryJson` TEXT, `createdAtEpochMs` INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_activity_variant_fileSha256` ON `activity_variant` (`fileSha256`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_variant_canonicalActivityId` ON `activity_variant` (`canonicalActivityId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `platform_copy` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `canonicalActivityId` INTEGER NOT NULL, `platformAccountId` INTEGER NOT NULL, `remoteActivityId` TEXT NOT NULL, `createdBySync` INTEGER NOT NULL, `sourceVariantId` INTEGER, `createdAtEpochMs` INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_platform_copy_platformAccountId_remoteActivityId` ON `platform_copy` (`platformAccountId`, `remoteActivityId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_platform_copy_canonicalActivityId_platformAccountId` ON `platform_copy` (`canonicalActivityId`, `platformAccountId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `delivery_job` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `canonicalActivityId` INTEGER NOT NULL, `destinationAccountId` INTEGER NOT NULL, `sourceVariantId` INTEGER NOT NULL, `idempotencyKey` TEXT NOT NULL, `status` TEXT NOT NULL, `attemptCount` INTEGER NOT NULL, `nextRetryAtEpochMs` INTEGER, `remoteUploadId` TEXT, `remoteActivityId` TEXT, `errorCode` TEXT, `errorMessage` TEXT, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_delivery_job_canonicalActivityId_destinationAccountId` ON `delivery_job` (`canonicalActivityId`, `destinationAccountId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_delivery_job_idempotencyKey` ON `delivery_job` (`idempotencyKey`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_delivery_job_status` ON `delivery_job` (`status`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `sync_rule` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sourcePlatform` TEXT NOT NULL, `destinationAccountId` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `historyStartTimeEpochMs` INTEGER, `createdAtEpochMs` INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_rule_sourcePlatform_destinationAccountId` ON `sync_rule` (`sourcePlatform`, `destinationAccountId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `import_job` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `displayName` TEXT, `status` TEXT NOT NULL, `localFilePath` TEXT, `fileSha256` TEXT, `validationStatus` TEXT, `errorMessage` TEXT, `canonicalActivityId` INTEGER, `variantId` INTEGER, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `app_settings` (`id` INTEGER NOT NULL, `storagePolicy` TEXT NOT NULL, `autoSyncEnabled` INTEGER NOT NULL, PRIMARY KEY(`id`))")
    }
}
