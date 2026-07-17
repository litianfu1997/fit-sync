package com.fitsync.app.connectors

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GarminConnectorTest {
    @Test
    fun uploadUrl_usesConnectApiWithoutLegacyFileSuffix() {
        assertThat(garminUploadUrl("garmin.com"))
            .isEqualTo("https://connectapi.garmin.com/upload-service/upload")
        assertThat(garminUploadUrl("garmin.cn"))
            .isEqualTo("https://connectapi.garmin.cn/upload-service/upload")
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
