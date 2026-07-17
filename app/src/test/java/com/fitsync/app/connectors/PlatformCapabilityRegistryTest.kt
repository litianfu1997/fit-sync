package com.fitsync.app.connectors

import com.fitsync.app.domain.model.ConnectorExecution
import com.fitsync.app.domain.model.Platform
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlatformCapabilityRegistryTest {
    @Test
    fun onelapAndXingzhe_supportBackgroundReadAndWrite() {
        listOf(Platform.ONELAP, Platform.XINGZHE).forEach { platform ->
            val capabilities = PlatformCapabilityRegistry.capabilities(platform)
            assertThat(capabilities.listActivitiesMode).isEqualTo(ConnectorExecution.BACKGROUND)
            assertThat(capabilities.downloadFitMode).isEqualTo(ConnectorExecution.BACKGROUND)
            assertThat(capabilities.uploadFitMode).isEqualTo(ConnectorExecution.BACKGROUND)
        }
    }

    @Test
    fun garmin_supportsBackgroundReadAndWrite() {
        val capabilities = PlatformCapabilityRegistry.capabilities(Platform.GARMIN)

        assertThat(capabilities.shareImportSupported).isFalse()
        assertThat(capabilities.listActivitiesMode).isEqualTo(ConnectorExecution.BACKGROUND)
        assertThat(capabilities.downloadFitMode).isEqualTo(ConnectorExecution.BACKGROUND)
        assertThat(capabilities.uploadFitMode).isEqualTo(ConnectorExecution.BACKGROUND)
    }

    @Test
    fun corosAndIgpsport_doNotClaimUnverifiedBackgroundAccess() {
        listOf(Platform.COROS, Platform.IGPSPORT).forEach { platform ->
            val capabilities = PlatformCapabilityRegistry.capabilities(platform)
            assertThat(capabilities.listActivitiesMode).isNotEqualTo(ConnectorExecution.BACKGROUND)
            assertThat(capabilities.uploadFitMode).isNotEqualTo(ConnectorExecution.BACKGROUND)
        }
    }

    @Test
    fun localImport_isSourceOnly() {
        val capabilities = PlatformCapabilityRegistry.capabilities(Platform.LOCAL_IMPORT)
        assertThat(capabilities.shareImportSupported).isTrue()
        assertThat(capabilities.canUploadActivity).isFalse()
    }
}
