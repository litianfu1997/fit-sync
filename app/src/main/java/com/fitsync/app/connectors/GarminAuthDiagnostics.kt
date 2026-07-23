package com.fitsync.app.connectors

import android.content.Context
import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/**
 * Garmin 登录态诊断记录。
 *
 * 用于定位「佳明登录已失效」误报的根因：区分 JWT_WEB 是真空（哪一级回退失败）还是陈旧
 * （有 JWT 但服务端返回 401 / 302→sso），以及 Garmin 是否在正常 200 响应里通过 Set-Cookie
 * 轮换 JWT_WEB（决定阶段二走 CookieJar 滚动还是 SSO 静默换发）。
 *
 * 安全性：只记布尔/枚举/码/主机名，**绝不记录 cookie 值或 JWT 明文**，可安全复制反馈。
 * 存放于专用非加密 SharedPreferences（[PREFS_NAME]），与凭据用的 SecureSessionStore 隔离，
 * 避免 Keystore 解密失败拖累诊断可读性。
 */
object GarminAuthDiagnostics {
    private const val TAG = "FitSyncGarmin"
    private const val PREFS_NAME = "garmin_auth_diag"
    private const val PREF_KEY = "events"
    private const val CAPACITY = 24

    private val buffer = ArrayDeque<Event>()
    private var prefs: android.content.SharedPreferences? = null

    /** 在 [FitSyncApp.onCreate] 调用，拿到 Context 以启用持久化；未初始化时仅保留内存缓冲。 */
    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        runCatching { prefs?.getString(PREF_KEY, null)?.let { restore(it) } }
    }

    @Synchronized
    fun record(event: Event) {
        if (buffer.size >= CAPACITY) buffer.removeFirst()
        buffer.addLast(event)
        persist()
        log(event)
    }

    @Synchronized
    fun snapshot(): List<Event> = buffer.reversed() // 最近在前

    @Synchronized
    fun clear() {
        buffer.clear()
        prefs?.edit()?.remove(PREF_KEY)?.apply()
    }

    private fun persist() {
        runCatching { prefs?.edit()?.putString(PREF_KEY, serialize())?.apply() }
    }

    private fun serialize(): String {
        val arr = JSONArray()
        buffer.forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    private fun restore(json: String) {
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val event = Event.fromJson(arr.getJSONObject(i)) ?: continue
            if (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast(event)
        }
    }

    private fun log(event: Event) {
        val msg = "${event.operation}/${event.phase} outcome=${event.outcome}" +
            " code=${event.httpCode ?: "-"} src=${event.cookieSource.ifBlank { "-" }}" +
            " setCookieJwt=${event.setCookieHasJwtWeb} loc=${event.locationHost ?: "-"}" +
            " hadAccount=${event.hadPersistedAccount}"
        if (event.outcome == OUTCOME_REQUIRES_LOGIN || event.outcome == OUTCOME_PERMANENT_FAIL) {
            Log.w(TAG, msg)
        } else {
            Log.i(TAG, msg)
        }
    }

    /**
     * 单条诊断事件。各字段均可选（按 phase 选填）。
     *
     * - [phase]：RESPONSE（OkHttp 响应）/ COOKIE_SELECT（cookie 三级回退）/ COOKIE_MISSING / DECISION。
     * - [cookieSource]：COOKIE_SELECT 用，LIVE/STORED/JWT/NONE。
     * - [setCookieHasJwtWeb]：RESPONSE 用，回答「服务端是否轮换 JWT_WEB」。
     * - [outcome]：结果标签，如 OK / HTTP_FAIL / RETRYABLE / REQUIRES_LOGIN / PERMANENT_FAIL。
     */
    data class Event(
        val timestampEpochMs: Long,
        val operation: String,
        val phase: String,
        val outcome: String,
        val cookieSource: String = "",
        val httpCode: Int? = null,
        val locationHost: String? = null,
        val setCookieHasJwtWeb: Boolean = false,
        val hadPersistedAccount: Boolean = false,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("ts", timestampEpochMs)
            put("op", operation)
            put("phase", phase)
            put("outcome", outcome)
            if (cookieSource.isNotBlank()) put("src", cookieSource)
            httpCode?.let { put("code", it) }
            locationHost?.let { put("loc", it) }
            put("setJwt", setCookieHasJwtWeb)
            put("hadAcct", hadPersistedAccount)
        }

        companion object {
            fun fromJson(o: JSONObject): Event? = runCatching {
                Event(
                    timestampEpochMs = o.optLong("ts", 0L),
                    operation = o.optString("op"),
                    phase = o.optString("phase"),
                    outcome = o.optString("outcome"),
                    cookieSource = o.optString("src"),
                    httpCode = if (o.has("code")) o.getInt("code") else null,
                    locationHost = o.optString("loc").takeIf { it.isNotBlank() },
                    setCookieHasJwtWeb = o.optBoolean("setJwt"),
                    hadPersistedAccount = o.optBoolean("hadAcct"),
                )
            }.getOrNull()
        }
    }

    const val OUTCOME_REQUIRES_LOGIN = "REQUIRES_LOGIN"
    const val OUTCOME_PERMANENT_FAIL = "PERMANENT_FAIL"
    const val OUTCOME_RETRYABLE = "RETRYABLE"
    const val OUTCOME_OK = "OK"
}

/**
 * OkHttp 应用拦截器：对 Garmin gc-api 每个响应只读 headers，记录 HTTP code、Location 主机、
 * Set-Cookie 是否含 JWT_WEB。不碰 body（对下载的大 FIT 流安全）。
 *
 * 因 [GarminConnectApiConnector.httpClient] 用了 followRedirects(false)，302 响应会原样
 * 经过本拦截器，其 Location 与 Set-Cookie 都被捕获——正好覆盖「302→sso」失效场景。
 */
class GarminAuthDiagnosticsInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        runCatching {
            val setCookieHasJwtWeb = response.headers("Set-Cookie").any { header ->
                header.substringBefore('=').trim().equals("JWT_WEB", ignoreCase = true)
            }
            val locationHost = response.header("Location")?.let { loc ->
                runCatching { java.net.URI(loc).host }.getOrNull()
            }
            GarminAuthDiagnostics.record(
                GarminAuthDiagnostics.Event(
                    timestampEpochMs = System.currentTimeMillis(),
                    operation = inferOperation(request.url.encodedPath),
                    phase = "RESPONSE",
                    outcome = if (response.isSuccessful) GarminAuthDiagnostics.OUTCOME_OK else "HTTP_${response.code}",
                    httpCode = response.code,
                    locationHost = locationHost,
                    setCookieHasJwtWeb = setCookieHasJwtWeb,
                ),
            )
        }
        return response
    }

    private fun inferOperation(encodedPath: String): String = when {
        encodedPath.contains("activitylist-service") -> "LIST"
        encodedPath.contains("download-service") -> "DOWNLOAD"
        encodedPath.contains("upload-service") -> "UPLOAD"
        encodedPath.contains("/status/") -> "POLL"
        encodedPath.contains("search/activities") -> "VALIDATE"
        else -> "OTHER"
    }
}
