package com.fitsync.app.data.repo

import com.fitsync.app.data.db.FitSyncDatabase
import com.fitsync.app.data.file.FitFileStore
import com.fitsync.app.domain.model.DeliveryJobStatus
import com.fitsync.app.domain.model.FitStoragePolicy

/**
 * 按 [FitStoragePolicy] 清理已不再需要本地文件的 FIT。
 * 仅删沙箱文件并清空 variant.localFilePath，元数据与 PlatformCopy 保留。
 */
class FitStorageJanitor(
    private val db: FitSyncDatabase,
    private val fileStore: FitFileStore,
    private val mutationGate: ActivityMutationGate = ActivityMutationGate(),
) {
    suspend fun run(
        policy: FitStoragePolicy? = null,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): FitStorageJanitorResult = mutationGate.withLock {
        val resolvedPolicy = policy
            ?: db.appSettingsDao().get()?.storagePolicy
            ?: FitStoragePolicy.DELETE_AFTER_7_DAYS
        if (resolvedPolicy == FitStoragePolicy.KEEP_FOREVER) {
            return@withLock FitStorageJanitorResult()
        }
        val variants = db.activityVariantDao().findWithLocalFiles()
        if (variants.isEmpty()) return@withLock FitStorageJanitorResult()

        val activeStatuses = ACTIVE_JOB_STATUSES.map { it.name }.toSet()
        val pathsToDelete = mutableListOf<String>()
        val variantIdsToClear = mutableListOf<Long>()

        for (variant in variants) {
            val path = variant.localFilePath
            if (path.isBlank()) continue
            val jobs = db.deliveryJobDao().findBySourceVariant(variant.id)
            if (jobs.any { it.status.name in activeStatuses }) continue

            val terminalJobs = jobs.filter {
                it.status == DeliveryJobStatus.SUCCEEDED ||
                    it.status == DeliveryJobStatus.DUPLICATE_REMOTE
            }
            val eligible = when (resolvedPolicy) {
                FitStoragePolicy.KEEP_FOREVER -> false
                // 至少成功投递过一次且无活跃任务，才删本地 FIT（避免未同步活动被误清）
                FitStoragePolicy.DELETE_AFTER_SYNC -> terminalJobs.isNotEmpty()
                FitStoragePolicy.DELETE_AFTER_7_DAYS -> {
                    val anchor = terminalJobs.maxOfOrNull { it.updatedAtEpochMs }
                        ?: variant.createdAtEpochMs
                    nowEpochMs - anchor >= SEVEN_DAYS_MS
                }
            }
            if (!eligible) continue
            pathsToDelete += path
            variantIdsToClear += variant.id
        }

        if (variantIdsToClear.isEmpty()) return@withLock FitStorageJanitorResult()

        val failed = fileStore.deleteStoredFiles(pathsToDelete)
        val failedSet = failed.toSet()
        var cleared = 0
        for (id in variantIdsToClear) {
            val variant = db.activityVariantDao().getById(id) ?: continue
            if (variant.localFilePath in failedSet) continue
            db.activityVariantDao().update(variant.copy(localFilePath = ""))
            cleared++
        }
        FitStorageJanitorResult(deletedFiles = cleared, failedPaths = failed)
    }

    companion object {
        private const val SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000
        private val ACTIVE_JOB_STATUSES = setOf(
            DeliveryJobStatus.PENDING,
            DeliveryJobStatus.UPLOADING,
            DeliveryJobStatus.WAITING_USER,
            DeliveryJobStatus.WAITING_REMOTE_PROCESS,
            DeliveryJobStatus.RETRY_WAIT,
        )
    }
}

data class FitStorageJanitorResult(
    val deletedFiles: Int = 0,
    val failedPaths: List<String> = emptyList(),
)
