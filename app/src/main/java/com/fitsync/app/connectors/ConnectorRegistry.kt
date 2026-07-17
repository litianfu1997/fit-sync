package com.fitsync.app.connectors

import com.fitsync.app.data.db.FitSyncDatabase
import com.fitsync.app.data.db.entity.CanonicalActivityEntity
import com.fitsync.app.data.file.FitFileStore
import com.fitsync.app.domain.model.ConnectorExecution
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.domain.model.SessionState
import com.fitsync.app.domain.model.SportType
import com.fitsync.app.web.PlatformWebConfigs
import com.fitsync.app.web.GarminWebSession
import com.fitsync.app.web.WebSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class ConnectorRegistry(
    db: FitSyncDatabase,
    fileStore: FitFileStore,
    sessionManager: WebSessionManager,
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val connectors: Map<Platform, PlatformConnector> = listOf(
        OnelapConnector(db, fileStore, sessionManager, httpClient),
        XingzheConnector(db, fileStore, sessionManager, httpClient),
        GarminConnector(db, fileStore, sessionManager, httpClient),
        ForegroundWebConnector(Platform.COROS, sessionManager),
        ForegroundWebConnector(Platform.IGPSPORT, sessionManager),
    ).associateBy { it.platform }

    fun get(platform: Platform): PlatformConnector? = connectors[platform]
    fun all(): Collection<PlatformConnector> = connectors.values
}

private abstract class HttpPlatformConnector(
    protected val db: FitSyncDatabase,
    protected val fileStore: FitFileStore,
    protected val sessionManager: WebSessionManager,
    protected val client: OkHttpClient,
) : PlatformConnector {
    protected fun request(url: String): Request.Builder =
        sessionManager.applyTo(platform, Request.Builder().url(url))

    protected suspend fun execute(request: Request): Response = withContext(Dispatchers.IO) {
        client.newCall(request).execute()
    }

    protected fun failure(response: Response, fallbackUrl: String): ConnectorResult<Nothing> = when {
        response.code == 401 || response.code == 403 ->
            ConnectorResult.RequiresLogin(fallbackUrl)
        response.code == 408 || response.code == 429 || response.code >= 500 ->
            ConnectorResult.RetryableFailure(response.code.toString(), "平台暂时不可用（HTTP ${response.code}）")
        else -> ConnectorResult.PermanentFailure(response.code.toString(), "平台请求失败（HTTP ${response.code}）")
    }

    protected suspend fun primaryFile(activity: CanonicalActivityEntity): Pair<File, com.fitsync.app.data.db.entity.ActivityVariantEntity>? {
        val variants = db.activityVariantDao().findByCanonical(activity.id)
        val variant = activity.primaryVariantId?.let { id -> variants.firstOrNull { it.id == id } }
            ?: variants.maxByOrNull { it.qualityScore ?: 0 }
            ?: return null
        val file = File(variant.localFilePath)
        return if (file.isFile) file to variant else null
    }

    protected suspend fun storeFit(response: Response, displayName: String, maxBytes: Long = 50L * 1024 * 1024): FitFile {
        val body = response.body ?: error("平台没有返回文件")
        val stored = body.byteStream().use { fileStore.storeFromStream(it, displayName, maxBytes) }
        return FitFile(stored.file, stored.sha256, stored.displayName)
    }
}

private class OnelapConnector(
    db: FitSyncDatabase,
    fileStore: FitFileStore,
    sessionManager: WebSessionManager,
    client: OkHttpClient,
) : HttpPlatformConnector(db, fileStore, sessionManager, client) {
    override val platform = Platform.ONELAP
    private val baseUrl = "https://otm.onelap.cn"

    override suspend fun validateSession(): SessionState {
        if (!sessionManager.hasSession(platform)) return SessionState.NOT_CONNECTED
        val today = dayString(System.currentTimeMillis())
        val body = JSONObject()
            .put("page", 1)
            .put("limit", 1)
            .put("start_date", today)
            .put("end_date", today)
            .toString()
            .toRequestBody(JSON)
        return runCatching {
            executeAuthorized(request("$baseUrl/api/otm/ride_record/list").post(body).build()).use { response ->
                when {
                    response.isSuccessful -> SessionState.AUTHENTICATED
                    response.code == 401 || response.code == 403 -> SessionState.EXPIRED
                    else -> SessionState.NEEDS_USER_ACTION
                }
            }
        }.getOrDefault(SessionState.NEEDS_USER_ACTION)
    }

    override suspend fun detectCapabilities(): ConnectorCapabilities =
        PlatformCapabilityRegistry.capabilities(platform)

    override suspend fun listActivities(
        sinceEpochMs: Long,
        cursor: SyncCursor?,
    ): ConnectorResult<ActivityPage> {
        val page = cursor?.raw?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val payload = JSONObject()
            .put("page", page)
            .put("limit", ONELAP_PAGE_SIZE)
            .put("start_date", dayString(sinceEpochMs))
            .put("end_date", dayString(System.currentTimeMillis()))
            .toString()
            .toRequestBody(JSON)
        return runCatching {
            executeAuthorized(request("$baseUrl/api/otm/ride_record/list").post(payload).build()).use { response ->
                if (!response.isSuccessful) return@use failure(response, PlatformWebConfigs.get(platform).loginUrl)
                val root = JSONObject(response.body?.string().orEmpty())
                val items = JsonPlatformParser.activities(root)
                val nextCursor = nextOnelapCursor(page, items.size)
                ConnectorResult.Success(
                    ActivityPage(items, nextCursor, nextCursor != null),
                )
            }
        }.getOrElse { ConnectorResult.RetryableFailure(null, it.message ?: "迈金活动列表读取失败") }
    }

    override suspend fun downloadFit(activity: RemoteActivitySummary): ConnectorResult<FitFile> {
        val fitUrl = activity.downloadUrl ?: activity.metadata["fit_url"]
        return if (fitUrl != null) {
            downloadFitUrl(fitUrl, activity)
        } else {
            resolveAndDownloadFit(activity)
        }
    }

    private suspend fun resolveAndDownloadFit(activity: RemoteActivitySummary): ConnectorResult<FitFile> = runCatching {
        val detailUrl = "$baseUrl/api/otm/ride_record/analysis/${activity.remoteActivityId}"
        executeAuthorized(request(detailUrl).get().build()).use { response ->
            if (!response.isSuccessful) return@use failure(response, PlatformWebConfigs.get(platform).loginUrl)
            val root = JSONObject(response.body?.string().orEmpty())
            val fitUrl = JsonPlatformParser.firstString(root, "fitUrl", "fit_url", "file_url", "download_url")
                ?: return@use ConnectorResult.PermanentFailure(null, "顽鹿未返回 FIT 下载地址")
            downloadFitUrl(fitUrl, activity)
        }
    }.getOrElse { ConnectorResult.RetryableFailure(null, it.message ?: "顽鹿活动详情读取失败") }

    private suspend fun downloadFitUrl(
        fitUrl: String,
        activity: RemoteActivitySummary,
    ): ConnectorResult<FitFile> {
        // Keep the same base64 path format used by the current Onelap web client.
        val encoded = Base64.getEncoder().encodeToString(fitUrl.toByteArray(Charsets.UTF_8))
        val url = "$baseUrl/api/otm/ride_record/analysis/fit_content/$encoded"
        return downloadPossiblyWrapped(url, activity)
    }

    private suspend fun downloadPossiblyWrapped(
        url: String,
        activity: RemoteActivitySummary,
    ): ConnectorResult<FitFile> = runCatching {
        executeAuthorized(request(url).get().build()).use outer@ { response ->
            if (!response.isSuccessful) return@outer failure(response, PlatformWebConfigs.get(platform).loginUrl)
            val contentType = response.header("Content-Type").orEmpty()
            if (contentType.contains("json", ignoreCase = true)) {
                val json = JSONObject(response.body?.string().orEmpty())
                val target = JsonPlatformParser.firstString(json, "url", "fit_url", "download_url", "data")
                    ?: return@outer ConnectorResult.PermanentFailure(null, "迈金未返回 FIT 文件")
                val targetUrl = target.toHttpUrlOrNull()
                    ?.takeIf { it.isHttps && isTrustedFitHost(it.host) }
                    ?: return@outer ConnectorResult.PermanentFailure(null, "迈金返回了不安全的 FIT 地址")
                execute(Request.Builder().url(targetUrl).get().build()).use nested@ { nested ->
                    if (!nested.isSuccessful) return@nested failure(nested, PlatformWebConfigs.get(platform).loginUrl)
                    ConnectorResult.Success(storeFit(nested, "onelap_${activity.remoteActivityId}.fit"))
                }
            } else {
                ConnectorResult.Success(storeFit(response, "onelap_${activity.remoteActivityId}.fit"))
            }
        }
    }.getOrElse { ConnectorResult.RetryableFailure(null, it.message ?: "迈金 FIT 下载失败") }

    override suspend fun uploadFit(activity: CanonicalActivityEntity): ConnectorResult<RemoteCopy> {
        val pair = primaryFile(activity)
            ?: return ConnectorResult.PermanentFailure("FILE_MISSING", "本地 FIT 文件不存在")
        val file = pair.first
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("jilu", file.name, file.asRequestBody(FIT))
            .build()
        return runCatching {
            executeAuthorized(request("$baseUrl/api/otm/ride_record/upload/fit").post(multipart).build()).use { response ->
                if (!response.isSuccessful) return@use failure(response, PlatformWebConfigs.get(platform).loginUrl)
                val text = response.body?.string().orEmpty()
                val root = JSONObject(text.ifBlank { "{}" })
                val remoteId = JsonPlatformParser.firstString(root, "id", "record_id", "ride_record_id", "jilu_id")
                    ?: "onelap-${activity.id}-${System.currentTimeMillis()}"
                ConnectorResult.Success(RemoteCopy(remoteId))
            }
        }.getOrElse { ConnectorResult.RetryableFailure(null, it.message ?: "迈金 FIT 上传失败") }
    }

    private suspend fun executeAuthorized(original: Request): Response {
        val first = execute(original)
        if (first.code != 401 && first.code != 403) return first
        first.close()
        if (!refreshSession()) return execute(original)
        val retried = sessionManager.applyTo(platform, original.newBuilder()).build()
        return execute(retried)
    }

    private suspend fun refreshSession(): Boolean {
        val refreshToken = sessionManager.refreshToken(platform) ?: return false
        val payload = JSONObject()
            .put("token", refreshToken)
            .put("from", "web")
            .put("to", "web")
            .toString()
            .toRequestBody(JSON)
        return runCatching {
            execute(Request.Builder().url("$baseUrl/api/token").post(payload).build()).use { response ->
                if (!response.isSuccessful) return@use false
                val root = JSONObject(response.body?.string().orEmpty())
                val data = root.optJSONObject("data") ?: root
                val token = JsonPlatformParser.firstString(data, "token", "access_token") ?: return@use false
                sessionManager.saveOnelapTokens(
                    token,
                    JsonPlatformParser.firstString(data, "refresh_token") ?: refreshToken,
                    JsonPlatformParser.firstString(data, "uid", "user_id")
                        ?: sessionManager.externalUserId(platform),
                )
                true
            }
        }.getOrDefault(false)
    }

    private fun isTrustedFitHost(host: String): Boolean =
        host == "onelap.cn" || host.endsWith(".onelap.cn") ||
            host == "aliyuncs.com" || host.endsWith(".aliyuncs.com") ||
            host == "myqcloud.com" || host.endsWith(".myqcloud.com")

}

internal const val ONELAP_PAGE_SIZE = 10

internal fun nextOnelapCursor(page: Int, itemCount: Int): SyncCursor? =
    if (itemCount >= ONELAP_PAGE_SIZE) SyncCursor((page + 1).toString()) else null

private class XingzheConnector(
    db: FitSyncDatabase,
    fileStore: FitFileStore,
    sessionManager: WebSessionManager,
    client: OkHttpClient,
) : HttpPlatformConnector(db, fileStore, sessionManager, client) {
    override val platform = Platform.XINGZHE
    private val baseUrl = "https://www.imxingzhe.com"

    override suspend fun validateSession(): SessionState {
        if (!sessionManager.hasSession(platform)) return SessionState.NOT_CONNECTED
        return runCatching {
            execute(request("$baseUrl/api/v1/pgworkout/?limit=1&offset=0").get().build()).use { response ->
                when {
                    response.isSuccessful -> SessionState.AUTHENTICATED
                    response.code == 401 || response.code == 403 -> SessionState.EXPIRED
                    else -> SessionState.NEEDS_USER_ACTION
                }
            }
        }.getOrDefault(SessionState.NEEDS_USER_ACTION)
    }

    override suspend fun detectCapabilities(): ConnectorCapabilities =
        PlatformCapabilityRegistry.capabilities(platform)

    override suspend fun listActivities(
        sinceEpochMs: Long,
        cursor: SyncCursor?,
    ): ConnectorResult<ActivityPage> {
        val offset = cursor?.raw?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val url = "$baseUrl/api/v1/pgworkout/?limit=$PAGE_SIZE&offset=$offset"
        return runCatching {
            execute(request(url).get().build()).use { response ->
                if (!response.isSuccessful) return@use failure(response, PlatformWebConfigs.get(platform).loginUrl)
                val text = response.body?.string().orEmpty()
                val root: Any = if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text)
                val all = JsonPlatformParser.activities(root)
                val items = all.filter { it.startTimeEpochMs == null || it.startTimeEpochMs >= sinceEpochMs }
                val hasMore = all.size >= PAGE_SIZE
                ConnectorResult.Success(
                    ActivityPage(items, if (hasMore) SyncCursor((offset + PAGE_SIZE).toString()) else null, hasMore),
                )
            }
        }.getOrElse { ConnectorResult.RetryableFailure(null, it.message ?: "行者活动列表读取失败") }
    }

    override suspend fun downloadFit(activity: RemoteActivitySummary): ConnectorResult<FitFile> {
        val url = "$baseUrl/api/v1/workout/${activity.remoteActivityId}/fit/"
        return runCatching {
            execute(request(url).get().build()).use { response ->
                if (!response.isSuccessful) return@use failure(response, PlatformWebConfigs.get(platform).loginUrl)
                ConnectorResult.Success(storeFit(response, "xingzhe_${activity.remoteActivityId}.fit"))
            }
        }.getOrElse { ConnectorResult.RetryableFailure(null, it.message ?: "行者 FIT 下载失败") }
    }

    override suspend fun uploadFit(activity: CanonicalActivityEntity): ConnectorResult<RemoteCopy> {
        val config = PlatformWebConfigs.get(platform)
        val pair = primaryFile(activity)
            ?: return ConnectorResult.PermanentFailure("FILE_MISSING", "本地 FIT 文件不存在")
        val (file, variant) = pair
        if (file.length() > 30L * 1024 * 1024) {
            return ConnectorResult.PermanentFailure("FILE_TOO_LARGE", "行者限制 FIT 文件不超过 30 MB")
        }
        val sport = xingzheSport(activity.sportType)
        val source = listOfNotNull(variant.recordingManufacturer, variant.recordingProduct)
            .joinToString("-")
            .ifBlank { "-" }
        val title = activity.title?.takeIf { it.isNotBlank() }
            ?: DateTimeFormatter.ofPattern("yyyy-MM-dd-HH:mm").withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(activity.startTimeEpochMs))
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file_source", source)
            .addFormDataPart("fit_filename", file.name)
            .addFormDataPart("md5", md5(file))
            .addFormDataPart("name", title)
            .addFormDataPart("sport", sport)
            .addFormDataPart("fit_file", file.name, file.asRequestBody(FIT))
            .build()
        return runCatching {
            execute(request("$baseUrl/api/v1/fit/upload/").post(multipart).build()).use { response ->
                val text = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
                val code = json.optInt("code", response.code)
                if (code == 9006) {
                    val id = Regex("\\d+").find(json.optString("msg"))?.value
                    return@use ConnectorResult.Duplicate(
                        id?.let { RemoteCopy(it, "$baseUrl/workouts/$it", duplicate = true) },
                    )
                }
                if (!response.isSuccessful || code != 0) {
                    if (response.code == 401 || response.code == 403 ||
                        json.optString("detail").contains("Authentication", ignoreCase = true)
                    ) return@use ConnectorResult.RequiresLogin(config.loginUrl)
                    if (response.code >= 500) {
                        return@use ConnectorResult.RetryableFailure(code.toString(), "行者上传暂时失败")
                    }
                    return@use ConnectorResult.RequiresUserAction(
                        config.uploadUrl,
                        "行者网页校验发生变化，请在网页中完成上传",
                    )
                }
                val data = json.optJSONObject("data") ?: json
                val id = JsonPlatformParser.firstString(data, "workout_id", "id")
                    ?: return@use ConnectorResult.RequiresUserAction(config.uploadUrl, "未取得行者活动 ID，请确认上传结果")
                ConnectorResult.Success(RemoteCopy(id, "$baseUrl/workouts/$id"))
            }
        }.getOrElse {
            ConnectorResult.RequiresUserAction(config.uploadUrl, it.message ?: "请在行者网页中完成上传")
        }
    }

    private fun xingzheSport(sportType: SportType): String = when (sportType) {
        SportType.RUNNING -> "1"
        SportType.INDOOR_CYCLING -> "11"
        SportType.CYCLING, SportType.MTB, SportType.GRAVEL -> "3"
        SportType.OTHER, SportType.UNKNOWN -> "7"
    }

    companion object {
        private const val PAGE_SIZE = 50
    }
}

private class GarminConnector(
    db: FitSyncDatabase,
    fileStore: FitFileStore,
    sessionManager: WebSessionManager,
    client: OkHttpClient,
) : HttpPlatformConnector(db, fileStore, sessionManager, client) {
    override val platform = Platform.GARMIN
    private val config = PlatformWebConfigs.get(platform)

    override suspend fun validateSession(): SessionState {
        if (sessionManager.garminWebSession() == null) return SessionState.NOT_CONNECTED
        return when (probeAuthenticatedSession()) {
            is ConnectorResult.Success -> SessionState.AUTHENTICATED
            is ConnectorResult.RequiresLogin -> SessionState.EXPIRED
            else -> SessionState.NEEDS_USER_ACTION
        }
    }

    override suspend fun detectCapabilities(): ConnectorCapabilities =
        PlatformCapabilityRegistry.capabilities(platform)

    override suspend fun listActivities(
        sinceEpochMs: Long,
        cursor: SyncCursor?,
    ): ConnectorResult<ActivityPage> = ConnectorResult.RequiresUserAction(
        config.loginUrl,
        "Garmin Connect 需要在网页中导出 FIT",
    )

    override suspend fun downloadFit(activity: RemoteActivitySummary): ConnectorResult<FitFile> =
        ConnectorResult.RequiresUserAction(
            activity.downloadUrl ?: config.loginUrl,
            "请在 Garmin Connect 下载 FIT 后分享到本应用",
        )

    override suspend fun uploadFit(activity: CanonicalActivityEntity): ConnectorResult<RemoteCopy> {
        val pair = primaryFile(activity)
            ?: return ConnectorResult.PermanentFailure("FILE_MISSING", "本地 FIT 文件不存在")
        val session = sessionManager.garminWebSession()
            ?: return ConnectorResult.RequiresLogin(config.loginUrl, "Garmin 自动同步登录态不可用，请重新登录并验证")
        val file = pair.first
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody(FIT))
            .build()

        return try {
            var response = execute(garminApiRequest(garminUploadUrl(session.domain), session).post(multipart).build())
            // 部分地区仍要求 CSRF；先按现行 JWT_WEB 协议请求，403 时再携带当前页面 token 重试一次。
            if (response.code == 403 && !session.csrf.isNullOrBlank()) {
                response.close()
                response = execute(
                    garminApiRequest(garminUploadUrl(session.domain), session, session.csrf)
                        .post(multipart)
                        .build(),
                )
            }
            response.use {
                val body = response.body?.string().orEmpty()
                parseFinalGarminResult(body, session.domain)?.let { return it }
                when {
                    response.code == 401 || response.code == 403 ->
                        ConnectorResult.RequiresLogin(loginUrl(session.domain), "Garmin 自动同步授权已失效，请重新登录并验证")
                    response.code == 408 || response.code == 429 || response.code >= 500 ->
                        ConnectorResult.RetryableFailure(response.code.toString(), garminFailureMessage(body, response.code))
                    response.code == 409 -> ConnectorResult.Duplicate()
                    response.code !in setOf(200, 201, 202) ->
                        ConnectorResult.PermanentFailure(response.code.toString(), garminFailureMessage(body, response.code))
                    else -> {
                        val statusUrl = garminStatusApiUrl(response.header("Location"), session.domain)
                            ?: return ConnectorResult.RetryableFailure(
                                "GARMIN_RESULT_PENDING",
                                "Garmin 已接收 FIT，但尚未返回可验证的处理结果",
                            )
                        pollUploadStatus(
                            session = session,
                            statusUrl = statusUrl,
                            initialDelayMs = response.header("Location-In-Milliseconds")
                                ?.toLongOrNull()?.coerceIn(250L, 5_000L) ?: 1_000L,
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            ConnectorResult.RetryableFailure(null, t.message ?: "Garmin 自动同步请求失败")
        }
    }

    private suspend fun probeAuthenticatedSession(): ConnectorResult<Unit> {
        val session = sessionManager.garminWebSession()
            ?: return ConnectorResult.RequiresLogin(config.loginUrl, "请先登录 Garmin Connect")
        return try {
            val profileUrl = "${garminApiOrigin(session.domain)}/userprofile-service/socialProfile"
            execute(garminApiRequest(profileUrl, session).get().build()).use { response ->
                when {
                    response.code == 401 || response.code == 403 ->
                        ConnectorResult.RequiresLogin(loginUrl(session.domain), "Garmin 自动同步授权已失效")
                    response.isSuccessful -> ConnectorResult.Success(Unit)
                    response.code == 408 || response.code == 429 || response.code >= 500 ->
                        ConnectorResult.RetryableFailure(response.code.toString(), "Garmin 登录校验暂时失败")
                    else -> ConnectorResult.PermanentFailure(response.code.toString(), "Garmin 登录校验失败（HTTP ${response.code}）")
                }
            }
        } catch (t: Throwable) {
            ConnectorResult.RetryableFailure(null, t.message ?: "Garmin 登录校验失败")
        }
    }

    private suspend fun pollUploadStatus(
        session: GarminWebSession,
        statusUrl: String,
        initialDelayMs: Long,
    ): ConnectorResult<RemoteCopy> {
        var waitMs = initialDelayMs
        repeat(GARMIN_STATUS_POLL_LIMIT) {
            delay(waitMs)
            execute(garminApiRequest(statusUrl, session).get().build()).use { response ->
                val body = response.body?.string().orEmpty()
                parseFinalGarminResult(body, session.domain)?.let { return it }
                when {
                    response.code == 401 || response.code == 403 ->
                        return ConnectorResult.RequiresLogin(loginUrl(session.domain), "Garmin 自动同步授权已失效")
                    response.code == 408 || response.code == 429 || response.code >= 500 ->
                        return ConnectorResult.RetryableFailure(response.code.toString(), garminFailureMessage(body, response.code))
                    response.code !in setOf(200, 201, 202) ->
                        return ConnectorResult.PermanentFailure(response.code.toString(), garminFailureMessage(body, response.code))
                }
                waitMs = response.header("Location-In-Milliseconds")
                    ?.toLongOrNull()?.coerceIn(250L, 5_000L) ?: 1_000L
            }
        }
        return ConnectorResult.RetryableFailure("GARMIN_PROCESSING_TIMEOUT", "Garmin 已接收 FIT，但处理结果暂未完成")
    }

    private fun garminApiRequest(url: String, session: GarminWebSession, csrf: String? = null): Request.Builder {
        val connectOrigin = "https://connect.${session.domain}"
        return Request.Builder().url(url)
            .header("User-Agent", GARMIN_USER_AGENT)
            .header("Accept", "application/json")
            .header("X-Lang", "zh-CN")
            .header("NK", "NT")
            .header("Origin", connectOrigin)
            .header("Referer", "$connectOrigin/modern/")
            .header("DI-Backend", "connectapi.${session.domain}")
            .header("Cookie", "JWT_WEB=${session.jwtWeb}")
            .apply { csrf?.let { header("connect-csrf-token", it) } }
    }

    private fun parseFinalGarminResult(body: String, domain: String): ConnectorResult<RemoteCopy>? {
        val state = parseGarminImportState(body) ?: return null
        if (!state.isFinal) return null
        val copy = state.remoteActivityId?.let { id ->
            RemoteCopy(
                remoteActivityId = id,
                remoteUrl = "https://connect.$domain/app/activity/$id",
                duplicate = state.duplicate,
            )
        }
        return when {
            state.duplicate -> ConnectorResult.Duplicate(copy)
            copy != null -> ConnectorResult.Success(copy)
            else -> ConnectorResult.PermanentFailure(
                "GARMIN_IMPORT_FAILED",
                state.failureMessage ?: "Garmin 未能导入该 FIT 文件",
            )
        }
    }

    private fun garminFailureMessage(body: String, statusCode: Int): String =
        parseGarminImportState(body)?.failureMessage
            ?: "Garmin 自动导入失败（HTTP $statusCode）"

    private fun loginUrl(domain: String): String {
        val connectOrigin = "https://connect.$domain"
        return "$connectOrigin/signin/?service=${java.net.URLEncoder.encode("$connectOrigin/app", Charsets.UTF_8.name())}"
    }
}

private class ForegroundWebConnector(
    override val platform: Platform,
    private val sessionManager: WebSessionManager,
) : PlatformConnector {
    override suspend fun validateSession(): SessionState =
        if (sessionManager.hasSession(platform)) SessionState.AUTHENTICATED else SessionState.NOT_CONNECTED

    override suspend fun detectCapabilities(): ConnectorCapabilities =
        PlatformCapabilityRegistry.capabilities(platform)

    override suspend fun listActivities(
        sinceEpochMs: Long,
        cursor: SyncCursor?,
    ): ConnectorResult<ActivityPage> = ConnectorResult.RequiresUserAction(
        PlatformWebConfigs.get(platform).loginUrl,
        "${platform.displayName} 需要在网页中导出 FIT",
    )

    override suspend fun downloadFit(activity: RemoteActivitySummary): ConnectorResult<FitFile> =
        ConnectorResult.RequiresUserAction(
            activity.downloadUrl ?: PlatformWebConfigs.get(platform).loginUrl,
            "请在网页中下载 FIT 后分享到本应用",
        )

    override suspend fun uploadFit(activity: CanonicalActivityEntity): ConnectorResult<RemoteCopy> =
        ConnectorResult.RequiresUserAction(
            PlatformWebConfigs.get(platform).uploadUrl,
            "请在网页中确认上传 FIT",
        )
}

internal object JsonPlatformParser {
    fun activities(root: Any): List<RemoteActivitySummary> {
        val array = findArray(root) ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = firstString(item, "workout_id", "ride_record_id", "record_id", "jilu_id", "id")
                    ?: continue
                val start = firstEpoch(
                    item,
                    "start_riding_time",
                    "start_time",
                    "startTime",
                    "start_date",
                    "timestamp",
                    "create_time",
                )
                val fitUrl = firstString(item, "fit_url", "fitUrl", "file_url", "download_url")
                val distanceMeters = firstDouble(item, "distance", "distance_meter", "total_distance")
                    ?: firstDouble(item, "distance_km")?.times(1000.0)
                add(
                    RemoteActivitySummary(
                        remoteActivityId = id,
                        startTimeEpochMs = start,
                        name = firstString(item, "name", "title", "record_name"),
                        sportType = firstString(item, "sport", "sport_type", "type"),
                        distanceMeters = distanceMeters,
                        downloadUrl = fitUrl,
                        metadata = buildMap { if (fitUrl != null) put("fit_url", fitUrl) },
                    ),
                )
            }
        }
    }

    fun firstString(root: JSONObject, vararg keys: String): String? {
        keys.forEach { key ->
            if (!root.has(key) || root.isNull(key)) return@forEach
            val value = root.opt(key)
            when (value) {
                is String -> if (value.isNotBlank()) return value
                is Number -> return value.toString()
                is JSONObject -> firstString(value, *keys)?.let { return it }
            }
        }
        nestedObjects(root).forEach { child ->
            firstString(child, *keys)?.let { return it }
        }
        return null
    }

    private fun firstDouble(root: JSONObject, vararg keys: String): Double? {
        keys.forEach { key ->
            val value = root.opt(key)
            when (value) {
                is Number -> return value.toDouble()
                is String -> value.toDoubleOrNull()?.let { return it }
            }
        }
        nestedObjects(root).forEach { child ->
            firstDouble(child, *keys)?.let { return it }
        }
        return null
    }

    private fun firstEpoch(root: JSONObject, vararg keys: String): Long? {
        keys.forEach { key ->
            val raw = root.opt(key) ?: return@forEach
            parseEpoch(raw)?.let { return it }
        }
        nestedObjects(root).forEach { child ->
            firstEpoch(child, *keys)?.let { return it }
        }
        return null
    }

    private fun nestedObjects(root: JSONObject): List<JSONObject> = listOf(
        "data",
        "ridingRecord",
        "riding_record",
        "rideRecord",
        "record",
        "activity",
        "detail",
    ).mapNotNull(root::optJSONObject)

    private fun parseEpoch(raw: Any): Long? {
        if (raw is Number) return raw.toLong().let { if (it in 1..9_999_999_999L) it * 1000 else it }
        val text = raw.toString().trim()
        text.toLongOrNull()?.let { return if (it in 1..9_999_999_999L) it * 1000 else it }
        return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching {
                LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrNull()
    }

    private fun findArray(root: Any?): JSONArray? = when (root) {
        is JSONArray -> root
        is JSONObject -> {
            val names = listOf("list", "rows", "results", "items", "records", "data")
            names.firstNotNullOfOrNull { key ->
                when (val child = root.opt(key)) {
                    is JSONArray -> child
                    is JSONObject -> findArray(child)
                    else -> null
                }
            }
        }
        else -> null
    }
}

internal data class GarminImportState(
    val isFinal: Boolean,
    val remoteActivityId: String? = null,
    val duplicate: Boolean = false,
    val failureMessage: String? = null,
)

internal fun garminApiOrigin(domain: String): String = when (domain) {
    "garmin.com", "garmin.cn" -> "https://connectapi.$domain"
    else -> error("不支持的 Garmin 域名")
}

internal fun garminUploadUrl(domain: String): String = "${garminApiOrigin(domain)}/upload-service/upload"

internal fun parseGarminImportState(raw: String): GarminImportState? = runCatching {
    val root = JSONObject(raw)
    val detail = root.optJSONObject("detailedImportResult") ?: return@runCatching null
    val successes = detail.optJSONArray("successes") ?: JSONArray()
    val failures = detail.optJSONArray("failures") ?: JSONArray()
    if (successes.length() > 0) {
        return@runCatching GarminImportState(
            isFinal = true,
            remoteActivityId = garminInternalId(successes),
        )
    }
    if (failures.length() > 0) {
        val message = failures.toString()
        val normalized = message.lowercase(Locale.ROOT)
        return@runCatching GarminImportState(
            isFinal = true,
            remoteActivityId = garminInternalId(failures),
            duplicate = normalized.contains("duplicate") ||
                normalized.contains("already exists") ||
                message.contains("重复") || message.contains("已存在"),
            failureMessage = message,
        )
    }
    GarminImportState(isFinal = false)
}.getOrNull()

internal fun garminStatusApiUrl(
    location: String?,
    domain: String = "garmin.com",
): String? {
    val apiOrigin = garminApiOrigin(domain)
    val url = when {
        location.isNullOrBlank() -> return null
        location.startsWith('/') -> "$apiOrigin$location".toHttpUrlOrNull()
        else -> location.toHttpUrlOrNull()
    } ?: return null
    if (!url.isHttps) return null
    val expectedApiHost = "connectapi.$domain"
    val expectedWebHost = "connect.$domain"
    val path = when {
        url.host == expectedApiHost && url.encodedPath.startsWith("/gc-api/") ->
            url.encodedPath.removePrefix("/gc-api")
        url.host == expectedApiHost -> url.encodedPath
        url.host == expectedWebHost && url.encodedPath.startsWith("/gc-api/") ->
            url.encodedPath.removePrefix("/gc-api")
        else -> return null
    }
    return buildString {
        append(apiOrigin)
        append(path)
        url.encodedQuery?.let { append('?').append(it) }
    }
}

private fun garminInternalId(items: JSONArray): String? {
    for (index in 0 until items.length()) {
        val value = items.optJSONObject(index)?.opt("internalId") ?: continue
        if (value != JSONObject.NULL && value.toString().isNotBlank()) return value.toString()
    }
    return null
}

private val JSON = "application/json; charset=utf-8".toMediaType()
private val FIT = "application/vnd.ant.fit".toMediaType()
private const val GARMIN_STATUS_POLL_LIMIT = 10
private const val GARMIN_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36 FitSync/1.0"

private fun dayString(epochMs: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).run {
    timeZone = TimeZone.getDefault()
    format(Date(epochMs))
}

private fun md5(file: File): String {
    val digest = MessageDigest.getInstance("MD5")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
