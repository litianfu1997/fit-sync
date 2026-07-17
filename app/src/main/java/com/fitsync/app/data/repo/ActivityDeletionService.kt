package com.fitsync.app.data.repo

import androidx.room.withTransaction
import com.fitsync.app.data.db.FitSyncDatabase
import com.fitsync.app.data.file.FitFileStore
import com.fitsync.app.notification.SyncNotificationManager

class ActivityDeletionService(
    private val db: FitSyncDatabase,
    private val fileStore: FitFileStore,
    private val notifications: SyncNotificationManager,
    private val mutationGate: ActivityMutationGate,
) {
    suspend fun delete(activityId: Long): ActivityDeletionResult = mutationGate.withLock {
        if (db.canonicalActivityDao().getById(activityId) == null) {
            return@withLock ActivityDeletionResult.AlreadyDeleted
        }

        val variants = db.activityVariantDao().findByCanonical(activityId)
        val importJobs = db.importJobDao().findByCanonical(activityId)
        val deliveryJobs = db.deliveryJobDao().findByCanonical(activityId)
        val paths = buildSet {
            variants.mapTo(this) { it.localFilePath }
            importJobs.mapNotNullTo(this) { it.localFilePath }
        }

        db.withTransaction {
            db.platformCopyDao().deleteByCanonical(activityId)
            db.deliveryJobDao().deleteByCanonical(activityId)
            db.importJobDao().deleteByCanonical(activityId)
            db.activityVariantDao().deleteByCanonical(activityId)
            db.canonicalActivityDao().delete(activityId)
        }
        deliveryJobs.forEach { notifications.cancel(it.id) }

        val failedPaths = fileStore.deleteStoredFiles(paths)
        ActivityDeletionResult.Deleted(failedPaths)
    }
}

sealed interface ActivityDeletionResult {
    data object AlreadyDeleted : ActivityDeletionResult
    data class Deleted(val failedPaths: List<String>) : ActivityDeletionResult
}
