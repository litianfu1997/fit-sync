package com.fitsync.app.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fitsync.app.data.db.FitSyncDatabase
import com.fitsync.app.data.db.entity.ActivityVariantEntity
import com.fitsync.app.data.db.entity.CanonicalActivityEntity
import com.fitsync.app.data.db.entity.DeliveryJobEntity
import com.fitsync.app.data.db.entity.PlatformAccountEntity
import com.fitsync.app.data.db.entity.PlatformCopyEntity
import com.fitsync.app.domain.model.CollectorType
import com.fitsync.app.domain.model.DeliveryJobStatus
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.domain.model.SportType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 覆盖「活动已在目标账号上、任务却停在 FAILED」的自愈收尾。
 *
 * 背景：Garmin 已受理文件、活动也建出来了，异步导入结果却报 500，任务被判定为永久失败；
 * FAILED 不在可运行状态集合里，之后拉取发现活动就在目标平台时也走不到收尾逻辑，首页长期显示失败。
 */
@RunWith(AndroidJUnit4::class)
class DeliveryJobReconcileTest {
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
    fun resolveFailedJobsWithConfirmedRemote_clearsFailureAndAdoptsRemoteId() = runBlocking {
        val garmin = db.platformAccountDao().upsert(PlatformAccountEntity(platform = Platform.GARMIN))
        val canonicalId = insertCanonical()
        val variantId = insertVariant(canonicalId, "sha-78")
        insertCopy(canonicalId, garmin, "640046580", "sha-78")
        val jobId = insertJob(
            canonicalId = canonicalId,
            accountId = garmin,
            sourceVariantId = variantId,
            status = DeliveryJobStatus.FAILED,
            remoteUploadId = "fitsync-async-v1:GARMIN:1789485277734:abc",
            errorCode = "GARMIN_IMPORT_REJECTED",
            errorMessage = "Garmin 拒绝导入该文件：code=500",
            attemptCount = 6,
            nextRetryAtEpochMs = 1_789_485_307_738,
        )

        val resolved = db.deliveryJobDao().resolveFailedJobsWithConfirmedRemote()

        assertThat(resolved).isEqualTo(1)
        val job = db.deliveryJobDao().getById(jobId)!!
        assertThat(job.status).isEqualTo(DeliveryJobStatus.DUPLICATE_REMOTE)
        // 远端活动 ID 取自已确认的 PlatformCopy，不凭空生成。
        assertThat(job.remoteActivityId).isEqualTo("640046580")
        assertThat(job.errorCode).isNull()
        assertThat(job.errorMessage).isNull()
        assertThat(job.nextRetryAtEpochMs).isNull()
        assertThat(job.remoteUploadId).isNull()
        // 重试次数是历史信息，收尾时保留。
        assertThat(job.attemptCount).isEqualTo(6)
    }

    @Test
    fun resolveFailedJobsWithConfirmedRemote_leavesFailedWithoutCopyPending() = runBlocking {
        val garmin = db.platformAccountDao().upsert(PlatformAccountEntity(platform = Platform.GARMIN))
        val canonicalId = insertCanonical()
        val jobId = insertJob(
            canonicalId = canonicalId,
            accountId = garmin,
            sourceVariantId = insertVariant(canonicalId, "sha-no-copy"),
            status = DeliveryJobStatus.FAILED,
            errorMessage = "目标平台暂时不可用",
        )

        assertThat(db.deliveryJobDao().resolveFailedJobsWithConfirmedRemote()).isEqualTo(0)
        val job = db.deliveryJobDao().getById(jobId)!!
        assertThat(job.status).isEqualTo(DeliveryJobStatus.FAILED)
        assertThat(job.errorMessage).isEqualTo("目标平台暂时不可用")
        assertThat(job.remoteActivityId).isNull()
    }

    @Test
    fun resolveFailedJobsWithConfirmedRemote_onlyTouchesFailedStatus() = runBlocking {
        val garmin = db.platformAccountDao().upsert(PlatformAccountEntity(platform = Platform.GARMIN))
        val untouched = listOf(
            DeliveryJobStatus.PENDING,
            DeliveryJobStatus.UPLOADING,
            DeliveryJobStatus.WAITING_USER,
            DeliveryJobStatus.WAITING_REMOTE_PROCESS,
            DeliveryJobStatus.SUCCEEDED,
            DeliveryJobStatus.DUPLICATE_REMOTE,
            DeliveryJobStatus.RETRY_WAIT,
            DeliveryJobStatus.DISABLED,
        )
        val canonicalIds = mutableListOf<Long>()
        untouched.forEachIndexed { index, status ->
            val canonicalId = insertCanonical(startTimeEpochMs = 1_000L + index)
            insertCopy(canonicalId, garmin, "remote-$index", "sha-$status")
            insertJob(
                canonicalId = canonicalId,
                accountId = garmin,
                sourceVariantId = insertVariant(canonicalId, "sha-$status"),
                status = status,
                errorMessage = "keep-me",
            )
            canonicalIds += canonicalId
        }

        assertThat(db.deliveryJobDao().resolveFailedJobsWithConfirmedRemote()).isEqualTo(0)
        canonicalIds.forEachIndexed { index, canonicalId ->
            val job = db.deliveryJobDao().findByCanonical(canonicalId).single()
            assertThat(job.status).isEqualTo(untouched[index])
            assertThat(job.errorMessage).isEqualTo("keep-me")
        }
    }

    @Test
    fun resolveFailedJobsWithConfirmedRemote_scopesToMatchingAccount() = runBlocking {
        val garmin = db.platformAccountDao().upsert(PlatformAccountEntity(platform = Platform.GARMIN))
        val strava = db.platformAccountDao().upsert(PlatformAccountEntity(platform = Platform.STRAVA))
        val canonicalId = insertCanonical()
        // 副本只挂在 Strava 账号上，发往 Garmin 的失败任务不该被收尾。
        insertCopy(canonicalId, strava, "20183989526", "sha-strava-copy")
        val garminJob = insertJob(
            canonicalId = canonicalId,
            accountId = garmin,
            sourceVariantId = insertVariant(canonicalId, "sha-scope"),
            status = DeliveryJobStatus.FAILED,
        )

        assertThat(db.deliveryJobDao().resolveFailedJobsWithConfirmedRemote()).isEqualTo(0)
        assertThat(db.deliveryJobDao().getById(garminJob)!!.status).isEqualTo(DeliveryJobStatus.FAILED)
    }

    private suspend fun insertCanonical(startTimeEpochMs: Long = 1_000L): Long =
        db.canonicalActivityDao().insert(
            CanonicalActivityEntity(
                sportType = SportType.CYCLING,
                startTimeEpochMs = startTimeEpochMs,
            ),
        )

    private suspend fun insertVariant(canonicalId: Long, sha: String): Long =
        db.activityVariantDao().insert(
            ActivityVariantEntity(
                canonicalActivityId = canonicalId,
                collectorType = CollectorType.CLOUD,
                sourcePlatform = Platform.STRAVA,
                localFilePath = "$sha.fit",
                fileSha256 = sha,
                fileSize = 1,
            ),
        )

    private suspend fun insertCopy(
        canonicalId: Long,
        accountId: Long,
        remoteActivityId: String,
        sha: String,
    ): Long = db.platformCopyDao().insert(
        PlatformCopyEntity(
            canonicalActivityId = canonicalId,
            platformAccountId = accountId,
            remoteActivityId = remoteActivityId,
            fileSha256 = sha,
        ),
    )

    private suspend fun insertJob(
        canonicalId: Long,
        accountId: Long,
        sourceVariantId: Long,
        status: DeliveryJobStatus,
        remoteUploadId: String? = null,
        errorCode: String? = null,
        errorMessage: String? = null,
        attemptCount: Int = 0,
        nextRetryAtEpochMs: Long? = null,
    ): Long = db.deliveryJobDao().insert(
        DeliveryJobEntity(
            canonicalActivityId = canonicalId,
            destinationAccountId = accountId,
            sourceVariantId = sourceVariantId,
            idempotencyKey = "idem-$canonicalId-$accountId",
            status = status,
            remoteUploadId = remoteUploadId,
            errorCode = errorCode,
            errorMessage = errorMessage,
            attemptCount = attemptCount,
            nextRetryAtEpochMs = nextRetryAtEpochMs,
        ),
    )
}