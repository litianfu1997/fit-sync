package com.fitsync.app.sync

import com.fitsync.app.data.db.entity.SyncRuleEntity
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.domain.model.DedupStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeliveryPlannerTest {
    @Test
    fun needsReviewActivity_isNotEligibleForAutomaticOrManualDelivery() {
        assertThat(isDeliveryPlanningAllowed(DedupStatus.NEEDS_REVIEW)).isFalse()
        assertThat(isDeliveryPlanningAllowed(DedupStatus.MANUALLY_KEPT_SEPARATE)).isTrue()
    }

    @Test
    fun ruleWithoutHistory_usesActivityStartTimeInsteadOfImportTime() {
        val rule = SyncRuleEntity(
            sourcePlatform = Platform.LOCAL_IMPORT,
            destinationAccountId = 7,
            includeHistory = false,
            createdAtEpochMs = 1_000,
        )

        assertThat(isActivityEligibleForRule(999, rule)).isFalse()
        assertThat(isActivityEligibleForRule(1_000, rule)).isTrue()
    }

    @Test
    fun idempotencyKey_isStableForSameFileAndDestination() {
        val first = DeliveryPlanner.idempotencyKey("abc123", 7)
        val second = DeliveryPlanner.idempotencyKey("abc123", 7)
        assertThat(first).isEqualTo(second)
        assertThat(first).hasLength(64)
    }

    @Test
    fun idempotencyKey_changesAcrossDestinationOrFile() {
        val base = DeliveryPlanner.idempotencyKey("abc123", 7)
        assertThat(DeliveryPlanner.idempotencyKey("abc123", 8)).isNotEqualTo(base)
        assertThat(DeliveryPlanner.idempotencyKey("different", 7)).isNotEqualTo(base)
    }

    @Test
    fun manualLocalImport_matchesActivitiesOriginallyImportedFromCloudPlatforms() {
        assertThat(
            matchesManualSyncSource(
                sourcePlatform = Platform.LOCAL_IMPORT,
                hasSourceVariant = false,
                hasSourceCopy = false,
            ),
        ).isTrue()
    }

    @Test
    fun manualCloudSource_requiresMatchingVariantOrPlatformCopy() {
        assertThat(
            matchesManualSyncSource(
                sourcePlatform = Platform.STRAVA,
                hasSourceVariant = false,
                hasSourceCopy = false,
            ),
        ).isFalse()
        assertThat(
            matchesManualSyncSource(
                sourcePlatform = Platform.STRAVA,
                hasSourceVariant = true,
                hasSourceCopy = false,
            ),
        ).isTrue()
        assertThat(
            matchesManualSyncSource(
                sourcePlatform = Platform.STRAVA,
                hasSourceVariant = false,
                hasSourceCopy = true,
            ),
        ).isTrue()
    }

    @Test
    fun destinationWhitelist_onlyAllowsAccountsRefreshedInCurrentRun() {
        assertThat(isDestinationReadyForPlanning(7, null)).isTrue()
        assertThat(isDestinationReadyForPlanning(7, setOf(7, 8))).isTrue()
        assertThat(isDestinationReadyForPlanning(7, setOf(8))).isFalse()
        assertThat(isDestinationReadyForPlanning(7, emptySet())).isFalse()
    }
}
