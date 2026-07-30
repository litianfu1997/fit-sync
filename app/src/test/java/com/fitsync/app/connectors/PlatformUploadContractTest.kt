package com.fitsync.app.connectors

import com.fitsync.app.domain.model.Platform
import com.fitsync.app.domain.model.SessionState
import com.google.common.truth.Truth.assertThat
import okio.Buffer
import org.junit.Test
import java.io.File

class PlatformUploadContractTest {
    @Test
    fun onelapMultipart_usesJiluFieldAndPortableFileMetadata() {
        val file = kotlin.io.path.createTempFile(prefix = "行者_", suffix = ".fit").toFile()
        try {
            file.writeBytes(byteArrayOf(0x0e, 0x20, 0x2e, 0x46, 0x49, 0x54))
            val multipart = onelapUploadMultipart(
                file = file,
                fileSha256 = "ac97409d083767b144461475725ec984",
            )
            val buffer = Buffer()
            multipart.writeTo(buffer)
            val raw = buffer.readUtf8()

            assertThat(multipart.contentType()?.toString()).isEqualTo(
                "multipart/form-data; boundary=----WebKitFormBoundaryac97409d083767b144461475",
            )
            assertThat(raw).contains(
                "Content-Disposition: form-data; name=\"jilu\"; " +
                    "filename=\"fitsync_ac97409d083767b1.fit\"",
            )
            assertThat(raw).contains("Content-Type: application/octet-stream\r\n\r\n")
            assertThat(raw).doesNotContain("Content-Length:")
        } finally {
            file.delete()
        }
    }

    @Test
    fun onelapMultipart_supportsServerFieldFallback() {
        val file = kotlin.io.path.createTempFile(prefix = "fallback_", suffix = ".fit").toFile()
        try {
            file.writeBytes(byteArrayOf(0x0e, 0x20, 0x2e, 0x46, 0x49, 0x54))
            val multipart = onelapUploadMultipart(file, "abcdef0123456789", fieldName = "file")
            val buffer = Buffer()
            multipart.writeTo(buffer)

            assertThat(buffer.readUtf8()).contains(
                "Content-Disposition: form-data; name=\"file\"; " +
                    "filename=\"fitsync_abcdef0123456789.fit\"",
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun onelapUploadRequest_matchesCurrentWebAuthorizationContract() {
        val multipart = onelapUploadMultipart(File("activity.fit"), "abcdef0123456789")
        val request = onelapUploadRequest(
            url = "https://otm.onelap.cn/api/otm/ride_record/upload/fit",
            token = "raw-onelap-token",
            multipart = multipart,
        )

        assertThat(request.header("Authorization")).isEqualTo("raw-onelap-token")
        assertThat(request.header("Accept")).isEqualTo("application/json, text/plain, */*")
        assertThat(request.header("Origin")).isEqualTo("https://otm.onelap.cn")
        assertThat(request.header("Referer")).isEqualTo("https://otm.onelap.cn/")
        assertThat(request.header("Sec-Fetch-Mode")).isEqualTo("cors")
        assertThat(request.header("Cookie")).isNull()
        assertThat(request.header("token")).isNull()
        assertThat(request.header("X-Requested-With")).isNull()
    }

    @Test
    fun onelapUploadRequest_carriesWebSessionCookieWhenAvailable() {
        val multipart = onelapUploadMultipart(File("activity.fit"), "abcdef0123456789")
        val request = onelapUploadRequest(
            url = "https://otm.onelap.cn/api/otm/ride_record/upload/fit",
            token = "raw-onelap-token",
            multipart = multipart,
            cookieHeader = "onelap_web_session=session-value",
        )

        assertThat(request.header("Cookie")).isEqualTo("onelap_web_session=session-value")
    }

    @Test
    fun onelapMissingFileResponse_enablesSafeFieldFallback() {
        val legacyWording = ConnectorResult.PermanentFailure("ONELAP_422", "没有上传文件")
        val currentWording = onelapAcceptedUploadResult(
            responseBody = """{"code":400,"msg":"没有文件上传"}""",
            provisionalRemoteId = "sha256",
        )
        val parseFailure = ConnectorResult.PermanentFailure("ONELAP_422", "FIT 文件解析失败")
        val unrelatedPlatform = ConnectorResult.PermanentFailure("UPLOAD_422", "没有文件上传")

        assertThat(legacyWording.isOnelapMissingFile()).isTrue()
        assertThat(currentWording.isOnelapMissingFile()).isTrue()
        assertThat(parseFailure.isOnelapMissingFile()).isFalse()
        assertThat(unrelatedPlatform.isOnelapMissingFile()).isFalse()
        assertThat(ONELAP_UPLOAD_FIELD_FALLBACKS)
            .containsExactly("jilu", "jilu", "file", "fit_file")
            .inOrder()
    }

    @Test
    fun mergeCookieHeaders_prefersLatestCookieValue() {
        assertThat(
            mergeCookieHeaders(
                "theme=dark; onelap_web_session=old",
                "onelap_web_session=new; locale=zh-CN",
            ),
        ).isEqualTo("theme=dark; onelap_web_session=new; locale=zh-CN")
    }

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
            responseBody = """{"code":200,"data":{"ride_record_id":12345}}""",
            provisionalRemoteId = "sha256",
        )

        assertThat(result).isEqualTo(ConnectorResult.Success(RemoteCopy("12345")))
    }

    @Test
    fun onelapAcceptedResponse_withoutRealIdWaitsForTargetList() {
        val result = onelapAcceptedUploadResult(
            responseBody = """{"code":200,"message":"accepted"}""",
            provisionalRemoteId = "sha256",
        )

        assertThat(result).isInstanceOf(ConnectorResult.Processing::class.java)
        val token = (result as ConnectorResult.Processing).remoteUploadId
        assertThat(parseAsyncUploadConfirmationToken(token)?.platform).isEqualTo(Platform.ONELAP)
    }

    @Test
    fun onelapBusinessFailure_doesNotEnterEndlessConfirmation() {
        val result = onelapAcceptedUploadResult(
            responseBody = """{"code":422,"msg":"FIT 文件解析失败"}""",
            provisionalRemoteId = "sha256",
        )

        assertThat(result).isEqualTo(
            ConnectorResult.PermanentFailure("ONELAP_422", "FIT 文件解析失败"),
        )
    }

    @Test
    fun onelapMalformedResponse_isRetryableInsteadOfAccepted() {
        val result = onelapAcceptedUploadResult(
            responseBody = "<html>error</html>",
            provisionalRemoteId = "sha256",
        )

        assertThat(result).isEqualTo(
            ConnectorResult.RetryableFailure(
                "ONELAP_INVALID_UPLOAD_RESPONSE",
                "迈金上传响应格式异常，请稍后重试",
            ),
        )
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
