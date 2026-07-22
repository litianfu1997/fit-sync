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
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.net.URI

/**
 * COROS 高驰中国站后台双向 connector（list/download/upload 均为 BACKGROUND）。
 *
 * 模仿 [IgpsportApiConnector]：独立文件 + 自持 helper + [withRelogin]，不继承 private 的
 * HttpPlatformConnector，**不**经过 [WebSessionManager.applyTo]（它注入 `Authorization: Bearer`，
 * COROS 网关只认小写 `accesstoken` 头）——[apiRequest] 自持注入 `accesstoken`。
 *
 * 协议来源：teamcnapi.coros.com 网页端逆向（woshicby/sync_garmin_to_coros、NYT87/coros-connect）。
 * - 登录：`POST /account/login {account, accountType:2, pwd:MD5(明文)}` → accessToken + userId + regionId。
 *   accessToken 无刷新接口，[login] 时一并持久化账号密码（Keystore 加密），业务 401 由 [withRelogin] 静默重登。
 * - 鉴权：业务请求带 `accesstoken: <token>` 头（非 Bearer）。
 * - 列表：`GET /activity/query?size=&pageNumber=`（活动 ID = labelId，sportType 写入 metadata）。
 * - 下载：`POST /activity/detail/download?labelId=&sportType=&fileType=4`（fileType=4=FIT）→ `data.fileUrl` → 匿名 GET。
 * - 上传：① `GET faq.coros.com/openapi/oss/sts` 拿 STS 凭证（删盐 base64 解码）
 *        ② FIT 打 zip
 *        ③ [CorosOssSigner] V1 签名 PUT 到阿里云 OSS（bucket coros-oss@北京）
 *        ④ `POST /activity/fit/import`（multipart 字段 jsonParameter，serviceName="oss"）
 *
 * 仅支持中国站（regionId=2）。协议为逆向私有接口，可能随 COROS 调整失效（实验性）。
 */
internal class CorosApiConnector(
    private val db: FitSyncDatabase,
    private val fileStore: FitFileStore,
    private val sessionManager: WebSessionManager,
    private val client: OkHttpClient,
) : PlatformConnector, CredentialsLoginConnector {
    override val platform = Platform.COROS

    override suspend fun validateSession(): SessionState {
        // 有 token，或有账号密码（可随时重登）即视为已连接；真实有效性在 list/download/upload 时按 401 判定。
        if (sessionManager.token(platform) != null) return SessionState.AUTHENTICATED
        val u = sessionManager.corosUsername()
        val p = sessionManager.corosPassword()
        return if (!u.isNullOrBlank() && !p.isNullOrBlank()) SessionState.AUTHENTICATED
        else SessionState.NOT_CONNECTED
    }

    override suspend fun detectCapabilities(): ConnectorCapabilities =
        PlatformCapabilityRegistry.capabilities(platform)

    /**
     * 账号密码登录（[CredentialsLoginConnector] 实现，由 `PlatformAccountRepository.loginWithCredentials` 调用）。
     * 密码经 MD5 小写 hex 后发送；成功则把 accessToken + userId + 账号密码持久化到 [WebSessionManager]。
     * regionId 非 2（非中国站）→ NEEDS_USER_ACTION。
     */
    override suspend fun login(username: String, password: String): SessionState {
        val payload = JSONObject()
            .put("account", username)
            .put("accountType", ACCOUNT_TYPE)
            .put("pwd", md5Hex(password))
            .toString()
            .toRequestBody(JSON)
        return runCatching {
            execute(
                Request.Builder().url(LOGIN_URL).post(payload)
                    .header("Content-Type", "application/json;charset=UTF-8")
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Origin", WEB_ORIGIN)
                    .header("Referer", "$WEB_ORIGIN/")
                    .header("User-Agent", USER_AGENT)
                    .build(),
            ).use { resp ->
                when {
                    resp.code == 401 || resp.code == 403 -> SessionState.EXPIRED
                    !resp.isSuccessful -> SessionState.NEEDS_USER_ACTION
                    else -> {
                        val parsed = parseCorosLogin(resp.body?.string().orEmpty())
                        when {
                            parsed == null -> SessionState.EXPIRED
                            parsed.regionId != REGION_CN -> SessionState.NEEDS_USER_ACTION // 仅支持中国站
                            else -> {
                                sessionManager.saveCorosSession(
                                    accessToken = parsed.accessToken,
                                    userId = parsed.userId,
                                    username = username,
                                    password = password,
                                )
                                SessionState.AUTHENTICATED
                            }
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
        val url = "$LIST_URL?size=$PAGE_SIZE&pageNumber=$pageNo"
        runCatching {
            execute(apiRequest(url).get().build()).use { resp ->
                when {
                    resp.code == 401 || resp.code == 403 -> requiresLogin()
                    !resp.isSuccessful -> failureStatus(resp)
                    else -> {
                        val text = resp.body?.string().orEmpty()
                        val all = parseCorosActivities(text)
                            ?: return@use ConnectorResult.RetryableFailure(
                                "COROS_INVALID_RESPONSE",
                                "COROS 活动列表响应格式异常",
                            )
                        val totalPage = parseCorosTotalPage(text)
                        // 仅保留窗口内活动；整页都被剔除说明已越过下界，停止翻页。
                        val items = all.filter { it.startTimeEpochMs == null || it.startTimeEpochMs >= sinceEpochMs }
                        val hasMore = pageNo < totalPage && items.isNotEmpty()
                        ConnectorResult.Success(
                            ActivityPage(items, SyncCursor((pageNo + 1).toString()), hasMore),
                        )
                    }
                }
            }
        }.getOrElse { ConnectorResult.RetryableFailure(null, it.message ?: "COROS 活动列表读取失败") }
    }

    override suspend fun downloadFit(activity: RemoteActivitySummary): ConnectorResult<FitFile> {
        // download 端点强制要 sportType（list 时写入 metadata）。
        val rawSportType = activity.metadata["sport_type"]
            ?: return ConnectorResult.PermanentFailure("COROS_SPORT_TYPE_MISSING", "COROS 活动缺少 sportType，无法下载")
        // 多项运动(65535)在下载端点不被接受，回退到 100（与开源实现 woshicby/NYT87 一致）
        val sportType = if (rawSportType == "65535") "100" else rawSportType
        return runCatching { downloadViaTwoSteps(activity.remoteActivityId, sportType) }
            .getOrElse { ConnectorResult.RetryableFailure(null, it.message ?: "COROS FIT 下载失败") }
    }

    /** POST detail/download 拿 fileUrl，再匿名 GET（阿里云 OSS 直链）。 */
    private suspend fun downloadViaTwoSteps(
        labelId: String,
        sportType: String,
    ): ConnectorResult<FitFile> = withRelogin {
        val url = "$DOWNLOAD_URL?labelId=$labelId&sportType=$sportType&fileType=$FILE_TYPE_FIT"
        val fileUrl = execute(apiRequest(url).post(EMPTY_BODY).build()).use { resp ->
            when {
                resp.code == 401 || resp.code == 403 -> return@withRelogin requiresLogin()
                !resp.isSuccessful -> return@withRelogin failureStatus(resp)
                else -> parseCorosFitUrl(resp.body?.string().orEmpty())
            }
        }
        if (fileUrl == null) {
            return@withRelogin ConnectorResult.PermanentFailure(
                "COROS_FIT_URL_MISSING", "COROS 未返回该活动的 FIT 下载地址",
            )
        }
        if (!isTrustedCorosHost(fileUrl)) {
            return@withRelogin ConnectorResult.PermanentFailure(
                "COROS_FIT_UNSAFE_HOST", "COROS 返回了非预期的 FIT 地址",
            )
        }
        execute(Request.Builder().url(fileUrl).get().build()).use { resp ->
            when {
                resp.code == 404 -> ConnectorResult.PermanentFailure(
                    "COROS_FIT_NOT_FOUND", "COROS 未保留该活动的原始 FIT",
                )
                !resp.isSuccessful -> ConnectorResult.RetryableFailure(
                    resp.code.toString(), "COROS FIT 下载失败（HTTP ${resp.code}）",
                )
                else -> storeFit(resp, "coros_$labelId.fit")
            }
        }
    }

    override suspend fun uploadFit(
        activity: CanonicalActivityEntity,
        sourceVariant: ActivityVariantEntity,
    ): ConnectorResult<RemoteCopy> {
        val file = File(sourceVariant.localFilePath).takeIf { it.isFile }
            ?: return ConnectorResult.PermanentFailure("FILE_MISSING", "本地 FIT 文件不存在")
        if (file.length() <= 0) return ConnectorResult.PermanentFailure("FILE_EMPTY", "本地 FIT 文件为空")
        if (file.length() > MAX_FIT_BYTES) {
            return ConnectorResult.PermanentFailure("FILE_TOO_LARGE", "COROS 限制 FIT 不超过 ${MAX_FIT_BYTES / (1024 * 1024)} MB")
        }
        val uploadName = if (file.name.endsWith(".fit", ignoreCase = true)) file.name else "${file.name}.fit"
        return runCatching { uploadViaOss(file, uploadName) }
            .getOrElse { ConnectorResult.RetryableFailure(null, it.message ?: "COROS 上传请求失败") }
    }

    /** STS 凭证 → 打 zip → OSS V1 签名 PUT → multipart import。 */
    private suspend fun uploadViaOss(file: File, uploadName: String): ConnectorResult<RemoteCopy> = withRelogin {
        // 1) 申请 OSS STS 临时凭证（固定 sign，不走 apiRequest，不 inject accesstoken）
        val sts = fetchOssSts() ?: return@withRelogin ConnectorResult.PermanentFailure(
            "COROS_STS_UNAVAILABLE", "COROS 未返回上传凭证（接口可能已变更）",
        )
        // 2) md5 用原始 FIT 的、size 用 zip 的；objectKey = fit_zip/{userId}/{fitMd5}.zip
        val fitMd5 = md5Hex(file)
        val userId = sessionManager.externalUserId(platform)
            ?: return@withRelogin ConnectorResult.PermanentFailure("COROS_NO_USERID", "COROS 账号缺少 userId，请重新登录")
        val objectKey = "fit_zip/$userId/$fitMd5.zip"
        val zipBytes = CorosOssSigner.buildZip(file, uploadName)
        // 3) OSS V1 签名 PUT（失败映射为可重试，不误标 AUTH_EXPIRED——STS 失效重拿即可）
        val plan = CorosOssSigner.plan(sts, objectKey, CorosOssSigner.currentRfc1123Date())
        execute(CorosOssSigner.buildPutRequest(plan, zipBytes)).use { resp ->
            if (!resp.isSuccessful) return@withRelogin ConnectorResult.RetryableFailure(
                resp.code.toString(), "COROS OSS 上传失败（HTTP ${resp.code}）",
            )
        }
        // 4) 通知服务端用该 OSS 对象创建活动（serviceName 必须 "oss"，不是 sts 的 "aliyun"）
        val jsonParameter = JSONObject()
            .put("source", 1)
            .put("timezone", TIMEZONE_CN)
            .put("bucket", sts.bucket)
            .put("md5", fitMd5)
            .put("size", zipBytes.size)
            .put("object", objectKey)
            .put("serviceName", "oss")
            .put("oriFileName", uploadName)
            .toString()
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("jsonParameter", jsonParameter)
            .build()
        execute(apiRequest(IMPORT_URL).post(multipart).build()).use { resp ->
            when {
                resp.code == 401 || resp.code == 403 -> requiresLogin()
                !resp.isSuccessful -> failureStatus(resp)
                else -> {
                    val root = runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrDefault(JSONObject())
                    if (root.optString("result") == "0000") {
                        // import 异步解析；用 fitMd5 作临时 remoteActivityId，
                        // 真正 labelId 会在下次 list 拉取时按 sha256 去重对齐（PlatformCopy 防重复投递）。
                        ConnectorResult.Success(RemoteCopy(fitMd5))
                    } else {
                        ConnectorResult.PermanentFailure(
                            root.optString("result").takeIf { it.isNotBlank() },
                            root.optString("message", "COROS 上传失败"),
                        )
                    }
                }
            }
        }
    }

    /** `GET faq.coros.com/openapi/oss/sts`，返回解码后的 STS 凭证；失败 null。 */
    private suspend fun fetchOssSts(): CorosStsCredentials? = runCatching {
        val url = "$OSS_STS_URL?bucket=$OSS_BUCKET&service=$OSS_SERVICE_QS&app_id=$OSS_APP_ID&sign=$OSS_SIGN&v=2"
        execute(
            Request.Builder().url(url).get()
                .header("Accept", "application/json, text/plain, */*")
                .header("User-Agent", USER_AGENT)
                .build(),
        ).use { resp -> if (!resp.isSuccessful) null else parseCorosSts(resp.body?.string().orEmpty()) }
    }.getOrDefault(null)

    /**
     * 业务请求 401 → 用持久化的账号密码静默重登并重试一次；重登失败则原样上报 RequiresLogin
     * （SyncCoordinator 据此把账号标 AUTH_EXPIRED + 通知用户重新登录）。
     */
    private suspend fun <T> withRelogin(block: suspend () -> ConnectorResult<T>): ConnectorResult<T> {
        val first = block()
        if (first !is ConnectorResult.RequiresLogin) return first
        val u = sessionManager.corosUsername()
        val p = sessionManager.corosPassword()
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
            .apply { if (!token.isNullOrBlank()) header("accesstoken", token) }
    }

    private suspend fun execute(request: Request): Response = withContext(Dispatchers.IO) {
        client.newCall(request).execute()
    }

    private suspend fun storeFit(response: Response, displayName: String): ConnectorResult<FitFile> {
        val body = response.body
            ?: return ConnectorResult.PermanentFailure(null, "COROS 未返回 FIT 文件")
        return runCatching {
            val stored = body.byteStream().use { fileStore.storeFromStream(it, displayName) }
            ConnectorResult.Success(FitFile(stored.file, stored.sha256, stored.displayName))
        }.getOrElse {
            ConnectorResult.PermanentFailure("COROS_DOWNLOAD_INVALID", it.message ?: "下载的内容不是有效 FIT")
        }
    }

    private fun isTrustedCorosHost(url: String): Boolean = runCatching {
        val host = URI(url).toURL().host.lowercase()
        host.endsWith("coros.com") || host.endsWith("aliyuncs.com")
    }.getOrDefault(false)

    private fun requiresLogin(): ConnectorResult<Nothing> =
        ConnectorResult.RequiresLogin(loginUrl, "COROS 登录已失效，请重新登录")

    private fun failureStatus(resp: Response): ConnectorResult<Nothing> = when (resp.code) {
        429, in 500..599 -> ConnectorResult.RetryableFailure(
            resp.code.toString(), "COROS 暂时不可用（HTTP ${resp.code}）",
        )
        else -> ConnectorResult.PermanentFailure(resp.code.toString(), "COROS 请求失败（HTTP ${resp.code}）")
    }

    private val loginUrl: String
        get() = PlatformWebConfigs.get(platform).loginUrl

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val EMPTY_BODY = "".toRequestBody(null)

    companion object {
        private const val WEB_ORIGIN = "https://teamcnapi.coros.com"
        private const val API_BASE = "https://teamcnapi.coros.com"
        private const val LOGIN_URL = "$API_BASE/account/login"
        private const val LIST_URL = "$API_BASE/activity/query"
        private const val DOWNLOAD_URL = "$API_BASE/activity/detail/download"
        private const val IMPORT_URL = "$API_BASE/activity/fit/import"
        private const val OSS_STS_URL = "https://faq.coros.com/openapi/oss/sts"

        private const val OSS_APP_ID = "1660188068672619112"
        private const val OSS_SIGN = "9AD4AA35AAFEE6BB1E847A76848D58DF"
        private const val OSS_BUCKET = "coros-oss"
        private const val OSS_SERVICE_QS = "aliyun" // sts query 参数；⚠️ import 的 serviceName 必须 "oss"

        private const val ACCOUNT_TYPE = 2 // 邮箱登录
        private const val REGION_CN = 2
        private const val FILE_TYPE_FIT = 4 // fileType=4 → FIT
        private const val TIMEZONE_CN = 32 // UTC+8（hours*4）
        private const val PAGE_SIZE = 200
        private const val MAX_FIT_BYTES = 25L * 1024 * 1024
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
