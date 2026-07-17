package com.fitsync.app.connectors

import com.fitsync.app.data.db.FitSyncDatabase
import com.fitsync.app.data.db.entity.ActivityVariantEntity
import com.fitsync.app.data.db.entity.CanonicalActivityEntity
import com.fitsync.app.data.file.FitFileStore
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.domain.model.SessionState
import com.fitsync.app.web.PlatformWebConfigs
import com.fitsync.app.web.WebSessionManager
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

    override suspend fun validateSession(): SessionState =
        if (sessionManager.hasSession(platform)) SessionState.AUTHENTICATED
        else SessionState.NOT_CONNECTED

    override suspend fun detectCapabilities(): ConnectorCapabilities =
        PlatformCapabilityRegistry.capabilities(platform)

    override suspend fun listActivities(
        sinceEpochMs: Long,
        cursor: SyncCursor?,
    ): ConnectorResult<ActivityPage> {
        val cookie = sessionCookie() ?: return requiresLogin()
        val csrf = sessionManager.garminCsrf()
        val start = cursor?.raw?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val url = "https://connect.garmin.cn/gc-api/activitylist-service/activities/search/activities" +
            "?start=$start&limit=$LIST_PAGE_SIZE"
        return runCatching {
            execute(buildGcApiRequest(url, cookie, csrf, null)).use { resp ->
                when {
                    resp.code == 401 || resp.code == 403 -> requiresLogin()
                    !resp.isSuccessful -> failureStatus(resp)
                    else -> {
                        val all = parseGarminActivities(resp.body?.string().orEmpty())
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
        val url = "https://connect.garmin.cn/gc-api/download-service/files/activity/${activity.remoteActivityId}/fit"
        return runCatching {
            execute(buildGcApiRequest(url, cookie, csrf, null)).use { resp ->
                when {
                    resp.code == 401 || resp.code == 403 -> requiresLogin()
                    resp.code == 404 -> ConnectorResult.PermanentFailure(
                        "GARMIN_FIT_NOT_FOUND", "Garmin 未保留该活动的原始 FIT",
                    )
                    !resp.isSuccessful -> failureStatus(resp)
                    else -> storeFit(resp, "garmin_${activity.remoteActivityId}.fit")
                }
            }
        }.getOrElse { ConnectorResult.RetryableFailure(null, it.message ?: "Garmin FIT 下载失败") }
    }

    override suspend fun uploadFit(activity: CanonicalActivityEntity): ConnectorResult<RemoteCopy> {
        val pair = primaryFile(activity)
            ?: return ConnectorResult.PermanentFailure("FILE_MISSING", "本地 FIT 文件不存在")
        val (file, _) = pair
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
        return resp.use { handleUploadResponse(it, cookie, csrf) } ?: requiresLogin()
    }

    private fun buildGcApiRequest(
        url: String,
        cookieHeader: String,
        csrf: String?,
        body: okhttp3.RequestBody?,
    ): Request {
        val builder = Request.Builder()
            .url(url)
            .header("Cookie", cookieHeader)
            .header("NK", "NT")
            .apply { if (!csrf.isNullOrBlank()) header("Connect-Csrf-Token", csrf) }
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/plain, */*")
            .header("Origin", "https://connect.garmin.cn")
            .header("Referer", "https://connect.garmin.cn/modern/")
            .header("User-Agent", sessionManager.garminUserAgent() ?: GARMIN_DESKTOP_USER_AGENT)
        return if (body is MultipartBody) builder.post(body).build() else builder.build()
    }

    private suspend fun handleUploadResponse(
        response: Response,
        cookieHeader: String,
        csrf: String?,
    ): ConnectorResult<RemoteCopy>? {
        val body = runCatching { response.body?.string() }.getOrNull().orEmpty()
        return when {
            response.code in 200..299 -> interpretSuccess(body, response, cookieHeader, csrf)
            response.code == 409 -> ConnectorResult.Duplicate(
                parseGarminImportState(body)?.let { remoteCopy(it.remoteActivityId, true) }
                    ?: RemoteCopy("", duplicate = true),
            )
            response.code == 401 -> requiresLogin()
            response.code == 403 -> null // Cookie/CSRF 失效，交上层提示重新登录
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
                state.duplicate -> ConnectorResult.Duplicate(remoteCopy(state.remoteActivityId, true))
                state.success -> ConnectorResult.Success(remoteCopy(state.remoteActivityId, false))
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
            val state = runCatching {
                execute(req).use { resp ->
                    if (resp.code == 401 || resp.code == 403) { authExpired = true; return@use null }
                    if (resp.code !in 200..299) return@use null
                    parseGarminImportState(resp.body?.string().orEmpty())
                }
            }.getOrNull()
            if (authExpired) return requiresLogin()
            if (state?.isFinal == true) {
                return when {
                    state.duplicate -> ConnectorResult.Duplicate(remoteCopy(state.remoteActivityId, true))
                    state.success -> ConnectorResult.Success(remoteCopy(state.remoteActivityId, false))
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

    private fun remoteCopy(remoteActivityId: String?, duplicate: Boolean): RemoteCopy {
        val id = remoteActivityId?.takeIf { it.isNotBlank() }
        return RemoteCopy(
            remoteActivityId = id ?: "",
            remoteUrl = id?.let { "https://connect.garmin.cn/modern/activity/$it" },
            duplicate = duplicate,
        )
    }

    private fun sessionCookie(): String? =
        sessionManager.garminCookieHeader() ?: sessionManager.garminJwtWeb()?.let { "JWT_WEB=$it" }

    private fun requiresLogin(): ConnectorResult<Nothing> =
        ConnectorResult.RequiresLogin(loginUrl, "Garmin 登录已失效，请重新登录")

    private fun failureStatus(resp: Response): ConnectorResult<Nothing> = when (resp.code) {
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

    private suspend fun primaryFile(activity: CanonicalActivityEntity): Pair<File, ActivityVariantEntity>? {
        val variants = db.activityVariantDao().findByCanonical(activity.id)
        val variant = activity.primaryVariantId?.let { id -> variants.firstOrNull { it.id == id } }
            ?: variants.maxByOrNull { it.qualityScore ?: 0 }
            ?: return null
        val file = File(variant.localFilePath)
        return if (file.isFile) file to variant else null
    }

    companion object {
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

private val OCTET_STREAM = "application/octet-stream".toMediaType()
