package com.fitsync.app.data.repo

import androidx.room.withTransaction
import com.fitsync.app.data.db.FitSyncDatabase
import com.fitsync.app.data.db.entity.CanonicalActivityEntity
import com.fitsync.app.data.file.FitFileNaming
import com.fitsync.app.domain.model.DedupStatus
import com.fitsync.app.domain.model.DeliveryJobStatus
import com.fitsync.app.notification.SyncNotificationManager

/**
 * 人工处理 NEEDS_REVIEW：合并到候选 canonical，或标记为保持分离。
 */
class ActivityMergeService(
    private val db: FitSyncDatabase,
    private val notifications: SyncNotificationManager,
    private val mutationGate: ActivityMutationGate = ActivityMutationGate(),
) {
    suspend fun keepSeparate(sourceCanonicalId: Long): Boolean = mutationGate.withLock {
        val source = db.canonicalActivityDao().getById(sourceCanonicalId) ?: return@withLock false
        if (source.dedupStatus != DedupStatus.NEEDS_REVIEW) return@withLock false
        db.canonicalActivityDao().update(
            source.copy(
                dedupStatus = DedupStatus.MANUALLY_KEPT_SEPARATE,
                reviewCandidateCanonicalId = null,
            ),
        )
        true
    }

    suspend fun mergeIntoCandidate(sourceCanonicalId: Long): MergeResult = mutationGate.withLock {
        val source = db.canonicalActivityDao().getById(sourceCanonicalId)
            ?: return@withLock MergeResult.NotFound
        val targetId = source.reviewCandidateCanonicalId
            ?: return@withLock MergeResult.NoCandidate
        if (source.dedupStatus != DedupStatus.NEEDS_REVIEW) {
            return@withLock MergeResult.InvalidState
        }
        if (targetId == sourceCanonicalId) return@withLock MergeResult.InvalidState
        val target = db.canonicalActivityDao().getById(targetId)
            ?: return@withLock MergeResult.CandidateMissing

        mergeLocked(source, target)
    }

    suspend fun merge(sourceCanonicalId: Long, targetCanonicalId: Long): MergeResult =
        mutationGate.withLock {
            val source = db.canonicalActivityDao().getById(sourceCanonicalId)
                ?: return@withLock MergeResult.NotFound
            val target = db.canonicalActivityDao().getById(targetCanonicalId)
                ?: return@withLock MergeResult.CandidateMissing
            if (sourceCanonicalId == targetCanonicalId) return@withLock MergeResult.InvalidState
            mergeLocked(source, target)
        }

    private suspend fun mergeLocked(
        source: CanonicalActivityEntity,
        target: CanonicalActivityEntity,
    ): MergeResult {
        val sourceId = source.id
        val targetId = target.id
        val sourceVariants = db.activityVariantDao().findByCanonical(sourceId)
        val targetVariants = db.activityVariantDao().findByCanonical(targetId)
        val sourceJobs = db.deliveryJobDao().findByCanonical(sourceId)
        val sourceCopies = db.platformCopyDao().findByCanonical(sourceId)
        val targetCopies = db.platformCopyDao().findByCanonical(targetId)
        val targetAccountIds = targetCopies.map { it.platformAccountId }.toSet()
        val targetDestinations = db.deliveryJobDao().findByCanonical(targetId)
            .map { it.destinationAccountId }
            .toSet()

        db.withTransaction {
            sourceVariants.forEach { variant ->
                db.activityVariantDao().update(variant.copy(canonicalActivityId = targetId))
            }

            val allVariants = targetVariants + sourceVariants.map { it.copy(canonicalActivityId = targetId) }
            val bestPrimary = allVariants.maxByOrNull { it.qualityScore ?: 0 }?.id
                ?: target.primaryVariantId
                ?: source.primaryVariantId

            sourceCopies.forEach { copy ->
                if (copy.platformAccountId in targetAccountIds) {
                    // 目标已有该账号副本，丢弃 source 映射
                    return@forEach
                }
                db.platformCopyDao().update(copy.copy(canonicalActivityId = targetId))
            }

            sourceJobs.forEach { job ->
                if (job.destinationAccountId in targetDestinations ||
                    job.destinationAccountId in targetAccountIds
                ) {
                    db.deliveryJobDao().update(
                        job.copy(
                            status = DeliveryJobStatus.DISABLED,
                            errorMessage = "已合并到活动 #$targetId",
                            updatedAtEpochMs = System.currentTimeMillis(),
                        ),
                    )
                    notifications.cancel(job.id)
                } else {
                    db.deliveryJobDao().update(job.copy(canonicalActivityId = targetId))
                }
            }

            db.importJobDao().findByCanonical(sourceId).forEach { job ->
                db.importJobDao().update(job.copy(canonicalActivityId = targetId))
            }

            // 指向 source 的其它待复核记录改指 target
            db.canonicalActivityDao().findByReviewCandidate(sourceId).forEach { other ->
                db.canonicalActivityDao().update(other.copy(reviewCandidateCanonicalId = targetId))
            }

            val merged = target.copy(
                endTimeEpochMs = target.endTimeEpochMs ?: source.endTimeEpochMs,
                elapsedSeconds = target.elapsedSeconds ?: source.elapsedSeconds,
                movingSeconds = target.movingSeconds ?: source.movingSeconds,
                distanceMeters = target.distanceMeters ?: source.distanceMeters,
                elevationGain = target.elevationGain ?: source.elevationGain,
                avgHeartRate = target.avgHeartRate ?: source.avgHeartRate,
                primaryVariantId = bestPrimary,
                dedupStatus = DedupStatus.MANUALLY_MERGED,
                reviewCandidateCanonicalId = null,
                title = FitFileNaming.displayTitle(target.startTimeEpochMs, target.sportType),
            )
            db.canonicalActivityDao().update(merged)
            db.platformCopyDao().deleteByCanonical(sourceId)
            db.deliveryJobDao().deleteByCanonical(sourceId)
            db.importJobDao().deleteByCanonical(sourceId)
            // variants already re-parented; nothing left under source
            db.canonicalActivityDao().delete(sourceId)
        }

        return MergeResult.Merged(targetId = targetId, movedVariants = sourceVariants.size)
    }
}

sealed interface MergeResult {
    data class Merged(val targetId: Long, val movedVariants: Int) : MergeResult
    data object NotFound : MergeResult
    data object NoCandidate : MergeResult
    data object CandidateMissing : MergeResult
    data object InvalidState : MergeResult
}
