package com.fitsync.app.connectors

import com.fitsync.app.data.db.FitSyncDatabase
import com.fitsync.app.data.db.entity.ActivityVariantEntity
import com.fitsync.app.data.db.entity.CanonicalActivityEntity
import com.fitsync.app.data.file.FitFileStore
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.domain.model.SessionState
import com.fitsync.app.web.PlatformWebConfigs
import com.fitsync.app.web.WebSessionManager
import com.fitsync.app.web.cookieValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
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

    override suspend fun validateSession(): SessionState {
        val cookie = sessionCookie() ?: return SessionState.NOT_CONNECTED
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
        val cookie = sessionCookie() ?: return requiresLogin()
        val csrf = sessionManager.garminCsrf()
        val start = cursor?.raw?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val url = "$GARMIN_GC_API_BASE/activitylist-service/activities/search/activities" +
            "?start=$start&limit=$LIST_PAGE_SIZE"
        return runCatching {
            execute(buildGcApiRequest(url, cookie, csrf, null)).use { resp ->
                when {
                    isGarminLoginResponse(resp.code, resp.header("Location")) -> requiresLogin()
                    !resp.isSuccessful -> failureStatus(resp)
                    else -> {
                        val all = parseGarminActivities(resp.body?.string().orEmpty())
                            ?: return@use ConnectorResult.RetryableFailure(
                                "GARMIN_INVALID_RESPONSE",
                                "Garmin 活动列表响应格式异常",
                            )
                        // Garmin 默认按时间倒序：只保留窗口内活动；整页都被剔除说明已越过下界，停止翻页。
                        val items = all.filter { it.startTimeEpochMs == null || it.startTimeEpochMs >= sinceEpochMs }
                        val hasMore = all.size >= LIST_PAGE_SIZE && items.isNotEmpty()
                        ConnectorResult.Success(
                            ActivityPage(items, SyncCursor((start + LIST_PAGE_SIZE).toString()), hasMore),
                        )
                    }
                }
            }
        }.getOrElse { ConnectorResult.RetryableFailure(null, it.message ?: "Garmin 活动列表读取失败") }
    }

    override suspend fun downloadFit(activity: RemoteActivitySummary): ConnectorResult<FitFile> {
        val cookie = sessionCookie() ?: return requiresLogin()
        val csrf = sessionManager.garminCsrf()
        val url = "$GARMIN_GC_API_BASE/download-service/files/activity/${activity.remoteActivityId}/fit"
        return runCatching {
            execute(buildGcApiRequest(url, cookie, csrf, null)).use { resp ->
                when {
                    isGarminLoginResponse(resp.code, resp.header("Location")) -> requiresLogin()
                    resp.code == 404 -> ConnectorResult.PermanentFailure(
                        "GARMIN_FIT_NOT_FOUND", "Garmin 未保留该活动的原始 FIT",
                    )
                    !resp.isSuccessful -> failureStatus(resp)
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
        val uploadName = if (file.name.endsWith(".fit", ignoreCase = true)) file.name else "${file.name}.fit"
        val multipart = MultipartBody.Builder()
            .addFormDataPart("file", uploadName, file.asRequestBody(OCTET_STREAM))
            .build()
        return runCatching { postUpload(multipart) }
            .getOrElse { ConnectorResult.RetryableFailure(null, it.message ?: "Garmin 上传请求失败") }
    }

    private suspend fun postUpload(multipart: MultipartBody): ConnectorResult<RemoteCopy> {
        val cookie = sessionCookie() ?: return requiresLogin()
        val csrf = sessionManager.garminCsrf()
        val resp = execute(buildGcApiRequest(garminUploadUrl(sessionManager.garminDomain()), cookie, csrf, multipart))
        return resp.use { handleUploadResponse(it, cookie, csrf) }
    }

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

    private suspend fun handleUploadResponse(
        response: Response,
        cookieHeader: String,
        csrf: String?,
    ): ConnectorResult<RemoteCopy> {
        val body = runCatching { response.body?.string() }.getOrNull().orEmpty()
        return when {
            response.code in 200..299 -> interpretSuccess(body, response, cookieHeader, csrf)
            response.code == 409 -> ConnectorResult.Duplicate(
                parseGarminImportState(body)?.let { garminRemoteCopyOrNull(it.remoteActivityId, true) },
            )
            isGarminLoginResponse(response.code, response.header("Location")) -> requiresLogin()
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

    private suspend fun interpretSuccess(
        body: String,
        response: Response,
        cookieHeader: String,
        csrf: String?,
    ): ConnectorResult<RemoteCopy> {
        val state = parseGarminImportState(body)
        if (state != null && state.isFinal) {
            return when {
                state.duplicate -> ConnectorResult.Duplicate(garminRemoteCopyOrNull(state.remoteActivityId, true))
                state.success -> garminRemoteCopyOrNull(state.remoteActivityId, false)
                    ?.let { ConnectorResult.Success(it) }
                    ?: ConnectorResult.RetryableFailure("GARMIN_ACTIVITY_ID_MISSING", "Garmin 未返回活动 ID")
                else -> ConnectorResult.RequiresUserAction(
                    PlatformWebConfigs.get(platform).uploadUrl,
                    "Garmin 拒绝导入该文件，请在网页中确认后重试",
                )
            }
        }
        val location = response.header("location") ?: response.header("Location")
        val statusUrl = location?.let { garminStatusApiUrl(it, "garmin.cn") }
        if (statusUrl != null) return pollStatus(statusUrl, cookieHeader, csrf)
        return ConnectorResult.RetryableFailure("GARMIN_PROCESSING", "Garmin 仍在处理上传，稍后会自动重试")
    }

    private suspend fun pollStatus(
        statusUrl: String,
        cookieHeader: String,
        csrf: String?,
    ): ConnectorResult<RemoteCopy> {
        repeat(GARMIN_POLL_MAX_TRIES) {
            delay(GARMIN_POLL_INTERVAL_MS)
            val req = buildGcApiRequest(statusUrl, cookieHeader, csrf, null)
            var authExpired = false
            var forbidden = false
            val state = runCatching {
                execute(req).use { resp ->
                    if (isGarminLoginResponse(resp.code, resp.header("Location"))) {
                        authExpired = true
                        return@use null
                    }
                    if (resp.code == 403) {
                        forbidden = true
                        return@use null
                    }
                    if (resp.code !in 200..299) return@use null
                    parseGarminImportState(resp.body?.string().orEmpty())
                }
            }.getOrNull()
            if (authExpired) return requiresLogin()
            if (forbidden) return garminForbiddenFailure()
            if (state?.isFinal == true) {
                return when {
                    state.duplicate -> ConnectorResult.Duplicate(garminRemoteCopyOrNull(state.remoteActivityId, true))
                    state.success -> garminRemoteCopyOrNull(state.remoteActivityId, false)
                        ?.let { ConnectorResult.Success(it) }
                        ?: ConnectorResult.RetryableFailure("GARMIN_ACTIVITY_ID_MISSING", "Garmin 未返回活动 ID")
                    else -> ConnectorResult.RequiresUserAction(
                        PlatformWebConfigs.get(platform).uploadUrl,
                        "Garmin 拒绝导入该文件，请在网页中确认后重试",
                    )
                }
            }
        }
        return ConnectorResult.RetryableFailure("GARMIN_PROCESSING", "Garmin 处理超时，稍后会自动重试")
    }

    private suspend fun storeFit(response: Response, displayName: String): ConnectorResult<FitFile> {
        val body = response.body
            ?: return ConnectorResult.PermanentFailure(null, "Garmin 未返回 FIT 文件")
        return runCatching {
            val stored = body.byteStream().use { fileStore.storeFromStream(it, displayName) }
            ConnectorResult.Success(FitFile(stored.file, stored.sha256, stored.displayName))
        }.getOrElse {
            ConnectorResult.PermanentFailure("GARMIN_DOWNLOAD_INVALID", it.message ?: "下载的内容不是有效 FIT")
        }
    }

    private fun sessionCookie(): String? = selectGarminApiCookie(
        liveCookie = sessionManager.cookieHeader(platform),
        storedCookie = sessionManager.garminCookieHeader(),
        storedJwt = sessionManager.garminJwtWeb(),
    )

    private fun requiresLogin(): ConnectorResult<Nothing> =
        ConnectorResult.RequiresLogin(loginUrl, "Garmin 登录已失效，请重新登录")

    private fun failureStatus(resp: Response): ConnectorResult<Nothing> = when (resp.code) {
        403 -> garminForbiddenFailure()
        429, in 500..599 -> ConnectorResult.RetryableFailure(
            resp.code.toString(), "Garmin 暂时不可用（HTTP ${resp.code}）",
        )
        else -> ConnectorResult.PermanentFailure(resp.code.toString(), "Garmin 请求失败（HTTP ${resp.code}）")
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
        private const val GARMIN_DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        fun httpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
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

private fun garminForbiddenFailure(): ConnectorResult.RetryableFailure =
    ConnectorResult.RetryableFailure(
        "403",
        "Garmin 中国站暂时拒绝后台请求，将自动重试；如持续发生，请打开平台后重新验证登录",
    )
