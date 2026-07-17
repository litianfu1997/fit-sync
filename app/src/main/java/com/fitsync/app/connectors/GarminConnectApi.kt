package com.fitsync.app.connectors

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Garmin Connect 后台自动上传的协议纯函数与状态解析。
 *
 * 这些函数刻意保持「无网络副作用」，便于单测覆盖。真正的 HTTP 调用在
 * [GarminConnectApiConnector] 中完成，逻辑分支全部委托到这里。
 *
 * 协议来源：Garmin Connect 网页端 `Import Data` 实际调用的 `connectapi.garmin.{cn|com}`
 * 私有上传接口。中国站（`connect.garmin.cn`）会话通过 `JWT_WEB` Cookie 维持。
 */
data class GarminImportState(
    val isFinal: Boolean,
    val remoteActivityId: String?,
    val duplicate: Boolean,
    val uploadId: Long? = null,
    /** isFinal 为真时区分：successes 终态(真成功)为 true；failures 终态(含 EU consent 等非重复失败)为 false。 */
    val success: Boolean = false,
)

/**
 * 上传端点。中国站统一走 connect.garmin.cn 同源 gc-api 代理——实测 connectapi 直连对中国站
 * cookie 会话不可靠，而 gc-api/upload-service/upload + Connect-Csrf-Token 稳定通过。[domain]
 * 参数保留兼容，实际仅认中国站。
 */
internal fun garminUploadUrl(domain: String): String =
    "https://connect.garmin.cn/gc-api/upload-service/upload"

/**
 * 解析上传（或轮询）返回的 `detailedImportResult`。
 *
 * - `successes` 非空 → 终态成功，[GarminImportState.remoteActivityId] 取首个 `internalId`。
 * - 否则 `failures` 非空 → 终态失败；若失败原因含 `duplicate.activity` 视为重复。
 * - 两者皆空（通常只有 `uploadId`）→ 仍在处理中，[GarminImportState.isFinal] = false。
 * - 缺少 `detailedImportResult` 字段或解析失败 → null（调用方按需重试/轮询）。
 */
internal fun parseGarminImportState(jsonText: String): GarminImportState? {
    val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
    val detail = root.optJSONObject("detailedImportResult") ?: return null
    val uploadId = detail.optLong("uploadId", -1L).takeIf { it >= 0 }

    val successes = detail.optJSONArray("successes")
    for (i in 0 until (successes?.length() ?: 0)) {
        val item = successes?.optJSONObject(i) ?: continue
        val id = readInternalId(item) ?: continue
        return GarminImportState(
            isFinal = true,
            remoteActivityId = id,
            duplicate = false,
            uploadId = uploadId,
            success = true,
        )
    }

    val failures = detail.optJSONArray("failures")
    for (i in 0 until (failures?.length() ?: 0)) {
        val item = failures?.optJSONObject(i) ?: continue
        val id = readInternalId(item)
        val messages = item.optJSONArray("messages")
        // 真实 Garmin 在重复时返回 messages[].code=202（整数）+ content="Duplicate Activity."；
        // 这里同时兼容旧测试里字符串 code "duplicate.activity"，以及 content 含 duplicate 的情况。
        val duplicate = (0 until (messages?.length() ?: 0)).any { idx ->
            val msg = messages?.optJSONObject(idx) ?: return@any false
            msg.optInt("code", -1) == 202 ||
                msg.optString("code") == "duplicate.activity" ||
                msg.optString("content").contains("duplicate", ignoreCase = true)
        }
        return GarminImportState(
            isFinal = true,
            remoteActivityId = id,
            duplicate = duplicate,
            uploadId = uploadId,
        )
    }

    // 既无成功也无失败：仍在服务端处理，需要轮询。
    return GarminImportState(
        isFinal = false,
        remoteActivityId = null,
        duplicate = false,
        uploadId = uploadId,
    )
}

private fun readInternalId(item: JSONObject): String? {
    val asLong = item.optLong("internalId", -1L)
    if (asLong >= 0) return asLong.toString()
    val asString = item.optString("internalId", "")
    return asString.takeIf { it.isNotBlank() && it != "null" }
}

/**
 * 规范化「上传状态轮询」URL。
 *
 * Garmin 网页端在上传后可能返回一个待轮询的 status 链接，形如
 * `…/activity-service/activity/status/{id}/{token}`。中国站有时经由
 * `connect.garmin.cn/gc-api/…` 代理，这里统一改写为直连 `connectapi.garmin.{cn|com}`。
 *
 * 只接受 https 且 host 属于 garmin 的来源；路径不匹配 `{id}/{token}` 两段形态时返回 null，
 * 以免把任意链接当成轮询地址去 GET。
 */
internal fun garminStatusApiUrl(rawUrl: String, domain: String = "garmin.com"): String? {
    val url = runCatching { URI(rawUrl).toURL() }.getOrNull() ?: return null
    if (!url.protocol.equals("https", ignoreCase = true)) return null
    val host = url.host?.lowercase().orEmpty()
    val isGarminHost = host.endsWith("connectapi.garmin.com") || host.endsWith("connectapi.garmin.cn") ||
        host.endsWith("connect.garmin.com") || host.endsWith("connect.garmin.cn")
    if (!isGarminHost) return null

    var path = url.path.orEmpty()
    if (path.startsWith("/gc-api/")) path = path.removePrefix("/gc-api")
    val match = Regex("""^/activity-service/activity/status/([^/]+)/([^/?#]+)$""").find(path)
        ?: return null

    val tld = if (domain.equals("garmin.cn", ignoreCase = true) || host.endsWith(".garmin.cn")) "cn" else "com"
    return buildString {
        append("https://connectapi.garmin.").append(tld)
        append("/activity-service/activity/status/")
        append(match.groupValues[1]).append('/').append(match.groupValues[2])
        url.query?.takeIf { it.isNotEmpty() }?.let { append('?').append(it) }
    }
}

/**
 * 解析活动列表 JSON（`gc-api/activitylist-service/activities/search/activities` 返回的数组）为
 * [RemoteActivitySummary]。不做时间过滤——由调用方按增量窗口过滤。
 */
internal fun parseGarminActivities(jsonText: String): List<RemoteActivitySummary> {
    val arr = runCatching { JSONArray(jsonText) }.getOrNull() ?: return emptyList()
    return buildList {
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val id = item.optLong("activityId", -1L)
            if (id < 0) continue
            add(
                RemoteActivitySummary(
                    remoteActivityId = id.toString(),
                    startTimeEpochMs = garminActivityEpoch(item),
                    name = item.optString("activityName").takeIf { it.isNotBlank() && it != "null" },
                    sportType = item.optJSONObject("activityType")
                        ?.optString("typeKey")
                        ?.takeIf { it.isNotBlank() && it != "null" },
                    distanceMeters = item.optDouble("distance", 0.0).takeIf { it > 0.0 },
                ),
            )
        }
    }
}

private fun garminActivityEpoch(item: JSONObject): Long? {
    // 优先 startTimeGMT（"yyyy-MM-dd HH:mm:ss" GMT）；回退 startTimeLocal（本地时区）。
    val gmt = item.optString("startTimeGMT").takeIf { it.isNotBlank() && it != "null" }
    if (gmt != null) {
        return runCatching {
            LocalDateTime.parse(gmt, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                .atZone(ZoneId.of("GMT")).toInstant().toEpochMilli()
        }.getOrNull()
    }
    val local = item.optString("startTimeLocal").takeIf { it.isNotBlank() && it != "null" } ?: return null
    return runCatching {
        LocalDateTime.parse(local, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()
}
