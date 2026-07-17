package com.fitsync.app.connectors

import com.fitsync.app.domain.model.Platform
import com.fitsync.app.domain.model.ConnectorExecution

/**
 * Baseline capability matrix. Runtime connectors may downgrade these modes after
 * session validation or when a vendor page changes.
 */
object PlatformCapabilityRegistry {

    fun capabilities(platform: Platform): ConnectorCapabilities = when (platform) {
        Platform.LOCAL_IMPORT -> ConnectorCapabilities(
            shareImportSupported = true,
        )
        Platform.ONELAP -> ConnectorCapabilities(
            shareImportSupported = true,
            listActivitiesMode = ConnectorExecution.BACKGROUND,
            downloadFitMode = ConnectorExecution.BACKGROUND,
            uploadFitMode = ConnectorExecution.BACKGROUND,
            supportsIncrementalCursor = true,
            supportsBulkHistory = true,
        )
        Platform.XINGZHE -> ConnectorCapabilities(
            shareImportSupported = true,
            listActivitiesMode = ConnectorExecution.BACKGROUND,
            downloadFitMode = ConnectorExecution.BACKGROUND,
            uploadFitMode = ConnectorExecution.BACKGROUND,
            supportsIncrementalCursor = true,
            supportsBulkHistory = true,
            experimental = true,
        )
        Platform.GARMIN -> ConnectorCapabilities(
            shareImportSupported = true,
            listActivitiesMode = ConnectorExecution.FOREGROUND_WEB,
            downloadFitMode = ConnectorExecution.FOREGROUND_WEB,
            uploadFitMode = ConnectorExecution.BACKGROUND,
        )
        Platform.IGPSPORT -> ConnectorCapabilities(
            shareImportSupported = true,
            listActivitiesMode = ConnectorExecution.MANUAL,
            downloadFitMode = ConnectorExecution.MANUAL,
            uploadFitMode = ConnectorExecution.FOREGROUND_WEB,
            experimental = true,
        )
        Platform.COROS -> ConnectorCapabilities(
            shareImportSupported = true,
            listActivitiesMode = ConnectorExecution.FOREGROUND_WEB,
            downloadFitMode = ConnectorExecution.MANUAL,
            uploadFitMode = ConnectorExecution.FOREGROUND_WEB,
        )
        Platform.UNKNOWN -> ConnectorCapabilities(
            shareImportSupported = true,
        )
    }

    fun allKnownPlatforms(): List<Platform> = listOf(
        Platform.ONELAP,
        Platform.XINGZHE,
        Platform.GARMIN,
        Platform.IGPSPORT,
        Platform.COROS,
    )
}
