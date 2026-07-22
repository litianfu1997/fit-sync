package com.fitsync.app.connectors

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * iGPSPORT 中国站后台读取的协议纯函数（无网络副作用，便于单测）。
 *
 * 真正的 HTTP 调用在 [IgpsportApiConnector]。协议来源：app.igpsport.cn 网页端逆向，
 * 后端 base `https://prod.zh.igpsport.com`，FIT 存阿里云 OSS（公共读，host
 * `igp-zh.oss-cn-hangzhou.aliyuncs.com`）。
 */
data class IgpsportLoginResult(
    val accessToken: String,
    val memberId: String?,
)

/** getSignedUrl 响应：OSS 直传地址（带签名）+ 对象 key（uploadByOss 用 ossName=ossId）。 */
data class IgpsportSignedUrl(
    val ossId: String,
    val signedUrl: String,
)

/**
 * 解析 `POST /service/auth/account/login` 响应。
 *
 * 成功响应形如 `{"code":0,"data":{"access_token":"<JWT>","refresh_token":...,"expires_in":604800,...}}`。
 * memberid 优先从 JWT payload 解（web 端不使用 refresh_token，且响应 data 不直接含 memberid）。
 * code!=0 或缺 access_token → null（调用方据此判定凭据错误）。
 */
internal fun parseIgpsportLogin(jsonText: String): IgpsportLoginResult? {
    val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
    if (root.optInt("code", -1) != 0) return null
    val data = root.optJSONObject("data") ?: return null
    val token = data.optString("access_token")
        .takeIf { it.isNotBlank() && it != "null" } ?: return null
    val memberId = extractMemberIdFromJwt(token) ?: data.opt("memberId")?.toString()
        ?.takeIf { it.isNotBlank() && it != "null" }
    return IgpsportLoginResult(token, memberId)
}

/**
 * 解析 `GET .../oss/getSignedUrl?fileExtension=.fit` 响应。
 * 成功形如 `{"code":0,"data":{"ossId":"<uuid>.fit","signedUrl":"https://igp-zh.oss-cn-hangzhou.aliyuncs.com/<uuid>.fit?Expires=...&Signature=..."}}`。
 * ossId 同时是 OSS 对象 key，[IgpsportApiConnector.uploadViaOss] 用它作为 uploadByOss 的 ossName。
 */
internal fun parseIgpsportSignedUrl(jsonText: String): IgpsportSignedUrl? {
    val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
    val data = root.optJSONObject("data") ?: return null
    val ossId = data.optString("ossId").takeIf { it.isNotBlank() && it != "null" } ?: return null
    val signedUrl = data.optString("signedUrl").takeIf { it.startsWith("http") } ?: return null
    return IgpsportSignedUrl(ossId, signedUrl)
}

/** 从 JWT payload 读 memberid（不验签，仅 base64 解码 payload 段）。 */
private fun extractMemberIdFromJwt(jwt: String): String? = runCatching {
    val parts = jwt.split(".")
    if (parts.size < 2) return null
    val segment = parts[1]
    val padded = segment + "=".repeat((4 - segment.length % 4) % 4)
    val json = String(Base64.getUrlDecoder().decode(padded))
    val obj = JSONObject(json)
    obj.opt("memberid")?.toString()?.takeIf { it.isNotBlank() && it != "null" }
        ?: obj.opt("memberId")?.toString()?.takeIf { it.isNotBlank() && it != "null" }
}.getOrNull()

/**
 * 解析 `GET /service/web-gateway/web-analyze/activity/queryMyActivity` 响应的 `data.rows[]`。
 *
 * 行字段（来自网页 JS rows.map）：rideId、title、startTime、rideDistance、recordTime/totalMovingTime、
 * dataStatus；行内可能直接带 fitUrl/fitOssPath（省去 detail 一步，[IgpsportApiConnector.downloadFit] 优先用）。
 * sportType 刻意留空——交由 FitEngine 从 FIT 文件本身解析运动类型，避免 exerciseType 数值映射错误。
 */
internal fun parseIgpsportActivities(jsonText: String): List<RemoteActivitySummary>? {
    val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
    if (root.has("code") && root.optInt("code", -1) != 0) return null
    val data = root.optJSONObject("data") ?: return null
    val rows = data.optJSONArray("rows") ?: return null
    return buildList {
        for (i in 0 until rows.length()) {
            val item = rows.optJSONObject(i) ?: continue
            val id = readRideId(item) ?: continue
            val fitUrl = firstNonBlankString(
                item,
                "fitUrl", "fitOssPath", "fit_url", "fileUrl", "downloadUrl",
            )
            add(
                RemoteActivitySummary(
                    remoteActivityId = id,
                    startTimeEpochMs = igpsportStartEpoch(item),
                    name = item.optString("title").takeIf { it.isNotBlank() && it != "null" },
                    sportType = null,
                    distanceMeters = item.optDouble("rideDistance", 0.0).takeIf { it > 0.0 },
                    downloadUrl = fitUrl,
                    metadata = buildMap {
                        if (fitUrl != null) put("fit_url", fitUrl)
                        item.optInt("exerciseType", -1).takeIf { it >= 0 }
                            ?.let { put("exercise_type", it.toString()) }
                        item.optString("dataStatus").takeIf { it.isNotBlank() && it != "null" }
                            ?.let { put("data_status", it) }
                    },
                ),
            )
        }
    }
}

private fun readRideId(item: JSONObject): String? {
    val asLong = item.optLong("rideId", -1L)
    if (asLong >= 0) return asLong.toString()
    item.optString("rideId").takeIf { it.isNotBlank() && it != "null" }?.let { return it }
    item.optString("idStr").takeIf { it.isNotBlank() && it != "null" }?.let { return it }
    item.optLong("id", -1L).takeIf { it >= 0 }?.let { return it.toString() }
    return null
}

private fun firstNonBlankString(root: JSONObject, vararg keys: String): String? {
    keys.forEach { key ->
        if (!root.has(key) || root.isNull(key)) return@forEach
        when (val v = root.opt(key)) {
            is String -> if (v.isNotBlank() && v != "null") return v
            is Number -> return v.toString()
        }
    }
    return null
}

/**
 * 解析 `GET .../queryActivityDetail/{rideId}`（或 merge-activity/detail）响应里的 FIT 下载地址。
 * 同时查 root 与 root.data 两层，兼容 fitUrl/fitOssPath 等多种字段名。
 */
internal fun parseIgpsportFitUrl(jsonText: String): String? {
    val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
    val data = root.optJSONObject("data")
    for (obj in listOfNotNull(data, root)) {
        firstNonBlankString(
            obj,
            "fitUrl", "fitOssPath", "fit_url", "fileUrl", "originalFitUrl", "downloadUrl",
        )?.let { return it }
    }
    return null
}

/**
 * iGPSPORT startTime 多格式兼容：ISO8601（带 Z 或偏移）/ `yyyy-MM-dd HH:mm:ss` / 毫秒或秒戳。
 * 解析失败返回 null（窗口过滤与去重对其容错）。
 */
internal fun parseIgpsportStartTime(raw: Any?): Long? {
    if (raw == null) return null
    if (raw is Number) {
        val v = raw.toLong()
        return if (v in 1..9_999_999_999L) v * 1000 else v
    }
    val text = raw.toString().trim()
    if (text.isBlank()) return null
    text.toLongOrNull()?.let { return if (it in 1..9_999_999_999L) it * 1000 else it }
    return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching {
            LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
        ?: runCatching {
            // list 行只给到日期精度："2026.03.07" 或 "2026-03-07"（精确时间在 detail 接口才有）
            LocalDate.parse(text.replace('.', '-'), DateTimeFormatter.ISO_LOCAL_DATE)
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
}

private fun igpsportStartEpoch(item: JSONObject): Long? {
    for (key in listOf("startTime", "rideStartTime", "beginTime", "createTime")) {
        if (item.has(key) && !item.isNull(key)) {
            parseIgpsportStartTime(item.opt(key))?.let { return it }
        }
    }
    return null
}
