package com.fitsync.app.sync

import com.fitsync.app.domain.model.Platform
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeliveryPlannerTest {
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
}
