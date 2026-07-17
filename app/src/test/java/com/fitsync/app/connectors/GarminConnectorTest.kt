package com.fitsync.app.connectors

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GarminConnectorTest {
    @Test
    fun uploadUrl_usesChinaGcApiProxy() {
        // 中国站后台上传走 connect.garmin.cn 同源 gc-api 代理（实测稳定），不走 connectapi 直连。
        assertThat(garminUploadUrl("garmin.com"))
            .isEqualTo("https://connect.garmin.cn/gc-api/upload-service/upload")
        assertThat(garminUploadUrl("garmin.cn"))
            .isEqualTo("https://connect.garmin.cn/gc-api/upload-service/upload")
    }

    @Test
    fun parseImportState_initialAcceptedResponseIsPending() {
        val state = parseGarminImportState(
            """
            {"detailedImportResult":{"uploadId":123,"successes":[],"failures":[]}}
            """.trimIndent(),
        )

        assertThat(state?.isFinal).isFalse()
        assertThat(state?.remoteActivityId).isNull()
    }

    @Test
    fun parseImportState_completedResponseReturnsActivityId() {
        val state = parseGarminImportState(
            """
            {"detailedImportResult":{"successes":[{"internalId":23626050000}],"failures":[]}}
            """.trimIndent(),
        )

        assertThat(state?.isFinal).isTrue()
        assertThat(state?.remoteActivityId).isEqualTo("23626050000")
        assertThat(state?.duplicate).isFalse()
    }

    @Test
    fun parseImportState_duplicateFailureIsRecognized() {
        val state = parseGarminImportState(
            """
            {"detailedImportResult":{"successes":[],"failures":[{"internalId":42,"messages":[{"code":"duplicate.activity"}]}]}}
            """.trimIndent(),
        )

        assertThat(state?.isFinal).isTrue()
        assertThat(state?.remoteActivityId).isEqualTo("42")
        assertThat(state?.duplicate).isTrue()
    }

    @Test
    fun parseImportState_realDuplicateUsesIntegerCode202() {
        // 真实 Garmin 重复上传返回 HTTP 409，messages[].code 是整数 202、content 含 "Duplicate"。
        val state = parseGarminImportState(
            """
            {"detailedImportResult":{"uploadId":"","successes":[],"failures":[{"internalId":20242598711,"messages":[{"code":202,"content":"Duplicate Activity."}]}]}}
            """.trimIndent(),
        )

        assertThat(state?.isFinal).isTrue()
        assertThat(state?.remoteActivityId).isEqualTo("20242598711")
        assertThat(state?.duplicate).isTrue()
    }

    @Test
    fun parseImportState_realSuccessCarriesUploadId() {
        val state = parseGarminImportState(
            """
            {"detailedImportResult":{"uploadId":212157427938,"uploadUuid":{"uuid":"abc"},"owner":2591602,"successes":[{"internalId":23626050000}],"failures":[]}}
            """.trimIndent(),
        )

        assertThat(state?.isFinal).isTrue()
        assertThat(state?.remoteActivityId).isEqualTo("23626050000")
        assertThat(state?.duplicate).isFalse()
        assertThat(state?.success).isTrue()
        assertThat(state?.uploadId).isEqualTo(212157427938L)
    }

    @Test
    fun parseImportState_nonDuplicateFailureIsFinalButNotSuccess() {
        // 例如 EU consent 拒绝：failures 非 duplicate，是终态但既不算成功也不算重复。
        val state = parseGarminImportState(
            """
            {"detailedImportResult":{"successes":[],"failures":[{"internalId":1,"messages":[{"code":403,"content":"The user is from EU location, but upload consent is not yet granted"}]}]}}
            """.trimIndent(),
        )

        assertThat(state?.isFinal).isTrue()
        assertThat(state?.success).isFalse()
        assertThat(state?.duplicate).isFalse()
    }

    @Test
    fun parseActivities_extractsIdNameSportDistanceAndStart() {
        val activities = parseGarminActivities(
            """[{"activityId":535949177,"activityName":"Paris 虚拟骑行","startTimeGMT":"2025-11-04 10:58:33","activityType":{"typeKey":"virtual_ride"},"distance":90760.74},{"activityId":123,"activityName":"晨跑","startTimeGMT":"2025-01-01 00:00:00","activityType":{"typeKey":"running"},"distance":5000}]""",
        )

        assertThat(activities).hasSize(2)
        val first = activities[0]
        assertThat(first.remoteActivityId).isEqualTo("535949177")
        assertThat(first.name).isEqualTo("Paris 虚拟骑行")
        assertThat(first.sportType).isEqualTo("virtual_ride")
        assertThat(first.distanceMeters).isEqualTo(90760.74)
        assertThat(first.startTimeEpochMs).isNotNull()
        assertThat(first.startTimeEpochMs).isGreaterThan(1_700_000_000_000L) // 2023 年之后
    }

    @Test
    fun statusLocation_isNormalizedOnlyFromMatchingGarminHost() {
        assertThat(
            garminStatusApiUrl(
                "https://connectapi.garmin.com/activity-service/activity/status/123/abc",
            ),
        ).isEqualTo("https://connectapi.garmin.com/activity-service/activity/status/123/abc")
        assertThat(
            garminStatusApiUrl(
                "https://connect.garmin.cn/gc-api/activity-service/activity/status/123/abc?upload=7",
                domain = "garmin.cn",
            ),
        ).isEqualTo("https://connectapi.garmin.cn/activity-service/activity/status/123/abc?upload=7")
        assertThat(garminStatusApiUrl("https://connectapi.garmin.cn/activity/status/123")).isNull()
        assertThat(garminStatusApiUrl("https://example.org/activity/status/123/abc")).isNull()
        assertThat(garminStatusApiUrl("http://connectapi.garmin.com/activity/status/123")).isNull()
    }
}
