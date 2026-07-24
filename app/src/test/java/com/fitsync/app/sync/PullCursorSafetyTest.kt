package com.fitsync.app.sync

import com.fitsync.app.connectors.ConnectorResult
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PullCursorSafetyTest {
    @Test
    fun retryableOrUnknownPermanentFailure_keepsCursorOnCurrentPage() {
        assertThat(
            shouldAbortPullAfterItemResult(ConnectorResult.RetryableFailure("TIMEOUT", "timeout")),
        ).isTrue()
        assertThat(
            shouldAbortPullAfterItemResult(ConnectorResult.PermanentFailure("BAD_RESPONSE", "bad")),
        ).isTrue()
    }

    @Test
    fun persistedPermanentSkip_allowsCursorToAdvance() {
        assertThat(
            shouldAbortPullAfterItemResult(
                ConnectorResult.PermanentFailure("NO_ORIGINAL_FILE", "no fit"),
            ),
        ).isFalse()
        assertThat(
            shouldAbortPullAfterItemResult(
                ConnectorResult.PermanentFailure("UNSUPPORTED_ORIGINAL_FORMAT", "not a fit"),
            ),
        ).isFalse()
        assertThat(shouldAbortPullAfterItemResult(ConnectorResult.Success(1L))).isFalse()
    }
}
