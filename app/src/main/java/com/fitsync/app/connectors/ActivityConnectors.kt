package com.fitsync.app.connectors

import com.fitsync.app.data.db.entity.CanonicalActivityEntity
import com.fitsync.app.domain.model.SessionState
import com.fitsync.app.domain.model.Platform
import java.io.File

data class SyncCursor(
    val raw: String? = null,
)

data class RemoteActivitySummary(
    val remoteActivityId: String,
    val startTimeEpochMs: Long?,
    val name: String?,
    val sportType: String?,
    val distanceMeters: Double?,
    val downloadUrl: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class ActivityPage(
    val items: List<RemoteActivitySummary>,
    val nextCursor: SyncCursor?,
    val hasMore: Boolean,
)

data class DownloadedActivity(
    val remoteActivityId: String,
    val fitFile: File,
    val remoteMetadata: Map<String, String> = emptyMap(),
)

data class FitFile(
    val file: File,
    val sha256: String,
    val displayName: String,
)

data class RemoteCopy(
    val remoteActivityId: String,
    val remoteUrl: String? = null,
    val duplicate: Boolean = false,
)

sealed interface ConnectorResult<out T> {
    data class Success<T>(val value: T) : ConnectorResult<T>
    data class Duplicate<T>(val value: T? = null) : ConnectorResult<T>
    data class RequiresLogin(val url: String, val message: String = "登录状态已失效") : ConnectorResult<Nothing>
    data class RequiresUserAction(val url: String, val message: String) : ConnectorResult<Nothing>
    data class RetryableFailure(val code: String?, val message: String) : ConnectorResult<Nothing>
    data class PermanentFailure(val code: String?, val message: String) : ConnectorResult<Nothing>
}

interface PlatformConnector {
    val platform: Platform
    suspend fun validateSession(): SessionState
    suspend fun detectCapabilities(): ConnectorCapabilities
    suspend fun listActivities(sinceEpochMs: Long, cursor: SyncCursor?): ConnectorResult<ActivityPage>
    suspend fun downloadFit(activity: RemoteActivitySummary): ConnectorResult<FitFile>
    suspend fun uploadFit(activity: CanonicalActivityEntity): ConnectorResult<RemoteCopy>
}

data class UploadResult(
    val success: Boolean,
    val remoteUploadId: String? = null,
    val remoteActivityId: String? = null,
    val duplicate: Boolean = false,
    val errorCode: String? = null,
    val errorMessage: String? = null,
)

enum class UploadStatusState {
    PENDING,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    DUPLICATE,
}

data class UploadStatus(
    val state: UploadStatusState,
    val remoteActivityId: String? = null,
    val errorMessage: String? = null,
)
