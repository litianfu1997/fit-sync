package com.fitsync.app.connectors

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test

class JsonPlatformParserTest {
    @Test
    fun onelapList_readsNestedFitUrlAndCurrentFieldNames() {
        val root = JSONObject(
            """
            {
              "data": {
                "list": [{
                  "id": 42,
                  "name": "晨骑",
                  "start_riding_time": "2026-07-01 08:30:00",
                  "distance_km": 12.5,
                  "ridingRecord": {
                    "fitUrl": "https://files.onelap.cn/activities/42.fit"
                  }
                }],
                "pagination": {"total": 1}
              }
            }
            """.trimIndent(),
        )

        val activities = JsonPlatformParser.activities(root)

        assertThat(activities).hasSize(1)
        assertThat(activities.single().remoteActivityId).isEqualTo("42")
        assertThat(activities.single().startTimeEpochMs).isNotNull()
        assertThat(activities.single().distanceMeters).isEqualTo(12_500.0)
        assertThat(activities.single().downloadUrl)
            .isEqualTo("https://files.onelap.cn/activities/42.fit")
    }

    @Test
    fun onelapDetail_readsNestedFitUrl() {
        val detail = JSONObject(
            """{"data":{"ridingRecord":{"fitUrl":"https://files.onelap.cn/42.fit"}}}""",
        )

        assertThat(JsonPlatformParser.firstString(detail, "fitUrl"))
            .isEqualTo("https://files.onelap.cn/42.fit")
    }
}
