package com.fitsync.app.sync

import com.fitsync.app.connectors.ConnectorCapabilities
import com.fitsync.app.domain.model.ConnectorExecution
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SyncCoordinatorPolicyTest {
    @Test
    fun accountPhases_putDestinationsBeforeEverySourceAccount() {
        val phases = buildSyncAccountPhases(
            accountIds = listOf(1, 2, 3, 4),
            destinationAccountIds = setOf(2, 4),
        )

        assertThat(phases.destinationAccountIds).containsExactly(2L, 4L).inOrder()
        assertThat(phases.remainingAccountIds).containsExactly(1L, 3L).inOrder()
    }

    @Test
    fun deliveryDrain_continuesImmediateBatchesWithoutAnotherUserAction() {
        assertThat(
            shouldContinueDeliveryDrain(
                hasMore = true,
                nextDelayMs = 0,
                completedBatches = 1,
                elapsedMs = 1_000,
            ),
        ).isTrue()
    }

    @Test
    fun deliveryDrain_yieldsForBackoffOrWorkerBudget() {
        assertThat(
            shouldContinueDeliveryDrain(
                hasMore = true,
                nextDelayMs = 60_000,
                completedBatches = 1,
                elapsedMs = 1_000,
            ),
        ).isFalse()
        assertThat(
            shouldContinueDeliveryDrain(
                hasMore = true,
                nextDelayMs = 0,
                completedBatches = MAX_DELIVERY_BATCHES_PER_WORKER,
                elapsedMs = 1_000,
            ),
        ).isFalse()
        assertThat(
            shouldContinueDeliveryDrain(
                hasMore = true,
                nextDelayMs = 0,
                completedBatches = 1,
                elapsedMs = DELIVERY_DRAIN_BUDGET_MS,
            ),
        ).isFalse()
    }

    @Test
    fun targetPrefetch_expandsToEarliestActivityWithClockTolerance() {
        val earlier = 10_000_000L
        val later = 20_000_000L

        assertThat(earlierEpoch(later, earlier)).isEqualTo(earlier)
        assertThat(targetPrefetchSince(earlier))
            .isEqualTo(earlier - TARGET_PREFETCH_TOLERANCE_MS)
        assertThat(targetPrefetchSince(TARGET_PREFETCH_TOLERANCE_MS - 1)).isEqualTo(0)
    }

    @Test
    fun targetComparison_requiresBackgroundListAndOriginalDownload() {
        assertThat(
            canCompareTargetInBackground(
                ConnectorCapabilities(
                    listActivitiesMode = ConnectorExecution.BACKGROUND,
                    downloadFitMode = ConnectorExecution.BACKGROUND,
                ),
            ),
        ).isTrue()
        assertThat(
            canCompareTargetInBackground(
                ConnectorCapabilities(
                    listActivitiesMode = ConnectorExecution.BACKGROUND,
                    downloadFitMode = ConnectorExecution.MANUAL,
                ),
            ),
        ).isFalse()
    }
}
