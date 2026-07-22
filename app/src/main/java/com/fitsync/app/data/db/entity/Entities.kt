package com.fitsync.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.fitsync.app.domain.model.AccountStatus
import com.fitsync.app.domain.model.CollectorType
import com.fitsync.app.domain.model.ConnectorExecution
import com.fitsync.app.domain.model.DedupStatus
import com.fitsync.app.domain.model.DeliveryJobStatus
import com.fitsync.app.domain.model.ImportJobStatus
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.domain.model.SportType
import com.fitsync.app.domain.model.ValidationStatus

@Entity(
    tableName = "platform_account",
    indices = [Index(value = ["platform"], unique = true)],
)
data class PlatformAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val platform: Platform,
    val externalUserId: String? = null,
    val displayName: String? = null,
    val encryptedToken: String? = null,
    val encryptedRefreshToken: String? = null,
    val tokenExpiresAtEpochMs: Long? = null,
    val syncCursor: String? = null,
    val status: AccountStatus = AccountStatus.ACTIVE,
    val capabilitySnapshot: String? = null,
    val lastValidatedAtEpochMs: Long? = null,
    val lastSyncAtEpochMs: Long? = null,
    @ColumnInfo(defaultValue = "0") val needsUserAction: Boolean = false,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "canonical_activity",
    indices = [
        Index(value = ["startTimeEpochMs"]),
        Index(value = ["activityFingerprint"]),
        Index(value = ["sportType", "startTimeEpochMs"]),
        Index(value = ["dedupStatus", "startTimeEpochMs"]),
    ],
)
data class CanonicalActivityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sportType: SportType = SportType.UNKNOWN,
    val startTimeEpochMs: Long,
    val endTimeEpochMs: Long? = null,
    val elapsedSeconds: Int? = null,
    val movingSeconds: Int? = null,
    val distanceMeters: Double? = null,
    val elevationGain: Double? = null,
    val avgHeartRate: Int? = null,
    val title: String? = null,
    val activityFingerprint: String? = null,
    val dedupStatus: DedupStatus = DedupStatus.UNIQUE,
    val primaryVariantId: Long? = null,
    /** NEEDS_REVIEW 时指向疑似同一活动的候选 canonical，供人工合并 */
    val reviewCandidateCanonicalId: Long? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "activity_variant",
    indices = [
        Index(value = ["fileSha256"], unique = true),
        Index(value = ["canonicalActivityId"]),
        Index(value = ["parserVersion", "id"]),
    ],
)
data class ActivityVariantEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val canonicalActivityId: Long,
    val collectorType: CollectorType,
    val sourcePlatform: Platform? = null,
    val remoteActivityId: String? = null,
    val localFilePath: String,
    val fileSha256: String,
    val fileSize: Long,
    val recordingManufacturer: Int? = null,
    val recordingProduct: Int? = null,
    val deviceSerial: String? = null,
    val qualityScore: Int? = null,
    val validationStatus: ValidationStatus = ValidationStatus.PENDING,
    val hasGps: Boolean = false,
    val hasHeartRate: Boolean = false,
    val hasCadence: Boolean = false,
    val hasPower: Boolean = false,
    val recordCount: Int? = null,
    val trackSummaryJson: String? = null,
    @ColumnInfo(defaultValue = "0") val parserVersion: Int = 0,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "platform_copy",
    indices = [
        Index(value = ["platformAccountId", "remoteActivityId"], unique = true),
        Index(value = ["canonicalActivityId", "platformAccountId"], unique = true),
        Index(value = ["platformAccountId", "fileSha256"]),
    ],
)
data class PlatformCopyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val canonicalActivityId: Long,
    val platformAccountId: Long,
    val remoteActivityId: String,
    val remoteUrl: String? = null,
    val fileSha256: String? = null,
    val createdBySync: Boolean = false,
    val sourceVariantId: Long? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "remote_activity_skip",
    indices = [Index(value = ["platformAccountId", "remoteActivityId"], unique = true)],
)
data class RemoteActivitySkipEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val platformAccountId: Long,
    val remoteActivityId: String,
    val reasonCode: String,
    val errorMessage: String? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "delivery_job",
    indices = [
        Index(value = ["canonicalActivityId", "destinationAccountId"], unique = true),
        Index(value = ["idempotencyKey"], unique = true),
        Index(value = ["status", "nextRetryAtEpochMs", "createdAtEpochMs"]),
        Index(value = ["sourceVariantId"]),
    ],
)
data class DeliveryJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val canonicalActivityId: Long,
    val destinationAccountId: Long,
    val sourceVariantId: Long,
    val idempotencyKey: String,
    val status: DeliveryJobStatus = DeliveryJobStatus.PENDING,
    @ColumnInfo(defaultValue = "'BACKGROUND'")
    val executionMode: ConnectorExecution = ConnectorExecution.BACKGROUND,
    val userActionUrl: String? = null,
    val requestedByRuleId: Long? = null,
    val attemptCount: Int = 0,
    val nextRetryAtEpochMs: Long? = null,
    val remoteUploadId: String? = null,
    val remoteActivityId: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "sync_rule",
    indices = [Index(value = ["sourcePlatform", "destinationAccountId"], unique = true)],
)
data class SyncRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourcePlatform: Platform,
    val destinationAccountId: Long,
    val enabled: Boolean = true,
    @ColumnInfo(defaultValue = "0") val includeHistory: Boolean = false,
    val historyStartTimeEpochMs: Long? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "import_job",
    indices = [Index(value = ["createdAtEpochMs"])],
)
data class ImportJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String?,
    val status: ImportJobStatus = ImportJobStatus.RECEIVED,
    val localFilePath: String? = null,
    val fileSha256: String? = null,
    val validationStatus: ValidationStatus? = null,
    val errorMessage: String? = null,
    val canonicalActivityId: Long? = null,
    val variantId: Long? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
)

@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val storagePolicy: com.fitsync.app.domain.model.FitStoragePolicy =
        com.fitsync.app.domain.model.FitStoragePolicy.DELETE_AFTER_7_DAYS,
    val autoSyncEnabled: Boolean = true,
    @ColumnInfo(defaultValue = "90") val initialHistoryDays: Int = 90,
    @ColumnInfo(defaultValue = "15") val syncIntervalMinutes: Int = 15,
    @ColumnInfo(defaultValue = "1") val notificationsEnabled: Boolean = true,
    @ColumnInfo(defaultValue = "0") val fitParserVersion: Int = 0,
)
