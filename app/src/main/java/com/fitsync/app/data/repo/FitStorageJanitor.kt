package com.fitsync.app.data.repo

import com.fitsync.app.data.db.FitSyncDatabase
import com.fitsync.app.data.file.FitFileStore
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
        val candidates = db.activityVariantDao().findCleanupCandidates()
        if (candidates.isEmpty()) return@withLock FitStorageJanitorResult()

        val pathsToDelete = mutableListOf<String>()
        val variantIdsToClear = mutableListOf<Long>()

        for (candidate in candidates) {
            val path = candidate.localFilePath
            if (path.isBlank()) continue
            val eligible = isFitStorageDeletionEligible(
                policy = resolvedPolicy,
                hasActiveJobs = candidate.activeJobCount > 0,
                latestSuccessfulDeliveryAtEpochMs = candidate.latestSuccessfulDeliveryAtEpochMs,
                nowEpochMs = nowEpochMs,
            )
            if (!eligible) continue
            pathsToDelete += path
            variantIdsToClear += candidate.variantId
        }

        if (variantIdsToClear.isEmpty()) return@withLock FitStorageJanitorResult()

        val failed = fileStore.deleteStoredFiles(pathsToDelete)
        val failedSet = failed.toSet()
        val candidatePaths = candidates.associate { it.variantId to it.localFilePath }
        val clearedIds = variantIdsToClear.filter { candidatePaths[it] !in failedSet }
        if (clearedIds.isNotEmpty()) db.activityVariantDao().clearLocalFilePaths(clearedIds)
        val cleared = clearedIds.size
        FitStorageJanitorResult(deletedFiles = cleared, failedPaths = failed)
    }
}

internal fun isFitStorageDeletionEligible(
    policy: FitStoragePolicy,
    hasActiveJobs: Boolean,
    latestSuccessfulDeliveryAtEpochMs: Long?,
    nowEpochMs: Long,
): Boolean {
    if (hasActiveJobs) return false
    return when (policy) {
        FitStoragePolicy.KEEP_FOREVER -> false
        FitStoragePolicy.DELETE_AFTER_SYNC -> latestSuccessfulDeliveryAtEpochMs != null
        FitStoragePolicy.DELETE_AFTER_7_DAYS -> latestSuccessfulDeliveryAtEpochMs != null &&
            nowEpochMs - latestSuccessfulDeliveryAtEpochMs >= SEVEN_DAYS_MS
    }
}

private const val SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000

data class FitStorageJanitorResult(
    val deletedFiles: Int = 0,
    val failedPaths: List<String> = emptyList(),
)
