package com.fitsync.app.connectors

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StravaWebConnectorTest {
    @Test
    fun trainingActivities_parsesWebsiteFieldsAndPagination() {
        val result = parseStravaTrainingActivities(
            responseText = """
                {
                  "models":[
                    {
                      "id":19712486508,
                      "id_str":"19712486508",
                      "name":"南宁市 跑步",
                      "sport_type":"Run",
                      "start_date_local_raw":1786562978,
                      "start_time":"2026-08-12T11:29:38+0000",
                      "distance_raw":6053.2,
                      "activity_url":"https://www.strava.com/activities/19712486508"
                    }
                  ],
                  "page":1,
                  "perPage":20,
                  "total":40
                }
            """.trimIndent(),
            sinceEpochMs = 0L,
            requestedPage = 1,
        )

        val page = (result as ConnectorResult.Success<ActivityPage>).value
        assertThat(page.items).containsExactly(
            RemoteActivitySummary(
                remoteActivityId = "19712486508",
                startTimeEpochMs = 1_786_534_178_000L,
                name = "南宁市 跑步",
                sportType = "Run",
                distanceMeters = 6053.2,
                downloadUrl = "https://www.strava.com/activities/19712486508/export_original",
                metadata = mapOf("remote_url" to "https://www.strava.com/activities/19712486508"),
            ),
        )
        assertThat(page.nextCursor).isEqualTo(SyncCursor("2"))
        assertThat(page.hasMore).isTrue()
    }

    @Test
    fun trainingActivities_stopsPagingAfterCrossingSyncWindow() {
        val result = parseStravaTrainingActivities(
            responseText = """
                {
                  "models":[
                    {"id_str":"200","start_date_local_raw":2000,"distance_raw":1000},
                    {"id_str":"100","start_date_local_raw":1000,"distance_raw":500}
                  ],
                  "page":2,
                  "perPage":20,
                  "total":268
                }
            """.trimIndent(),
            sinceEpochMs = 1_500_000L,
            requestedPage = 2,
        )

        val page = (result as ConnectorResult.Success<ActivityPage>).value
        assertThat(page.items.map { it.remoteActivityId }).containsExactly("200")
        assertThat(page.nextCursor).isNull()
        assertThat(page.hasMore).isFalse()
    }

    @Test
    fun trainingActivities_rejectsChangedWebsiteShape() {
        assertThat(parseStravaTrainingActivities("{}", 0L, 1)).isEqualTo(
            ConnectorResult.PermanentFailure(
                "STRAVA_PAGE_CHANGED",
                "Strava 网页活动响应缺少 models",
            ),
        )
    }

    @Test
    fun uploadPage_extractsRailsTokenWithoutDependingOnAttributeOrder() {
        val html = """
            <form action="/upload/files" method="post">
              <input value="token&amp;&#x2F;value" type="hidden" name="authenticity_token">
              <input type="file" name="files[]">
            </form>
        """.trimIndent()

        assertThat(extractStravaAuthenticityToken(html)).isEqualTo("token&/value")
    }

    @Test
    fun uploadPage_acceptsMetaCsrfTokenAsFallback() {
        val html = """<meta content='meta-token' name='csrf-token'>"""

        assertThat(extractStravaAuthenticityToken(html)).isEqualTo("meta-token")
    }

    @Test
    fun initialUploadResponse_returnsProcessingWithWebsiteUploadId() {
        val result = parseStravaWebUploadResponse(
            """[{"id":987654321,"workflow":"uploaded","error":null}]""",
        )

        assertThat(result).isEqualTo(ConnectorResult.Processing("987654321", 1_000L))
    }

    @Test
    fun progressResponse_returnsRealActivityId() {
        val result = parseStravaWebUploadResponse(
            """[{"id":987654321,"workflow":"success","activity":{"id":"123456789012","name":"Morning Ride"}}]""",
            fallbackUploadId = "987654321",
        )

        assertThat(result).isEqualTo(
            ConnectorResult.Success(
                RemoteCopy(
                    remoteActivityId = "123456789012",
                    remoteUrl = "https://www.strava.com/activities/123456789012",
                ),
            ),
        )
    }

    @Test
    fun duplicateResponse_extractsExistingActivityId() {
        val result = parseStravaWebUploadResponse(
            """[{"id":987654321,"workflow":"error","error":"duplicate of activity 24681012","duplicate_url":"/activities/24681012"}]""",
        )

        assertThat(result).isEqualTo(
            ConnectorResult.Duplicate(
                RemoteCopy(
                    remoteActivityId = "24681012",
                    remoteUrl = "https://www.strava.com/activities/24681012",
                    duplicate = true,
                ),
            ),
        )
    }

    @Test
    fun failedWorkflow_isPermanentFailureInsteadOfFalseSuccess() {
        val result = parseStravaWebUploadResponse(
            """[{"id":987654321,"workflow":"error","error":"The file is malformed"}]""",
        )

        assertThat(result).isEqualTo(
            ConnectorResult.PermanentFailure(
                "STRAVA_UPLOAD_FAILED",
                "The file is malformed",
            ),
        )
    }
}
