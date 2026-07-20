package com.fitsync.app.connectors

import com.fitsync.app.domain.model.ConnectorExecution

/**
 * Declares real read/write capabilities for a platform connector.
 * Read success must never be treated as write capability.
 */
data class ConnectorCapabilities(
    val listActivitiesMode: ConnectorExecution = ConnectorExecution.UNSUPPORTED,
    val downloadFitMode: ConnectorExecution = ConnectorExecution.UNSUPPORTED,
    val uploadFitMode: ConnectorExecution = ConnectorExecution.UNSUPPORTED,
    val supportsWebhook: Boolean = false,
    val supportsIncrementalCursor: Boolean = false,
    val supportsBulkHistory: Boolean = false,
    val supportsIdempotencyKey: Boolean = false,
    val shareImportSupported: Boolean = true,
    val experimental: Boolean = false,
) {
    val canListActivities: Boolean
        get() = listActivitiesMode != ConnectorExecution.UNSUPPORTED
    val canDownloadOriginalFit: Boolean
        get() = downloadFitMode != ConnectorExecution.UNSUPPORTED
    val canUploadActivity: Boolean
        get() = uploadFitMode != ConnectorExecution.UNSUPPORTED

    fun serialize(): String = listOf(
        listActivitiesMode.name,
        downloadFitMode.name,
        uploadFitMode.name,
        experimental.toString(),
    ).joinToString("|")

    fun capabilityLabel(): String = when {
        experimental -> "实验性连接"
        canListActivities && canDownloadOriginalFit && canUploadActivity -> "可读取和写入"
        canListActivities && canDownloadOriginalFit -> "仅可读取"
        canUploadActivity -> "仅可写入"
        shareImportSupported -> "仅分享导入"
        else -> "暂不支持"
    }

    companion object {
        fun deserialize(value: String?): ConnectorCapabilities? {
            if (value.isNullOrBlank()) return null
            val parts = value.split('|')
            if (parts.size != 4) return null
            return runCatching {
                ConnectorCapabilities(
                    listActivitiesMode = ConnectorExecution.valueOf(parts[0]),
                    downloadFitMode = ConnectorExecution.valueOf(parts[1]),
                    uploadFitMode = ConnectorExecution.valueOf(parts[2]),
                    experimental = parts[3].toBooleanStrict(),
                )
            }.getOrNull()
        }
    }
}
