package com.fitsync.app.strava

import android.net.Uri
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.web.WebSessionManager
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class StravaOAuthAccount(
    val athleteId: String?,
    val displayName: String?,
)

sealed interface StravaOAuthResult {
    data class Success(val account: StravaOAuthAccount) : StravaOAuthResult
    data class Failure(val message: String) : StravaOAuthResult
}

class StravaOAuthService(
    private val sessions: WebSessionManager,
    private val client: OkHttpClient = OkHttpClient(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val refreshMutex = Mutex()

    fun saveCredentials(clientId: String, clientSecret: String) {
        require(clientId.isNotBlank()) { "Client ID 不能为空" }
        require(clientSecret.isNotBlank()) { "Client Secret 不能为空" }
        sessions.saveStravaCredentials(clientId.trim(), clientSecret.trim())
    }

    fun isConfigured(): Boolean =
        !sessions.stravaClientId().isNullOrBlank() && !sessions.stravaClientSecret().isNullOrBlank()

    fun hasOAuthSession(): Boolean =
        !sessions.token(Platform.STRAVA).isNullOrBlank() &&
            !sessions.refreshToken(Platform.STRAVA).isNullOrBlank() &&
            hasRequiredScopes(sessions.stravaScope())

    fun hasWebSession(): Boolean = sessions.hasStravaWebSession()

    fun createAuthorizationUri(): Uri {
        val clientId = requireNotNull(sessions.stravaClientId()) { "请先配置 Strava Client ID" }
        require(!sessions.stravaClientSecret().isNullOrBlank()) { "请先配置 Strava Client Secret" }
        val stateBytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val state = Base64.getUrlEncoder().withoutPadding().encodeToString(stateBytes)
        sessions.saveStravaOAuthState(state, nowMs())
        return Uri.parse(AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("approval_prompt", "auto")
            .appendQueryParameter("scope", REQUIRED_SCOPE)
            .appendQueryParameter("state", state)
            .build()
    }

    suspend fun completeAuthorization(callback: Uri): StravaOAuthResult {
        if (callback.scheme != REDIRECT_SCHEME || callback.host != REDIRECT_HOST ||
            callback.path != REDIRECT_PATH
        ) {
            return StravaOAuthResult.Failure("Strava 回调地址不正确")
        }
        val pending = sessions.consumeStravaOAuthState()
            ?: return StravaOAuthResult.Failure("Strava 授权请求不存在或已处理")
        val callbackState = callback.getQueryParameter("state")
        if (callbackState != pending.first) return StravaOAuthResult.Failure("Strava 授权 state 校验失败")
        if (nowMs() - pending.second !in 0..STATE_TTL_MS) {
            return StravaOAuthResult.Failure("Strava 授权已超时，请重新发起")
        }
        callback.getQueryParameter("error")?.let {
            return StravaOAuthResult.Failure("已取消 Strava 授权")
        }
        val grantedScope = callback.getQueryParameter("scope")
        if (!hasRequiredScopes(grantedScope)) {
            return StravaOAuthResult.Failure("Strava 未授予读取全部活动和上传权限")
        }
        val code = callback.getQueryParameter("code")
            ?.takeIf(String::isNotBlank)
            ?: return StravaOAuthResult.Failure("Strava 回调缺少授权码")
        return exchangeCode(code, grantedScope.orEmpty())
    }

    suspend fun accessToken(failedToken: String? = null): String? = refreshMutex.withLock {
        val current = sessions.token(Platform.STRAVA) ?: return@withLock null
        val expiresAt = sessions.stravaExpiresAtEpochSeconds() ?: 0L
        val shouldRefresh = failedToken != null && current == failedToken ||
            expiresAt * 1000L - nowMs() < REFRESH_EARLY_MS
        if (!shouldRefresh || failedToken != null && current != failedToken) return@withLock current
        refreshToken() ?: return@withLock null
    }

    suspend fun revoke(): Boolean = withContext(Dispatchers.IO) {
        val refreshToken = sessions.refreshToken(Platform.STRAVA)
        val token = refreshToken ?: sessions.token(Platform.STRAVA) ?: return@withContext true
        val clientId = sessions.stravaClientId() ?: return@withContext false
        val clientSecret = sessions.stravaClientSecret() ?: return@withContext false
        val basic = Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray())
        val request = Request.Builder()
            .url(REVOKE_URL)
            .post(
                FormBody.Builder()
                    .add("token", token)
                    .add("token_type_hint", if (refreshToken != null) "refresh_token" else "access_token")
                    .build(),
            )
            .header("Authorization", "Basic $basic")
            .build()
        runCatching { client.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    private suspend fun exchangeCode(code: String, grantedScope: String): StravaOAuthResult =
        withContext(Dispatchers.IO) {
            val clientId = sessions.stravaClientId()
                ?: return@withContext StravaOAuthResult.Failure("缺少 Strava Client ID")
            val secret = sessions.stravaClientSecret()
                ?: return@withContext StravaOAuthResult.Failure("缺少 Strava Client Secret")
            val body = FormBody.Builder()
                .add("client_id", clientId)
                .add("client_secret", secret)
                .add("code", code)
                .add("grant_type", "authorization_code")
                .build()
            val request = Request.Builder().url(TOKEN_URL).post(body).build()
            runCatching {
                client.newCall(request).execute().use { response ->
                    val json = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@use StravaOAuthResult.Failure("Strava token 交换失败（${response.code}）")
                    }
                    val root = JSONObject(json)
                    val athlete = root.optJSONObject("athlete")
                    val athleteId = athlete?.optLong("id")?.takeIf { it > 0 }?.toString()
                    val displayName = listOf(
                        athlete?.optString("firstname").orEmpty(),
                        athlete?.optString("lastname").orEmpty(),
                    ).filter(String::isNotBlank).joinToString(" ").ifBlank { null }
                    saveTokenJson(root, grantedScope, athleteId, displayName)
                    StravaOAuthResult.Success(StravaOAuthAccount(athleteId, displayName))
                }
            }.getOrElse { StravaOAuthResult.Failure("无法连接 Strava，请检查网络后重试") }
        }

    private suspend fun refreshToken(): String? = withContext(Dispatchers.IO) {
        val clientId = sessions.stravaClientId() ?: return@withContext null
        val secret = sessions.stravaClientSecret() ?: return@withContext null
        val refreshToken = sessions.refreshToken(Platform.STRAVA) ?: return@withContext null
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", secret)
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
            .build()
        val request = Request.Builder().url(TOKEN_URL).post(body).build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val root = JSONObject(response.body?.string().orEmpty())
                saveTokenJson(
                    root = root,
                    grantedScope = sessions.stravaScope().orEmpty(),
                    athleteId = sessions.externalUserId(Platform.STRAVA),
                    displayName = sessions.stravaDisplayName(),
                )
                root.optString("access_token").takeIf(String::isNotBlank)
            }
        }.getOrNull()
    }

    private fun saveTokenJson(
        root: JSONObject,
        grantedScope: String,
        athleteId: String?,
        displayName: String?,
    ) {
        val accessToken = root.getString("access_token")
        val refreshToken = root.getString("refresh_token")
        val expiresAt = root.getLong("expires_at")
        sessions.saveStravaTokens(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtEpochSeconds = expiresAt,
            scope = grantedScope,
            athleteId = athleteId,
            displayName = displayName,
        )
    }

    companion object {
        const val REDIRECT_URI = "com.fitsync.app://localhost/strava/oauth"
        const val REQUIRED_SCOPE = "read,activity:read_all,activity:write"
        private const val REDIRECT_SCHEME = "com.fitsync.app"
        private const val REDIRECT_HOST = "localhost"
        private const val REDIRECT_PATH = "/strava/oauth"
        private const val AUTHORIZE_URL = "https://www.strava.com/oauth/mobile/authorize"
        private const val TOKEN_URL = "https://www.strava.com/oauth/token"
        private const val REVOKE_URL = "https://www.strava.com/oauth/revoke"
        private const val STATE_TTL_MS = 10L * 60 * 1000
        private const val REFRESH_EARLY_MS = 5L * 60 * 1000

        fun hasRequiredScopes(scope: String?): Boolean {
            val granted = scope.orEmpty().split(',', ' ').filter(String::isNotBlank).toSet()
            return setOf("read", "activity:read_all", "activity:write").all(granted::contains)
        }
    }
}
