package com.fitsync.app.web

import android.webkit.CookieManager
import android.webkit.WebStorage
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.security.SecureSessionStore
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

class WebSessionManager(
    private val secureStore: SecureSessionStore,
) {
    private val cookies: CookieManager
        get() = CookieManager.getInstance()

    /** 最近一次 WebView 页面 URL，用于 Cookie 探测（比静态 origins 更贴近真实登录域）。 */
    private val lastWebViewUrls = ConcurrentHashMap<Platform, String>()

    fun prepareCookies() {
        cookies.setAcceptCookie(true)
    }

    fun rememberWebViewUrl(platform: Platform, url: String?) {
        if (!url.isNullOrBlank()) lastWebViewUrls[platform] = url
    }

    /**
     * 读取 Cookie 字符串。
     *
     * - 若提供 [forUrl]，优先返回该 URL 可携带的 Cookie。
     * - 否则在「探测 URL 列表」中选择更像登录会话的一组。
     */
    fun cookieHeader(platform: Platform, forUrl: String? = null): String? {
        if (!forUrl.isNullOrBlank()) {
            cookies.getCookie(forUrl)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return cookieCandidates(platform)
            .maxByOrNull { (_, header) -> sessionCookieScore(header) }
            ?.second
    }

    fun hasSession(platform: Platform): Boolean = when (platform) {
        Platform.GARMIN -> hasGarminSession()
        else -> !cookieHeader(platform).isNullOrBlank() || !token(platform).isNullOrBlank()
    }

    fun hasStravaWebSession(): Boolean {
        val header = cookieHeader(Platform.STRAVA, "https://www.strava.com/dashboard") ?: return false
        return header.split(';').any { part ->
            val name = part.substringBefore('=').trim().lowercase()
            name == "_strava4_session" || name.contains("session")
        }
    }

    /**
     * 诊断用：返回「有 Cookie 的探测 URL → cookie 名列表」，不含值。
     */
    fun cookieDiagnostic(platform: Platform): String {
        val parts = cookieCandidates(platform).map { (url, header) ->
            val names = header.split(';')
                .map { it.substringBefore('=').trim() }
                .filter { it.isNotBlank() }
                .joinToString(",")
            val host = runCatching { java.net.URI(url).host }.getOrDefault(url)
            "$host($names)"
        }
        return if (parts.isEmpty()) "无Cookie" else parts.joinToString(" | ")
    }

    fun applyTo(platform: Platform, builder: Request.Builder): Request.Builder {
        val probeUrl = runCatching { builder.build().url.toString() }.getOrNull()
        cookieHeader(platform, forUrl = probeUrl)?.let { builder.header("Cookie", it) }
        token(platform)?.let { raw ->
            builder.header("Authorization", authorizationHeaderValue(platform, raw))
            builder.header("token", raw)
        }
        return builder
            .header("Accept", "application/json, application/octet-stream, */*")
            .header("X-Requested-With", "XMLHttpRequest")
    }

    fun saveOnelapTokens(token: String?, refreshToken: String?, userId: String?) {
        secureStore.put(key(Platform.ONELAP, "token"), token)
        secureStore.put(key(Platform.ONELAP, "refresh_token"), refreshToken)
        secureStore.put(key(Platform.ONELAP, "uid"), userId)
    }

    fun token(platform: Platform): String? = secureStore.get(key(platform, "token"))
    fun refreshToken(platform: Platform): String? = secureStore.get(key(platform, "refresh_token"))
    fun externalUserId(platform: Platform): String? = secureStore.get(key(platform, "uid"))

    fun saveStravaCredentials(clientId: String, clientSecret: String) {
        secureStore.putAll(
            mapOf(
                key(Platform.STRAVA, "client_id") to clientId,
                key(Platform.STRAVA, "client_secret") to clientSecret,
            ),
        )
    }

    fun stravaClientId(): String? = secureStore.get(key(Platform.STRAVA, "client_id"))
    fun stravaClientSecret(): String? = secureStore.get(key(Platform.STRAVA, "client_secret"))

    fun saveStravaTokens(
        accessToken: String,
        refreshToken: String,
        expiresAtEpochSeconds: Long,
        scope: String,
        athleteId: String?,
        displayName: String?,
    ) {
        secureStore.putAll(
            mapOf(
                key(Platform.STRAVA, "token") to accessToken,
                key(Platform.STRAVA, "refresh_token") to refreshToken,
                key(Platform.STRAVA, "expires_at") to expiresAtEpochSeconds.toString(),
                key(Platform.STRAVA, "scope") to scope,
                key(Platform.STRAVA, "uid") to athleteId,
                key(Platform.STRAVA, "display_name") to displayName,
            ),
        )
    }

    fun stravaExpiresAtEpochSeconds(): Long? =
        secureStore.get(key(Platform.STRAVA, "expires_at"))?.toLongOrNull()

    fun stravaScope(): String? = secureStore.get(key(Platform.STRAVA, "scope"))
    fun stravaDisplayName(): String? = secureStore.get(key(Platform.STRAVA, "display_name"))

    fun saveStravaOAuthState(state: String, createdAtEpochMs: Long) {
        secureStore.putAll(
            mapOf(
                key(Platform.STRAVA, "oauth_state") to state,
                key(Platform.STRAVA, "oauth_state_created_at") to createdAtEpochMs.toString(),
            ),
        )
    }

    fun consumeStravaOAuthState(): Pair<String, Long>? {
        val state = secureStore.get(key(Platform.STRAVA, "oauth_state")) ?: return null
        val createdAt = secureStore.get(key(Platform.STRAVA, "oauth_state_created_at"))?.toLongOrNull()
        secureStore.remove(key(Platform.STRAVA, "oauth_state"))
        secureStore.remove(key(Platform.STRAVA, "oauth_state_created_at"))
        return createdAt?.let { state to it }
    }

    /**
     * 持久化 Garmin 中国站后台直连所需的最小会话信息（Keystore 加密）。
     *
     * - [jwtWeb]：登录后 connect.garmin.cn 的 JWT_WEB Cookie 值，后台 OkHttp 直连
     *   connectapi.garmin.cn 时作为会话凭据。
     * - [csrf]：中国站写操作使用的 `Connect-Csrf-Token`；`NK: NT` 是额外代理头，不能替代它。
     * - [domain]：登录域归属 garmin.cn / garmin.com（决定 connectapi 后缀）。
     * - [userAgent]：复用 WebView 登录时的 UA，避免被网关按移动端拦截。
     * - [ssoCookieHeader]：sso.garmin.cn 域的 cookie 串（含 CASTGC 等），作为后台静默续登的持久化
     *   「重登凭据」——进程被杀、WebView cookie 库被清后，[com.fitsync.app.connectors.GarminConnectApiConnector]
     *   的 refreshJwtWebViaSso 仍可凭它换发新 JWT_WEB。Garmin 无账号密码登录，CASTGC 即等价的长会话凭据。
     */
    fun saveGarminSession(
        jwtWeb: String?,
        csrf: String?,
        domain: String?,
        userAgent: String?,
        cookieHeader: String? = null,
        ssoCookieHeader: String? = null,
    ) {
        secureStore.put(key(Platform.GARMIN, "jwt_web"), jwtWeb)
        // 完整 Cookie 头用于网页代理路径（connect.garmin.cn/modern/proxy 上传）；
        // 缺省退化为仅 JWT_WEB，保证直连路径仍可用。
        secureStore.put(
            key(Platform.GARMIN, "cookie"),
            cookieHeader?.takeIf { it.isNotBlank() } ?: jwtWeb?.let { "JWT_WEB=$it" },
        )
        secureStore.put(key(Platform.GARMIN, "csrf"), csrf)
        secureStore.put(key(Platform.GARMIN, "domain"), domain)
        secureStore.put(key(Platform.GARMIN, "user_agent"), userAgent)
        // put(null/blank) 会自动 remove（见 SecureSessionStore），向后兼容既有调用点。
        secureStore.put(key(Platform.GARMIN, "sso_cookie"), ssoCookieHeader)
    }

    fun garminJwtWeb(): String? = secureStore.get(key(Platform.GARMIN, "jwt_web"))
    fun garminCookieHeader(): String? = secureStore.get(key(Platform.GARMIN, "cookie"))
    fun garminCsrf(): String? = secureStore.get(key(Platform.GARMIN, "csrf"))
    fun garminDomain(): String =
        secureStore.get(key(Platform.GARMIN, "domain"))?.takeIf { it.isNotBlank() } ?: "garmin.cn"
    fun garminUserAgent(): String? = secureStore.get(key(Platform.GARMIN, "user_agent"))
    fun garminSsoCookie(): String? = secureStore.get(key(Platform.GARMIN, "sso_cookie"))

    /**
     * 持久化 iGPSPORT 中国站后台登录凭据（Keystore 加密）。
     *
     * iGPSPORT 走纯 API 账号密码登录（不走 WebView），JWT token 7 天过期且无刷新接口，
     * 故除 token 外还需保存账号密码，以便过期时后台自动重新登录（[com.fitsync.app.connectors.IgpsportApiConnector] 的 withRelogin）。
     * - [token]：access_token，存通用 key `IGPSPORT:token`，[applyTo] 会自动注入 `Authorization: Bearer`。
     * - [memberId]：JWT 里的 memberid，存通用 key `IGPSPORT:uid`（对应 [externalUserId]）。
     * - [username]/[password]：仅用于 token 过期后静默重登，不再用于其它用途。
     */
    fun saveIgpsportSession(
        token: String?,
        memberId: String?,
        username: String?,
        password: String?,
    ) {
        secureStore.put(key(Platform.IGPSPORT, "token"), token)
        secureStore.put(key(Platform.IGPSPORT, "uid"), memberId)
        secureStore.put(key(Platform.IGPSPORT, "username"), username)
        secureStore.put(key(Platform.IGPSPORT, "password"), password)
    }

    fun igpsportUsername(): String? = secureStore.get(key(Platform.IGPSPORT, "username"))
    fun igpsportPassword(): String? = secureStore.get(key(Platform.IGPSPORT, "password"))
    fun igpsportMemberId(): String? = secureStore.get(key(Platform.IGPSPORT, "uid"))

    /**
     * 持久化 COROS 中国站后台登录凭据（Keystore 加密）。
     *
     * COROS 走纯 API 账号密码登录（不走 WebView），accessToken 无刷新接口，故除 token 外还需
     * 保存账号密码，以便过期时后台自动重新登录（[com.fitsync.app.connectors.CorosApiConnector] 的 withRelogin）。
     *
     * - [accessToken]：存通用 key `COROS:token`（让 [hasSession]/[externalUserId] 正常工作）。
     *   ⚠️ COROS 的请求**绝不**走 [applyTo]——它会注入 `Authorization: Bearer` + `token` 头，
     *   COROS 网关只认小写 `accesstoken` 头；[CorosApiConnector] 自持 `apiRequest` 注入 `accesstoken`。
     * - [userId]：登录返回的 userId，存通用 key `COROS:uid`（[externalUserId] 读取，OSS objectKey 用）。
     * - [username]/[password]：仅用于 token 过期后静默重登。
     */
    fun saveCorosSession(
        accessToken: String?,
        userId: String?,
        username: String?,
        password: String?,
    ) {
        secureStore.put(key(Platform.COROS, "token"), accessToken)
        secureStore.put(key(Platform.COROS, "uid"), userId)
        secureStore.put(key(Platform.COROS, "username"), username)
        secureStore.put(key(Platform.COROS, "password"), password)
    }

    fun corosUsername(): String? = secureStore.get(key(Platform.COROS, "username"))
    fun corosPassword(): String? = secureStore.get(key(Platform.COROS, "password"))

    fun clear(platform: Platform) {
        val config = PlatformWebConfigs.get(platform)
        cookieProbeUrls(platform).forEach { origin ->
            val names = cookies.getCookie(origin).orEmpty().split(';')
                .mapNotNull { it.substringBefore('=').trim().takeIf(String::isNotBlank) }
            names.forEach { name ->
                cookies.setCookie(origin, "$name=; Max-Age=0; Path=/; Secure; SameSite=Lax")
            }
        }
        config.origins.forEach { origin ->
            WebStorage.getInstance().deleteOrigin(origin)
        }
        lastWebViewUrls.remove(platform)
        cookies.flush()
        listOf(
            "token", "refresh_token", "uid", "csrf", "jwt_web", "cookie", "sso_cookie", "domain",
            "user_agent", "username", "password", "client_id", "client_secret", "expires_at",
            "scope", "display_name", "oauth_state", "oauth_state_created_at",
        ).forEach {
            secureStore.remove(key(platform, it))
        }
    }

    private fun cookieCandidates(platform: Platform): List<Pair<String, String>> =
        cookieProbeUrls(platform).mapNotNull { url ->
            cookies.getCookie(url)?.takeIf { it.isNotBlank() }?.let { url to it }
        }

    private fun cookieProbeUrls(platform: Platform): List<String> {
        val config = PlatformWebConfigs.get(platform)
        val urls = LinkedHashSet<String>()
        lastWebViewUrls[platform]?.let { urls.add(it) }
        config.origins.forEach { urls.add(it) }
        if (platform == Platform.GARMIN) {
            GARMIN_EXTRA_COOKIE_URLS.forEach { urls.add(it) }
        }
        return urls.toList()
    }

    private fun hasGarminSession(): Boolean =
        // 后台 WorkManager 跑时 WebView 进程可能不在，优先认可持久化的 JWT_WEB；
        // WebView 登录刚完成时则看 cookie。
        !secureStore.get(key(Platform.GARMIN, "jwt_web")).isNullOrBlank() ||
            GARMIN_EXTRA_COOKIE_URLS.any { url -> hasGarminSessionCookie(cookies.getCookie(url)) }

    private fun key(platform: Platform, field: String) = "${platform.name}:$field"

    companion object {
        private val GARMIN_EXTRA_COOKIE_URLS = listOf(
            "https://connect.garmin.cn/",
            "https://connect.garmin.cn/modern/",
            "https://connect.garmin.cn/app/",
            "https://sso.garmin.cn/",
            "https://sso.garmin.cn/sso/",
            "https://www.garmin.cn/",
        )

    }
}

internal fun cookieValue(header: String?, name: String): String? = header
    ?.split(';')
    ?.asSequence()
    ?.map { it.trim() }
    ?.firstOrNull { it.substringBefore('=').equals(name, ignoreCase = true) }
    ?.substringAfter('=', "")
    ?.trim()
    ?.removeSurrounding("\"")
    ?.takeIf { it.isNotEmpty() }

/** Garmin 偶尔会调整会话 Cookie 名；兼容 JWT_WEB 及明确的 session/SSO/token 类 Cookie。 */
internal fun hasGarminSessionCookie(header: String?): Boolean =
    cookieValue(header, "JWT_WEB") != null ||
        (!header.isNullOrBlank() && sessionCookieScore(header) >= 11)

/**
 * Cookie 质量分：包含常见会话/ SSO 关键字的 Cookie 优先于仅追踪/同意偏好。
 */
internal fun sessionCookieScore(header: String): Int {
    val names = header.split(';')
        .map { it.substringBefore('=').trim().lowercase() }
        .filter { it.isNotBlank() }
    if (names.isEmpty()) return 0
    var score = names.size
    val strongHints = listOf(
        "sessionid",
        "session",
        "jwt",
        "oauth",
        "garmin-sso",
        "castgc",
        "ssotoken",
        "ticket",
        "sid",
        "token",
        "auth",
        "di-oauth",
        "idtoken",
        "access",
    )
    for (name in names) {
        if (strongHints.any { hint -> name.contains(hint) }) score += 10
    }
    return score
}

internal fun authorizationHeaderValue(platform: Platform, rawToken: String): String = when {
    platform == Platform.ONELAP -> rawToken
    rawToken.startsWith("Bearer ", ignoreCase = true) -> rawToken
    else -> "Bearer $rawToken"
}
