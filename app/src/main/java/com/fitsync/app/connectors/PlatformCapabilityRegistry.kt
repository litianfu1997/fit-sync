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
            shareImportSupported = false,
            // 复用 WebView 登录态(JWT_WEB + Connect-Csrf-Token)走 gc-api 同源代理，后台读取与写入。
            listActivitiesMode = ConnectorExecution.BACKGROUND,
            downloadFitMode = ConnectorExecution.BACKGROUND,
            uploadFitMode = ConnectorExecution.BACKGROUND,
            supportsIncrementalCursor = true,
            supportsBulkHistory = true,
            experimental = true,
        )
        Platform.IGPSPORT -> ConnectorCapabilities(
            shareImportSupported = true,
            // 纯 API 账号密码后台登录（不走 WebView）：后台 list→download→upload(getSignedUrl+PUT OSS+uploadByOss)。
            listActivitiesMode = ConnectorExecution.BACKGROUND,
            downloadFitMode = ConnectorExecution.BACKGROUND,
            uploadFitMode = ConnectorExecution.BACKGROUND,
            supportsIncrementalCursor = true,
            supportsBulkHistory = true,
            experimental = true,
        )
        Platform.COROS -> ConnectorCapabilities(
            shareImportSupported = true,
            // 纯 API 账号密码后台登录（不走 WebView，仅中国站）：后台 list→download→upload(STS+OSS V1 PUT+import)。
            listActivitiesMode = ConnectorExecution.BACKGROUND,
            downloadFitMode = ConnectorExecution.BACKGROUND,
            uploadFitMode = ConnectorExecution.BACKGROUND,
            supportsIncrementalCursor = true,
            supportsBulkHistory = true,
            experimental = true,
        )
        Platform.STRAVA -> ConnectorCapabilities(
            shareImportSupported = true,
            // 不走开发者 API：复用 WebView 登录 Cookie，读取网页活动列表、导出原文件并提交 FIT。
            listActivitiesMode = ConnectorExecution.BACKGROUND,
            downloadFitMode = ConnectorExecution.BACKGROUND,
            uploadFitMode = ConnectorExecution.BACKGROUND,
            supportsIncrementalCursor = true,
            supportsBulkHistory = true,
            experimental = true,
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
        Platform.STRAVA,
    )
}
