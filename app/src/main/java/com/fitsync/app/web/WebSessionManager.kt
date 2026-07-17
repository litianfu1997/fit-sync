package com.fitsync.app.web

import android.webkit.CookieManager
import android.webkit.WebStorage
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.security.SecureSessionStore
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

data class GarminWebSession(
    val domain: String,
    val jwtWeb: String,
    val csrf: String?,
)

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
        Platform.GARMIN -> garminWebSession() != null
        else -> !cookieHeader(platform).isNullOrBlank() || !token(platform).isNullOrBlank()
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

    /** 从 WebView 当前页捕获的 Garmin CSRF，供后台上传复用。 */
    fun saveGarminCsrf(token: String?) {
        secureStore.put(key(Platform.GARMIN, "csrf"), token)
    }

    fun garminCsrf(): String? = secureStore.get(key(Platform.GARMIN, "csrf"))

    /**
     * 从 WebView Cookie 中保存可供后台 API 使用的 JWT_WEB。
     * 普通 SSO/追踪 Cookie 不能证明后台上传已具备授权。
     */
    fun captureGarminWebSession(connectOrigin: String?, csrf: String?): Boolean {
        val hintedDomain = garminDomain(connectOrigin)
        val current = findGarminJwtWeb(hintedDomain) ?: return false
        val previousJwtWeb = secureStore.get(key(Platform.GARMIN, "jwt_web"))
        secureStore.put(key(Platform.GARMIN, "jwt_web"), current.second)
        secureStore.put(key(Platform.GARMIN, "domain"), current.first)
        when {
            !csrf.isNullOrBlank() -> saveGarminCsrf(csrf)
            previousJwtWeb != current.second -> saveGarminCsrf(null)
        }
        return true
    }

    fun garminWebSession(): GarminWebSession? {
        val storedDomain = secureStore.get(key(Platform.GARMIN, "domain"))
            ?.takeIf { it == GARMIN_COM_DOMAIN || it == GARMIN_CN_DOMAIN }
        val current = findGarminJwtWeb(storedDomain)
        if (current != null) {
            secureStore.put(key(Platform.GARMIN, "jwt_web"), current.second)
            secureStore.put(key(Platform.GARMIN, "domain"), current.first)
        }
        val jwtWeb = current?.second ?: secureStore.get(key(Platform.GARMIN, "jwt_web"))
            ?: return null
        return GarminWebSession(
            domain = current?.first ?: storedDomain ?: GARMIN_COM_DOMAIN,
            jwtWeb = jwtWeb,
            csrf = garminCsrf(),
        )
    }

    fun token(platform: Platform): String? = secureStore.get(key(platform, "token"))
    fun refreshToken(platform: Platform): String? = secureStore.get(key(platform, "refresh_token"))
    fun externalUserId(platform: Platform): String? = secureStore.get(key(platform, "uid"))

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
        listOf("token", "refresh_token", "uid", "csrf", "jwt_web", "domain").forEach {
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

    private fun findGarminJwtWeb(preferredDomain: String?): Pair<String, String>? {
        val domains = linkedSetOf<String>().apply {
            preferredDomain?.let(::add)
            add(GARMIN_COM_DOMAIN)
            add(GARMIN_CN_DOMAIN)
        }
        domains.forEach { domain ->
            val urls = listOf(
                "https://connect.$domain/",
                "https://connect.$domain/modern/",
                "https://connectapi.$domain/",
            )
            urls.forEach { url ->
                cookieValue(cookies.getCookie(url), "JWT_WEB")?.let { return domain to it }
            }
        }
        return null
    }

    private fun key(platform: Platform, field: String) = "${platform.name}:$field"

    companion object {
        private val GARMIN_EXTRA_COOKIE_URLS = listOf(
            "https://connect.garmin.com/",
            "https://connect.garmin.com/modern/",
            "https://connect.garmin.com/app/",
            "https://sso.garmin.com/",
            "https://sso.garmin.com/sso/",
            "https://www.garmin.com/",
            "https://accounts.garmin.com/",
            "https://connectapi.garmin.com/",
            "https://connect.garmin.cn/",
            "https://connect.garmin.cn/modern/",
            "https://connect.garmin.cn/app/",
            "https://sso.garmin.cn/",
            "https://sso.garmin.cn/sso/",
            "https://www.garmin.cn/",
            "https://connectapi.garmin.cn/",
        )

        private const val GARMIN_COM_DOMAIN = "garmin.com"
        private const val GARMIN_CN_DOMAIN = "garmin.cn"
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

private fun garminDomain(origin: String?): String? {
    val value = origin ?: return null
    val host = runCatching { java.net.URI(value).host }.getOrNull() ?: return null
    return when {
        host == "garmin.cn" || host.endsWith(".garmin.cn") -> "garmin.cn"
        host == "garmin.com" || host.endsWith(".garmin.com") -> "garmin.com"
        else -> null
    }
}

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
