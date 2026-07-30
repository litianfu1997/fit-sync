package com.fitsync.app.connectors

import com.fitsync.app.data.db.entity.CanonicalActivityEntity
import com.fitsync.app.data.db.entity.ActivityVariantEntity
import com.fitsync.app.domain.model.SessionState
import com.fitsync.app.domain.model.Platform
import java.io.File
import java.util.Base64

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

internal data class AsyncUploadConfirmationToken(
    val platform: Platform,
    val provisionalRemoteId: String,
    val submittedAtEpochMs: Long,
)

sealed interface ConnectorResult<out T> {
    data class Success<T>(val value: T) : ConnectorResult<T>
    data class Duplicate<T>(val value: T? = null) : ConnectorResult<T>
    data class Processing(val remoteUploadId: String, val pollAfterMs: Long) : ConnectorResult<Nothing>
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
    suspend fun uploadFit(
        activity: CanonicalActivityEntity,
        sourceVariant: ActivityVariantEntity,
    ): ConnectorResult<RemoteCopy>
    suspend fun checkUpload(remoteUploadId: String): ConnectorResult<RemoteCopy> =
        ConnectorResult.PermanentFailure("UNSUPPORTED", "该平台不支持查询远端上传状态")
}

/**
 * 部分平台的提交接口只表示“已受理/进入异步解析”，并不代表活动已创建成功。
 * token 持久化在 DeliveryJob.remoteUploadId，后续 Worker 会先重拉目标活动列表；若列表仍未出现，
 * [pendingAsyncUploadConfirmation] 继续等待，超时后才明确失败，避免写入伪造的 PlatformCopy。
 */
internal fun encodeAsyncUploadConfirmationToken(
    platform: Platform,
    provisionalRemoteId: String,
    submittedAtEpochMs: Long = System.currentTimeMillis(),
): String {
    require(provisionalRemoteId.isNotBlank()) { "临时上传标识不能为空" }
    val encodedId = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(provisionalRemoteId.toByteArray(Charsets.UTF_8))
    return "$ASYNC_UPLOAD_TOKEN_PREFIX:${platform.name}:$submittedAtEpochMs:$encodedId"
}

internal fun parseAsyncUploadConfirmationToken(raw: String): AsyncUploadConfirmationToken? {
    val parts = raw.split(':', limit = 4)
    if (parts.size != 4 || parts[0] != ASYNC_UPLOAD_TOKEN_PREFIX) return null
    val platform = runCatching { Platform.valueOf(parts[1]) }.getOrNull() ?: return null
    val submittedAt = parts[2].toLongOrNull()?.takeIf { it >= 0L } ?: return null
    val provisionalId = runCatching {
        String(Base64.getUrlDecoder().decode(parts[3]), Charsets.UTF_8)
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
    return AsyncUploadConfirmationToken(platform, provisionalId, submittedAt)
}

internal fun pendingAsyncUploadConfirmation(
    rawToken: String,
    expectedPlatform: Platform,
    nowEpochMs: Long = System.currentTimeMillis(),
): ConnectorResult<RemoteCopy> {
    val token = parseAsyncUploadConfirmationToken(rawToken)
    if (token == null || token.platform != expectedPlatform) {
        return ConnectorResult.PermanentFailure(
            "${expectedPlatform.name}_CONFIRM_TOKEN_INVALID",
            "${expectedPlatform.displayName} 上传确认信息无效，请重新同步",
        )
    }
    val elapsed = (nowEpochMs - token.submittedAtEpochMs).coerceAtLeast(0L)
    return if (elapsed < ASYNC_UPLOAD_CONFIRM_TIMEOUT_MS) {
        ConnectorResult.Processing(rawToken, ASYNC_UPLOAD_CONFIRM_POLL_MS)
    } else {
        ConnectorResult.PermanentFailure(
            "${expectedPlatform.name}_CONFIRM_TIMEOUT",
            "${expectedPlatform.displayName} 长时间未创建活动，上传解析可能失败",
        )
    }
}

internal const val ASYNC_UPLOAD_CONFIRM_POLL_MS = 30_000L
internal const val ASYNC_UPLOAD_CONFIRM_TIMEOUT_MS = 30L * 60 * 1000
private const val ASYNC_UPLOAD_TOKEN_PREFIX = "fitsync-async-v1"

/**
 * 走纯 API 账号密码登录（不走 WebView）的 connector（如 iGPSPORT、COROS 中国站）。
 * 登录成功后由 connector 自行把凭据持久化到 WebSessionManager（Keystore 加密），
 * token 失效时用持久化的账号密码静默重登。
 */
internal interface CredentialsLoginConnector : PlatformConnector {
    suspend fun login(username: String, password: String): SessionState
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
