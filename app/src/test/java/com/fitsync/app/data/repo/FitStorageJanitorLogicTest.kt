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
            eligible(
                FitStoragePolicy.KEEP_FOREVER,
                activeJobs = 0,
                terminalJobUpdatedAt = 0L,
                variantCreatedAt = 0L,
                now = Long.MAX_VALUE,
            ),
        ).isFalse()
    }

    @Test
    fun deleteAfterSync_requiresTerminalSuccessAndNoActive() {
        assertThat(
            eligible(
                FitStoragePolicy.DELETE_AFTER_SYNC,
                activeJobs = 1,
                terminalJobUpdatedAt = 1L,
                variantCreatedAt = 1L,
                now = 100L,
            ),
        ).isFalse()
        assertThat(
            eligible(
                FitStoragePolicy.DELETE_AFTER_SYNC,
                activeJobs = 0,
                terminalJobUpdatedAt = null,
                variantCreatedAt = 1L,
                now = 100L,
            ),
        ).isFalse()
        assertThat(
            eligible(
                FitStoragePolicy.DELETE_AFTER_SYNC,
                activeJobs = 0,
                terminalJobUpdatedAt = 1L,
                variantCreatedAt = 1L,
                now = 100L,
            ),
        ).isTrue()
    }

    @Test
    fun deleteAfter7Days_usesTerminalOrCreatedAnchor() {
        val day = 24L * 60 * 60 * 1000
        val now = 10 * day
        assertThat(
            eligible(
                FitStoragePolicy.DELETE_AFTER_7_DAYS,
                activeJobs = 0,
                terminalJobUpdatedAt = now - 6 * day,
                variantCreatedAt = 0L,
                now = now,
            ),
        ).isFalse()
        assertThat(
            eligible(
                FitStoragePolicy.DELETE_AFTER_7_DAYS,
                activeJobs = 0,
                terminalJobUpdatedAt = now - 8 * day,
                variantCreatedAt = 0L,
                now = now,
            ),
        ).isTrue()
        assertThat(
            eligible(
                FitStoragePolicy.DELETE_AFTER_7_DAYS,
                activeJobs = 0,
                terminalJobUpdatedAt = null,
                variantCreatedAt = now - 8 * day,
                now = now,
            ),
        ).isTrue()
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

    private fun eligible(
        policy: FitStoragePolicy,
        activeJobs: Int,
        terminalJobUpdatedAt: Long?,
        variantCreatedAt: Long,
        now: Long,
    ): Boolean {
        if (activeJobs > 0) return false
        return when (policy) {
            FitStoragePolicy.KEEP_FOREVER -> false
            FitStoragePolicy.DELETE_AFTER_SYNC -> terminalJobUpdatedAt != null
            FitStoragePolicy.DELETE_AFTER_7_DAYS -> {
                val anchor = terminalJobUpdatedAt ?: variantCreatedAt
                now - anchor >= 7L * 24 * 60 * 60 * 1000
            }
        }
    }
}
