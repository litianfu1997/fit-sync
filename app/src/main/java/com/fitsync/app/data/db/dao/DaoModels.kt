package com.fitsync.app.data.db.dao

import com.fitsync.app.domain.model.DeliveryJobStatus
import com.fitsync.app.domain.model.DedupStatus
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.domain.model.SportType

data class HomeSnapshot(
    val discovered: Int,
    val synced: Int,
    val pending: Int,
    val review: Int,
    val failed: Int,
)

data class ActivityListQuery(
    val searchText: String,
    val filter: String,
    val oldestFirst: Boolean,
)

data class ActivityListItem(
    val id: Long,
    val title: String?,
    val sportType: SportType,
    val startTimeEpochMs: Long,
    val elapsedSeconds: Int?,
    val distanceMeters: Double?,
    val dedupStatus: DedupStatus,
)

data class SyncJobListItem(
    val id: Long,
    val canonicalActivityId: Long,
    val destinationAccountId: Long,
    val status: DeliveryJobStatus,
    val attemptCount: Int,
    val errorCode: String?,
    val errorMessage: String?,
    val updatedAtEpochMs: Long,
    val activityTitle: String?,
    val activitySportType: SportType?,
    val destinationPlatform: Platform?,
)

data class VariantCleanupCandidate(
    val variantId: Long,
    val localFilePath: String,
    val activeJobCount: Int,
    val latestSuccessfulDeliveryAtEpochMs: Long?,
)
