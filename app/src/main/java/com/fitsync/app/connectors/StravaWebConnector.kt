package com.fitsync.app.connectors

import com.fitsync.app.data.db.entity.ActivityVariantEntity
import com.fitsync.app.data.db.entity.CanonicalActivityEntity
import com.fitsync.app.data.file.FitFileStore
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.domain.model.SessionState
import com.fitsync.app.web.PlatformWebConfigs
import com.fitsync.app.web.WebSessionManager
import java.io.FilterInputStream
import java.io.File
import java.io.InputStream
import java.io.PushbackInputStream
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
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

/**
 * 复用 WebView 中的 Strava 登录 Cookie，按官网上传页自身的请求链路自动上传 FIT。
 *
 * 这里不使用 Strava OAuth 或开发者 API：先从 /upload/select 读取 Rails CSRF token，再将
 * files[] 提交到 /upload/files，最后轮询 /upload/progress.json，直到拿到真实 activity id。
 */
class StravaWebConnector(
    private val sessions: WebSessionManager,
    private val fileStore: FitFileStore,
    client: OkHttpClient,
) : PlatformConnector {
    override val platform: Platform = Platform.STRAVA
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    @Volatile
    private var cachedCsrfToken: String? = null

    override suspend fun validateSession(): SessionState {
        if (!sessions.hasStravaWebSession()) return SessionState.NOT_CONNECTED
        return when (loadUploadPage()) {
            is UploadPageResult.Ready -> SessionState.AUTHENTICATED
            UploadPageResult.LoginRequired -> SessionState.EXPIRED
            is UploadPageResult.Failed -> SessionState.NEEDS_USER_ACTION
        }
    }

    override suspend fun detectCapabilities(): ConnectorCapabilities =
        PlatformCapabilityRegistry.capabilities(platform)

    override suspend fun listActivities(
        sinceEpochMs: Long,
        cursor: SyncCursor?,
    ): ConnectorResult<ActivityPage> {
        if (!sessions.hasStravaWebSession()) {
            return ConnectorResult.RequiresLogin(LOGIN_URL, "请先登录 Strava 网页")
        }
        val csrfToken = when (val page = csrfForWebRequest()) {
            is UploadPageResult.Ready -> page.csrfToken
            UploadPageResult.LoginRequired -> return ConnectorResult.RequiresLogin(
                LOGIN_URL,
                "Strava 网页登录已失效，请重新登录",
            )
            is UploadPageResult.Failed -> return page.failure
        }
        val page = cursor?.raw?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val url = TRAINING_ACTIVITIES_URL.toHttpUrl().newBuilder()
            .addQueryParameter("keywords", "")
            .addQueryParameter("sport_type", "")
            .addQueryParameter("tags", "")
            .addQueryParameter("commute", "")
            .addQueryParameter("private_activities", "")
            .addQueryParameter("trainer", "")
            .addQueryParameter("gear", "")
            .addQueryParameter("new_activity_only", "false")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", ACTIVITIES_PER_PAGE.toString())
            .build()
        val request = authenticatedRequest(url.toString())
            .header("Accept", "text/javascript, application/javascript, application/ecmascript")
            .header("Referer", TRAINING_PAGE_URL)
            .header("X-CSRF-Token", csrfToken)
            .header("X-Requested-With", "XMLHttpRequest")
            .get()
            .build()
        val response = try {
            execute(request)
        } catch (_: Exception) {
            return ConnectorResult.RetryableFailure("NETWORK", "读取 Strava 网页活动失败")
        }
        response.use {
            sessions.captureResponseCookies(platform, request.url.toString(), it.headers("Set-Cookie"))
            if (it.isLoginRedirect()) {
                return ConnectorResult.RequiresLogin(LOGIN_URL, "Strava 网页登录已失效，请重新登录")
            }
            val text = it.body?.string().orEmpty()
            if (it.code == 401 || it.code == 403 || looksLikeLoginPage(text)) {
                return ConnectorResult.RequiresLogin(LOGIN_URL, "Strava 网页登录已失效，请重新登录")
            }
            if (it.code == 408 || it.code == 429 || it.code >= 500) {
                return ConnectorResult.RetryableFailure(it.code.toString(), "Strava 网页活动暂时不可用")
            }
            if (!it.isSuccessful) {
                return ConnectorResult.PermanentFailure(
                    it.code.toString(),
                    "读取 Strava 网页活动失败（HTTP ${it.code}）",
                )
            }
            return parseStravaTrainingActivities(text, sinceEpochMs, page)
        }
    }

    override suspend fun downloadFit(activity: RemoteActivitySummary): ConnectorResult<FitFile> {
        if (!sessions.hasStravaWebSession()) {
            return ConnectorResult.RequiresLogin(LOGIN_URL, "Strava 网页登录已失效，请重新登录")
        }
        var url = "$WEB_BASE/activities/${activity.remoteActivityId}/export_original"
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val parsed = runCatching { url.toHttpUrl() }.getOrNull()
                ?: return ConnectorResult.PermanentFailure("INVALID_URL", "Strava 原文件地址无效")
            if (!parsed.isHttps) {
                return ConnectorResult.PermanentFailure("INSECURE_REDIRECT", "Strava 原文件发生非 HTTPS 跳转")
            }
            val builder = Request.Builder()
                .url(parsed)
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("Accept-Encoding", "identity")
                .header("Accept", "application/octet-stream,*/*")
                .header("Referer", "$WEB_BASE/activities/${activity.remoteActivityId}")
            if (parsed.host == "www.strava.com") {
                sessions.cookieHeader(platform, parsed.toString())?.let { builder.header("Cookie", it) }
            }
            val response = try {
                execute(builder.build())
            } catch (_: Exception) {
                return ConnectorResult.RetryableFailure("NETWORK", "下载 Strava 原文件失败，请稍后重试")
            }
            response.use {
                sessions.captureResponseCookies(platform, parsed.toString(), it.headers("Set-Cookie"))
                if (it.isRedirect) {
                    val location = it.header("Location")
                        ?: return ConnectorResult.PermanentFailure("INVALID_REDIRECT", "Strava 原文件跳转缺少地址")
                    val next = parsed.resolve(location)
                        ?: return ConnectorResult.PermanentFailure("INVALID_REDIRECT", "Strava 原文件跳转地址无效")
                    if (!next.isHttps) {
                        return ConnectorResult.PermanentFailure("INSECURE_REDIRECT", "Strava 原文件发生非 HTTPS 跳转")
                    }
                    if (next.encodedPath.contains("login", ignoreCase = true)) {
                        return ConnectorResult.RequiresLogin(LOGIN_URL, "Strava 网页登录已失效，请重新登录")
                    }
                    if (redirectCount >= MAX_REDIRECTS) {
                        return ConnectorResult.PermanentFailure("TOO_MANY_REDIRECTS", "Strava 原文件跳转次数过多")
                    }
                    url = next.toString()
                    return@repeat
                }
                when {
                    it.code == 401 || it.code == 403 -> return ConnectorResult.RequiresLogin(
                        LOGIN_URL,
                        "Strava 网页登录已失效，请重新登录",
                    )
                    it.code == 404 || it.code == 410 -> return ConnectorResult.PermanentFailure(
                        "NO_ORIGINAL_FILE",
                        "该 Strava 活动没有可导出的原始文件",
                    )
                    it.code == 408 || it.code == 429 || it.code >= 500 ->
                        return ConnectorResult.RetryableFailure(it.code.toString(), "Strava 原文件暂时无法下载")
                    !it.isSuccessful -> return ConnectorResult.PermanentFailure(
                        it.code.toString(),
                        "Strava 原文件下载失败（HTTP ${it.code}）",
                    )
                }
                val contentLength = it.body?.contentLength() ?: -1L
                if (contentLength > MAX_DOWNLOAD_BYTES) {
                    return ConnectorResult.PermanentFailure("FILE_TOO_LARGE", "Strava 原文件超过 50 MB")
                }
                return importOriginalResponse(it, activity)
            }
        }
        return ConnectorResult.RetryableFailure("REDIRECT", "Strava 原文件下载未完成")
    }

    override suspend fun uploadFit(
        activity: CanonicalActivityEntity,
        sourceVariant: ActivityVariantEntity,
    ): ConnectorResult<RemoteCopy> {
        val file = File(sourceVariant.localFilePath)
        when {
            !file.isFile -> return ConnectorResult.PermanentFailure(
                "NO_FILE",
                "原始 FIT 已被清理，无法上传到 Strava",
            )
            file.length() <= 0L -> return ConnectorResult.PermanentFailure(
                "FILE_EMPTY",
                "本地 FIT 文件为空",
            )
            file.length() > MAX_FILE_BYTES -> return ConnectorResult.PermanentFailure(
                "FILE_TOO_LARGE",
                "Strava 网页要求单个 FIT 文件不超过 25 MB",
            )
        }
        if (!sessions.hasStravaWebSession()) {
            return ConnectorResult.RequiresLogin(LOGIN_URL, "请先登录 Strava 网页")
        }
        val csrfToken = when (val page = loadUploadPage()) {
            is UploadPageResult.Ready -> page.csrfToken
            UploadPageResult.LoginRequired -> return ConnectorResult.RequiresLogin(
                LOGIN_URL,
                "Strava 网页登录已失效，请重新登录",
            )
            is UploadPageResult.Failed -> return page.failure
        }
        val uploadName = "fitsync_${sourceVariant.fileSha256.take(24)}.fit"
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("_method", "post")
            .addFormDataPart("authenticity_token", csrfToken)
            .addFormDataPart("files[]", uploadName, file.asRequestBody(FIT_MEDIA_TYPE))
            .build()
        val request = authenticatedRequest(UPLOAD_FILES_URL)
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Origin", WEB_BASE)
            .header("Referer", UPLOAD_PAGE_URL)
            .header("X-CSRF-Token", csrfToken)
            .header("X-Requested-With", "XMLHttpRequest")
            .post(multipart)
            .build()
        return executeWebRequest(request, "上传到 Strava 失败") { responseText ->
            parseStravaWebUploadResponse(responseText)
        }
    }

    override suspend fun checkUpload(remoteUploadId: String): ConnectorResult<RemoteCopy> {
        if (!remoteUploadId.matches(POSITIVE_ID)) {
            return ConnectorResult.PermanentFailure("INVALID_UPLOAD_ID", "Strava 上传任务 ID 无效")
        }
        if (!sessions.hasStravaWebSession()) {
            return ConnectorResult.RequiresLogin(LOGIN_URL, "Strava 网页登录已失效，请重新登录")
        }
        val url = PROGRESS_URL.toHttpUrl().newBuilder()
            .addQueryParameter("ids[]", remoteUploadId)
            .build()
        val request = authenticatedRequest(url.toString())
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Referer", UPLOAD_PAGE_URL)
            .header("X-Requested-With", "XMLHttpRequest")
            .get()
            .build()
        return executeWebRequest(request, "查询 Strava 网页上传状态失败") { responseText ->
            parseStravaWebUploadResponse(responseText, remoteUploadId)
        }
    }

    private suspend fun csrfForWebRequest(): UploadPageResult =
        cachedCsrfToken?.let(UploadPageResult::Ready) ?: loadUploadPage()

    private suspend fun loadUploadPage(): UploadPageResult {
        val request = authenticatedRequest(UPLOAD_PAGE_URL)
            .header("Accept", "text/html,application/xhtml+xml")
            .get()
            .build()
        val response = try {
            execute(request)
        } catch (_: Exception) {
            return UploadPageResult.Failed(
                ConnectorResult.RetryableFailure("NETWORK", "无法打开 Strava 上传页"),
            )
        }
        response.use {
            sessions.captureResponseCookies(platform, request.url.toString(), it.headers("Set-Cookie"))
            if (it.isLoginRedirect()) return UploadPageResult.LoginRequired
            if (it.code == 401 || it.code == 403) return UploadPageResult.LoginRequired
            if (it.code == 408 || it.code == 429 || it.code >= 500) {
                return UploadPageResult.Failed(
                    ConnectorResult.RetryableFailure(it.code.toString(), "Strava 上传页暂时不可用"),
                )
            }
            if (!it.isSuccessful) {
                return UploadPageResult.Failed(
                    ConnectorResult.PermanentFailure(
                        it.code.toString(),
                        "无法打开 Strava 上传页（HTTP ${it.code}）",
                    ),
                )
            }
            val html = it.body?.string().orEmpty()
            val token = extractStravaAuthenticityToken(html)
            if (token != null) {
                cachedCsrfToken = token
                return UploadPageResult.Ready(token)
            }
            if (looksLikeLoginPage(html)) return UploadPageResult.LoginRequired
            return UploadPageResult.Failed(
                ConnectorResult.PermanentFailure(
                    "STRAVA_PAGE_CHANGED",
                    "Strava 上传页结构已变化，暂时无法自动上传",
                ),
            )
        }
    }

    private suspend fun importOriginalResponse(
        response: Response,
        activity: RemoteActivitySummary,
    ): ConnectorResult<FitFile> {
        val raw = response.body?.byteStream()
            ?: return ConnectorResult.PermanentFailure("EMPTY_FILE", "Strava 没有返回原文件内容")
        val limited = LimitedInputStream(raw, MAX_DOWNLOAD_BYTES)
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
                GZIPInputStream(input).use { gzip ->
                    fileStore.storeFromStream(gzip, displayName, MAX_DOWNLOAD_BYTES)
                }
            } else {
                if (looksLikeHtmlOrJson(prefix, count)) {
                    return ConnectorResult.RequiresLogin(LOGIN_URL, "Strava 返回了登录页，请重新登录网页")
                }
                if (!hasFitMagic &&
                    (looksLikeXml(prefix, count) || disposition.contains(".tcx") || disposition.contains(".gpx"))
                ) {
                    return ConnectorResult.PermanentFailure(
                        "UNSUPPORTED_ORIGINAL_FORMAT",
                        "该活动的原始文件不是 FIT，暂不自动导入",
                    )
                }
                input.use { fileStore.storeFromStream(it, displayName, MAX_DOWNLOAD_BYTES) }
            }
            ConnectorResult.Success(FitFile(stored.file, stored.sha256, stored.displayName))
        } catch (_: SizeLimitExceededException) {
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

    private suspend fun executeWebRequest(
        request: Request,
        networkMessage: String,
        parse: (String) -> ConnectorResult<RemoteCopy>,
    ): ConnectorResult<RemoteCopy> {
        val response = try {
            execute(request)
        } catch (_: Exception) {
            return ConnectorResult.RetryableFailure("NETWORK", networkMessage)
        }
        response.use {
            sessions.captureResponseCookies(platform, request.url.toString(), it.headers("Set-Cookie"))
            if (it.isLoginRedirect()) {
                return ConnectorResult.RequiresLogin(LOGIN_URL, "Strava 网页登录已失效，请重新登录")
            }
            val text = it.body?.string().orEmpty()
            if (it.code == 401 || it.code == 403 || looksLikeLoginPage(text)) {
                return ConnectorResult.RequiresLogin(LOGIN_URL, "Strava 网页登录已失效，请重新登录")
            }
            if (it.code == 408 || it.code == 429 || it.code >= 500) {
                return ConnectorResult.RetryableFailure(it.code.toString(), "Strava 网页暂时不可用")
            }
            if (!it.isSuccessful) {
                return ConnectorResult.PermanentFailure(
                    it.code.toString(),
                    stravaWebErrorMessage(text) ?: "Strava 网页请求失败（HTTP ${it.code}）",
                )
            }
            return parse(text)
        }
    }

    private fun authenticatedRequest(url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("User-Agent", DESKTOP_USER_AGENT)
        .apply {
            sessions.cookieHeader(platform, url)?.let { header("Cookie", it) }
        }

    private suspend fun execute(request: Request): Response = withContext(Dispatchers.IO) {
        client.newCall(request).execute()
    }

    private fun Response.isLoginRedirect(): Boolean =
        isRedirect && header("Location").orEmpty().contains("/login", ignoreCase = true)

    private sealed interface UploadPageResult {
        data class Ready(val csrfToken: String) : UploadPageResult
        data object LoginRequired : UploadPageResult
        data class Failed(val failure: ConnectorResult<Nothing>) : UploadPageResult
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

    private companion object {
        const val WEB_BASE = "https://www.strava.com"
        const val LOGIN_URL = "$WEB_BASE/login"
        const val TRAINING_PAGE_URL = "$WEB_BASE/athlete/training"
        const val TRAINING_ACTIVITIES_URL = "$WEB_BASE/athlete/training_activities"
        const val UPLOAD_PAGE_URL = "$WEB_BASE/upload/select"
        const val UPLOAD_FILES_URL = "$WEB_BASE/upload/files"
        const val PROGRESS_URL = "$WEB_BASE/upload/progress.json"
        const val MAX_FILE_BYTES = 25L * 1024 * 1024
        const val MAX_DOWNLOAD_BYTES = 50L * 1024 * 1024
        const val MAX_REDIRECTS = 3
        const val MAGIC_BYTES = 64
        const val ACTIVITIES_PER_PAGE = 20
        val FIT_MEDIA_TYPE = "application/vnd.ant.fit".toMediaType()
        val POSITIVE_ID = Regex("[1-9]\\d*")
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        fun looksLikeXml(bytes: ByteArray, length: Int): Boolean =
            String(bytes, 0, length.coerceAtLeast(0), Charsets.UTF_8).trimStart().startsWith("<")

        fun looksLikeHtmlOrJson(bytes: ByteArray, length: Int): Boolean {
            val text = String(bytes, 0, length.coerceAtLeast(0), Charsets.UTF_8).trimStart().lowercase()
            return text.startsWith("<!doctype html") || text.startsWith("<html") ||
                text.startsWith("{") || text.startsWith("[")
        }
    }
}

internal fun parseStravaTrainingActivities(
    responseText: String,
    sinceEpochMs: Long,
    requestedPage: Int,
): ConnectorResult<ActivityPage> {
    val root = runCatching { JSONObject(responseText) }.getOrNull()
        ?: return ConnectorResult.PermanentFailure(
            "STRAVA_PAGE_CHANGED",
            "Strava 网页活动响应无法解析",
        )
    val models = root.optJSONArray("models")
        ?: return ConnectorResult.PermanentFailure(
            "STRAVA_PAGE_CHANGED",
            "Strava 网页活动响应缺少 models",
        )
    val page = root.optInt("page", requestedPage).coerceAtLeast(1)
    val perPage = root.optInt("perPage", 20).coerceAtLeast(1)
    val total = root.optInt("total", models.length()).coerceAtLeast(0)
    var reachedWindowEnd = false
    val items = buildList {
        for (index in 0 until models.length()) {
            val item = models.optJSONObject(index) ?: continue
            val id = positiveJsonId(item, "id_str") ?: positiveJsonId(item, "id") ?: continue
            val startEpochMs = stravaWebStartEpochMs(item)
            if (startEpochMs != null && startEpochMs < sinceEpochMs) {
                reachedWindowEnd = true
                continue
            }
            val activityUrl = item.cleanString("activity_url")
                .takeIf { it.startsWith("https://www.strava.com/activities/") }
                ?: "https://www.strava.com/activities/$id"
            add(
                RemoteActivitySummary(
                    remoteActivityId = id,
                    startTimeEpochMs = startEpochMs,
                    name = item.cleanString("name").takeIf(String::isNotBlank),
                    sportType = item.cleanString("sport_type").takeIf(String::isNotBlank),
                    distanceMeters = item.optDouble("distance_raw").takeUnless(Double::isNaN),
                    downloadUrl = "$activityUrl/export_original",
                    metadata = mapOf("remote_url" to activityUrl),
                ),
            )
        }
    }
    val hasMore = !reachedWindowEnd && models.length() > 0 && page * perPage < total
    return ConnectorResult.Success(
        ActivityPage(
            items = items,
            nextCursor = if (hasMore) SyncCursor((page + 1).toString()) else null,
            hasMore = hasMore,
        ),
    )
}

private fun stravaWebStartEpochMs(item: JSONObject): Long? =
    item.cleanString("start_time").takeIf(String::isNotBlank)?.let { raw ->
        runCatching { OffsetDateTime.parse(raw, STRAVA_WEB_TIME).toInstant().toEpochMilli() }.getOrNull()
    } ?: item.optLong("start_date_local_raw").takeIf { it > 0L }?.times(1000L)

internal fun extractStravaAuthenticityToken(html: String): String? {
    val inputToken = INPUT_TAG.findAll(html)
        .map { it.value }
        .firstOrNull { tag ->
            htmlAttribute(tag, "name").equals("authenticity_token", ignoreCase = true)
        }
        ?.let { htmlAttribute(it, "value") }
    val metaToken = META_TAG.findAll(html)
        .map { it.value }
        .firstOrNull { tag -> htmlAttribute(tag, "name").equals("csrf-token", ignoreCase = true) }
        ?.let { htmlAttribute(it, "content") }
    return (inputToken ?: metaToken)
        ?.let(::decodeHtmlEntities)
        ?.trim()
        ?.takeIf(String::isNotBlank)
}

internal fun parseStravaWebUploadResponse(
    responseText: String,
    fallbackUploadId: String? = null,
): ConnectorResult<RemoteCopy> {
    val item = runCatching {
        if (responseText.trimStart().startsWith("[")) {
            JSONArray(responseText).optJSONObject(0)
        } else {
            JSONObject(responseText)
        }
    }.getOrNull() ?: return ConnectorResult.PermanentFailure(
        "INVALID_RESPONSE",
        "Strava 网页上传响应无法解析",
    )

    val workflow = item.optString("workflow", "").lowercase()
    val error = item.cleanString("error")
    val duplicateId = stravaDuplicateActivityId(item, error)
    if (duplicateId != null || error.contains("duplicate", ignoreCase = true)) {
        return ConnectorResult.Duplicate(
            duplicateId?.let {
                RemoteCopy(it, "https://www.strava.com/activities/$it", duplicate = true)
            },
        )
    }
    if (workflow in STRAVA_FAILURE_WORKFLOWS || error.isNotBlank()) {
        val failureMessage = error.ifBlank { item.cleanString("message") }
        return ConnectorResult.PermanentFailure(
            "STRAVA_UPLOAD_FAILED",
            failureMessage.ifBlank { "Strava 无法处理该 FIT 文件（$workflow）" },
        )
    }

    val activityId = stravaWebActivityId(item)
    if (activityId != null) {
        return ConnectorResult.Success(
            RemoteCopy(activityId, "https://www.strava.com/activities/$activityId"),
        )
    }
    if (workflow == "success") {
        return ConnectorResult.PermanentFailure(
            "INVALID_RESPONSE",
            "Strava 已处理完成，但网页未返回活动 ID",
        )
    }
    val uploadId = positiveJsonId(item, "id") ?: fallbackUploadId?.takeIf { it.matches(POSITIVE_JSON_ID) }
    if (uploadId != null && workflow !in STRAVA_FAILURE_WORKFLOWS) {
        return ConnectorResult.Processing(uploadId, 1_000L)
    }
    return ConnectorResult.PermanentFailure(
        "INVALID_RESPONSE",
        "Strava 网页未返回上传任务 ID 或活动 ID",
    )
}

internal fun stravaWebActivityId(item: JSONObject): String? =
    item.optJSONObject("activity")?.let { positiveJsonId(it, "id") }
        ?: positiveJsonId(item, "activity_id")

private fun stravaDuplicateActivityId(item: JSONObject, error: String): String? {
    val url = item.optString("duplicate_url")
    return ACTIVITY_URL_ID.find(url)?.groupValues?.getOrNull(1)
        ?: ACTIVITY_MESSAGE_ID.find(error)?.groupValues?.getOrNull(1)
}

private fun stravaWebErrorMessage(text: String): String? {
    val item = runCatching {
        when {
            text.trimStart().startsWith("[") -> JSONArray(text).optJSONObject(0)
            text.trimStart().startsWith("{") -> JSONObject(text)
            else -> null
        }
    }.getOrNull() ?: return text.trim().takeIf { it.isNotBlank() && !it.contains("<html", true) }
    return sequenceOf("error", "message", "status")
        .map(item::cleanString)
        .firstOrNull(String::isNotBlank)
}

private fun JSONObject.cleanString(key: String): String =
    optString(key, "").trim().takeUnless { it.equals("null", ignoreCase = true) }.orEmpty()

private fun positiveJsonId(root: JSONObject, key: String): String? =
    root.optString(key).trim().takeIf { it.matches(POSITIVE_JSON_ID) }

private fun htmlAttribute(tag: String, name: String): String? {
    val pattern = Regex("(?is)\\b${Regex.escape(name)}\\s*=\\s*([\"'])(.*?)\\1")
    return pattern.find(tag)?.groupValues?.getOrNull(2)
}

private fun decodeHtmlEntities(value: String): String = HTML_ENTITY.replace(value) { match ->
    when (val entity = match.groupValues[1]) {
        "amp" -> "&"
        "quot" -> "\""
        "apos", "#39" -> "'"
        "lt" -> "<"
        "gt" -> ">"
        else -> when {
            entity.startsWith("#x", ignoreCase = true) ->
                entity.drop(2).toIntOrNull(16)?.let(::codePointToString)
            entity.startsWith("#") -> entity.drop(1).toIntOrNull()?.let(::codePointToString)
            else -> null
        } ?: match.value
    }
}

private fun codePointToString(codePoint: Int): String = runCatching {
    String(Character.toChars(codePoint))
}.getOrDefault("")

private fun looksLikeLoginPage(html: String): Boolean {
    val normalized = html.lowercase()
    return normalized.contains("/login") &&
        (normalized.contains("name=\"email\"") || normalized.contains("name='email'"))
}

private val INPUT_TAG = Regex("(?is)<input\\b[^>]*>")
private val META_TAG = Regex("(?is)<meta\\b[^>]*>")
private val HTML_ENTITY = Regex("&(#x?[0-9a-fA-F]+|amp|quot|apos|lt|gt|#39);")
private val POSITIVE_JSON_ID = Regex("[1-9]\\d*")
private val ACTIVITY_URL_ID = Regex("/activities/([1-9]\\d*)", RegexOption.IGNORE_CASE)
private val ACTIVITY_MESSAGE_ID =
    Regex("(?:activity\\s+|activities/)([1-9]\\d*)", RegexOption.IGNORE_CASE)
private val STRAVA_WEB_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")
private val STRAVA_FAILURE_WORKFLOWS = setOf(
    "error",
    "empty",
    "not_available",
    "deleted_activity",
)
