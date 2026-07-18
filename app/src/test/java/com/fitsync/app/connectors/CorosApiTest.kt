package com.fitsync.app.connectors

import com.google.common.truth.Truth.assertThat
import java.util.Base64
import org.junit.Test

class CorosApiTest {

    @Test
    fun parseLogin_success_extractsAllFields() {
        val json = """{"result":"0000","message":"OK","data":""" +
            """{"accessToken":"at-1","userId":"u-7","regionId":2,"nickname":"骑行者"}}"""
        val r = parseCorosLogin(json)
        assertThat(r).isNotNull()
        assertThat(r!!.accessToken).isEqualTo("at-1")
        assertThat(r.userId).isEqualTo("u-7")
        assertThat(r.regionId).isEqualTo(2)
        assertThat(r.nickname).isEqualTo("骑行者")
    }

    @Test
    fun parseLogin_nonZeroResult_returnsNull() {
        assertThat(parseCorosLogin("""{"result":"9001","message":"password error"}""")).isNull()
    }

    @Test
    fun parseLogin_nonCnRegion_stillParsedForCallerToReject() {
        // regionId=1（国际站）：解析器只管结构，是否拒绝由 connector.login 判定
        val json = """{"result":"0000","data":{"accessToken":"at","userId":"u","regionId":1}}"""
        val r = parseCorosLogin(json)
        assertThat(r).isNotNull()
        assertThat(r!!.regionId).isEqualTo(1)
    }

    @Test
    fun parseActivities_mapsLabelIdSportTypeDistance() {
        val json = """{"result":"0000","data":{"count":1,"totalPage":2,"pageNumber":1,"dataList":[""" +
            """{"labelId":"461428804292739075","name":"晨骑","sportType":200,""" +
            """"timestamp":1721280000000,"distance":42500.0}]}}"""
        val items = parseCorosActivities(json)
        assertThat(items).hasSize(1)
        val a = items.first()
        assertThat(a.remoteActivityId).isEqualTo("461428804292739075")
        assertThat(a.name).isEqualTo("晨骑")
        assertThat(a.distanceMeters).isEqualTo(42500.0)
        assertThat(a.metadata["sport_type"]).isEqualTo("200")
        assertThat(a.sportType).isNull()
        assertThat(a.startTimeEpochMs).isEqualTo(1721280000000)
        assertThat(parseCorosTotalPage(json)).isEqualTo(2)
    }

    @Test
    fun parseActivities_dateFallbackToYmd() {
        // 仅给 date=20241130（YYYYMMDD 整数），无 timestamp：仍能解出起始时间
        val json = """{"result":"0000","data":{"dataList":[""" +
            """{"labelId":"abc","sportType":201,"date":20241130}]}}"""
        val items = parseCorosActivities(json)
        assertThat(items).hasSize(1)
        assertThat(items.first().startTimeEpochMs).isNotNull()
    }

    @Test
    fun parseActivities_dataNull_returnsEmpty() {
        assertThat(parseCorosActivities("""{"result":"0000","data":null}""")).isEmpty()
    }

    @Test
    fun parseFitUrl_extractsDataFileUrl() {
        val json = """{"result":"0000","data":{"fileUrl":"https://coros-oss.oss-cn-beijing.aliyuncs.com/x/fit.fit"}}"""
        assertThat(parseCorosFitUrl(json))
            .isEqualTo("https://coros-oss.oss-cn-beijing.aliyuncs.com/x/fit.fit")
    }

    @Test
    fun parseFitUrl_nonZeroResult_returnsNull() {
        assertThat(parseCorosFitUrl("""{"result":"8001","data":null}""")).isNull()
    }

    @Test
    fun parseSts_decodesSaltedCredentials() {
        val json = """{"AccessKeyId":"ak-id","AccessKeySecret":"ak-secret",""" +
            """"SecurityToken":"sec-token","Bucket":"coros-oss","Region":"oss-cn-beijing",""" +
            """"Expiration":"2026-01-01T00:00:00Z"}"""
        // 模拟服务端：把盐拼进 base64 文本（位置不限，replace 能去掉即可）
        val salted = Base64.getEncoder().encodeToString(json.toByteArray()) + OSS_SALT
        val resp = """{"code":200,"data":{"credentials":"$salted"}}"""

        val c = parseCorosSts(resp)
        assertThat(c).isNotNull()
        assertThat(c!!.accessKeyId).isEqualTo("ak-id")
        assertThat(c.accessKeySecret).isEqualTo("ak-secret")
        assertThat(c.securityToken).isEqualTo("sec-token")
        assertThat(c.bucket).isEqualTo("coros-oss")
        assertThat(c.region).isEqualTo("oss-cn-beijing")
        assertThat(c.expiration).isEqualTo("2026-01-01T00:00:00Z")
    }

    @Test
    fun parseSts_saltEmbeddedInsideBase64AlsoDecodes() {
        // 盐即使插在 base64 中间，replace 也能去掉（真实服务端加盐位置不固定）
        val json = """{"AccessKeyId":"ak","AccessKeySecret":"sk","SecurityToken":"st"}"""
        val base64 = Base64.getEncoder().encodeToString(json.toByteArray())
        val salted = base64.substring(0, 5) + OSS_SALT + base64.substring(5)
        val resp = """{"code":200,"data":{"credentials":"$salted"}}"""
        val c = parseCorosSts(resp)
        assertThat(c).isNotNull()
        assertThat(c!!.accessKeyId).isEqualTo("ak")
    }

    @Test
    fun parseSts_codeNot200_returnsNull() {
        assertThat(parseCorosSts("""{"code":403,"data":null}""")).isNull()
    }

    @Test
    fun md5Hex_knownVector() {
        // md5("123456") = e10adc3949ba59abbe56e057f20f883e
        assertThat(md5Hex("123456")).isEqualTo("e10adc3949ba59abbe56e057f20f883e")
    }

    @Test
    fun parseTotalPage_missing_defaultsToMaxSoPagingContinues() {
        // totalPage 缺失时返回 Int.MAX_VALUE，由 listActivities 的 items.isNotEmpty() 兜底停止，
        // 避免误判「只有一页」而漏拉历史
        assertThat(parseCorosTotalPage("""{"result":"0000","data":{"dataList":[]}}"""))
            .isEqualTo(Int.MAX_VALUE)
        assertThat(parseCorosTotalPage("not json")).isEqualTo(Int.MAX_VALUE)
    }
}
