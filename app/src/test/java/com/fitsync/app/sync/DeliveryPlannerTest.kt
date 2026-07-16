package com.fitsync.app.sync

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
}
