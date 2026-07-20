package com.fitsync.app.sync

import androidx.work.Data
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.domain.model.SportType
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ManualSyncSelectionTest {
    @Test
    fun workData_roundTripsSelection() {
        val selection = ManualSyncSelection(
            sourcePlatform = Platform.GARMIN,
            destinationAccountId = 42L,
            sportTypes = setOf(SportType.CYCLING, SportType.MTB, SportType.RUNNING),
        )

        val data = Data.Builder().putManualSyncSelection(selection).build()

        assertThat(data.toManualSyncSelection()).isEqualTo(selection)
    }

    @Test
    fun malformedWorkData_doesNotBroadenToGlobalSync() {
        val data = Data.Builder()
            .putBoolean("manual_sync_scoped", true)
            .putString("manual_sync_source", "NOT_A_PLATFORM")
            .putLong("manual_sync_destination", 42L)
            .putStringArray("manual_sync_sport_types", arrayOf(SportType.CYCLING.name))
            .build()

        assertThrows(IllegalArgumentException::class.java) {
            data.toManualSyncSelection()
        }
    }
}
