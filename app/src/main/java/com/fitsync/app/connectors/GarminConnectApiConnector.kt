package com.fitsync.app.connectors

import com.fitsync.app.data.db.FitSyncDatabase
import com.fitsync.app.data.db.entity.ActivityVariantEntity
import com.fitsync.app.data.db.entity.CanonicalActivityEntity
import com.fitsync.app.data.file.FitFileStore
import com.fitsync.app.domain.model.AccountStatus
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.domain.model.SessionState
import com.fitsync.app.web.PlatformWebConfigs
import com.fitsync.app.web.WebSessionManager
import com.fitsync.app.web.cookieValue
import android.webkit.CookieManager
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Garmin Connect 中国站后台双向同步。
 *
 * 全部走中国站网页端实际使用的同源代理 `connect.garmin.cn/gc-api`（注意：Kotlin 块注释会嵌套，
 * 这里避免在 KDoc 写 `gc-api/星号`），复用 WebView 登录后捕获的
 * JWT_WEB 会话 Cookie + Connect-Csrf-Token。三个方向均为 BACKGROUND：
 * - 列表：GET gc-api/activitylist-service/activities/search/activities?start=&limit=（offset 分页）
 * - 下载：GET gc-api/download-service/files/activity/{id}/fit（裸 .FIT；不带 /fit 后缀返回 ZIP）
 * - 上传：POST gc-api/upload-service/upload（multipart 字段 file）
 *
 * 共同 headers：Cookie(JWT_WEB 等) + `NK: NT` + **`Connect-Csrf-Token`**(来自登录页
 * `<meta name="csrf-token">`) + `X-Requested-With`。协议真机实测确认（见 memory: garmin-cn-upload-protocol）。
 */
internal class GarminConnectApiConnector(
    private val db: FitSyncDatabase,
    private val fileStore: FitFileStore,
    private val sessionManager: WebSessionManager,
    private val client: OkHttpClient,
) : PlatformConnector {
    override val platform = Platform.GARMIN
    private val ssoRefreshMutex = Mutex()

    override suspend fun validateSession(): SessionState {
        val cookie = sessionCookie("VALIDATE") ?: return SessionState.NOT_CONNECTED
        val request = buildGcApiRequest(
            "$GARMIN_GC_API_BASE/activitylist-service/activities/search/activities?start=0&limit=1",
            cookie,
            sessionManager.garminCsrf(),
            null,
        )
        return runCatching {
            execute(request).use { response ->
                when {
                    response.isSuccessful -> SessionState.AUTHENTICATED
                    isGarminLoginResponse(response.code, response.header("Location")) -> SessionState.EXPIRED
                    // 403、限流、服务异常或页面协议变化都不能证明 JWT 已失效；保留现有登录态，
                    // 由具体同步任务按可重试失败处理，避免“刷新即正常”的假状态切换。
                    else -> SessionState.AUTHENTICATED
                }
            }
        }.getOrDefault(SessionState.AUTHENTICATED)
    }

    override suspend fun detectCapabilities(): ConnectorCapabilities =
        PlatformCapabilityRegistry.capabilities(platform)

    override suspend fun listActivities(
        sinceEpochMs: Long,
        cursor: SyncCursor?,
    ): ConnectorResult<ActivityPage> {
        val start = cursor?.raw?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val url = "$GARMIN_GC_API_BASE/activitylist-service/activities/search/activities" +
            "?start=$start&limit=$LIST_PAGE_SIZE"
        return runCatching {
            executeGcApiWithSessionRecovery(
                operation = "LIST",
                requestFactory = { cookie, csrf -> buildGcApiRequest(url, cookie, csrf, null) },
            ) { resp ->
                when {
                    isGarminLoginResponse(resp.code, resp.header("Location")) -> requiresLogin("LIST")
                    !resp.isSuccessful -> failureStatus(resp, "LIST")
                    else -> {
                        val all = parseGarminActivities(resp.body?.string().orEmpty())
                        if (all == null) {
                            ConnectorResult.RetryableFailure(
                                "GARMIN_INVALID_RESPONSE",
                                "Garmin 活动列表响应格式异常",
                            )
                        } else {
                            // Garmin 默认按时间倒序：只保留窗口内活动；整页都被剔除说明已越过下界，停止翻页。
                            val items = all.filter {
                                it.startTimeEpochMs == null || it.startTimeEpochMs >= sinceEpochMs
                            }
                            val hasMore = all.size >= LIST_PAGE_SIZE && items.isNotEmpty()
                            ConnectorResult.Success(
                                ActivityPage(items, SyncCursor((start + LIST_PAGE_SIZE).toString()), hasMore),
                            )
                        }
                    }
                }
            }
        }.getOrElse { ConnectorResult.RetryableFailure(null, it.message ?: "Garmin 活动列表读取失败") }
    }

    override suspend fun downloadFit(activity: RemoteActivitySummary): ConnectorResult<FitFile> {
        val url = "$GARMIN_GC_API_BASE/download-service/files/activity/${activity.remoteActivityId}/fit"
        return runCatching {
            executeGcApiWithSessionRecovery(
                operation = "DOWNLOAD",
                requestFactory = { cookie, csrf -> buildGcApiRequest(url, cookie, csrf, null) },
            ) { resp ->
                when {
                    isGarminLoginResponse(resp.code, resp.header("Location")) -> requiresLogin("DOWNLOAD")
                    resp.code == 404 || resp.code == 410 -> ConnectorResult.PermanentFailure(
                        "NO_ORIGINAL_FILE", "Garmin 未保留该活动的原始 FIT",
                    )
                    !resp.isSuccessful -> failureStatus(resp, "DOWNLOAD")
                    else -> storeFit(resp, "garmin_${activity.remoteActivityId}.fit")
                }
            }
        }.getOrElse { ConnectorResult.RetryableFailure(null, it.message ?: "Garmin FIT 下载失败") }
    }

    override suspend fun uploadFit(
        activity: CanonicalActivityEntity,
        sourceVariant: ActivityVariantEntity,
    ): ConnectorResult<RemoteCopy> {
        val file = File(sourceVariant.localFilePath).takeIf { it.isFile }
            ?: return ConnectorResult.PermanentFailure("FILE_MISSING", "本地 FIT 文件不存在")
        if (file.length() <= 0) return ConnectorResult.PermanentFailure("FILE_EMPTY", "本地 FIT 文件为空")
        if (file.length() > GARMIN_MAX_FIT_BYTES) {
            return ConnectorResult.PermanentFailure("FILE_TOO_LARGE", "Garmin 限制 FIT 不超过 25 MB")
        }
        val prepared = withContext(Dispatchers.IO) { prepareGarminFitBytes(file.readBytes()) }
            ?: return ConnectorResult.PermanentFailure("GARMIN_FIT_INVALID", "FIT 文件结构或长度不完整")
        if (prepared.fileCrcRepaired || prepared.headerCrcRepaired) {
            GarminAuthDiagnostics.record(
                GarminAuthDiagnostics.Event(
                    timestampEpochMs = System.currentTimeMillis(),
                    operation = "UPLOAD",
                    phase = "FILE_PREPARE",
                    outcome = "CRC_REPAIRED",
                    detail = "file=${prepared.fileCrcRepaired} header=${prepared.headerCrcRepaired}",
                ),
            )
        }
        // Garmin 中国站导入器对非 ASCII multipart 文件名兼容性不稳定；网页上传预处理也采用同一策略。
        val uploadName = garminUploadFileName(sourceVariant.fileSha256)
        val multipart = MultipartBody.Builder()
            .addFormDataPart("file", uploadName, prepared.bytes.toRequestBody(OCTET_STREAM))
            .build()
        return runCatching { postUpload(multipart, sourceVariant.fileSha256) }
            .getOrElse { ConnectorResult.RetryableFailure(null, it.message ?: "Garmin 上传请求失败") }
    }

    override suspend fun checkUpload(remoteUploadId: String): ConnectorResult<RemoteCopy> {
        val confirmationToken = parseAsyncUploadConfirmationToken(remoteUploadId)
        if (confirmationToken?.platform == platform) {
            val pending = pendingAsyncUploadConfirmation(remoteUploadId, platform)
            if (pending !is ConnectorResult.Processing) return pending
            val statusUrl = garminStatusApiUrl(confirmationToken.provisionalRemoteId, "garmin.cn")
                ?: return pending
            return pollStatus(statusUrl, remoteUploadId)
        }
        val statusUrl = garminStatusApiUrl(remoteUploadId, "garmin.cn")
            ?: return ConnectorResult.PermanentFailure(
                "GARMIN_STATUS_URL_INVALID",
                "Garmin 上传状态地址无效，请重新同步",
            )
        val migratedToken = garminAcceptedProcessingResult(statusUrl, statusUrl).remoteUploadId
        return pollStatus(statusUrl, migratedToken)
    }

    private suspend fun postUpload(
        multipart: MultipartBody,
        provisionalRemoteId: String,
    ): ConnectorResult<RemoteCopy> =
        executeGcApiWithSessionRecovery(
            operation = "UPLOAD",
            requestFactory = { cookie, csrf ->
                buildGcApiRequest(garminUploadUrl(sessionManager.garminDomain()), cookie, csrf, multipart)
            },
            responseHandler = { handleUploadResponse(it, provisionalRemoteId) },
        )

    private fun buildGcApiRequest(
        url: String,
        cookieHeader: String,
        csrf: String?,
        body: okhttp3.RequestBody?,
    ): Request {
        return buildGarminGcApiRequest(
            url = url,
            cookieHeader = cookieHeader,
            csrf = csrf,
            userAgent = sessionManager.garminUserAgent() ?: GARMIN_DESKTOP_USER_AGENT,
            body = body,
        )
    }

    private fun handleUploadResponse(
        response: Response,
        provisionalRemoteId: String,
    ): ConnectorResult<RemoteCopy> {
        val body = runCatching { response.body?.string() }.getOrNull().orEmpty()
        val state = parseGarminImportState(body)
        if (state?.isFinal == true) {
            return when {
                state.duplicate -> ConnectorResult.Duplicate(
                    garminRemoteCopyOrNull(state.remoteActivityId, true),
                )
                state.success -> garminRemoteCopyOrNull(state.remoteActivityId, false)
                    ?.let { ConnectorResult.Success(it) }
                    ?: garminAcceptedProcessingResult(null, provisionalRemoteId)
                else -> garminImportRejectedFailure(
                    garminImportFailureSummary(body)?.let { summary ->
                        garminSafeResponseDetail(summary, "text/plain").removePrefix("text=")
                    },
                )
            }
        }
        return when {
            response.code in 200..299 -> interpretSuccess(body, response, provisionalRemoteId)
            response.code == 409 -> ConnectorResult.Duplicate(
                parseGarminImportState(body)?.let { garminRemoteCopyOrNull(it.remoteActivityId, true) },
            )
            isGarminLoginResponse(response.code, response.header("Location")) -> requiresLogin("UPLOAD")
            response.code == 403 -> garminForbiddenFailure()
            response.code == 429 || response.code >= 500 -> ConnectorResult.RetryableFailure(
                response.code.toString(), "Garmin 暂时不可用（HTTP ${response.code}）",
            )
            else -> ConnectorResult.PermanentFailure(
                response.code.toString(),
                "Garmin 上传失败（HTTP ${response.code}）${if (body.isNotBlank()) "：${body.take(160)}" else ""}",
            )
        }
    }

    private fun interpretSuccess(
        body: String,
        response: Response,
        provisionalRemoteId: String,
    ): ConnectorResult<RemoteCopy> {
        val state = parseGarminImportState(body)
        if (state != null && state.isFinal) {
            return when {
                state.duplicate -> ConnectorResult.Duplicate(garminRemoteCopyOrNull(state.remoteActivityId, true))
                state.success -> garminRemoteCopyOrNull(state.remoteActivityId, false)
                    ?.let { ConnectorResult.Success(it) }
                    ?: garminAcceptedProcessingResult(null, provisionalRemoteId)
                else -> garminImportRejectedFailure(
                    garminImportFailureSummary(body)?.let { summary ->
                        garminSafeResponseDetail(summary, "text/plain").removePrefix("text=")
                    },
                )
            }
        }
        val location = response.header("location") ?: response.header("Location")
        val statusUrl = location?.let { garminStatusApiUrl(it, "garmin.cn") }
        val acceptedId = state?.uploadId?.toString()?.takeIf { it.isNotBlank() } ?: provisionalRemoteId
        return garminAcceptedProcessingResult(statusUrl, acceptedId)
    }

    private suspend fun pollStatus(
        statusUrl: String,
        continuationToken: String,
    ): ConnectorResult<RemoteCopy> {
        var lastFailureSummary: String? = null
        repeat(GARMIN_POLL_MAX_TRIES) {
            delay(GARMIN_POLL_INTERVAL_MS)
            val polled: ConnectorResult<GarminImportState?> = runCatching {
                executeGcApiWithSessionRecovery(
                    operation = "POLL",
                    requestFactory = { cookie, csrf -> buildGcApiRequest(statusUrl, cookie, csrf, null) },
                ) { resp ->
                    when {
                        isGarminLoginResponse(resp.code, resp.header("Location")) -> requiresLogin("POLL")
                        resp.code == 403 -> garminForbiddenFailure()
                        else -> {
                            val body = runCatching { resp.body?.string() }.getOrNull().orEmpty()
                            val state = garminPollImportState(resp.code, body)
                            if (state?.isFinal == true && !state.success && !state.duplicate) {
                                lastFailureSummary = garminImportFailureSummary(body)?.let { summary ->
                                    garminSafeResponseDetail(summary, "text/plain").removePrefix("text=")
                                }
                            }
                            if (resp.code !in 200..299) {
                                GarminAuthDiagnostics.record(
                                    GarminAuthDiagnostics.Event(
                                        timestampEpochMs = System.currentTimeMillis(),
                                        operation = "POLL",
                                        phase = "RESPONSE_DETAIL",
                                        outcome = "HTTP_${resp.code}",
                                        httpCode = resp.code,
                                        detail = state?.let {
                                            val stateSummary = "IMPORT_RESULT final=${it.isFinal} success=${it.success} " +
                                                "duplicate=${it.duplicate} hasActivity=${!it.remoteActivityId.isNullOrBlank()}"
                                            lastFailureSummary?.let { failure -> "$stateSummary failure=$failure" }
                                                ?: stateSummary
                                        } ?: garminSafeResponseDetail(body, resp.header("Content-Type")),
                                    ),
                                )
                            }
                            ConnectorResult.Success(state)
                        }
                    }
                }
            }.getOrElse { ConnectorResult.Success(null) }
            when (polled) {
                is ConnectorResult.Success -> {
                    val state = polled.value
                    if (state?.isFinal == true) {
                        return when {
                            state.duplicate -> garminRemoteCopyOrNull(state.remoteActivityId, true)
                                ?.let { ConnectorResult.Duplicate(it) }
                                ?: ConnectorResult.Processing(
                                    continuationToken,
                                    GARMIN_REMOTE_POLL_AFTER_MS,
                                )
                            state.success -> garminRemoteCopyOrNull(state.remoteActivityId, false)
                                ?.let { ConnectorResult.Success(it) }
                                ?: ConnectorResult.Processing(
                                    continuationToken,
                                    GARMIN_REMOTE_POLL_AFTER_MS,
                                )
                            else -> garminImportRejectedFailure(lastFailureSummary)
                        }
                    }
                }
                is ConnectorResult.RequiresLogin -> return polled
                is ConnectorResult.RetryableFailure -> return polled
                else -> return ConnectorResult.RetryableFailure(
                    "GARMIN_POLL_INVALID_STATE",
                    "Garmin 上传状态返回了无法识别的结果",
                )
            }
        }
        return ConnectorResult.Processing(continuationToken, GARMIN_REMOTE_POLL_AFTER_MS)
    }

    private suspend fun storeFit(response: Response, displayName: String): ConnectorResult<FitFile> {
        val body = response.body
            ?: return ConnectorResult.PermanentFailure("NO_ORIGINAL_FILE", "Garmin 未返回 FIT 文件")
        return runCatching {
            val stored = body.byteStream().use { fileStore.storeFromStream(it, displayName) }
            ConnectorResult.Success(FitFile(stored.file, stored.sha256, stored.displayName))
        }.getOrElse {
            ConnectorResult.PermanentFailure("UNSUPPORTED_ORIGINAL_FORMAT", it.message ?: "下载的内容不是有效 FIT")
        }
    }

    private fun sessionCookie(operation: String): String? {
        val live = sessionManager.cookieHeader(platform)
        val stored = sessionManager.garminCookieHeader()
        val jwt = sessionManager.garminJwtWeb()
        val liveHasJwt = cookieValue(live, "JWT_WEB") != null
        val storedHasJwt = cookieValue(stored, "JWT_WEB") != null
        val jwtPresent = !jwt.isNullOrBlank()
        val source = when {
            liveHasJwt -> "LIVE"
            storedHasJwt -> "STORED"
            jwtPresent -> "JWT"
            else -> "NONE"
        }
        GarminAuthDiagnostics.record(
            GarminAuthDiagnostics.Event(
                timestampEpochMs = System.currentTimeMillis(),
                operation = operation,
                phase = "COOKIE_SELECT",
                outcome = if (source == "NONE") "MISS" else GarminAuthDiagnostics.OUTCOME_OK,
                cookieSource = source,
            ),
        )
        return selectGarminApiCookie(live, stored, jwt)
    }

    private fun requiresLogin(operation: String = "OTHER"): ConnectorResult<Nothing> {
        GarminAuthDiagnostics.record(
            GarminAuthDiagnostics.Event(
                timestampEpochMs = System.currentTimeMillis(),
                operation = operation,
                phase = "DECISION",
                outcome = GarminAuthDiagnostics.OUTCOME_REQUIRES_LOGIN,
            ),
        )
        return ConnectorResult.RequiresLogin(loginUrl, "Garmin 登录已失效，请重新登录")
    }

    /**
     * Cookie 三级回退都读不到时的容错：若数据库里有 Garmin 账号行（说明登录过），降级为可重试失败，
     * 让 WorkManager 下一轮自动重试，而不是立即误报「登录已失效」；从未登录过才真正要求登录。
     * 用账号行而非 hasSession() 判定——后者与 sessionCookie() 同源（secureStore / WebView cookie），
     * 后台进程里会一起失明，不可靠。
     */
    private suspend fun cookieMissingResult(operation: String): ConnectorResult<Nothing> {
        val account = db.platformAccountDao().getByPlatform(platform)
        val hasPersistedAccount = account != null && account.status != AccountStatus.DISABLED
        GarminAuthDiagnostics.record(
            GarminAuthDiagnostics.Event(
                timestampEpochMs = System.currentTimeMillis(),
                operation = operation,
                phase = "COOKIE_MISSING",
                outcome = if (hasPersistedAccount) {
                    GarminAuthDiagnostics.OUTCOME_RETRYABLE
                } else {
                    GarminAuthDiagnostics.OUTCOME_REQUIRES_LOGIN
                },
                cookieSource = "NONE",
                hadPersistedAccount = hasPersistedAccount,
            ),
        )
        return garminCookieMissingResult(hasPersistedAccount, loginUrl)
    }

    private fun failureStatus(resp: Response, operation: String = "OTHER"): ConnectorResult<Nothing> {
        val outcome = when (resp.code) {
            403 -> GarminAuthDiagnostics.OUTCOME_RETRYABLE
            429, in 500..599 -> GarminAuthDiagnostics.OUTCOME_RETRYABLE
            else -> GarminAuthDiagnostics.OUTCOME_PERMANENT_FAIL
        }
        GarminAuthDiagnostics.record(
            GarminAuthDiagnostics.Event(
                timestampEpochMs = System.currentTimeMillis(),
                operation = operation,
                phase = "DECISION",
                outcome = outcome,
                httpCode = resp.code,
            ),
        )
        return when (resp.code) {
            403 -> garminForbiddenFailure()
            429, in 500..599 -> ConnectorResult.RetryableFailure(
                resp.code.toString(), "Garmin 暂时不可用（HTTP ${resp.code}）",
            )
            else -> ConnectorResult.PermanentFailure(resp.code.toString(), "Garmin 请求失败（HTTP ${resp.code}）")
        }
    }

    /**
     * 在单次 gc-api HTTP 请求边界恢复 Garmin 会话。401/登录跳转或 403 都先尝试用 CASTGC
     * 静默换发 JWT_WEB，再用最新 Cookie/CSRF 原样重试该请求一次。
     *
     * 恢复放在 HTTP 请求层而不是整个业务操作层，尤其保证上传状态轮询遇到 403 时只重试
     * status GET，不会重新提交已经被 Garmin 接收的 FIT POST。
     */
    private suspend fun <T> executeGcApiWithSessionRecovery(
        operation: String,
        requestFactory: (cookieHeader: String, csrf: String?) -> Request,
        responseHandler: suspend (Response) -> ConnectorResult<T>,
    ): ConnectorResult<T> {
        val jwtBeforeRequest = sessionManager.garminJwtWeb()
        var refreshAlreadyAttempted = false
        var cookie = sessionCookie(operation)
        if (cookie == null) {
            val missing = cookieMissingResult(operation)
            if (missing !is ConnectorResult.RetryableFailure || missing.code != GARMIN_COOKIE_UNREADABLE_CODE) {
                return missing
            }
            val refreshed = refreshSessionOnce(operation, "COOKIE_MISSING", jwtBeforeRequest, null)
            refreshAlreadyAttempted = true
            if (refreshed.state != SessionState.AUTHENTICATED) return missing
            cookie = sessionCookie(operation) ?: return cookieMissingResult(operation)
        }

        val firstResponse = execute(requestFactory(cookie, sessionManager.garminCsrf()))
        val recovered = recoverGarminResponseOnce(
            firstResponse = firstResponse,
            responseCode = { it.code },
            responseLocation = { it.header("Location") },
            closeFirstResponse = Response::close,
            refreshSession = { reason ->
                if (refreshAlreadyAttempted) {
                    SessionState.EXPIRED
                } else {
                    refreshAlreadyAttempted = true
                    refreshSessionOnce(
                        operation = operation,
                        reason = reason.name,
                        jwtBeforeRequest = jwtBeforeRequest,
                        httpCode = firstResponse.code,
                    ).state
                }
            },
            retryRequest = {
                val retryCookie = sessionCookie(operation)
                    ?: throw IllegalStateException("Garmin 会话续期后未读取到 Cookie")
                execute(requestFactory(retryCookie, sessionManager.garminCsrf())).also { retry ->
                    GarminAuthDiagnostics.record(
                        GarminAuthDiagnostics.Event(
                            timestampEpochMs = System.currentTimeMillis(),
                            operation = operation,
                            phase = "SESSION_RETRY",
                            outcome = if (retry.isSuccessful) {
                                GarminAuthDiagnostics.OUTCOME_OK
                            } else {
                                "HTTP_${retry.code}"
                            },
                            httpCode = retry.code,
                            locationHost = retry.header("Location")?.let { location ->
                                runCatching { java.net.URI(location).host }.getOrNull()
                            },
                        ),
                    )
                }
            },
        )
        return recovered.response.use { responseHandler(it) }
    }

    private suspend fun refreshSessionOnce(
        operation: String,
        reason: String,
        jwtBeforeRequest: String?,
        httpCode: Int?,
    ): GarminSessionRefreshOutcome {
        GarminAuthDiagnostics.record(
            GarminAuthDiagnostics.Event(
                timestampEpochMs = System.currentTimeMillis(),
                operation = operation,
                phase = "SESSION_REFRESH_TRIGGER",
                outcome = reason,
                httpCode = httpCode,
            ),
        )
        val outcome = refreshGarminSessionOnce(
            mutex = ssoRefreshMutex,
            jwtBeforeRequest = jwtBeforeRequest,
            currentJwt = sessionManager::garminJwtWeb,
            refresh = { refreshJwtWebViaSso(operation) },
        )
        GarminAuthDiagnostics.record(
            GarminAuthDiagnostics.Event(
                timestampEpochMs = System.currentTimeMillis(),
                operation = operation,
                phase = "SESSION_REFRESH_RESULT",
                outcome = when {
                    outcome.reusedExistingRefresh -> "REUSED"
                    outcome.state == SessionState.AUTHENTICATED -> GarminAuthDiagnostics.OUTCOME_OK
                    else -> "FAILED"
                },
                httpCode = httpCode,
            ),
        )
        return outcome
    }

    /**
     * 用 SSO 长会话（sso.garmin.cn 的 CASTGC 等）走 CAS 重定向链换发新 JWT_WEB。手动逐跳
     * （client 是 followRedirects(false)），每跳按 host 拼对应域 cookie；诊断拦截器会把每跳的
     * code/Location/Set-Cookie 记成 RESPONSE(operation=SSO)，这里再补一条换发总结。
     *
     * CASTGC 优先取 live CookieManager；为空时回退登录时持久化到 Keystore 的副本（[selectGarminSsoCookie]），
     * 这样进程被杀、WebView cookie 库被清后仍能静默换发。换发用到的 sso cookie 直接以 Cookie 头发送，
     * 不依赖 CookieManager，故回退值即可生效。
     *
     * 每一跳返回的 Set-Cookie 都会立刻合并到对应域，供下一跳继续使用；成功后把新 JWT_WEB、CASTGC、
     * Connect Cookie 和页面 CSRF 同步写回 CookieManager 与 Keystore，并恢复数据库账号和待投递任务。
     */
    private suspend fun refreshJwtWebViaSso(operation: String): SessionState {
        val cm = CookieManager.getInstance()
        var connectCookie = cm.getCookie("https://connect.garmin.cn/").orEmpty()
            .ifBlank { sessionManager.garminCookieHeader().orEmpty() }
        val ssoChoice = selectGarminSsoCookie(
            liveSso = cm.getCookie("https://sso.garmin.cn/"),
            storedSso = sessionManager.garminSsoCookie(),
        )
        var ssoCookie = ssoChoice.cookie.orEmpty()
        val ssoSource = ssoChoice.source
        val ua = sessionManager.garminUserAgent() ?: GARMIN_DESKTOP_USER_AGENT
        // connect.garmin.cn/signin 现在是依赖 JS 的 Astro 页面；后台直连必须使用页面实际拼出的 SSO 地址。
        var currentUrl = garminDirectSsoUrl("https://connect.garmin.cn/modern/")
        var newJwt: String? = null
        var newCsrf: String? = null
        var lastCode: Int? = null
        var lastLocation: String? = null
        for (hop in 0 until SSO_MAX_HOPS) {
            val ssoHop = isGarminSsoHost(currentUrl)
            val cookieForHop = if (ssoHop) ssoCookie else connectCookie
            val req = buildGarminBrowserRequest(currentUrl, cookieForHop, ua)
            val advanced = runCatching {
                execute(req).use { resp ->
                    lastCode = resp.code
                    lastLocation = resp.header("Location")
                    val setCookies = resp.headers("Set-Cookie")
                    if (ssoHop) {
                        ssoCookie = mergeGarminCookieHeader(ssoCookie, setCookies).orEmpty()
                    } else {
                        connectCookie = mergeGarminCookieHeader(connectCookie, setCookies).orEmpty()
                    }
                    extractJwtWebFromHeaders(setCookies)?.let { newJwt = it }
                    val responseBody = resp.body?.string().orEmpty()
                    extractGarminCsrfFromHtml(responseBody)?.let { newCsrf = it }
                    Triple(resp.code, resp.header("Location"), responseBody)
                }
            }.getOrNull() ?: break
            val (code, location) = advanced
            if (code in 300..399 && !location.isNullOrBlank()) {
                // Garmin/CAS 会混用绝对与相对 Location，必须以上一跳为基准解析。
                currentUrl = resolveGarminRedirect(currentUrl, location) ?: break
            } else {
                break
            }
        }
        GarminAuthDiagnostics.record(
            GarminAuthDiagnostics.Event(
                timestampEpochMs = System.currentTimeMillis(),
                operation = operation,
                phase = "SSO_RELOGIN",
                outcome = if (newJwt != null) GarminAuthDiagnostics.OUTCOME_OK else "FAIL",
                httpCode = lastCode,
                locationHost = lastLocation?.let { runCatching { java.net.URI(it).host }.getOrNull() },
                cookieSource = ssoSource,
            ),
        )
        val jwt = newJwt ?: return SessionState.EXPIRED
        val finalConnect = mergeGarminCookieHeader(connectCookie, listOf("JWT_WEB=$jwt; Path=/; Secure; HttpOnly"))
            ?: "JWT_WEB=$jwt"
        val finalSso = ssoCookie.takeIf { it.isNotBlank() }
        val csrf = newCsrf ?: sessionManager.garminCsrf()
        // 同步写回 CookieManager（LIVE，sessionCookie 优先读它）和 secureStore（STORED 兜底）。
        runCatching {
            rehydrateCookieHeader(cm, "https://connect.garmin.cn/", finalConnect)
            if (!finalSso.isNullOrBlank()) rehydrateCookieHeader(cm, "https://sso.garmin.cn/", finalSso)
            cm.flush()
        }
        sessionManager.saveGarminSession(jwt, csrf, "garmin.cn", ua, finalConnect, finalSso)
        restoreAccountAfterSilentRefresh()
        return SessionState.AUTHENTICATED
    }

    /** 静默续登成功后同步修复可见账号状态，并恢复此前因 AUTH_EXPIRED 暂停的后台任务。 */
    private suspend fun restoreAccountAfterSilentRefresh() {
        val account = db.platformAccountDao().getByPlatform(platform) ?: return
        if (account.status == AccountStatus.DISABLED) return
        val now = System.currentTimeMillis()
        db.platformAccountDao().update(
            account.copy(
                status = AccountStatus.ACTIVE,
                needsUserAction = false,
                lastValidatedAtEpochMs = now,
                updatedAtEpochMs = now,
            ),
        )
        db.deliveryJobDao().resumeJobsForForeground(platform, now)
    }

    private fun buildGarminBrowserRequest(url: String, cookieHeader: String, ua: String): Request =
        Request.Builder()
            .url(url)
            .header("Cookie", cookieHeader)
            .header("User-Agent", ua)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Sec-CH-UA", "\"Chromium\";v=\"124\", \"Not-A.Brand\";v=\"99\"")
            .header("Sec-CH-UA-Mobile", "?0")
            .header("Sec-CH-UA-Platform", "\"Windows\"")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "same-site")
            .header("Referer", "https://connect.garmin.cn/")
            .header("Upgrade-Insecure-Requests", "1")
            .get()
            .build()

    /** 把 cookie 串（name=val; name2=val2，无属性）逐个写回 CookieManager，保持 LIVE 与 STORED 一致。 */
    private fun rehydrateCookieHeader(cm: CookieManager, url: String, header: String) {
        header.split(';').map { it.trim() }.filter { it.isNotBlank() }.forEach { pair ->
            cm.setCookie(url, "$pair; Path=/; Secure; HttpOnly")
        }
    }

    private val loginUrl: String
        get() = PlatformWebConfigs.get(platform).loginUrl

    private suspend fun execute(request: Request): Response = withContext(Dispatchers.IO) {
        client.newCall(request).execute()
    }

    companion object {
        private const val GARMIN_GC_API_BASE = "https://connect.garmin.cn/gc-api"
        private const val LIST_PAGE_SIZE = 20
        private const val GARMIN_MAX_FIT_BYTES = 25L * 1024 * 1024
        private const val GARMIN_POLL_MAX_TRIES = 3
        private const val GARMIN_POLL_INTERVAL_MS = 1200L
        private const val SSO_MAX_HOPS = 8
        private const val GARMIN_DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        fun httpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .followRedirects(false)
            .addInterceptor(GarminAuthDiagnosticsInterceptor())
            .build()
    }
}

internal enum class GarminSessionRefreshReason {
    AUTH_REQUIRED,
    FORBIDDEN,
}

internal fun garminSessionRefreshReason(code: Int, location: String?): GarminSessionRefreshReason? = when {
    code == 403 -> GarminSessionRefreshReason.FORBIDDEN
    isGarminLoginResponse(code, location) -> GarminSessionRefreshReason.AUTH_REQUIRED
    else -> null
}

internal data class GarminRecoveredResponse<T>(
    val response: T,
    val reason: GarminSessionRefreshReason?,
    val refreshState: SessionState?,
    val retried: Boolean,
)

/**
 * 对一个已经完成的 HTTP 请求最多做一次会话恢复，并且只重放传入的同一个请求工厂。
 * 刷新失败时保留首个响应给调用方分类；刷新成功才关闭首响应并执行一次重试。
 */
internal suspend fun <T> recoverGarminResponseOnce(
    firstResponse: T,
    responseCode: (T) -> Int,
    responseLocation: (T) -> String?,
    closeFirstResponse: (T) -> Unit,
    refreshSession: suspend (GarminSessionRefreshReason) -> SessionState,
    retryRequest: suspend () -> T,
): GarminRecoveredResponse<T> {
    val reason = garminSessionRefreshReason(responseCode(firstResponse), responseLocation(firstResponse))
        ?: return GarminRecoveredResponse(firstResponse, null, null, retried = false)
    val refreshState = try {
        refreshSession(reason)
    } catch (error: Throwable) {
        closeFirstResponse(firstResponse)
        throw error
    }
    if (refreshState != SessionState.AUTHENTICATED) {
        return GarminRecoveredResponse(firstResponse, reason, refreshState, retried = false)
    }
    closeFirstResponse(firstResponse)
    return GarminRecoveredResponse(retryRequest(), reason, refreshState, retried = true)
}

internal data class GarminSessionRefreshOutcome(
    val state: SessionState,
    val reusedExistingRefresh: Boolean,
)

/** 同一批并发 403 只允许一个请求执行 CAS，其余请求检测到 JWT 已轮换后直接复用。 */
internal suspend fun refreshGarminSessionOnce(
    mutex: Mutex,
    jwtBeforeRequest: String?,
    currentJwt: () -> String?,
    refresh: suspend () -> SessionState,
): GarminSessionRefreshOutcome = mutex.withLock {
    val latestJwt = currentJwt()
    if (!latestJwt.isNullOrBlank() && latestJwt != jwtBeforeRequest) {
        GarminSessionRefreshOutcome(SessionState.AUTHENTICATED, reusedExistingRefresh = true)
    } else {
        GarminSessionRefreshOutcome(refresh(), reusedExistingRefresh = false)
    }
}

internal fun garminRemoteCopyOrNull(remoteActivityId: String?, duplicate: Boolean): RemoteCopy? {
    val id = remoteActivityId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return RemoteCopy(
        remoteActivityId = id,
        remoteUrl = "https://connect.garmin.cn/modern/activity/$id",
        duplicate = duplicate,
    )
}

private val OCTET_STREAM = "application/octet-stream".toMediaType()

internal fun buildGarminGcApiRequest(
    url: String,
    cookieHeader: String,
    csrf: String?,
    userAgent: String,
    body: okhttp3.RequestBody?,
): Request {
    val builder = Request.Builder()
        .url(url)
        .header("Cookie", cookieHeader)
        .header("NK", "NT")
        .header("DI-Backend", "connectapi.garmin.cn")
        .apply { if (!csrf.isNullOrBlank()) header("Connect-Csrf-Token", csrf) }
        .header("X-Requested-With", "XMLHttpRequest")
        .header("Accept", "application/json, text/plain, */*")
        .header("Origin", "https://connect.garmin.cn")
        .header("Referer", "https://connect.garmin.cn/modern/")
        // Garmin 中国站网关会校验 Fetch Metadata；缺失时即使 JWT_WEB 与 CSRF 有效也返回空正文 403。
        .header("Sec-Fetch-Dest", "empty")
        .header("Sec-Fetch-Mode", "cors")
        .header("Sec-Fetch-Site", "same-origin")
        .header("User-Agent", userAgent)
    return if (body is MultipartBody) builder.post(body).build() else builder.build()
}

internal fun selectGarminApiCookie(
    liveCookie: String?,
    storedCookie: String?,
    storedJwt: String?,
): String? = sequenceOf(liveCookie, storedCookie)
    .firstOrNull { cookieValue(it, "JWT_WEB") != null }
    ?: storedJwt?.takeIf { it.isNotBlank() }?.let { "JWT_WEB=$it" }

/**
 * 选择 SSO 换发要带的 sso.garmin.cn cookie：优先 LIVE（CookieManager 里最新的），LIVE 为空或不含
 * CASTGC 时回退 STORED（Keystore 里登录时抓的）。返回所选 cookie 与来源标签（LIVE/STORED/NONE）。
 * 来源标签写入 SSO_RELOGIN 诊断的 cookieSource，用来定位「进程被杀后还能不能静默换发」。
 */
internal data class GarminSsoCookieChoice(val cookie: String?, val source: String)

internal fun selectGarminSsoCookie(liveSso: String?, storedSso: String?): GarminSsoCookieChoice {
    if (cookieValue(liveSso, "CASTGC") != null) return GarminSsoCookieChoice(liveSso, "LIVE")
    if (cookieValue(storedSso, "CASTGC") != null) return GarminSsoCookieChoice(storedSso, "STORED")
    // 都没 CASTGC：尽力一搏（可能仍带 GDID 等），标记 NONE 让诊断区分「真无 ticket」。
    val best = liveSso?.takeIf { it.isNotBlank() } ?: storedSso?.takeIf { it.isNotBlank() }
    return GarminSsoCookieChoice(best, "NONE")
}

internal fun isGarminLoginResponse(code: Int, location: String?): Boolean {
    if (code == 401) return true
    if (code !in 300..399 || location.isNullOrBlank()) return false
    return runCatching {
        val uri = java.net.URI(location)
        val host = uri.host.orEmpty().lowercase()
        val path = uri.path.orEmpty().lowercase()
        host.endsWith("sso.garmin.cn") || path.contains("/signin") || path.contains("/portal/sso")
    }.getOrDefault(false)
}

/**
 * Cookie 读不到时的判定：[hasPersistedAccount] = true（数据库里有 Garmin 账号，说明登录过）
 * → 可重试失败，避免一次读取抖动就误报「登录已失效」；否则 → 要求登录。
 */
internal fun garminCookieMissingResult(hasPersistedAccount: Boolean, loginUrl: String): ConnectorResult<Nothing> =
    if (hasPersistedAccount) {
        ConnectorResult.RetryableFailure(GARMIN_COOKIE_UNREADABLE_CODE, "Garmin 会话凭据暂时读取失败，稍后会自动重试")
    } else {
        ConnectorResult.RequiresLogin(loginUrl, "Garmin 尚未登录，请先完成登录")
    }

internal const val GARMIN_COOKIE_UNREADABLE_CODE = "GARMIN_COOKIE_UNREADABLE"

/** 构造 CAS SSO 入口：connect.garmin.cn/signin/?service=<编码后的落地页>。 */
internal fun garminSsoEntryUrl(serviceUrl: String): String =
    "https://connect.garmin.cn/signin/?service=" + URLEncoder.encode(serviceUrl, "UTF-8")

/** 对应 Garmin 当前 signin 前端脚本生成的中国站 SSO 地址，用于无 WebView 的后台兜底续登。 */
internal fun garminDirectSsoUrl(serviceUrl: String): String =
    "https://sso.garmin.cn/portal/sso/zh-CN/sign-in?clientId=GarminConnect&service=" +
        URLEncoder.encode(serviceUrl, "UTF-8")

/** CAS 的 Location 既可能是绝对地址，也可能是 `/sso/...` 相对地址。 */
internal fun resolveGarminRedirect(currentUrl: String, location: String): String? = runCatching {
    java.net.URI(currentUrl).resolve(location)
}.getOrNull()?.takeIf { uri ->
    uri.scheme.equals("https", ignoreCase = true) &&
        (uri.host.equals("connect.garmin.cn", ignoreCase = true) ||
            uri.host.equals("sso.garmin.cn", ignoreCase = true))
}?.toString()

/**
 * 把响应 Set-Cookie 合并成下一跳可直接发送的 Cookie 头；同名覆盖，明确删除的 Cookie 会移除。
 * 仅保留 name=value，不把 Path/Domain/Expires 等属性误发成 Cookie。
 */
internal fun mergeGarminCookieHeader(currentHeader: String?, setCookieHeaders: List<String>): String? {
    val cookies = linkedMapOf<String, String>()
    currentHeader.orEmpty().split(';').map { it.trim() }.filter { '=' in it }.forEach { pair ->
        val name = pair.substringBefore('=').trim()
        if (name.isNotBlank()) cookies[name.lowercase()] = "$name=${pair.substringAfter('=', "").trim()}"
    }
    setCookieHeaders.forEach { header ->
        val pair = header.substringBefore(';').trim()
        val name = pair.substringBefore('=').trim()
        if (name.isBlank() || '=' !in pair) return@forEach
        val value = pair.substringAfter('=', "").trim()
        val deleted = value.isBlank() ||
            Regex("(?:^|;)\\s*Max-Age=0(?:;|$)", RegexOption.IGNORE_CASE).containsMatchIn(header) ||
            Regex("Expires=[^;]*(?:1970|1969)", RegexOption.IGNORE_CASE).containsMatchIn(header)
        if (deleted) cookies.remove(name.lowercase()) else cookies[name.lowercase()] = "$name=$value"
    }
    return cookies.values.joinToString("; ").takeIf { it.isNotBlank() }
}

/**
 * 只输出排查协议所需的响应形态和 Garmin 错误字段。所有值都做凭据/URL 脱敏，
 * 不保存原始响应正文，避免诊断日志意外带出 Cookie、JWT 或 status token。
 */
internal fun garminSafeResponseDetail(body: String, contentType: String?): String {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return "EMPTY type=${contentType?.substringBefore(';') ?: "unknown"}"
    val allowedKeys = setOf("code", "error", "errorcode", "errormessage", "message", "status")
    val root = runCatching { org.json.JSONObject(trimmed) }.getOrNull()
    val fields = runCatching {
        requireNotNull(root)
        root.keys().asSequence()
            .filter { it.lowercase() in allowedKeys }
            .take(4)
            .map { key -> "$key=${root.opt(key)}" }
            .toList()
    }.getOrDefault(emptyList())
    val source = if (fields.isNotEmpty()) {
        fields.joinToString(",")
    } else {
        when {
            trimmed.startsWith("<", ignoreCase = true) ->
                Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .find(trimmed)?.groupValues?.getOrNull(1)?.let { "html=$it" } ?: "HTML"
            root != null -> "JSON_KEYS=" + root.keys().asSequence().take(8).joinToString(",")
            trimmed.startsWith("{") || trimmed.startsWith("[") -> "JSON_UNRECOGNIZED"
            else -> "text=$trimmed"
        }
    }
    return source
        .replace(Regex("(?i)(JWT_WEB|CASTGC|authorization|bearer|cookie|ticket|token)\\s*[=:]\\s*[^,\\s}\"<]+")) {
            "${it.groupValues[1]}=[redacted]"
        }
        .replace(Regex("https?://[^\\s,}\"<]+")) { match -> match.value.substringBefore('?') }
        .replace(Regex("\\s+"), " ")
        .take(180)
}

/**
 * 中国站 gc-api 代理已在真机观察到以 HTTP 500 返回合法 `detailedImportResult`。
 * 5xx 在没有业务结果时仍按“处理中”继续轮询；有业务结果时必须以正文为准，避免反复上传。
 */
internal fun garminPollImportState(httpCode: Int, body: String): GarminImportState? =
    if (httpCode in 200..299 || httpCode == 409 || httpCode >= 500) {
        parseGarminImportState(body)
    } else {
        null
    }

/** Garmin 私有导入接口使用稳定的纯 ASCII 文件名，文件内容哈希仍用于服务端去重。 */
internal fun garminUploadFileName(sha256: String): String {
    val safeHash = sha256.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }.take(16)
    return "FitSync_${safeHash.ifBlank { "activity" }}.fit"
}

/** 从 Connect 最终 HTML/内嵌配置中提取上传所需的 CSRF。 */
internal fun extractGarminCsrfFromHtml(html: String): String? {
    if (html.isBlank()) return null
    val patterns = listOf(
        Regex("""<meta[^>]+name=[\"']csrf-token[\"'][^>]+content=[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE),
        Regex("""<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+name=[\"']csrf-token[\"']""", RegexOption.IGNORE_CASE),
        Regex("""[\"'](?:csrfToken|csrf_token)[\"']\s*:\s*[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE),
    )
    return patterns.firstNotNullOfOrNull { pattern ->
        pattern.find(html)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/** 从一组 Set-Cookie 头里提取指定 cookie 的值（首个匹配），取不到返回 null。 */
internal fun extractCookieValueFromSetCookie(setCookieHeaders: List<String>, name: String): String? =
    setCookieHeaders.firstNotNullOfOrNull { header ->
        if (header.substringBefore('=').trim().equals(name, ignoreCase = true)) {
            header.substringAfter('=').substringBefore(';').trim().removeSurrounding("\"")
                .takeIf { it.isNotEmpty() }
        } else null
    }

/** 从一组 Set-Cookie 头里提取 JWT_WEB 的值（首个匹配），取不到返回 null。 */
internal fun extractJwtWebFromHeaders(setCookieHeaders: List<String>): String? =
    extractCookieValueFromSetCookie(setCookieHeaders, "JWT_WEB")

/** 判断 URL 是否属于 SSO 域（决定换发该跳带 sso cookie 还是 connect cookie）。 */
internal fun isGarminSsoHost(url: String): Boolean =
    runCatching { java.net.URI(url).host.orEmpty().lowercase().contains("sso.garmin") }.getOrDefault(false)

private fun garminForbiddenFailure(): ConnectorResult.RetryableFailure =
    ConnectorResult.RetryableFailure(
        "403",
        "Garmin 中国站暂时拒绝后台请求，将自动重试；如持续发生，请打开平台后重新验证登录",
    )
