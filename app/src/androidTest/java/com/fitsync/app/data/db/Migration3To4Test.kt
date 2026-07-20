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
class Migration3To4Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration-3-4-test.db"
    private var database: FitSyncDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun migrationAddsReviewCandidateColumn() = runBlocking {
        context.deleteDatabase(dbName)
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) = createVersion3Schema(db)
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).use { helper ->
            helper.writableDatabase.execSQL(
                """
                INSERT INTO canonical_activity(
                    sportType, startTimeEpochMs, dedupStatus, createdAtEpochMs
                ) VALUES('CYCLING', 1000, 'UNIQUE', 2000)
                """.trimIndent(),
            )
        }

        database = Room.databaseBuilder(context, FitSyncDatabase::class.java, dbName)
            .addMigrations(FitSyncDatabase.MIGRATION_3_4)
            .build()
        val activity = database!!.canonicalActivityDao().getById(1)
        assertThat(activity).isNotNull()
        assertThat(activity!!.reviewCandidateCanonicalId).isNull()
        assertThat(activity.startTimeEpochMs).isEqualTo(1000L)
    }

    private fun createVersion3Schema(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `canonical_activity` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `sportType` TEXT NOT NULL,
                `startTimeEpochMs` INTEGER NOT NULL,
                `endTimeEpochMs` INTEGER,
                `elapsedSeconds` INTEGER,
                `movingSeconds` INTEGER,
                `distanceMeters` REAL,
                `elevationGain` REAL,
                `avgHeartRate` INTEGER,
                `title` TEXT,
                `activityFingerprint` TEXT,
                `dedupStatus` TEXT NOT NULL,
                `primaryVariantId` INTEGER,
                `createdAtEpochMs` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS `platform_account` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `platform` TEXT NOT NULL, `status` TEXT NOT NULL, `needsUserAction` INTEGER NOT NULL DEFAULT 0, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `activity_variant` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `canonicalActivityId` INTEGER NOT NULL, `collectorType` TEXT NOT NULL, `localFilePath` TEXT NOT NULL, `fileSha256` TEXT NOT NULL, `fileSize` INTEGER NOT NULL, `validationStatus` TEXT NOT NULL, `hasGps` INTEGER NOT NULL, `hasHeartRate` INTEGER NOT NULL, `hasCadence` INTEGER NOT NULL, `hasPower` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `platform_copy` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `canonicalActivityId` INTEGER NOT NULL, `platformAccountId` INTEGER NOT NULL, `remoteActivityId` TEXT NOT NULL, `createdBySync` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `delivery_job` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `canonicalActivityId` INTEGER NOT NULL, `destinationAccountId` INTEGER NOT NULL, `sourceVariantId` INTEGER NOT NULL, `idempotencyKey` TEXT NOT NULL, `status` TEXT NOT NULL, `executionMode` TEXT NOT NULL DEFAULT 'BACKGROUND', `attemptCount` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `sync_rule` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sourcePlatform` TEXT NOT NULL, `destinationAccountId` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `includeHistory` INTEGER NOT NULL DEFAULT 0, `createdAtEpochMs` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `import_job` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `displayName` TEXT, `status` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `app_settings` (`id` INTEGER NOT NULL, `storagePolicy` TEXT NOT NULL, `autoSyncEnabled` INTEGER NOT NULL, `initialHistoryDays` INTEGER NOT NULL DEFAULT 90, `syncIntervalMinutes` INTEGER NOT NULL DEFAULT 15, `notificationsEnabled` INTEGER NOT NULL DEFAULT 1, `fitParserVersion` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))")
    }
}
