package com.fitsync.app.connectors

import com.fitsync.app.domain.model.Platform
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AsyncUploadConfirmationTest {
    @Test
    fun token_roundTripsPlatformProvisionalIdAndSubmissionTime() {
        val token = encodeAsyncUploadConfirmationToken(
            platform = Platform.IGPSPORT,
            provisionalRemoteId = "folder/object.fit",
            submittedAtEpochMs = 123_456L,
        )

        assertThat(parseAsyncUploadConfirmationToken(token)).isEqualTo(
            AsyncUploadConfirmationToken(
                platform = Platform.IGPSPORT,
                provisionalRemoteId = "folder/object.fit",
                submittedAtEpochMs = 123_456L,
            ),
        )
    }

    @Test
    fun confirmation_waitsUntilTargetListFindsActivity() {
        val submittedAt = 1_000_000L
        val token = encodeAsyncUploadConfirmationToken(Platform.COROS, "abc123", submittedAt)

        val result = pendingAsyncUploadConfirmation(
            rawToken = token,
            expectedPlatform = Platform.COROS,
            nowEpochMs = submittedAt + ASYNC_UPLOAD_CONFIRM_TIMEOUT_MS - 1,
        )

        assertThat(result).isEqualTo(
            ConnectorResult.Processing(token, ASYNC_UPLOAD_CONFIRM_POLL_MS),
        )
    }

    @Test
    fun confirmation_failsAfterTimeoutInsteadOfCreatingSyntheticCopy() {
        val submittedAt = 1_000_000L
        val token = encodeAsyncUploadConfirmationToken(Platform.IGPSPORT, "abc123", submittedAt)

        val result = pendingAsyncUploadConfirmation(
            rawToken = token,
            expectedPlatform = Platform.IGPSPORT,
            nowEpochMs = submittedAt + ASYNC_UPLOAD_CONFIRM_TIMEOUT_MS,
        )

        assertThat(result).isEqualTo(
            ConnectorResult.PermanentFailure(
                "IGPSPORT_CONFIRM_TIMEOUT",
                "iGPSPORT 长时间未创建活动，上传解析可能失败",
            ),
        )
    }

    @Test
    fun tokenCannotBePolledByAnotherPlatform() {
        val token = encodeAsyncUploadConfirmationToken(Platform.COROS, "abc123", 1_000_000L)

        val result = pendingAsyncUploadConfirmation(token, Platform.IGPSPORT, 1_000_001L)

        assertThat(result).isInstanceOf(ConnectorResult.PermanentFailure::class.java)
    }

    @Test
    fun confirmation_supportsPlatformSpecificTimeout() {
        val submittedAt = 1_000_000L
        val token = encodeAsyncUploadConfirmationToken(Platform.ONELAP, "abc123", submittedAt)

        val result = pendingAsyncUploadConfirmation(
            rawToken = token,
            expectedPlatform = Platform.ONELAP,
            nowEpochMs = submittedAt + 5_000L,
            timeoutMs = 5_000L,
        )

        assertThat(result).isEqualTo(
            ConnectorResult.PermanentFailure(
                "ONELAP_CONFIRM_TIMEOUT",
                "迈金 OnelapFit 长时间未创建活动，上传解析可能失败",
            ),
        )
    }
}
