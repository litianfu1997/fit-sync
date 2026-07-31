package com.fitsync.app.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fitsync.app.data.db.FitSyncDatabase
import com.fitsync.app.data.db.entity.ActivityVariantEntity
import com.fitsync.app.data.db.entity.CanonicalActivityEntity
import com.fitsync.app.data.db.entity.PlatformAccountEntity
import com.fitsync.app.data.db.entity.PlatformCopyEntity
import com.fitsync.app.data.db.entity.SyncRuleEntity
import com.fitsync.app.domain.model.CollectorType
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.domain.model.SportType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeliveryPlannerDatabaseTest {
    private lateinit var db: FitSyncDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, FitSyncDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun planRuleBacklogs_plansAllRulesInOnePassAndHonorsSourceAndDestinationCopies() = runBlocking {
        val sourceAccountId = db.platformAccountDao().upsert(PlatformAccountEntity(platform = Platform.ONELAP))
        val garminAccountId = db.platformAccountDao().upsert(PlatformAccountEntity(platform = Platform.GARMIN))
        val corosAccountId = db.platformAccountDao().upsert(PlatformAccountEntity(platform = Platform.COROS))
        val garminRule = SyncRuleEntity(
            id = db.syncRuleDao().upsert(
                SyncRuleEntity(
                    sourcePlatform = Platform.ONELAP,
                    destinationAccountId = garminAccountId,
                    includeHistory = true,
                ),
            ),
            sourcePlatform = Platform.ONELAP,
            destinationAccountId = garminAccountId,
            includeHistory = true,
        )
        val corosRule = SyncRuleEntity(
            id = db.syncRuleDao().upsert(
                SyncRuleEntity(
                    sourcePlatform = Platform.ONELAP,
                    destinationAccountId = corosAccountId,
                    includeHistory = true,
                ),
            ),
            sourcePlatform = Platform.ONELAP,
            destinationAccountId = corosAccountId,
            includeHistory = true,
        )

        val directSourceActivity = insertActivityWithVariant(Platform.ONELAP, "direct")
        val sourceCopyActivity = insertActivityWithVariant(Platform.LOCAL_IMPORT, "copy")
        val unrelatedActivity = insertActivityWithVariant(Platform.LOCAL_IMPORT, "unrelated")
        db.platformCopyDao().insert(
            PlatformCopyEntity(
                canonicalActivityId = sourceCopyActivity,
                platformAccountId = sourceAccountId,
                remoteActivityId = "source-copy",
            ),
        )
        db.platformCopyDao().insert(
            PlatformCopyEntity(
                canonicalActivityId = directSourceActivity,
                platformAccountId = corosAccountId,
                remoteActivityId = "already-on-coros",
            ),
        )

        val planned = DeliveryPlanner(db).planRuleBacklogs(
            rules = listOf(garminRule, corosRule),
            allowedDestinationAccountIds = setOf(garminAccountId, corosAccountId),
        )

        assertThat(planned).isEqualTo(3)
        assertThat(db.deliveryJobDao().findByDestinationAccount(garminAccountId).map { it.canonicalActivityId })
            .containsExactly(directSourceActivity, sourceCopyActivity)
        assertThat(db.deliveryJobDao().findByDestinationAccount(corosAccountId).map { it.canonicalActivityId })
            .containsExactly(sourceCopyActivity)
        assertThat(
            db.deliveryJobDao().findByCanonical(unrelatedActivity),
        ).isEmpty()
    }

    private suspend fun insertActivityWithVariant(source: Platform, sha: String): Long {
        val activityId = db.canonicalActivityDao().insert(
            CanonicalActivityEntity(
                sportType = SportType.CYCLING,
                startTimeEpochMs = 1_000,
            ),
        )
        db.activityVariantDao().insert(
            ActivityVariantEntity(
                canonicalActivityId = activityId,
                collectorType = CollectorType.CLOUD,
                sourcePlatform = source,
                localFilePath = "$sha.fit",
                fileSha256 = sha,
                fileSize = 1,
            ),
        )
        return activityId
    }
}
