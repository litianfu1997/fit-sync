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
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.net.URI

/**
 * iGPSPORT 中国站后台双向 connector（list/download/upload 均为 BACKGROUND）。
 *
 * 与 Garmin（WebView 抓 cookie+csrf）不同，iGPSPORT 走**纯 API 账号密码登录**：
 * `POST /service/auth/account/login {appId:"igpsport-web",username,password}` → JWT `access_token`。
 * token 7 天过期且**无刷新接口**，故 [login] 时一并把账号密码（Keystore 加密）持久化，
 * 业务请求 401 时由 [withRelogin] 用账号密码静默重登并重试一次——实现「全自动」。
 *
 * 四方向（base `https://prod.zh.igpsport.com`）：
 * - 列表：`GET /service/web-gateway/web-analyze/activity/queryMyActivity?reqType=1&sportType=0&pageNo=&pageSize=&sort=1`
 * - 详情（列表行未带 fitUrl 时回退）：`GET .../activity/queryActivityDetail/{rideId}`
 * - 下载：`GET <fitUrl>`（阿里云 OSS 公共读，匿名）
 * - 上传：① `GET /service/sportg/third-party-server/oss/getSignedUrl?fileExtension=.fit` → {ossId,signedUrl}
 *        ② `PUT` FIT 字节到 signedUrl（匿名，签名自带鉴权）
 *        ③ `POST /service/web-gateway/web-analyze/activity/uploadByOss {fileName, ossName:ossId}` 创建活动
 *
 * 协议真机实测确认（见 memory: igpsport-cn-api-protocol）。
 */
internal class IgpsportApiConnector(
    private val db: FitSyncDatabase,
    private val fileStore: FitFileStore,
    private val sessionManager: WebSessionManager,
    private val client: OkHttpClient,
) : PlatformConnector, CredentialsLoginConnector {
    override val platform = Platform.IGPSPORT

    override suspend fun validateSession(): SessionState {
        // 有 token，或有账号密码（可随时重登）即视为已连接；真实有效性在 list/download/upload 时按 401 判定。
        if (sessionManager.token(platform) != null) return SessionState.AUTHENTICATED
        val u = sessionManager.igpsportUsername()
        val p = sessionManager.igpsportPassword()
        return if (!u.isNullOrBlank() && !p.isNullOrBlank()) SessionState.AUTHENTICATED
        else SessionState.NOT_CONNECTED
    }

    override suspend fun detectCapabilities(): ConnectorCapabilities =
        PlatformCapabilityRegistry.capabilities(platform)

    /**
     * 账号密码登录（[CredentialsLoginConnector] 实现，由 `PlatformAccountRepository.loginWithCredentials` 调用）。
     * 成功则把 token + memberid + 账号密码一并持久化到 [WebSessionManager]。
     */
    override suspend fun login(username: String, password: String): SessionState {
        val payload = JSONObject()
            .put("appId", APP_ID)
            .put("username", username)
            .put("password", password)
            .toString()
            .toRequestBody(JSON)
        return runCatching {
            execute(apiRequest(LOGIN_URL).post(payload).build()).use { resp ->
                when {
                    resp.code == 401 || resp.code == 403 -> SessionState.EXPIRED
                    !resp.isSuccessful -> SessionState.NEEDS_USER_ACTION
                    else -> {
                        val parsed = parseIgpsportLogin(resp.body?.string().orEmpty())
                        if (parsed == null) {
                            SessionState.EXPIRED
                        } else {
                            sessionManager.saveIgpsportSession(
                                token = parsed.accessToken,
                                memberId = parsed.memberId,
                                username = username,
                                password = password,
                            )
                            SessionState.AUTHENTICATED
                        }
                    }
                }
            }
        }.getOrDefault(SessionState.NEEDS_USER_ACTION)
    }

    override suspend fun listActivities(
        sinceEpochMs: Long,
        cursor: SyncCursor?,
    ): ConnectorResult<ActivityPage> = withRelogin {
        val pageNo = cursor?.raw?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val url = "$LIST_URL?reqType=1&sportType=0&pageNo=$pageNo&pageSize=$PAGE_SIZE&sort=1"
        runCatching {
            execute(apiRequest(url).get().build()).use { resp ->
                when {
                    resp.code == 401 || resp.code == 403 -> requiresLogin()
                    !resp.isSuccessful -> failureStatus(resp)
                    else -> {
                        val all = parseIgpsportActivities(resp.body?.string().orEmpty())
                        // 默认按时间倒序：仅保留窗口内活动；整页都被剔除说明已越过下界，停止翻页。
                        val items = all.filter { it.startTimeEpochMs == null || it.startTimeEpochMs >= sinceEpochMs }
                        val hasMore = all.size >= PAGE_SIZE && items.isNotEmpty()
                        ConnectorResult.Success(
                            ActivityPage(items, SyncCursor((pageNo + 1).toString()), hasMore),
                        )
                    }
                }
            }
        }.getOrElse { ConnectorResult.RetryableFailure(null, it.message ?: "iGPSPORT 活动列表读取失败") }
    }

    override suspend fun downloadFit(activity: RemoteActivitySummary): ConnectorResult<FitFile> {
        // 优先用列表行自带的 fitUrl（省一次 detail）；否则查 queryActivityDetail/{rideId}。
        val inline = activity.downloadUrl?.takeIf { it.startsWith("http") }
            ?: activity.metadata["fit_url"]?.takeIf { it.startsWith("http") }
        val fitUrl: String = if (inline != null) {
            inline
        } else {
            when (val d = resolveFitUrlByDetail(activity.remoteActivityId)) {
                is ConnectorResult.Success -> d.value
                is ConnectorResult.RequiresLogin -> return d
                is ConnectorResult.RetryableFailure -> return d
                is ConnectorResult.PermanentFailure -> return d
                is ConnectorResult.RequiresUserAction -> return d
                is ConnectorResult.Duplicate<*> ->
                    return ConnectorResult.PermanentFailure("IGPSPORT_FIT_URL_MISSING", "iGPSPORT 未返回该活动的 FIT 下载地址")
                is ConnectorResult.Processing ->
                    return ConnectorResult.PermanentFailure("IGPSPORT_FIT_URL_MISSING", "iGPSPORT 返回了无效的下载状态")
            }
        }
        if (!fitUrl.startsWith("http")) {
            return ConnectorResult.PermanentFailure("IGPSPORT_FIT_URL_MISSING", "iGPSPORT 未返回该活动的 FIT 下载地址")
        }
        if (!isTrustedIgpHost(fitUrl)) {
            return ConnectorResult.PermanentFailure("IGPSPORT_FIT_UNSAFE_HOST", "iGPSPORT 返回了非预期的 FIT 地址")
        }
        return runCatching {
            // OSS 公共读，匿名 GET（不带 Authorization）。
            execute(Request.Builder().url(fitUrl).get().build()).use { resp ->
                when {
                    resp.code == 404 -> ConnectorResult.PermanentFailure(
                        "IGPSPORT_FIT_NOT_FOUND", "iGPSPORT 未保留该活动的原始 FIT",
                    )
                    !resp.isSuccessful -> ConnectorResult.RetryableFailure(
                        resp.code.toString(), "iGPSPORT FIT 下载失败（HTTP ${resp.code}）",
                    )
                    else -> storeFit(resp, "igpsport_${activity.remoteActivityId}.fit")
                }
            }
        }.getOrElse { ConnectorResult.RetryableFailure(null, it.message ?: "iGPSPORT FIT 下载失败") }
    }

    override suspend fun uploadFit(activity: CanonicalActivityEntity): ConnectorResult<RemoteCopy> {
        val pair = primaryFile(activity)
            ?: return ConnectorResult.PermanentFailure("FILE_MISSING", "本地 FIT 文件不存在")
        val (file, _) = pair
        if (file.length() <= 0) return ConnectorResult.PermanentFailure("FILE_EMPTY", "本地 FIT 文件为空")
        if (file.length() > IGP_MAX_FIT_BYTES) {
            return ConnectorResult.PermanentFailure("FILE_TOO_LARGE", "iGPSPORT 限制 FIT 不超过 25 MB")
        }
        val uploadName = if (file.name.endsWith(".fit", ignoreCase = true)) file.name else "${file.name}.fit"
        return runCatching { uploadViaOss(file, uploadName) }
            .getOrElse { ConnectorResult.RetryableFailure(null, it.message ?: "iGPSPORT 上传请求失败") }
    }

    /** 列表行未带 fitUrl 时，查 detail 拿 fitUrl/fitOssPath。 */
    private suspend fun resolveFitUrlByDetail(rideId: String): ConnectorResult<String> = withRelogin {
        runCatching {
            execute(apiRequest("$DETAIL_URL/$rideId").get().build()).use { resp ->
                when {
                    resp.code == 401 || resp.code == 403 -> requiresLogin()
                    !resp.isSuccessful -> failureStatus(resp)
                    else -> {
                        val url = parseIgpsportFitUrl(resp.body?.string().orEmpty())
                        if (url != null) {
                            ConnectorResult.Success(url)
                        } else {
                            ConnectorResult.PermanentFailure(
                                "IGPSPORT_FIT_URL_MISSING", "iGPSPORT 详情未返回 FIT 地址",
                            )
                        }
                    }
                }
            }
        }.getOrElse { ConnectorResult.RetryableFailure(null, it.message ?: "iGPSPORT 活动详情读取失败") }
    }

    /** getSignedUrl → PUT OSS → uploadByOss 三步后台上传。 */
    private suspend fun uploadViaOss(file: File, uploadName: String): ConnectorResult<RemoteCopy> = withRelogin {
        // 1) 申请 OSS 直传地址
        val signJson = execute(apiRequest("$GET_SIGNED_URL?fileExtension=.fit").get().build()).use { resp ->
            when {
                resp.code == 401 || resp.code == 403 -> return@withRelogin requiresLogin()
                !resp.isSuccessful -> return@withRelogin failureStatus(resp)
                else -> resp.body?.string().orEmpty()
            }
        }
        val signed = parseIgpsportSignedUrl(signJson)
            ?: return@withRelogin ConnectorResult.PermanentFailure("IGPSPORT_SIGN_FAIL", "iGPSPORT 未返回上传地址")
        // 2) PUT 文件到 OSS（匿名，签名 URL 自带鉴权）。中国站网页端明确使用空 Content-Type；
        // 这里不能附带 application/octet-stream，否则它会参与 OSS 签名校验并导致 403。
        val putReq = buildIgpsportOssPutRequest(signed.signedUrl, file)
        execute(putReq).use { resp ->
            if (!resp.isSuccessful) return@withRelogin ConnectorResult.RetryableFailure(
                resp.code.toString(), "iGPSPORT OSS 上传失败（HTTP ${resp.code}）",
            )
        }
        // 3) 通知服务端用该 OSS 对象创建活动
        val createBody = JSONObject()
            .put("fileName", uploadName)
            .put("ossName", signed.ossId)
            .toString()
            .toRequestBody(JSON)
        execute(apiRequest(UPLOAD_BY_OSS_URL).post(createBody).build()).use { resp ->
            when {
                resp.code == 401 || resp.code == 403 -> requiresLogin()
                !resp.isSuccessful -> failureStatus(resp)
                else -> {
                    val root = runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrDefault(JSONObject())
                    if (root.optInt("code", -1) == 0) {
                        // uploadByOss 异步解析、响应 data 多为 null；用 ossId 作临时 remoteActivityId，
                        // 真正 rideId 会在下次 list 拉取时按 sha256 去重对齐（PlatformCopy 防重复投递）。
                        ConnectorResult.Success(RemoteCopy(signed.ossId))
                    } else {
                        ConnectorResult.PermanentFailure(
                            root.opt("code")?.toString(),
                            root.optString("message", "iGPSPORT 上传失败"),
                        )
                    }
                }
            }
        }
    }

    /**
     * 业务请求 401 → 用持久化的账号密码静默重登并重试一次；重登失败则原样上报 RequiresLogin
     * （SyncCoordinator 据此把账号标 AUTH_EXPIRED + 通知用户重新登录）。
     */
    private suspend fun <T> withRelogin(block: suspend () -> ConnectorResult<T>): ConnectorResult<T> {
        val first = block()
        if (first !is ConnectorResult.RequiresLogin) return first
        val u = sessionManager.igpsportUsername()
        val p = sessionManager.igpsportPassword()
        if (!u.isNullOrBlank() && !p.isNullOrBlank() && login(u, p) == SessionState.AUTHENTICATED) {
            return block()
        }
        return first
    }

    private fun apiRequest(url: String): Request.Builder {
        val token = sessionManager.token(platform)
        return Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/plain, */*")
            .header("Origin", WEB_ORIGIN)
            .header("Referer", "$WEB_ORIGIN/")
            .header("User-Agent", USER_AGENT)
            .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }
    }

    private suspend fun execute(request: Request): Response = withContext(Dispatchers.IO) {
        client.newCall(request).execute()
    }

    private suspend fun storeFit(response: Response, displayName: String): ConnectorResult<FitFile> {
        val body = response.body
            ?: return ConnectorResult.PermanentFailure(null, "iGPSPORT 未返回 FIT 文件")
        return runCatching {
            val stored = body.byteStream().use { fileStore.storeFromStream(it, displayName) }
            ConnectorResult.Success(FitFile(stored.file, stored.sha256, stored.displayName))
        }.getOrElse {
            ConnectorResult.PermanentFailure("IGPSPORT_DOWNLOAD_INVALID", it.message ?: "下载的内容不是有效 FIT")
        }
    }

    private suspend fun primaryFile(activity: CanonicalActivityEntity): Pair<File, ActivityVariantEntity>? {
        val variants = db.activityVariantDao().findByCanonical(activity.id)
        val variant = activity.primaryVariantId?.let { id -> variants.firstOrNull { it.id == id } }
            ?: variants.maxByOrNull { it.qualityScore ?: 0 }
            ?: return null
        val file = File(variant.localFilePath)
        return if (file.isFile) file to variant else null
    }

    private fun isTrustedIgpHost(url: String): Boolean = runCatching {
        val host = URI(url).toURL().host.lowercase()
        host.endsWith("aliyuncs.com") || host.endsWith("igpsport.com") || host.endsWith("igpsport.cn")
    }.getOrDefault(false)

    private fun requiresLogin(): ConnectorResult<Nothing> =
        ConnectorResult.RequiresLogin(loginUrl, "iGPSPORT 登录已失效，请重新登录")

    private fun failureStatus(resp: Response): ConnectorResult<Nothing> = when (resp.code) {
        429, in 500..599 -> ConnectorResult.RetryableFailure(
            resp.code.toString(), "iGPSPORT 暂时不可用（HTTP ${resp.code}）",
        )
        else -> ConnectorResult.PermanentFailure(resp.code.toString(), "iGPSPORT 请求失败（HTTP ${resp.code}）")
    }

    private val loginUrl: String
        get() = PlatformWebConfigs.get(platform).loginUrl

    private val JSON = "application/json; charset=utf-8".toMediaType()

    companion object {
        private const val APP_ID = "igpsport-web"
        private const val WEB_ORIGIN = "https://app.igpsport.cn"
        private const val API_BASE = "https://prod.zh.igpsport.com"
        private const val LOGIN_URL = "$API_BASE/service/auth/account/login"
        private const val LIST_URL =
            "$API_BASE/service/web-gateway/web-analyze/activity/queryMyActivity"
        private const val DETAIL_URL =
            "$API_BASE/service/web-gateway/web-analyze/activity/queryActivityDetail"
        private const val GET_SIGNED_URL =
            "$API_BASE/service/sportg/third-party-server/oss/getSignedUrl"
        private const val UPLOAD_BY_OSS_URL =
            "$API_BASE/service/web-gateway/web-analyze/activity/uploadByOss"
        private const val PAGE_SIZE = 20
        private const val IGP_MAX_FIT_BYTES = 25L * 1024 * 1024
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}

internal fun buildIgpsportOssPutRequest(signedUrl: String, file: File): Request =
    Request.Builder()
        .url(signedUrl)
        .put(file.asRequestBody(null))
        .build()
