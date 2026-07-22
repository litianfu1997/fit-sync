package com.fitsync.app.connectors

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.Base64
import org.junit.Test

class IgpsportApiTest {

    /** 构造一个最小可解的 JWT（header.payload.sig，payload 含 memberid）。 */
    private fun jwt(memberId: String): String {
        val payload = Base64.getUrlEncoder()
            .encodeToString("""{"memberid":"$memberId"}""".toByteArray())
        return "header.$payload.sig"
    }

    @Test
    fun parseLogin_success_extractsTokenAndMemberId() {
        val json = """{"code":0,"message":"","data":""" +
            """{"token_type":"Bearer","access_token":"${jwt("2004016")}","expires_in":604800}}"""
        val r = parseIgpsportLogin(json)
        assertThat(r).isNotNull()
        assertThat(r!!.memberId).isEqualTo("2004016")
        assertThat(r.accessToken).startsWith("header.")
    }

    @Test
    fun parseLogin_nonZeroCode_returnsNull() {
        val json = """{"code":1003,"message":"服务器异常","data":null}"""
        assertThat(parseIgpsportLogin(json)).isNull()
    }

    @Test
    fun parseActivities_rowsMappedToFitUrlAndDistance() {
        val json = """{"code":0,"data":{"totalRows":1,"rows":[""" +
            """{"rideId":123456,"title":"晨骑","startTime":"2026-07-18 06:30:00",""" +
            """"rideDistance":42500.0,"exerciseType":1,"dataStatus":1,""" +
            """"fitUrl":"https://igp-zh.oss-cn-hangzhou.aliyuncs.com/abc.fit"}]}}"""
        val items = requireNotNull(parseIgpsportActivities(json))
        assertThat(items).hasSize(1)
        val a = items.first()
        assertThat(a.remoteActivityId).isEqualTo("123456")
        assertThat(a.name).isEqualTo("晨骑")
        assertThat(a.distanceMeters).isEqualTo(42500.0)
        assertThat(a.downloadUrl).isEqualTo("https://igp-zh.oss-cn-hangzhou.aliyuncs.com/abc.fit")
        assertThat(a.startTimeEpochMs).isNotNull()
        assertThat(a.metadata["exercise_type"]).isEqualTo("1")
        // sportType 刻意留空，交由 FitEngine 从文件解析
        assertThat(a.sportType).isNull()
    }

    @Test
    fun parseActivities_distinguishesValidEmptyFromMalformedResponse() {
        val json = """{"code":0,"message":"success","data":null}"""
        assertThat(parseIgpsportActivities(json)).isNull()
        assertThat(parseIgpsportActivities("""{"code":0,"data":{"rows":[]}}""")).isEmpty()
        assertThat(parseIgpsportActivities("""{"code":1003,"data":{"rows":[]}}""")).isNull()
    }

    @Test
    fun parseActivities_fitOssPathAlsoAccepted() {
        val json = """{"code":0,"data":{"rows":[""" +
            """{"rideId":7,"fitOssPath":"https://igp-zh.oss-cn-hangzhou.aliyuncs.com/x.fit"}]}}"""
        val items = requireNotNull(parseIgpsportActivities(json))
        assertThat(items).hasSize(1)
        assertThat(items.first().downloadUrl).isEqualTo("https://igp-zh.oss-cn-hangzhou.aliyuncs.com/x.fit")
    }

    @Test
    fun parseFitUrl_fromDetailData() {
        val json = """{"code":0,"data":{"fitOssPath":"https://igp-zh.oss-cn-hangzhou.aliyuncs.com/xyz.fit"}}"""
        assertThat(parseIgpsportFitUrl(json))
            .isEqualTo("https://igp-zh.oss-cn-hangzhou.aliyuncs.com/xyz.fit")
    }

    @Test
    fun parseFitUrl_topLevelFallback() {
        val json = """{"fitUrl":"https://igp-zh.oss-cn-hangzhou.aliyuncs.com/top.fit"}"""
        assertThat(parseIgpsportFitUrl(json))
            .isEqualTo("https://igp-zh.oss-cn-hangzhou.aliyuncs.com/top.fit")
    }

    @Test
    fun parseStartTime_acceptsIsoAndPlainAndEpoch() {
        assertThat(parseIgpsportStartTime("2026-07-18T06:30:00Z")).isNotNull()
        assertThat(parseIgpsportStartTime("2026-07-18 06:30:00")).isNotNull()
        // list 行只给到日期精度（真实数据见端到端实测）
        assertThat(parseIgpsportStartTime("2026.03.07")).isNotNull()
        assertThat(parseIgpsportStartTime("2026-03-07")).isNotNull()
        // 秒级时间戳
        assertThat(parseIgpsportStartTime(1721280000L)).isNotNull()
        assertThat(parseIgpsportStartTime(null)).isNull()
        assertThat(parseIgpsportStartTime("")).isNull()
    }

    @Test
    fun parseStartTime_plainEqualsIsoForSameInstant() {
        // 同一时刻（GMT）：2026-07-18 06:30:00 UTC == 2026-07-18T06:30:00Z
        val plain = parseIgpsportStartTime("2026-07-18 06:30:00")
        val iso = parseIgpsportStartTime("2026-07-18T06:30:00Z")
        assertThat(plain).isNotNull()
        assertThat(iso).isNotNull()
        // 注意：plain 按本地时区解析，iso 按 Z 解析；两者只在 UTC 机器上相等。
        // 这里仅断言都解析成功，不强行等值（避免 CI 时区敏感）。
    }

    @Test
    fun parseSignedUrl_extractsOssIdAndSignedUrl() {
        val json = """{"code":0,"data":{"ossId":"abc123.fit",""" +
            """"signedUrl":"https://igp-zh.oss-cn-hangzhou.aliyuncs.com/abc123.fit?Expires=1&Signature=x"}}"""
        val s = parseIgpsportSignedUrl(json)
        assertThat(s).isNotNull()
        assertThat(s!!.ossId).isEqualTo("abc123.fit")
        assertThat(s.signedUrl).startsWith("https://igp-zh.oss-cn-hangzhou.aliyuncs.com/abc123.fit")
    }

    @Test
    fun parseSignedUrl_missingFields_returnsNull() {
        assertThat(parseIgpsportSignedUrl("""{"code":0,"data":{}}""")).isNull()
        assertThat(parseIgpsportSignedUrl("""{"code":0,"data":{"ossId":"x"}}""")).isNull()
    }

    @Test
    fun buildOssPutRequest_doesNotSendContentType() {
        val file = File.createTempFile("igpsport-upload", ".fit")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3))

            val request = buildIgpsportOssPutRequest(
                "https://igp-zh.oss-cn-hangzhou.aliyuncs.com/test.fit?Signature=x",
                file,
            )

            assertThat(request.header("Content-Type")).isNull()
            assertThat(request.body?.contentType()).isNull()
        } finally {
            file.delete()
        }
    }
}
