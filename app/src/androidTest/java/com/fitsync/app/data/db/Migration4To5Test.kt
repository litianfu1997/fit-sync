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
class Migration4To5Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration-4-5-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migrationAddsSkipTableAndPreservesExistingRows() {
        context.deleteDatabase(dbName)
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE platform_account(id INTEGER PRIMARY KEY NOT NULL, platform TEXT NOT NULL)")
                        db.execSQL("CREATE TABLE sync_rule(id INTEGER PRIMARY KEY NOT NULL, sourcePlatform TEXT NOT NULL)")
                        db.execSQL("CREATE TABLE delivery_job(id INTEGER PRIMARY KEY NOT NULL, remoteUploadId TEXT)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                },
            )
            .build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).use { helper ->
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO platform_account VALUES(7, 'STRAVA')")
            db.execSQL("INSERT INTO sync_rule VALUES(8, 'STRAVA')")
            db.execSQL("INSERT INTO delivery_job VALUES(9, '12345')")
            FitSyncDatabase.MIGRATION_4_5.migrate(db)
            db.execSQL(
                "INSERT INTO remote_activity_skip(" +
                    "platformAccountId, remoteActivityId, reasonCode, createdAtEpochMs, updatedAtEpochMs" +
                    ") VALUES(7, '42', 'UNSUPPORTED_ORIGINAL_FORMAT', 100, 100)",
            )

            db.query("SELECT platform FROM platform_account WHERE id = 7").use {
                assertThat(it.moveToFirst()).isTrue()
                assertThat(it.getString(0)).isEqualTo("STRAVA")
            }
            db.query("SELECT remoteUploadId FROM delivery_job WHERE id = 9").use {
                assertThat(it.moveToFirst()).isTrue()
                assertThat(it.getString(0)).isEqualTo("12345")
            }
            db.query("SELECT reasonCode FROM remote_activity_skip WHERE remoteActivityId = '42'").use {
                assertThat(it.moveToFirst()).isTrue()
                assertThat(it.getString(0)).isEqualTo("UNSUPPORTED_ORIGINAL_FORMAT")
            }
        }
    }
}
