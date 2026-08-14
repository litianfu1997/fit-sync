package com.fitsync.app.sync

import com.fitsync.app.connectors.ConnectorCapabilities
import com.fitsync.app.connectors.ConnectorResult
import com.fitsync.app.connectors.RemoteActivitySummary
import com.fitsync.app.connectors.RemoteCopy
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
    fun unreadyDestinationWithActiveJobsKeepsContinuationScheduled() {
        assertThat(
            withUnreadyDestinationRetry(
                schedule = DeliverySchedule(hasMore = false, delayMs = null),
                hasUnreadyActiveJobs = true,
            ),
        ).isEqualTo(DeliverySchedule(hasMore = true, delayMs = UNREADY_DESTINATION_RETRY_MS))

        assertThat(
            withUnreadyDestinationRetry(
                schedule = DeliverySchedule(hasMore = true, delayMs = 30_000L),
                hasUnreadyActiveJobs = true,
            ),
        ).isEqualTo(DeliverySchedule(hasMore = true, delayMs = 30_000L))
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
    fun onelapUploadWindow_acceptsTenItemsPerAccount() {
        val inFlightByAccount = mutableMapOf(7L to 0)

        repeat(MAX_ONELAP_UPLOADS_PER_BATCH) {
            assertThat(tryReserveOnelapUploadSlot(inFlightByAccount, 7L)).isTrue()
        }

        assertThat(inFlightByAccount[7L]).isEqualTo(10)
        assertThat(tryReserveOnelapUploadSlot(inFlightByAccount, 7L)).isFalse()
        assertThat(tryReserveOnelapUploadSlot(inFlightByAccount, 8L)).isTrue()
    }

    @Test
    fun uploadResult_rejectsBlankRemoteIdButKeepsConfirmedCopy() {
        val missingId = validateUploadResult(ConnectorResult.Success(RemoteCopy(remoteActivityId = " ")))
        val confirmed = ConnectorResult.Success(RemoteCopy(remoteActivityId = "remote-1"))

        assertThat(missingId).isEqualTo(
            ConnectorResult.RetryableFailure("REMOTE_ID_MISSING", "平台未返回活动 ID，稍后会自动重试"),
        )
        assertThat(validateUploadResult(confirmed)).isSameInstanceAs(confirmed)
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

    @Test
    fun backgroundDestination_allowsUploadOnlyWebsiteConnector() {
        val uploadOnly = ConnectorCapabilities(
            listActivitiesMode = ConnectorExecution.UNSUPPORTED,
            downloadFitMode = ConnectorExecution.UNSUPPORTED,
            uploadFitMode = ConnectorExecution.BACKGROUND,
        )
        val manualUpload = uploadOnly.copy(uploadFitMode = ConnectorExecution.FOREGROUND_WEB)

        assertThat(canPrepareDestinationInBackground(uploadOnly)).isTrue()
        assertThat(canPrepareDestinationInBackground(manualUpload)).isFalse()
    }

    @Test
    fun pendingUpload_exactRemoteMarkerConfirmsWithoutDownloadingOriginal() {
        val candidate = pendingCandidate(
            jobId = 1,
            sha256 = "abc123",
            confirmationMarker = "oss-object-123.fit",
        )
        val remote = remoteSummary(
            id = "remote-1",
            start = null,
            distance = null,
            downloadUrl = "https://oss.example/oss-object-123.fit",
        )

        assertThat(findHighConfidencePendingUploadMatches(listOf(remote), listOf(candidate)))
            .containsExactly(PendingUploadRemoteMatch(remote, candidate))
    }

    @Test
    fun pendingUpload_startAndDistanceConfirmUniqueRealRemoteId() {
        val candidate = pendingCandidate(jobId = 1, start = 10_000_000L, distance = 42_000.0)
        val matching = remoteSummary("remote-1", 10_030_000L, 42_050.0)
        val unrelated = remoteSummary("remote-2", 20_000_000L, 42_050.0)

        assertThat(
            findHighConfidencePendingUploadMatches(listOf(matching, unrelated), listOf(candidate)),
        ).containsExactly(PendingUploadRemoteMatch(matching, candidate))
    }

    @Test
    fun pendingUpload_ambiguousSummaryDoesNotCreatePlatformCopy() {
        val remote = remoteSummary("remote-1", 10_000_000L, 42_000.0)
        val candidates = listOf(
            pendingCandidate(jobId = 1, start = 10_000_000L, distance = 42_000.0),
            pendingCandidate(jobId = 2, start = 10_000_000L, distance = 42_000.0),
        )

        assertThat(findHighConfidencePendingUploadMatches(listOf(remote), candidates)).isEmpty()
    }

    private fun pendingCandidate(
        jobId: Long,
        sha256: String = "sha-$jobId",
        start: Long = 1_000L,
        distance: Double? = null,
        confirmationMarker: String? = null,
    ) = PendingUploadMatchCandidate(
        jobId = jobId,
        canonicalActivityId = jobId * 10,
        sourceVariantId = jobId * 100,
        sourceFileSha256 = sha256,
        startTimeEpochMs = start,
        distanceMeters = distance,
        title = null,
        confirmationMarker = confirmationMarker,
    )

    private fun remoteSummary(
        id: String,
        start: Long?,
        distance: Double?,
        downloadUrl: String? = null,
    ) = RemoteActivitySummary(
        remoteActivityId = id,
        startTimeEpochMs = start,
        name = null,
        sportType = null,
        distanceMeters = distance,
        downloadUrl = downloadUrl,
    )
}
