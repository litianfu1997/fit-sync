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
    ): ConnectorResult<ActivityPage> = withRelogin("LIST") {
        val cookie = sessionCookie("LIST") ?: return@withRelogin cookieMissingResult("LIST")
        val csrf = sessionManager.garminCsrf()
        val start = cursor?.raw?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val url = "$GARMIN_GC_API_BASE/activitylist-service/activities/search/activities" +
            "?start=$start&limit=$LIST_PAGE_SIZE"
        runCatching {
            execute(buildGcApiRequest(url, cookie, csrf, null)).use { resp ->
                when {
                    isGarminLoginResponse(resp.code, resp.header("Location")) -> requiresLogin("LIST")
                    !resp.isSuccessful -> failureStatus(resp, "LIST")
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

    override suspend fun downloadFit(activity: RemoteActivitySummary): ConnectorResult<FitFile> = withRelogin("DOWNLOAD") {
        val cookie = sessionCookie("DOWNLOAD") ?: return@withRelogin cookieMissingResult("DOWNLOAD")
        val csrf = sessionManager.garminCsrf()
        val url = "$GARMIN_GC_API_BASE/download-service/files/activity/${activity.remoteActivityId}/fit"
        runCatching {
            execute(buildGcApiRequest(url, cookie, csrf, null)).use { resp ->
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
        val uploadName = if (file.name.endsWith(".fit", ignoreCase = true)) file.name else "${file.name}.fit"
        val multipart = MultipartBody.Builder()
            .addFormDataPart("file", uploadName, file.asRequestBody(OCTET_STREAM))
            .build()
        return runCatching { withRelogin("UPLOAD") { postUpload(multipart) } }
            .getOrElse { ConnectorResult.RetryableFailure(null, it.message ?: "Garmin 上传请求失败") }
    }

    private suspend fun postUpload(multipart: MultipartBody): ConnectorResult<RemoteCopy> {
        val cookie = sessionCookie("UPLOAD") ?: return cookieMissingResult("UPLOAD")
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
            if (authExpired) return requiresLogin("POLL")
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
     * 包裹业务调用：收到 RequiresLogin（JWT_WEB 过期/被拒）时，先用 SSO 长会话静默换发新 JWT_WEB，
     * 成功就重试一次原业务；失败则原样上报 RequiresLogin（零回归，等价阶段一行为）。
     * 照抄 IgpsportApiConnector.withRelogin 骨架——Garmin 没有「账号密码」重登，SSO cookie 即等价的
     * 「持久化重登凭据」（用户登录一次，CASTGC 长期有效）。
     */
    private suspend fun <T> withRelogin(
        operation: String,
        block: suspend () -> ConnectorResult<T>,
    ): ConnectorResult<T> {
        val first = block()
        if (first !is ConnectorResult.RequiresLogin) return first
        if (refreshJwtWebViaSso(operation) == SessionState.AUTHENTICATED) {
            return block()
        }
        return first
    }

    /**
     * 用 SSO 长会话（sso.garmin.cn 的 CASTGC 等）走 CAS 重定向链换发新 JWT_WEB。手动逐跳
     * （client 是 followRedirects(false)），每跳按 host 拼对应域 cookie；诊断拦截器会把每跳的
     * code/Location/Set-Cookie 记成 RESPONSE(operation=SSO)，这里再补一条换发总结。
     * 成功后同步写回 CookieManager（LIVE）和 secureStore（STORED），让下一次 sessionCookie() 读到新值。
     * 第一版只刷 JWT_WEB、不动 CSRF（读操作不需要；上传若 403 走现有重试，不致命）。
     */
    private suspend fun refreshJwtWebViaSso(operation: String): SessionState {
        val cm = CookieManager.getInstance()
        val connectCookie = cm.getCookie("https://connect.garmin.cn/").orEmpty()
        val ssoCookie = cm.getCookie("https://sso.garmin.cn/")
        val ua = sessionManager.garminUserAgent() ?: GARMIN_DESKTOP_USER_AGENT
        var currentUrl = garminSsoEntryUrl("https://connect.garmin.cn/modern/")
        var newJwt: String? = null
        var lastCode: Int? = null
        var lastLocation: String? = null
        for (hop in 0 until SSO_MAX_HOPS) {
            val cookieForHop = if (isGarminSsoHost(currentUrl)) ssoCookie.orEmpty() else connectCookie
            val req = buildGarminBrowserRequest(currentUrl, cookieForHop, ua)
            val pair = runCatching {
                execute(req).use { resp ->
                    lastCode = resp.code
                    lastLocation = resp.header("Location")
                    val jwtHere = extractJwtWebFromHeaders(resp.headers("Set-Cookie"))
                    if (jwtHere != null) newJwt = jwtHere
                    resp.code to resp.header("Location")
                }
            }.getOrNull() ?: break
            val (code, location) = pair
            if (code in 300..399 && !location.isNullOrBlank()) {
                currentUrl = location
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
            ),
        )
        val jwt = newJwt ?: return SessionState.EXPIRED
        // 同步写回 CookieManager（LIVE，sessionCookie 优先读它）和 secureStore（STORED 兜底）
        runCatching {
            cm.setCookie("https://connect.garmin.cn/", "JWT_WEB=$jwt; Path=/; Secure; HttpOnly")
            cm.flush()
        }
        sessionManager.saveGarminSession(jwt, sessionManager.garminCsrf(), "garmin.cn", ua, null)
        return SessionState.AUTHENTICATED
    }

    private fun buildGarminBrowserRequest(url: String, cookieHeader: String, ua: String): Request =
        Request.Builder()
            .url(url)
            .header("Cookie", cookieHeader)
            .header("User-Agent", ua)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Upgrade-Insecure-Requests", "1")
            .get()
            .build()

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

/**
 * Cookie 读不到时的判定：[hasPersistedAccount] = true（数据库里有 Garmin 账号，说明登录过）
 * → 可重试失败，避免一次读取抖动就误报「登录已失效」；否则 → 要求登录。
 */
internal fun garminCookieMissingResult(hasPersistedAccount: Boolean, loginUrl: String): ConnectorResult<Nothing> =
    if (hasPersistedAccount) {
        ConnectorResult.RetryableFailure("GARMIN_COOKIE_UNREADABLE", "Garmin 会话凭据暂时读取失败，稍后会自动重试")
    } else {
        ConnectorResult.RequiresLogin(loginUrl, "Garmin 尚未登录，请先完成登录")
    }

/** 构造 CAS SSO 入口：connect.garmin.cn/signin/?service=<编码后的落地页>。 */
internal fun garminSsoEntryUrl(serviceUrl: String): String =
    "https://connect.garmin.cn/signin/?service=" + URLEncoder.encode(serviceUrl, "UTF-8")

/** 从一组 Set-Cookie 头里提取 JWT_WEB 的值（首个匹配），取不到返回 null。 */
internal fun extractJwtWebFromHeaders(setCookieHeaders: List<String>): String? =
    setCookieHeaders.firstNotNullOfOrNull { header ->
        if (header.substringBefore('=').trim().equals("JWT_WEB", ignoreCase = true)) {
            header.substringAfter('=').substringBefore(';').trim().removeSurrounding("\"")
                .takeIf { it.isNotEmpty() }
        } else null
    }

/** 判断 URL 是否属于 SSO 域（决定换发该跳带 sso cookie 还是 connect cookie）。 */
internal fun isGarminSsoHost(url: String): Boolean =
    runCatching { java.net.URI(url).host.orEmpty().lowercase().contains("sso.garmin") }.getOrDefault(false)

private fun garminForbiddenFailure(): ConnectorResult.RetryableFailure =
    ConnectorResult.RetryableFailure(
        "403",
        "Garmin 中国站暂时拒绝后台请求，将自动重试；如持续发生，请打开平台后重新验证登录",
    )
