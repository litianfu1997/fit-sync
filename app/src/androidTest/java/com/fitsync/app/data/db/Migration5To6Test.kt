package com.fitsync.app.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration5To6Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration-5-6-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migrationBackfillsParserVersionAndCreatesPerformanceIndexes() {
        context.deleteDatabase(dbName)
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                override fun onCreate(db: SupportSQLiteDatabase) = createVersion5Tables(db)
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        FrameworkSQLiteOpenHelperFactory().create(configuration).use { helper ->
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO app_settings(id, fitParserVersion) VALUES(1, 5)")
            db.execSQL("INSERT INTO activity_variant(id, canonicalActivityId) VALUES(11, 7)")
            FitSyncDatabase.MIGRATION_5_6.migrate(db)

            assertThat(singleLong(db, "SELECT canonicalActivityId FROM activity_variant WHERE id = 11"))
                .isEqualTo(7)
            assertThat(singleLong(db, "SELECT parserVersion FROM activity_variant WHERE id = 11"))
                .isEqualTo(5)
            assertThat(indexNames(db, "activity_variant"))
                .contains("index_activity_variant_parserVersion_id")
            assertThat(indexNames(db, "delivery_job"))
                .containsAtLeast(
                    "index_delivery_job_status_nextRetryAtEpochMs_createdAtEpochMs",
                    "index_delivery_job_sourceVariantId",
                )
            assertThat(indexNames(db, "delivery_job")).doesNotContain("index_delivery_job_status")
        }
    }

    private fun createVersion5Tables(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE app_settings(id INTEGER PRIMARY KEY NOT NULL, fitParserVersion INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE activity_variant(id INTEGER PRIMARY KEY NOT NULL, canonicalActivityId INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE canonical_activity(id INTEGER PRIMARY KEY NOT NULL, sportType TEXT NOT NULL, startTimeEpochMs INTEGER NOT NULL, dedupStatus TEXT NOT NULL)")
        db.execSQL("CREATE TABLE platform_copy(id INTEGER PRIMARY KEY NOT NULL, platformAccountId INTEGER NOT NULL, fileSha256 TEXT)")
        db.execSQL("CREATE TABLE delivery_job(id INTEGER PRIMARY KEY NOT NULL, status TEXT NOT NULL, nextRetryAtEpochMs INTEGER, createdAtEpochMs INTEGER NOT NULL, sourceVariantId INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX index_delivery_job_status ON delivery_job(status)")
        db.execSQL("CREATE TABLE import_job(id INTEGER PRIMARY KEY NOT NULL, createdAtEpochMs INTEGER NOT NULL)")
    }

    private fun singleLong(db: SupportSQLiteDatabase, sql: String): Long =
        db.query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun indexNames(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA index_list(`$table`)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }
}
