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
class Migration6To7Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration-6-7-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migrationCreatesDestinationQueueIndex() {
        context.deleteDatabase(dbName)
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(6) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE delivery_job(
                            id INTEGER PRIMARY KEY NOT NULL,
                            destinationAccountId INTEGER NOT NULL,
                            status TEXT NOT NULL,
                            nextRetryAtEpochMs INTEGER,
                            createdAtEpochMs INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        FrameworkSQLiteOpenHelperFactory().create(configuration).use { helper ->
            val db = helper.writableDatabase
            FitSyncDatabase.MIGRATION_6_7.migrate(db)

            assertThat(indexNames(db, "delivery_job")).contains(
                "index_delivery_job_destinationAccountId_status_nextRetryAtEpochMs_createdAtEpochMs",
            )
        }
    }

    private fun indexNames(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA index_list(`$table`)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }
}
