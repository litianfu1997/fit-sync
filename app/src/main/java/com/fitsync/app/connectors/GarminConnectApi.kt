package com.fitsync.app.connectors

import com.fitsync.app.domain.model.Platform
import com.fitsync.app.fit.FitCrc
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
 * Garmin 的 2xx 只代表导入已受理：有状态地址时持久化该地址继续轮询；没有时改由目标列表确认。
 * 两种分支都返回 Processing，确保 Worker 不会重新 POST 同一个 FIT。
 */
internal fun garminAcceptedProcessingResult(
    statusUrl: String?,
    provisionalRemoteId: String,
): ConnectorResult.Processing = ConnectorResult.Processing(
    encodeAsyncUploadConfirmationToken(Platform.GARMIN, statusUrl ?: provisionalRemoteId),
    ASYNC_UPLOAD_CONFIRM_POLL_MS,
)

internal const val GARMIN_REMOTE_POLL_AFTER_MS = 30_000L

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

/** 提取 Garmin 明确返回的首条导入失败原因；只读取错误 code/content/message，不读取凭据字段。 */
internal fun garminImportFailureSummary(jsonText: String): String? {
    val detail = runCatching { JSONObject(jsonText).optJSONObject("detailedImportResult") }.getOrNull()
        ?: return null
    val failures = detail.optJSONArray("failures") ?: return null
    val failure = failures.optJSONObject(0) ?: return null
    val messages = failure.optJSONArray("messages")
    val message = messages?.optJSONObject(0)
    if (message != null) {
        val parts = buildList {
            message.opt("code")?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                ?.let { add("code=$it") }
            sequenceOf("content", "message", "errorMessage")
                .mapNotNull { key -> message.optString(key).takeIf { it.isNotBlank() } }
                .firstOrNull()
                ?.let { add("message=$it") }
        }
        if (parts.isNotEmpty()) return parts.joinToString(" ")
        return "MESSAGE_KEYS=" + message.keys().asSequence().take(8).joinToString(",")
    }
    return "FAILURE_KEYS=" + failure.keys().asSequence().take(8).joinToString(",")
}

/** Garmin 已完成处理但拒绝了当前 FIT：这是文件级结果，不能据此把整个账号标记为待处理。 */
internal fun garminImportRejectedFailure(summary: String? = null): ConnectorResult.PermanentFailure =
    ConnectorResult.PermanentFailure(
        code = "GARMIN_IMPORT_REJECTED",
        message = summary?.takeIf { it.isNotBlank() }
            ?.let { "Garmin 拒绝导入该文件：$it" }
            ?: "Garmin 拒绝导入该文件",
    )

internal data class GarminPreparedFitBytes(
    val bytes: ByteArray,
    val fileCrcRepaired: Boolean,
    val headerCrcRepaired: Boolean,
)

/**
 * 为 Garmin 导入修复 FIT 的 Header CRC（若原文件声明了 CRC）和文件尾 CRC。
 * 只接受结构完整、长度与 Header 声明一致的 FIT；修复发生在上传副本，不会改写本地原件。
 */
internal fun prepareGarminFitBytes(source: ByteArray): GarminPreparedFitBytes? {
    if (source.size < 14 || String(source, 8, 4, Charsets.US_ASCII) != ".FIT") return null
    val headerSize = source[0].toInt() and 0xFF
    if (headerSize !in setOf(12, 14)) return null
    val dataSize = FitCrc.readUInt32Le(source, 4)
    val contentSize = headerSize.toLong() + dataSize
    val totalSize = contentSize + 2L
    if (contentSize > Int.MAX_VALUE || totalSize != source.size.toLong()) return null

    val repaired = source.copyOf()
    var headerRepaired = false
    if (headerSize == 14) {
        val storedHeaderCrc = FitCrc.readUInt16Le(repaired, 12)
        val actualHeaderCrc = FitCrc.compute(repaired, 0, 12)
        if (storedHeaderCrc != 0 && storedHeaderCrc != actualHeaderCrc) {
            repaired[12] = (actualHeaderCrc and 0xFF).toByte()
            repaired[13] = ((actualHeaderCrc ushr 8) and 0xFF).toByte()
            headerRepaired = true
        }
    }

    val fileCrcOffset = contentSize.toInt()
    val storedFileCrc = FitCrc.readUInt16Le(repaired, fileCrcOffset)
    val actualFileCrc = FitCrc.compute(repaired, 0, fileCrcOffset)
    val fileRepaired = storedFileCrc != actualFileCrc
    if (fileRepaired) {
        repaired[fileCrcOffset] = (actualFileCrc and 0xFF).toByte()
        repaired[fileCrcOffset + 1] = ((actualFileCrc ushr 8) and 0xFF).toByte()
    }
    return GarminPreparedFitBytes(repaired, fileRepaired, headerRepaired)
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
 * `…/activity-service/activity/status/{id}/{token}`。中国站上传响应虽然返回
 * `connectapi.garmin.cn`，但 JWT_WEB 网页会话直连该域会得到 403；必须与上传请求一致，
 * 经 `connect.garmin.cn/gc-api/…` 同源代理并携带 DI-Backend。国际站仍保留直连形式。
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
        if (tld == "cn") {
            append("https://connect.garmin.cn/gc-api")
        } else {
            append("https://connectapi.garmin.com")
        }
        append("/activity-service/activity/status/")
        append(match.groupValues[1]).append('/').append(match.groupValues[2])
        url.query?.takeIf { it.isNotEmpty() }?.let { append('?').append(it) }
    }
}

/**
 * 解析活动列表 JSON（`gc-api/activitylist-service/activities/search/activities` 返回的数组）为
 * [RemoteActivitySummary]。不做时间过滤——由调用方按增量窗口过滤。
 */
internal fun parseGarminActivities(jsonText: String): List<RemoteActivitySummary>? {
    val arr = runCatching { JSONArray(jsonText) }.getOrNull() ?: return null
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
