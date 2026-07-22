package com.fitsync.app.connectors

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * COROS 中国站后台双向同步的协议纯函数（无网络副作用，便于单测）。
 *
 * 真正的 HTTP 调用在 [CorosApiConnector]。协议来源：teamcnapi.coros.com 网页端逆向
 * （参考开源项目 woshicby/sync_garmin_to_coros、NYT87/coros-connect），后端 base
 * `https://teamcnapi.coros.com`，上传用阿里云 OSS（bucket `coros-oss`@北京）。
 *
 * 与 iGPSPORT 的关键差异：响应成功标记是 `result=="0000"`（非 code:0）；鉴权头是
 * 小写 `accesstoken`（非 Authorization Bearer）；上传的 OSS STS 凭证被盐串污染，
 * 解码时需先把 [OSS_SALT] replace 掉再 base64。
 */

/** STS 凭证 base64 文本里被 COROS 网页端拼接的盐串；解码时先 replace 掉。 */
internal const val OSS_SALT = "9y78gpoERW4lBNYL"

/** STS 响应缺失 bucket 时的兜底（中国站固定 coros-oss）。 */
internal const val COROS_OSS_BUCKET_DEFAULT = "coros-oss"

data class CorosLoginResult(
    val accessToken: String,
    val userId: String,
    val regionId: Int,
    val nickname: String?,
)

/** OSS STS 临时凭证（解码盐污染后）。中国站 region 形如 "oss-cn-beijing"。 */
data class CorosStsCredentials(
    val accessKeyId: String,
    val accessKeySecret: String,
    val securityToken: String,
    val bucket: String,
    val region: String,
    val expiration: String?,
)

/**
 * 解析 `POST /account/login` 响应。
 *
 * 成功形如 `{"result":"0000","data":{"accessToken":"...","userId":"...","regionId":2,"nickname":"..."}}`。
 * result!="0000"（凭据错/锁定）或缺 accessToken/userId → null。regionId 透传给调用方判定是否中国站。
 */
internal fun parseCorosLogin(jsonText: String): CorosLoginResult? {
    val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
    if (root.optString("result") != "0000") return null
    val data = root.optJSONObject("data") ?: return null
    val token = data.optString("accessToken").takeIf { it.isNotBlank() && it != "null" } ?: return null
    val userId = data.optString("userId").takeIf { it.isNotBlank() && it != "null" }
        ?: data.opt("userId")?.toString()?.takeIf { it.isNotBlank() && it != "null" }
        ?: return null
    val regionId = data.optInt("regionId", -1)
    val nickname = data.optString("nickname").takeIf { it.isNotBlank() && it != "null" }
    return CorosLoginResult(token, userId, regionId, nickname)
}

/**
 * 解析 `GET /activity/query` 响应的 `data.dataList[]`。
 *
 * 行字段：labelId（活动 ID，字符串）、name、sportType（int）、date（YYYYMMDD 整数）/startTime（时间戳）、
 * distance（米）。sportType 写入 metadata["sport_type"]（download 端点必需）；RemoteActivitySummary.sportType
 * 刻意留 null——交由 FitEngine 从 FIT 文件本身解析运动类型（与 iGPSPORT 一致）。
 */
internal fun parseCorosActivities(jsonText: String): List<RemoteActivitySummary>? {
    val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
    if (root.optString("result") != "0000") return null
    val data = root.optJSONObject("data") ?: return null
    val list = data.optJSONArray("dataList") ?: return null
    return buildList {
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val id = item.optString("labelId").takeIf { it.isNotBlank() && it != "null" }
                ?: item.opt("labelId")?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                ?: continue
            val sportType = item.optInt("sportType", -1).takeIf { it >= 0 }
            add(
                RemoteActivitySummary(
                    remoteActivityId = id,
                    startTimeEpochMs = corosStartEpoch(item),
                    name = item.optString("name").takeIf { it.isNotBlank() && it != "null" },
                    sportType = null,
                    distanceMeters = item.optDouble("distance", 0.0).takeIf { it > 0.0 },
                    metadata = buildMap {
                        if (sportType != null) put("sport_type", sportType.toString())
                    },
                ),
            )
        }
    }
}

/**
 * 解析 `data.totalPage`（分页上界）。缺失时返回 [Int.MAX_VALUE]——由 [CorosApiConnector.listActivities]
 * 的 `items.isNotEmpty()` 兜底停止翻页，避免接口偶发不返回 totalPage 时误判「只有一页」而漏拉历史。
 */
internal fun parseCorosTotalPage(jsonText: String): Int {
    val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return Int.MAX_VALUE
    val data = root.optJSONObject("data") ?: return Int.MAX_VALUE
    val tp = data.optInt("totalPage", -1)
    return if (tp >= 1) tp else Int.MAX_VALUE
}

/** 解析 `POST /activity/detail/download` 响应的 `data.fileUrl`（http 开头才返回）。 */
internal fun parseCorosFitUrl(jsonText: String): String? {
    val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
    if (root.optString("result") != "0000") return null
    val data = root.optJSONObject("data") ?: return null
    return data.optString("fileUrl").takeIf { it.startsWith("http") }
}

/**
 * 解析 `GET faq.coros.com/openapi/oss/sts` 响应。
 *
 * 成功形如 `{"code":200,"data":{"credentials":"<被盐污染的 base64>"}}`：先 [OSS_SALT] replace 掉，
 * 再 base64 解码得 JSON `{AccessKeyId, AccessKeySecret, SecurityToken, Bucket, Region, Expiration}`。
 * code!=200 或任一关键字段缺失 → null（调用方降级为 PermanentFailure("COROS_STS_UNAVAILABLE")）。
 */
internal fun parseCorosSts(jsonText: String): CorosStsCredentials? {
    val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
    if (root.optInt("code", -1) != 200) return null
    val data = root.optJSONObject("data") ?: return null
    val raw = data.optString("credentials").takeIf { it.isNotBlank() && it != "null" } ?: return null
    val cleaned = raw.replace(OSS_SALT, "")
    val json = runCatching {
        String(Base64.getDecoder().decode(cleaned), Charsets.UTF_8)
    }.getOrNull() ?: return null
    val creds = runCatching { JSONObject(json) }.getOrNull() ?: return null
    val accessKeyId = creds.optString("AccessKeyId").takeIf { it.isNotBlank() && it != "null" } ?: return null
    val accessKeySecret = creds.optString("AccessKeySecret").takeIf { it.isNotBlank() && it != "null" } ?: return null
    val securityToken = creds.optString("SecurityToken").takeIf { it.isNotBlank() && it != "null" } ?: return null
    val bucket = creds.optString("Bucket").takeIf { it.isNotBlank() && it != "null" } ?: COROS_OSS_BUCKET_DEFAULT
    val region = creds.optString("Region").takeIf { it.isNotBlank() && it != "null" } ?: "oss-cn-beijing"
    val expiration = creds.optString("Expiration").takeIf { it.isNotBlank() && it != "null" }
    return CorosStsCredentials(accessKeyId, accessKeySecret, securityToken, bucket, region, expiration)
}

/**
 * COROS dataList 行的起始时间：
 * - 优先 startTime/timestamp（Number→毫秒戳，秒戳自动 ×1000）；
 * - 回退 date（YYYYMMDD 8 位整数，如 20241130）→ 当地 00:00。
 * 解析失败返回 null（窗口过滤容错）。
 */
private fun corosStartEpoch(item: JSONObject): Long? {
    for (key in listOf("startTime", "timestamp", "startTimeStamp", "createTime")) {
        if (!item.has(key) || item.isNull(key)) continue
        val raw = item.opt(key)
        if (raw is Number) {
            val v = raw.toLong()
            return if (v > 9_999_999_999L) v else v * 1000
        }
    }
    val dateInt = item.optInt("date", -1)
    if (dateInt in 1000_0101..9999_1231) {
        val s = dateInt.toString().padStart(8, '0')
        return runCatching {
            LocalDate.parse(s, DateTimeFormatter.ofPattern("yyyyMMdd"))
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }
    return null
}

/** MD5 小写 hex（密码用）。COROS 登录的 pwd 字段 = MD5(明文) 32 位小写 hex。 */
internal fun md5Hex(text: String): String = md5Hex(text.toByteArray(Charsets.UTF_8))

/** MD5 小写 hex（FIT 文件用，流式）。上传时 jsonParameter.md5 与 objectKey 用原始 FIT 的 MD5。 */
internal fun md5Hex(file: File): String {
    val digest = MessageDigest.getInstance("MD5")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** MD5 小写 hex（通用字节）。 */
internal fun md5Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
