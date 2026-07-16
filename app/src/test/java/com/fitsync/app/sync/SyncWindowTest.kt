package com.fitsync.app.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SyncWindowTest {
    @Test
    fun manualSync_rescansConfiguredHistoryRange() {
        val now = 1_800_000_000_000L
        val previousSync = now - 60_000L

        val since = calculatePullSince(now, previousSync, historyDays = 800, forceFullHistory = true)

        assertThat(since).isEqualTo(now - 800L * 24 * 60 * 60 * 1000)
    }

    @Test
    fun backgroundSync_uses48HourOverlapAfterFirstSuccess() {
        val now = 1_800_000_000_000L
        val previousSync = now - 60_000L

        val since = calculatePullSince(now, previousSync, historyDays = 800, forceFullHistory = false)

        assertThat(since).isEqualTo(previousSync - 48L * 60 * 60 * 1000)
    }
}
