package com.fitsync.app.connectors

import com.fitsync.app.domain.model.Platform
import com.fitsync.app.domain.model.SessionState
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlatformUploadContractTest {
    @Test
    fun activityListProbe_onlyAuthenticatesParseableApiResponses() {
        assertThat(activityListSessionState(200, """{"data":{"rows":[]}}"""))
            .isEqualTo(SessionState.AUTHENTICATED)
        assertThat(activityListSessionState(200, "[]"))
            .isEqualTo(SessionState.AUTHENTICATED)
        assertThat(activityListSessionState(200, "<html>login</html>"))
            .isEqualTo(SessionState.NEEDS_USER_ACTION)
        assertThat(activityListSessionState(401, ""))
            .isEqualTo(SessionState.EXPIRED)
    }

    @Test
    fun onelapAcceptedResponse_withRealIdCompletesImmediately() {
        val result = onelapAcceptedUploadResult(
            responseBody = """{"code":0,"data":{"ride_record_id":12345}}""",
            provisionalRemoteId = "sha256",
        )

        assertThat(result).isEqualTo(ConnectorResult.Success(RemoteCopy("12345")))
    }

    @Test
    fun onelapAcceptedResponse_withoutRealIdWaitsForTargetList() {
        val result = onelapAcceptedUploadResult(
            responseBody = """{"code":0,"message":"accepted"}""",
            provisionalRemoteId = "sha256",
        )

        assertThat(result).isInstanceOf(ConnectorResult.Processing::class.java)
        val token = (result as ConnectorResult.Processing).remoteUploadId
        assertThat(parseAsyncUploadConfirmationToken(token)?.platform).isEqualTo(Platform.ONELAP)
    }

    @Test
    fun xingzheAcceptedResponse_withoutRealIdWaitsForTargetList() {
        val result = xingzheUploadResult(
            httpCode = 200,
            responseBody = """{"code":0,"data":{}}""",
            provisionalRemoteId = "sha256",
            loginUrl = "https://login",
            uploadUrl = "https://upload",
        )

        assertThat(result).isInstanceOf(ConnectorResult.Processing::class.java)
        val token = (result as ConnectorResult.Processing).remoteUploadId
        assertThat(parseAsyncUploadConfirmationToken(token)?.platform).isEqualTo(Platform.XINGZHE)
    }

    @Test
    fun xingzheTransientHttpFailureIsRetryable() {
        listOf(408, 429, 503).forEach { httpCode ->
            val result = xingzheUploadResult(
                httpCode = httpCode,
                responseBody = """{"code":$httpCode}""",
                provisionalRemoteId = "sha256",
                loginUrl = "https://login",
                uploadUrl = "https://upload",
            )

            assertThat(result).isInstanceOf(ConnectorResult.RetryableFailure::class.java)
        }
    }

    @Test
    fun xingzheDuplicateKeepsRealRemoteIdWhenProvided() {
        val result = xingzheUploadResult(
            httpCode = 200,
            responseBody = """{"code":9006,"msg":"workout 87654 already exists"}""",
            provisionalRemoteId = "sha256",
            loginUrl = "https://login",
            uploadUrl = "https://upload",
        )

        assertThat(result).isEqualTo(
            ConnectorResult.Duplicate(
                RemoteCopy(
                    remoteActivityId = "87654",
                    remoteUrl = "https://www.imxingzhe.com/workouts/87654",
                    duplicate = true,
                ),
            ),
        )
    }

    @Test
    fun garminAcceptedUpload_neverRequestsFitResubmission() {
        val statusUrl =
            "https://connect.garmin.cn/gc-api/activity-service/activity/status/123/token"
        val withStatusUrl = garminAcceptedProcessingResult(statusUrl, "sha256")
        val statusToken = parseAsyncUploadConfirmationToken(withStatusUrl.remoteUploadId)
        assertThat(statusToken?.platform).isEqualTo(Platform.GARMIN)
        assertThat(statusToken?.provisionalRemoteId).isEqualTo(statusUrl)

        val withoutStatusUrl = garminAcceptedProcessingResult(null, "sha256")
        assertThat(parseAsyncUploadConfirmationToken(withoutStatusUrl.remoteUploadId)?.platform)
            .isEqualTo(Platform.GARMIN)
    }

    @Test
    fun asyncTargetConfirmationSupportsEveryConnectorWithoutSynchronousActivityId() {
        listOf(
            Platform.ONELAP,
            Platform.XINGZHE,
            Platform.GARMIN,
            Platform.IGPSPORT,
            Platform.COROS,
        ).forEach { platform ->
            val token = encodeAsyncUploadConfirmationToken(platform, "provisional", 1_000L)

            assertThat(
                pendingAsyncUploadConfirmation(token, platform, 1_001L),
            ).isEqualTo(ConnectorResult.Processing(token, ASYNC_UPLOAD_CONFIRM_POLL_MS))
        }
    }
}
