package com.fitsync.app.web

import android.webkit.CookieManager
import android.webkit.WebStorage
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.security.SecureSessionStore
import okhttp3.Request

class WebSessionManager(
    private val secureStore: SecureSessionStore,
) {
    private val cookies: CookieManager
        get() = CookieManager.getInstance()

    fun prepareCookies() {
        cookies.setAcceptCookie(true)
    }

    fun cookieHeader(platform: Platform): String? {
        val config = PlatformWebConfigs.get(platform)
        return config.origins.asSequence()
            .mapNotNull { cookies.getCookie(it) }
            .firstOrNull { it.isNotBlank() }
    }

    fun hasSession(platform: Platform): Boolean =
        !cookieHeader(platform).isNullOrBlank() || !token(platform).isNullOrBlank()

    fun applyTo(platform: Platform, builder: Request.Builder): Request.Builder {
        cookieHeader(platform)?.let { builder.header("Cookie", it) }
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

    fun clear(platform: Platform) {
        val config = PlatformWebConfigs.get(platform)
        config.origins.forEach { origin ->
            val names = cookies.getCookie(origin).orEmpty().split(';')
                .mapNotNull { it.substringBefore('=').trim().takeIf(String::isNotBlank) }
            names.forEach { name ->
                cookies.setCookie(origin, "$name=; Max-Age=0; Path=/; Secure; SameSite=Lax")
            }
            WebStorage.getInstance().deleteOrigin(origin)
        }
        cookies.flush()
        listOf("token", "refresh_token", "uid").forEach { secureStore.remove(key(platform, it)) }
    }

    private fun key(platform: Platform, field: String) = "${platform.name}:$field"
}

internal fun authorizationHeaderValue(platform: Platform, rawToken: String): String = when {
    platform == Platform.ONELAP -> rawToken
    rawToken.startsWith("Bearer ", ignoreCase = true) -> rawToken
    else -> "Bearer $rawToken"
}
