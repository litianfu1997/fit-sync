package com.fitsync.app.connectors

import com.fitsync.app.data.db.FitSyncDatabase
import com.fitsync.app.data.db.entity.CanonicalActivityEntity
import com.fitsync.app.data.file.FitFileStore
import com.fitsync.app.domain.model.ConnectorExecution
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.domain.model.SessionState
import com.fitsync.app.domain.model.SportType
import com.fitsync.app.strava.StravaOAuthService
import com.fitsync.app.web.WebSessionManager
import java.io.FilterInputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.time.Instant
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

class StravaConnector(
    private val db: FitSyncDatabase,
    private val fileStore: FitFileStore,
    private val sessions: WebSessionManager,
    private val oauth: StravaOAuthService,
    private val client: OkHttpClient,
) : PlatformConnector {
    override val platform: Platform = Platform.STRAVA

    override suspend fun validateSession(): SessionState {
        if (!oauth.hasOAuthSession()) return SessionState.NOT_CONNECTED
        return when (val response = executeApi { token ->
            Request.Builder().url("$API_BASE/athlete").header("Authorization", "Bearer $token").build()
        }) {
            is ApiResponse.Success -> response.response.use { SessionState.AUTHENTICATED }
            ApiResponse.Unauthorized -> SessionState.EXPIRED
            is ApiResponse.Failure -> SessionState.NEEDS_USER_ACTION
        }
    }

    override suspend fun detectCapabilities(): ConnectorCapabilities {
        if (!oauth.hasOAuthSession()) {
            return ConnectorCapabilities(shareImportSupported = true, experimental = true)
        }
        return ConnectorCapabilities(
            listActivitiesMode = ConnectorExecution.BACKGROUND,
            downloadFitMode = if (oauth.hasWebSession()) {
                ConnectorExecution.BACKGROUND
            } else {
                ConnectorExecution.MANUAL
            },
            uploadFitMode = ConnectorExecution.BACKGROUND,
            supportsIncrementalCursor = true,
            supportsBulkHistory = true,
            supportsIdempotencyKey = true,
            shareImportSupported = true,
            experimental = true,
        )
    }

    override suspend fun listActivities(
        sinceEpochMs: Long,
        cursor: SyncCursor?,
    ): ConnectorResult<ActivityPage> {
        val cursorParts = cursor?.raw?.split(':').orEmpty()
        val page = cursorParts.getOrNull(0)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val beforeEpochSeconds = cursorParts.getOrNull(1)?.toLongOrNull()
            ?: System.currentTimeMillis() / 1000L
        val url = "$API_BASE/athlete/activities".toHttpUrl().newBuilder()
            .addQueryParameter("after", (sinceEpochMs / 1000L).toString())
            .addQueryParameter("before", beforeEpochSeconds.toString())
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", PAGE_SIZE.toString())
            .build()
        return when (val api = executeApi { token ->
            Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        }) {
            is ApiResponse.Success -> api.response.use { response ->
                val text = response.body?.string().orEmpty()
                val array = runCatching { JSONArray(text) }.getOrNull()
                    ?: return@use ConnectorResult.PermanentFailure("INVALID_RESPONSE", "Strava 活动列表响应无法解析")
                val items = buildList {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        val id = item.optLong("id").takeIf { it > 0 }?.toString() ?: continue
                        add(
                            RemoteActivitySummary(
                                remoteActivityId = id,
                                startTimeEpochMs = item.optString("start_date").takeIf(String::isNotBlank)
                                    ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
                                name = item.optString("name").takeIf(String::isNotBlank),
                                sportType = item.optString("sport_type").ifBlank {
                                    item.optString("type")
                                }.takeIf(String::isNotBlank),
                                distanceMeters = item.optDouble("distance").takeUnless(Double::isNaN),
                                metadata = buildMap {
                                    put("remote_url", "$WEB_BASE/activities/$id")
                                    item.optString("external_id").takeIf(String::isNotBlank)
                                        ?.let { put("external_id", it) }
                                    item.optLong("upload_id").takeIf { it > 0 }
                                        ?.let { put("upload_id", it.toString()) }
                                },
                            ),
                        )
                    }
                }
                ConnectorResult.Success(
                    ActivityPage(
                        items = items,
                        nextCursor = if (array.length() == PAGE_SIZE) {
                            SyncCursor("${page + 1}:$beforeEpochSeconds")
                        } else {
                            null
                        },
                        hasMore = array.length() == PAGE_SIZE,
                    ),
                )
            }
            ApiResponse.Unauthorized -> ConnectorResult.RequiresLogin(OAUTH_URL, "Strava API 授权已失效")
            is ApiResponse.Failure -> api.toConnectorFailure("读取 Strava 活动失败")
        }
    }

    override suspend fun downloadFit(activity: RemoteActivitySummary): ConnectorResult<FitFile> {
        if (!oauth.hasWebSession()) {
            return ConnectorResult.RequiresUserAction(
                "https://www.strava.com/activities/${activity.remoteActivityId}",
                "请登录 Strava 网页后导出原始活动文件",
            )
        }
        var url = "https://www.strava.com/activities/${activity.remoteActivityId}/export_original"
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val parsed = runCatching { url.toHttpUrl() }.getOrNull()
                ?: return ConnectorResult.PermanentFailure("INVALID_URL", "Strava 原文件地址无效")
            if (parsed.scheme != "https") {
                return ConnectorResult.PermanentFailure("INSECURE_REDIRECT", "Strava 原文件发生非 HTTPS 跳转")
            }
            val builder = Request.Builder()
                .url(parsed)
                .header("Accept-Encoding", "identity")
                .header("Accept", "application/octet-stream,*/*")
            if (parsed.host == "www.strava.com") {
                sessions.cookieHeader(platform, parsed.toString())?.let { builder.header("Cookie", it) }
            }
            val response = try {
                withContext(Dispatchers.IO) { client.newCall(builder.build()).execute() }
            } catch (_: Exception) {
                return ConnectorResult.RetryableFailure("NETWORK", "下载 Strava 原文件失败，请稍后重试")
            }
            response.use {
                if (it.isRedirect) {
                    val location = it.header("Location")
                        ?: return ConnectorResult.PermanentFailure("INVALID_REDIRECT", "Strava 原文件跳转缺少地址")
                    val next = parsed.resolve(location)
                        ?: return ConnectorResult.PermanentFailure("INVALID_REDIRECT", "Strava 原文件跳转地址无效")
                    if (next.scheme != "https") {
                        return ConnectorResult.PermanentFailure("INSECURE_REDIRECT", "Strava 原文件发生非 HTTPS 跳转")
                    }
                    if (next.encodedPath.contains("login", ignoreCase = true)) {
                        return ConnectorResult.RequiresUserAction(
                            "https://www.strava.com/login",
                            "Strava 网页会话已失效，请重新登录",
                        )
                    }
                    if (redirectCount >= MAX_REDIRECTS) {
                        return ConnectorResult.PermanentFailure("TOO_MANY_REDIRECTS", "Strava 原文件跳转次数过多")
                    }
                    url = next.toString()
                    return@repeat
                }
                when {
                    it.code == 401 || it.code == 403 -> return ConnectorResult.RequiresUserAction(
                        "https://www.strava.com/login",
                        "Strava 网页会话已失效，请重新登录",
                    )
                    it.code == 404 || it.code == 410 -> return ConnectorResult.PermanentFailure(
                        "NO_ORIGINAL_FILE",
                        "该 Strava 活动没有可导出的原始文件",
                    )
                    it.code == 408 || it.code == 429 || it.code >= 500 -> return ConnectorResult.RetryableFailure(
                        it.code.toString(),
                        "Strava 原文件暂时无法下载",
                    )
                    !it.isSuccessful -> return ConnectorResult.PermanentFailure(
                        it.code.toString(),
                        "Strava 原文件下载失败（HTTP ${it.code}）",
                    )
                }
                val contentLength = it.body?.contentLength() ?: -1L
                if (contentLength > MAX_FILE_BYTES) {
                    return ConnectorResult.PermanentFailure("FILE_TOO_LARGE", "Strava 原文件超过 50 MB")
                }
                return importOriginalResponse(it, activity)
            }
        }
        return ConnectorResult.RetryableFailure("REDIRECT", "Strava 原文件下载未完成")
    }

    override suspend fun uploadFit(activity: CanonicalActivityEntity): ConnectorResult<RemoteCopy> {
        val variants = db.activityVariantDao().findByCanonical(activity.id)
        val variant = activity.primaryVariantId?.let { id -> variants.firstOrNull { it.id == id } }
            ?: variants.maxByOrNull { it.qualityScore ?: 0 }
            ?: return ConnectorResult.PermanentFailure("NO_FILE", "没有可上传的 FIT 副本")
        val file = java.io.File(variant.localFilePath)
        if (!file.isFile) return ConnectorResult.PermanentFailure("NO_FILE", "原始 FIT 已被清理，无法上传")
        val sha256 = FitFileStore.sha256Of(file)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody(FIT_MEDIA_TYPE))
            .addFormDataPart("data_type", "fit")
            .addFormDataPart("external_id", "fitsync-$sha256.fit")
            .apply {
                activity.title?.takeIf(String::isNotBlank)?.let { addFormDataPart("name", it) }
                if (activity.sportType == SportType.INDOOR_CYCLING) addFormDataPart("trainer", "true")
            }
            .build()
        return when (val api = executeApi { token ->
            Request.Builder().url("$API_BASE/uploads").header("Authorization", "Bearer $token")
                .post(body).build()
        }) {
            is ApiResponse.Success -> api.response.use { parseUploadResponse(it) }
            ApiResponse.Unauthorized -> ConnectorResult.RequiresLogin(OAUTH_URL, "Strava API 授权已失效")
            is ApiResponse.Failure -> api.toConnectorFailure("上传到 Strava 失败")
        }
    }

    override suspend fun checkUpload(remoteUploadId: String): ConnectorResult<RemoteCopy> =
        when (val api = executeApi { token ->
            Request.Builder().url("$API_BASE/uploads/$remoteUploadId")
                .header("Authorization", "Bearer $token").build()
        }) {
            is ApiResponse.Success -> api.response.use { parseUploadResponse(it, remoteUploadId) }
            ApiResponse.Unauthorized -> ConnectorResult.RequiresLogin(OAUTH_URL, "Strava API 授权已失效")
            is ApiResponse.Failure -> api.toConnectorFailure("查询 Strava 上传状态失败")
        }

    private suspend fun importOriginalResponse(
        response: Response,
        activity: RemoteActivitySummary,
    ): ConnectorResult<FitFile> {
        val raw = response.body?.byteStream()
            ?: return ConnectorResult.PermanentFailure("EMPTY_FILE", "Strava 没有返回原文件内容")
        val limited = LimitedInputStream(raw, MAX_FILE_BYTES)
        val input = PushbackInputStream(limited, MAGIC_BYTES)
        val prefix = ByteArray(MAGIC_BYTES)
        val count = input.read(prefix)
        if (count > 0) input.unread(prefix, 0, count)
        val disposition = response.header("Content-Disposition").orEmpty().lowercase(Locale.ROOT)
        val hasFitMagic = count >= 12 && String(prefix, 8, 4, Charsets.US_ASCII) == ".FIT"
        val isGzip = count >= 2 && prefix[0] == 0x1f.toByte() && prefix[1] == 0x8b.toByte() ||
            !hasFitMagic && (disposition.contains(".fit.gz") || disposition.contains(".fit.gzip"))
        val displayName = "strava_${activity.remoteActivityId}.fit"
        return try {
            val stored = if (isGzip) {
                GZIPInputStream(input).use { gzip -> fileStore.storeFromStream(gzip, displayName, MAX_FILE_BYTES) }
            } else {
                if (looksLikeHtmlOrJson(prefix, count)) {
                    return ConnectorResult.RequiresUserAction(
                        "https://www.strava.com/login",
                        "Strava 返回了登录页，请重新登录网页",
                    )
                }
                if (!hasFitMagic &&
                    (looksLikeXml(prefix, count) || disposition.contains(".tcx") || disposition.contains(".gpx"))
                ) {
                    return ConnectorResult.PermanentFailure(
                        "UNSUPPORTED_ORIGINAL_FORMAT",
                        "该活动的原始文件不是 FIT，首版暂不自动导入",
                    )
                }
                input.use { fileStore.storeFromStream(it, displayName, MAX_FILE_BYTES) }
            }
            ConnectorResult.Success(FitFile(stored.file, stored.sha256, stored.displayName))
        } catch (tooLarge: SizeLimitExceededException) {
            ConnectorResult.PermanentFailure("FILE_TOO_LARGE", "Strava 原文件解压前后不能超过 50 MB")
        } catch (error: IllegalArgumentException) {
            if (error.message?.contains("大小限制") == true) {
                ConnectorResult.PermanentFailure("FILE_TOO_LARGE", "Strava 原文件解压前后不能超过 50 MB")
            } else {
                ConnectorResult.PermanentFailure("UNSUPPORTED_ORIGINAL_FORMAT", "Strava 原文件不是有效的 FIT")
            }
        } catch (_: Exception) {
            ConnectorResult.PermanentFailure("INVALID_ORIGINAL_FILE", "Strava 原文件损坏或无法解压")
        }
    }

    private fun parseUploadResponse(
        response: Response,
        fallbackUploadId: String? = null,
    ): ConnectorResult<RemoteCopy> {
        val text = response.body?.string().orEmpty()
        val root = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
        val error = root.optString("error")
        val status = root.optString("status")
        if (error.contains("duplicate", ignoreCase = true) || status.contains("duplicate", ignoreCase = true)) {
            val activityId = root.optLong("activity_id").takeIf { it > 0 }?.toString()
            return ConnectorResult.Duplicate(
                activityId?.let { RemoteCopy(it, "$WEB_BASE/activities/$it", duplicate = true) },
            )
        }
        if (!response.isSuccessful) {
            return when {
                response.code == 403 -> ConnectorResult.RequiresLogin(OAUTH_URL, "Strava 缺少上传权限，请重新授权")
                response.code == 408 || response.code == 429 || response.code >= 500 ->
                    ConnectorResult.RetryableFailure(response.code.toString(), "Strava 上传暂时失败")
                else -> ConnectorResult.PermanentFailure(
                    response.code.toString(),
                    error.ifBlank { status.ifBlank { "Strava 拒绝了 FIT 文件" } },
                )
            }
        }
        val activityId = root.optLong("activity_id").takeIf { it > 0 }?.toString()
        if (activityId != null) return ConnectorResult.Success(RemoteCopy(activityId, "$WEB_BASE/activities/$activityId"))
        if (error.isNotBlank()) return ConnectorResult.PermanentFailure("UPLOAD_FAILED", error)
        val uploadId = root.optLong("id").takeIf { it > 0 }?.toString() ?: fallbackUploadId
        return if (uploadId != null) {
            ConnectorResult.Processing(uploadId, POLL_AFTER_MS)
        } else {
            ConnectorResult.PermanentFailure("INVALID_RESPONSE", "Strava 未返回 upload ID")
        }
    }

    private suspend fun executeApi(build: (String) -> Request): ApiResponse {
        val initialToken = oauth.accessToken() ?: return ApiResponse.Unauthorized
        val first = execute(build(initialToken)) ?: return ApiResponse.Failure(null, "NETWORK")
        if (first.code != 401) return classify(first)
        first.close()
        val refreshed = oauth.accessToken(failedToken = initialToken) ?: return ApiResponse.Unauthorized
        return classify(execute(build(refreshed)) ?: return ApiResponse.Failure(null, "NETWORK"))
    }

    private suspend fun execute(request: Request): Response? = withContext(Dispatchers.IO) {
        runCatching { client.newCall(request).execute() }.getOrNull()
    }

    private fun classify(response: Response): ApiResponse = when {
        response.isSuccessful -> ApiResponse.Success(response)
        response.code == 401 -> response.use { ApiResponse.Unauthorized }
        else -> {
            val code = response.code
            response.close()
            ApiResponse.Failure(code, code.toString())
        }
    }

    private sealed interface ApiResponse {
        data class Success(val response: Response) : ApiResponse
        data object Unauthorized : ApiResponse
        data class Failure(val httpCode: Int?, val code: String?) : ApiResponse
    }

    private fun ApiResponse.Failure.toConnectorFailure(message: String): ConnectorResult<Nothing> = when {
        httpCode == 403 -> ConnectorResult.RequiresLogin(OAUTH_URL, "Strava 权限不足，请重新授权")
        httpCode == 408 || httpCode == 429 || httpCode != null && httpCode >= 500 || httpCode == null ->
            ConnectorResult.RetryableFailure(code, message)
        else -> ConnectorResult.PermanentFailure(code, "$message（HTTP $httpCode）")
    }

    private class LimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int {
            val value = super.read()
            if (value >= 0) add(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) add(read.toLong())
            return read
        }

        private fun add(bytes: Long) {
            count += bytes
            if (count > limit) throw SizeLimitExceededException()
        }
    }

    private class SizeLimitExceededException : RuntimeException()

    companion object {
        const val API_BASE = "https://www.strava.com/api/v3"
        private const val WEB_BASE = "https://www.strava.com"
        private const val OAUTH_URL = "https://www.strava.com/oauth/mobile/authorize"
        private const val PAGE_SIZE = 100
        private const val MAX_REDIRECTS = 3
        private const val MAX_FILE_BYTES = 50L * 1024 * 1024
        private const val POLL_AFTER_MS = 2_000L
        private const val MAGIC_BYTES = 64
        private val FIT_MEDIA_TYPE = "application/vnd.ant.fit".toMediaType()

        private fun looksLikeXml(bytes: ByteArray, length: Int): Boolean =
            String(bytes, 0, length.coerceAtLeast(0), Charsets.UTF_8).trimStart().startsWith("<")

        private fun looksLikeHtmlOrJson(bytes: ByteArray, length: Int): Boolean {
            val text = String(bytes, 0, length.coerceAtLeast(0), Charsets.UTF_8).trimStart().lowercase()
            return text.startsWith("<!doctype html") || text.startsWith("<html") ||
                text.startsWith("{") || text.startsWith("[")
        }
    }
}
