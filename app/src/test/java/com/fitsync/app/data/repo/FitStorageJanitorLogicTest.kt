package com.fitsync.app.data.repo

import com.fitsync.app.domain.model.DeliveryJobStatus
import com.fitsync.app.domain.model.FitStoragePolicy
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 存储策略可删判定（与 [FitStorageJanitor] 语义对齐的纯函数测试）。
 */
class FitStorageJanitorLogicTest {

    @Test
    fun keepForever_neverEligible() {
        assertThat(
            isFitStorageDeletionEligible(
                FitStoragePolicy.KEEP_FOREVER,
                hasActiveJobs = false,
                latestSuccessfulDeliveryAtEpochMs = 0L,
                nowEpochMs = Long.MAX_VALUE,
            ),
        ).isFalse()
    }

    @Test
    fun deleteAfterSync_requiresTerminalSuccessAndNoActive() {
        assertThat(
            isFitStorageDeletionEligible(
                FitStoragePolicy.DELETE_AFTER_SYNC,
                hasActiveJobs = true,
                latestSuccessfulDeliveryAtEpochMs = 1L,
                nowEpochMs = 100L,
            ),
        ).isFalse()
        assertThat(
            isFitStorageDeletionEligible(
                FitStoragePolicy.DELETE_AFTER_SYNC,
                hasActiveJobs = false,
                latestSuccessfulDeliveryAtEpochMs = null,
                nowEpochMs = 100L,
            ),
        ).isFalse()
        assertThat(
            isFitStorageDeletionEligible(
                FitStoragePolicy.DELETE_AFTER_SYNC,
                hasActiveJobs = false,
                latestSuccessfulDeliveryAtEpochMs = 1L,
                nowEpochMs = 100L,
            ),
        ).isTrue()
    }

    @Test
    fun deleteAfter7Days_requiresSuccessfulDeliveryAndUsesItsTimestamp() {
        val day = 24L * 60 * 60 * 1000
        val now = 10 * day
        assertThat(
            isFitStorageDeletionEligible(
                FitStoragePolicy.DELETE_AFTER_7_DAYS,
                hasActiveJobs = false,
                latestSuccessfulDeliveryAtEpochMs = now - 6 * day,
                nowEpochMs = now,
            ),
        ).isFalse()
        assertThat(
            isFitStorageDeletionEligible(
                FitStoragePolicy.DELETE_AFTER_7_DAYS,
                hasActiveJobs = false,
                latestSuccessfulDeliveryAtEpochMs = now - 8 * day,
                nowEpochMs = now,
            ),
        ).isTrue()
        assertThat(
            isFitStorageDeletionEligible(
                FitStoragePolicy.DELETE_AFTER_7_DAYS,
                hasActiveJobs = false,
                latestSuccessfulDeliveryAtEpochMs = null,
                nowEpochMs = now,
            ),
        ).isFalse()
    }

    @Test
    fun activeStatuses_matchJanitor() {
        val active = setOf(
            DeliveryJobStatus.PENDING,
            DeliveryJobStatus.UPLOADING,
            DeliveryJobStatus.WAITING_USER,
            DeliveryJobStatus.WAITING_REMOTE_PROCESS,
            DeliveryJobStatus.RETRY_WAIT,
        )
        val terminal = setOf(
            DeliveryJobStatus.SUCCEEDED,
            DeliveryJobStatus.DUPLICATE_REMOTE,
            DeliveryJobStatus.FAILED,
            DeliveryJobStatus.DISABLED,
        )
        assertThat(active.intersect(terminal)).isEmpty()
    }

}
