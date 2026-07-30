package com.fitsync.app.connectors

import com.fitsync.app.data.db.FitSyncDatabase
import com.fitsync.app.data.db.entity.CanonicalActivityEntity
import com.fitsync.app.data.db.entity.ActivityVariantEntity
import com.fitsync.app.data.file.FitFileStore
import com.fitsync.app.domain.model.ConnectorExecution
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.domain.model.SessionState
import com.fitsync.app.domain.model.SportType
import com.fitsync.app.web.PlatformWebConfigs
import com.fitsync.app.web.WebSessionManager
import com.fitsync.app.strava.StravaOAuthService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Cookie
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
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
    stravaOAuthService: StravaOAuthService,
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val garminHttpClient = GarminConnectApiConnector.httpClient()
    private val stravaHttpClient = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val connectors: Map<Platform, PlatformConnector> = listOf(
        OnelapConnector(db, fileStore, sessionManager, httpClient),
        XingzheConnector(db, fileStore, sessionManager, httpClient),
        // Garmin 中国站复用 WebView 会话，经 gc-api 同源代理完成后台列表、下载与上传。
        GarminConnectApiConnector(db, fileStore, sessionManager, garminHttpClient),
        IgpsportApiConnector(db, fileStore, sessionManager, httpClient),
        CorosApiConnector(db, fileStore, sessionManager, httpClient),
        StravaConnector(db, fileStore, sessionManager, stravaOAuthService, stravaHttpClient),
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

    protected fun fileForVariant(variant: ActivityVariantEntity): File? {
        val file = File(variant.localFilePath)
        return file.takeIf { it.isFile }
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
    @Volatile
    private var responseCookieHeader: String? = null

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
                activityListSessionState(response.code, response.body?.string().orEmpty())
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
                    ?: return@use ConnectorResult.RetryableFailure("ONELAP_INVALID_RESPONSE", "迈金活动列表响应格式异常")
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
            if (response.code == 404 || response.code == 410) {
                return@use ConnectorResult.PermanentFailure(
                    "NO_ORIGINAL_FILE",
                    "迈金未保留该活动的原始 FIT",
                )
            }
            if (!response.isSuccessful) return@use failure(response, PlatformWebConfigs.get(platform).loginUrl)
            val root = JSONObject(response.body?.string().orEmpty())
            val fitUrl = JsonPlatformParser.firstString(root, "fitUrl", "fit_url", "file_url", "download_url")
                ?: return@use ConnectorResult.PermanentFailure("NO_ORIGINAL_FILE", "迈金未返回 FIT 下载地址")
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
            if (response.code == 404 || response.code == 410) {
                return@outer ConnectorResult.PermanentFailure(
                    "NO_ORIGINAL_FILE",
                    "迈金未保留该活动的原始 FIT",
                )
            }
            if (!response.isSuccessful) return@outer failure(response, PlatformWebConfigs.get(platform).loginUrl)
            val contentType = response.header("Content-Type").orEmpty()
            if (contentType.contains("json", ignoreCase = true)) {
                val json = JSONObject(response.body?.string().orEmpty())
                val target = JsonPlatformParser.firstString(json, "url", "fit_url", "download_url", "data")
                    ?: return@outer ConnectorResult.PermanentFailure("NO_ORIGINAL_FILE", "迈金未返回 FIT 文件")
                val targetUrl = target.toHttpUrlOrNull()
                    ?.takeIf { it.isHttps && isTrustedFitHost(it.host) }
                    ?: return@outer ConnectorResult.PermanentFailure(
                        "UNSUPPORTED_ORIGINAL_FORMAT",
                        "迈金返回了不安全的 FIT 地址",
                    )
                execute(Request.Builder().url(targetUrl).get().build()).use nested@ { nested ->
                    if (nested.code == 404 || nested.code == 410) {
                        return@nested ConnectorResult.PermanentFailure(
                            "NO_ORIGINAL_FILE",
                            "迈金未保留该活动的原始 FIT",
                        )
                    }
                    if (!nested.isSuccessful) return@nested failure(nested, PlatformWebConfigs.get(platform).loginUrl)
                    ConnectorResult.Success(storeFit(nested, "onelap_${activity.remoteActivityId}.fit"))
                }
            } else {
                ConnectorResult.Success(storeFit(response, "onelap_${activity.remoteActivityId}.fit"))
            }
        }
    }.getOrElse { ConnectorResult.RetryableFailure(null, it.message ?: "迈金 FIT 下载失败") }

    override suspend fun uploadFit(
        activity: CanonicalActivityEntity,
        sourceVariant: ActivityVariantEntity,
    ): ConnectorResult<RemoteCopy> {
        val file = fileForVariant(sourceVariant)
            ?: return ConnectorResult.PermanentFailure("FILE_MISSING", "本地 FIT 文件不存在")
        if (file.length() <= 0L) {
            return ConnectorResult.PermanentFailure("FILE_EMPTY", "本地 FIT 文件为空")
        }
        return runCatching {
            var lastMissingFile: ConnectorResult.PermanentFailure? = null
            for (fieldName in ONELAP_UPLOAD_FIELD_FALLBACKS) {
                val result = uploadFitOnce(file, sourceVariant.fileSha256, fieldName)
                if (!result.isOnelapMissingFile()) return@runCatching result
                lastMissingFile = result as ConnectorResult.PermanentFailure
            }
            lastMissingFile?.let {
                ConnectorResult.PermanentFailure(
                    "ONELAP_FILE_FIELD_REJECTED",
                    "迈金仍未识别上传文件（已兼容 jilu、file、fit_file）",
                )
            } ?: ConnectorResult.PermanentFailure("ONELAP_UPLOAD_REJECTED", "迈金拒绝 FIT 上传")
        }.getOrElse { ConnectorResult.RetryableFailure(null, it.message ?: "迈金 FIT 上传失败") }
    }

    private suspend fun uploadFitOnce(
        file: File,
        fileSha256: String,
        fieldName: String,
    ): ConnectorResult<RemoteCopy> {
        val multipart = onelapUploadMultipart(file, fileSha256, fieldName)
        return executeUploadAuthorized(multipart).use { response ->
            captureResponseCookies(response)
            if (!response.isSuccessful) return@use failure(response, PlatformWebConfigs.get(platform).loginUrl)
            onelapAcceptedUploadResult(
                responseBody = response.body?.string().orEmpty(),
                provisionalRemoteId = fileSha256,
                loginUrl = PlatformWebConfigs.get(platform).loginUrl,
            )
        }
    }

    private suspend fun executeUploadAuthorized(multipart: RequestBody): Response {
        val uploadUrl = "$baseUrl/api/otm/ride_record/upload/fit"
        val cookieHeader = currentUploadCookieHeader(uploadUrl)
        val firstToken = sessionManager.token(platform)
            ?: return execute(onelapUploadRequest(uploadUrl, "", multipart, cookieHeader))
        val first = execute(onelapUploadRequest(uploadUrl, firstToken, multipart, cookieHeader))
        captureResponseCookies(first)
        if (first.code != 401 && first.code != 403) return first
        first.close()
        if (!refreshSession()) {
            return execute(onelapUploadRequest(uploadUrl, firstToken, multipart, currentUploadCookieHeader(uploadUrl)))
        }
        val refreshedToken = sessionManager.token(platform) ?: firstToken
        return execute(onelapUploadRequest(uploadUrl, refreshedToken, multipart, currentUploadCookieHeader(uploadUrl)))
    }

    private fun currentUploadCookieHeader(uploadUrl: String): String? = mergeCookieHeaders(
        sessionManager.cookieHeader(platform, uploadUrl),
        responseCookieHeader,
    )

    private fun captureResponseCookies(response: Response) {
        val received = Cookie.parseAll(response.request.url, response.headers)
            .joinToString("; ") { "${it.name}=${it.value}" }
            .takeIf { it.isNotBlank() }
            ?: return
        responseCookieHeader = mergeCookieHeaders(responseCookieHeader, received)
    }

    override suspend fun checkUpload(remoteUploadId: String): ConnectorResult<RemoteCopy> =
        pendingAsyncUploadConfirmation(
            rawToken = remoteUploadId,
            expectedPlatform = platform,
            timeoutMs = ONELAP_UPLOAD_CONFIRM_TIMEOUT_MS,
        )

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
internal const val ONELAP_UPLOAD_CONFIRM_TIMEOUT_MS = 5L * 60 * 1000
internal val ONELAP_UPLOAD_FIELD_FALLBACKS = listOf("jilu", "jilu", "file", "fit_file")

internal fun onelapUploadMultipart(
    file: File,
    fileSha256: String,
    fieldName: String = "jilu",
): RequestBody {
    // 迈金 Web 端由浏览器 FormData 生成 multipart。OkHttp MultipartBody 会额外写入分片级
    // Content-Length，迈金的解析器可能因此忽略文件；这里严格生成浏览器风格的单文件请求体。
    val uploadName = "fitsync_${fileSha256.take(16)}.fit"
    val boundary = "----WebKitFormBoundary${fileSha256.take(24)}"
    return OnelapBrowserMultipartBody(file, uploadName, fieldName, boundary)
}

private class OnelapBrowserMultipartBody(
    private val file: File,
    uploadName: String,
    fieldName: String,
    private val boundary: String,
) : RequestBody() {
    private val prefix = buildString {
        append("--").append(boundary).append("\r\n")
        append("Content-Disposition: form-data; name=\"")
            .append(fieldName)
            .append("\"; filename=\"")
            .append(uploadName)
            .append("\"\r\n")
        append("Content-Type: application/octet-stream\r\n\r\n")
    }.toByteArray(Charsets.UTF_8)
    private val suffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
    private val mediaType = "multipart/form-data; boundary=$boundary".toMediaType()

    override fun contentType() = mediaType

    override fun contentLength(): Long = prefix.size.toLong() + file.length() + suffix.size

    override fun writeTo(sink: BufferedSink) {
        sink.write(prefix)
        file.source().use { source -> sink.writeAll(source) }
        sink.write(suffix)
    }
}

internal fun onelapUploadRequest(
    url: String,
    token: String,
    multipart: RequestBody,
    cookieHeader: String? = null,
): Request =
    Request.Builder()
        .url(url)
        .apply {
            if (token.isNotBlank()) header("Authorization", token)
            if (!cookieHeader.isNullOrBlank()) header("Cookie", cookieHeader)
        }
        .header("Accept", "application/json, text/plain, */*")
        .header("Origin", "https://otm.onelap.cn")
        .header("Referer", "https://otm.onelap.cn/")
        .header(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36",
        )
        .header("Sec-Fetch-Dest", "empty")
        .header("Sec-Fetch-Mode", "cors")
        .header("Sec-Fetch-Site", "same-origin")
        .post(multipart)
        .build()

internal fun ConnectorResult<RemoteCopy>.isOnelapMissingFile(): Boolean =
    this is ConnectorResult.PermanentFailure &&
        code?.startsWith("ONELAP_") == true &&
        ONELAP_MISSING_FILE_MESSAGES.any(message::contains)

private val ONELAP_MISSING_FILE_MESSAGES = listOf(
    "没有上传文件",
    "没有文件上传",
)

internal fun mergeCookieHeaders(vararg rawHeaders: String?): String? {
    val cookies = linkedMapOf<String, String>()
    rawHeaders.filterNotNull().forEach { raw ->
        raw.split(';').forEach { part ->
            val name = part.substringBefore('=').trim()
            val value = part.substringAfter('=', "").trim()
            if (name.isNotBlank() && value.isNotBlank()) cookies[name] = value
        }
    }
    return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }.takeIf { it.isNotBlank() }
}

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
                activityListSessionState(response.code, response.body?.string().orEmpty())
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
                    ?: return@use ConnectorResult.RetryableFailure("XINGZHE_INVALID_RESPONSE", "行者活动列表响应格式异常")
                val items = all.filter { it.startTimeEpochMs == null || it.startTimeEpochMs >= sinceEpochMs }
                val hasMore = shouldContinueXingzhePagination(all.size, items.size, PAGE_SIZE)
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
                if (response.code == 404 || response.code == 410) {
                    return@use ConnectorResult.PermanentFailure(
                        "NO_ORIGINAL_FILE",
                        "行者未保留该活动的原始 FIT",
                    )
                }
                if (!response.isSuccessful) return@use failure(response, PlatformWebConfigs.get(platform).loginUrl)
                ConnectorResult.Success(storeFit(response, "xingzhe_${activity.remoteActivityId}.fit"))
            }
        }.getOrElse { ConnectorResult.RetryableFailure(null, it.message ?: "行者 FIT 下载失败") }
    }

    override suspend fun uploadFit(
        activity: CanonicalActivityEntity,
        sourceVariant: ActivityVariantEntity,
    ): ConnectorResult<RemoteCopy> {
        val config = PlatformWebConfigs.get(platform)
        val file = fileForVariant(sourceVariant)
            ?: return ConnectorResult.PermanentFailure("FILE_MISSING", "本地 FIT 文件不存在")
        if (file.length() <= 0L) {
            return ConnectorResult.PermanentFailure("FILE_EMPTY", "本地 FIT 文件为空")
        }
        if (file.length() > 30L * 1024 * 1024) {
            return ConnectorResult.PermanentFailure("FILE_TOO_LARGE", "行者限制 FIT 文件不超过 30 MB")
        }
        val sport = xingzheSport(activity.sportType)
        val source = listOfNotNull(sourceVariant.recordingManufacturer, sourceVariant.recordingProduct)
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
                xingzheUploadResult(
                    httpCode = response.code,
                    responseBody = text,
                    provisionalRemoteId = sourceVariant.fileSha256,
                    loginUrl = config.loginUrl,
                    uploadUrl = config.uploadUrl,
                    baseUrl = baseUrl,
                )
            }
        }.getOrElse {
            ConnectorResult.RetryableFailure("NETWORK", it.message ?: "行者上传请求失败，请稍后重试")
        }
    }

    override suspend fun checkUpload(remoteUploadId: String): ConnectorResult<RemoteCopy> =
        pendingAsyncUploadConfirmation(remoteUploadId, platform)

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

internal fun shouldContinueXingzhePagination(
    rawItemCount: Int,
    eligibleItemCount: Int,
    pageSize: Int = 50,
): Boolean = rawItemCount >= pageSize && eligibleItemCount > 0

internal fun activityListSessionState(httpCode: Int, responseBody: String): SessionState {
    if (httpCode == 401 || httpCode == 403) return SessionState.EXPIRED
    if (httpCode !in 200..299) return SessionState.NEEDS_USER_ACTION
    val root = runCatching {
        if (responseBody.trimStart().startsWith("[")) JSONArray(responseBody) else JSONObject(responseBody)
    }.getOrNull() ?: return SessionState.NEEDS_USER_ACTION
    return if (JsonPlatformParser.activities(root) != null) {
        SessionState.AUTHENTICATED
    } else {
        SessionState.NEEDS_USER_ACTION
    }
}

internal fun onelapAcceptedUploadResult(
    responseBody: String,
    provisionalRemoteId: String,
    loginUrl: String = "https://otm.onelap.cn/login",
): ConnectorResult<RemoteCopy> {
    val root = runCatching { JSONObject(responseBody) }.getOrNull()
        ?: return ConnectorResult.RetryableFailure(
            "ONELAP_INVALID_UPLOAD_RESPONSE",
            "迈金上传响应格式异常，请稍后重试",
        )
    val responseCode = root.opt("code")
        ?.takeUnless { it == JSONObject.NULL }
        ?.toString()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    val message = JsonPlatformParser.firstString(root, "msg", "message", "detail", "error")
    if (responseCode != null && responseCode != "0" && responseCode != "200") {
        val numericCode = responseCode.toIntOrNull()
        val errorMessage = message ?: "迈金拒绝 FIT 上传（业务码 $responseCode）"
        return when {
            numericCode == 401 || numericCode == 403 ||
                errorMessage.contains("登录") || errorMessage.contains("token", ignoreCase = true) ->
                ConnectorResult.RequiresLogin(loginUrl, errorMessage)
            numericCode == 408 || numericCode == 429 || numericCode != null && numericCode >= 500 ->
                ConnectorResult.RetryableFailure("ONELAP_$responseCode", errorMessage)
            else -> ConnectorResult.PermanentFailure("ONELAP_$responseCode", errorMessage)
        }
    }
    if (root.has("success") && !root.optBoolean("success", true)) {
        return ConnectorResult.PermanentFailure(
            "ONELAP_UPLOAD_REJECTED",
            message ?: "迈金拒绝 FIT 上传",
        )
    }
    val remoteId = JsonPlatformParser.firstString(root, "id", "record_id", "ride_record_id", "jilu_id")
    return remoteId?.let { ConnectorResult.Success(RemoteCopy(it)) }
        ?: ConnectorResult.Processing(
            encodeAsyncUploadConfirmationToken(Platform.ONELAP, provisionalRemoteId),
            ASYNC_UPLOAD_CONFIRM_POLL_MS,
        )
}

internal fun xingzheUploadResult(
    httpCode: Int,
    responseBody: String,
    provisionalRemoteId: String,
    loginUrl: String,
    uploadUrl: String,
    baseUrl: String = "https://www.imxingzhe.com",
): ConnectorResult<RemoteCopy> {
    val json = runCatching { JSONObject(responseBody) }.getOrDefault(JSONObject())
    val code = json.optInt("code", httpCode)
    if (code == 9006) {
        val id = Regex("\\d+").find(json.optString("msg"))?.value
        return ConnectorResult.Duplicate(
            id?.let { RemoteCopy(it, "$baseUrl/workouts/$it", duplicate = true) },
        )
    }
    if (httpCode !in 200..299 || code != 0) {
        if (httpCode == 401 || httpCode == 403 ||
            json.optString("detail").contains("Authentication", ignoreCase = true)
        ) return ConnectorResult.RequiresLogin(loginUrl)
        if (httpCode == 408 || httpCode == 429 || httpCode >= 500) {
            return ConnectorResult.RetryableFailure(code.toString(), "行者上传暂时失败")
        }
        return ConnectorResult.RequiresUserAction(
            uploadUrl,
            "行者网页校验发生变化，请在网页中完成上传",
        )
    }
    val data = json.optJSONObject("data") ?: json
    val id = JsonPlatformParser.firstString(data, "workout_id", "id")
    return id?.let { ConnectorResult.Success(RemoteCopy(it, "$baseUrl/workouts/$it")) }
        ?: ConnectorResult.Processing(
            encodeAsyncUploadConfirmationToken(Platform.XINGZHE, provisionalRemoteId),
            ASYNC_UPLOAD_CONFIRM_POLL_MS,
        )
}

internal object JsonPlatformParser {
    fun activities(root: Any): List<RemoteActivitySummary>? {
        val array = findArray(root) ?: return null
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

private val JSON = "application/json; charset=utf-8".toMediaType()
private val FIT = "application/vnd.ant.fit".toMediaType()

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
