package com.fitsync.app.ui.screens

import com.fitsync.app.domain.model.DeliveryJobStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SyncJobFilterTest {
    @Test
    fun failed_includesLoginAndRetryProblems() {
        assertThat(SyncJobFilter.FAILED.includes(DeliveryJobStatus.FAILED)).isTrue()
        assertThat(SyncJobFilter.FAILED.includes(DeliveryJobStatus.WAITING_USER)).isTrue()
        assertThat(SyncJobFilter.FAILED.includes(DeliveryJobStatus.RETRY_WAIT)).isTrue()
        assertThat(SyncJobFilter.WAITING.includes(DeliveryJobStatus.WAITING_USER)).isFalse()
        assertThat(SyncJobFilter.WAITING.includes(DeliveryJobStatus.RETRY_WAIT)).isFalse()
    }

    @Test
    fun completed_includesRemoteDuplicate() {
        assertThat(SyncJobFilter.COMPLETED.includes(DeliveryJobStatus.SUCCEEDED)).isTrue()
        assertThat(SyncJobFilter.COMPLETED.includes(DeliveryJobStatus.DUPLICATE_REMOTE)).isTrue()
    }
}
