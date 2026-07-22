package com.fitsync.app.sync

import com.fitsync.app.connectors.PlatformCapabilityRegistry
import com.fitsync.app.data.db.FitSyncDatabase
import com.fitsync.app.data.db.entity.DeliveryJobEntity
import com.fitsync.app.data.db.entity.SyncRuleEntity
import com.fitsync.app.domain.model.DedupStatus
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.domain.model.SportType
import java.security.MessageDigest

class DeliveryPlanner(private val db: FitSyncDatabase) {
    suspend fun planForActivity(canonicalActivityId: Long, sourcePlatform: Platform): Int =
        planForActivities(listOf(canonicalActivityId), sourcePlatform)

    suspend fun planForActivities(canonicalActivityIds: List<Long>, sourcePlatform: Platform): Int {
        if (canonicalActivityIds.isEmpty()) return 0
        val activities = db.canonicalActivityDao().getByIds(canonicalActivityIds)
            .filter { isDeliveryPlanningAllowed(it.dedupStatus) }
        if (activities.isEmpty()) return 0
        val variants = db.activityVariantDao().findByCanonicalIds(activities.map { it.id })
            .groupBy { it.canonicalActivityId }
        val rules = db.syncRuleDao().getEnabled().filter { it.sourcePlatform == sourcePlatform }
        if (rules.isEmpty()) return 0
        val accounts = db.platformAccountDao().getAll().associateBy { it.id }
        val existingCopies = db.platformCopyDao().findByCanonicalIds(activities.map { it.id })
            .mapTo(hashSetOf()) { it.canonicalActivityId to it.platformAccountId }
        val jobs = buildList {
            activities.forEach { activity ->
                val candidates = variants[activity.id].orEmpty()
                val variant = candidates.firstOrNull { it.id == activity.primaryVariantId }
                    ?: candidates.maxByOrNull { it.qualityScore ?: 0 }
                    ?: return@forEach
                rules.forEach { rule ->
                    if (!isActivityEligibleForRule(activity.startTimeEpochMs, rule)) return@forEach
                    val destination = accounts[rule.destinationAccountId] ?: return@forEach
                    if (destination.platform == sourcePlatform) return@forEach
                    if ((activity.id to destination.id) in existingCopies) return@forEach
                    add(
                        DeliveryJobEntity(
                            canonicalActivityId = activity.id,
                            destinationAccountId = destination.id,
                            sourceVariantId = variant.id,
                            idempotencyKey = idempotencyKey(variant.fileSha256, destination.id),
                            executionMode = PlatformCapabilityRegistry.capabilities(destination.platform).uploadFitMode,
                            requestedByRuleId = rule.id,
                        ),
                    )
                }
            }
        }
        return if (jobs.isEmpty()) 0 else db.deliveryJobDao().insertAll(jobs).count { it > 0 }
    }

    suspend fun enqueueManual(canonicalActivityId: Long, destinationAccountId: Long): Long {
        val activity = db.canonicalActivityDao().getById(canonicalActivityId) ?: return -1
        if (!isDeliveryPlanningAllowed(activity.dedupStatus)) return -1
        val destination = db.platformAccountDao().getById(destinationAccountId) ?: return -1
        if (db.platformCopyDao().findByCanonicalAndAccount(canonicalActivityId, destinationAccountId) != null) {
            return 0
        }
        val variant = activity.primaryVariantId?.let { db.activityVariantDao().getById(it) }
            ?: db.activityVariantDao().findByCanonical(canonicalActivityId).maxByOrNull { it.qualityScore ?: 0 }
            ?: return -1
        return db.deliveryJobDao().insert(
            DeliveryJobEntity(
                canonicalActivityId = canonicalActivityId,
                destinationAccountId = destinationAccountId,
                sourceVariantId = variant.id,
                idempotencyKey = idempotencyKey(variant.fileSha256, destinationAccountId),
                executionMode = PlatformCapabilityRegistry.capabilities(destination.platform).uploadFitMode,
            ),
        )
    }

    /**
     * 为首页发起的一次性同步建立任务。范围由来源、目标、活动类型和历史起点共同限定，
     * 不读取也不修改长期自动化规则。
     */
    suspend fun planManualSelection(
        sourcePlatform: Platform,
        destinationAccountId: Long,
        sportTypes: Set<SportType>,
        fromEpochMs: Long,
    ): Int {
        if (sportTypes.isEmpty()) return 0
        val destination = db.platformAccountDao().getById(destinationAccountId) ?: return 0
        if (destination.platform == sourcePlatform) return 0
        val sourceAccountId = if (sourcePlatform == Platform.LOCAL_IMPORT) {
            null
        } else {
            db.platformAccountDao().getByPlatform(sourcePlatform)?.id
        }

        var count = 0
        var beforeTime = Long.MAX_VALUE
        var beforeId = Long.MAX_VALUE
        var reachedHistoryStart = false
        while (!reachedHistoryStart) {
            val page = db.canonicalActivityDao().getPageAfter(beforeTime, beforeId, HISTORY_PAGE_SIZE)
            if (page.isEmpty()) break
            val variants = db.activityVariantDao().findByCanonicalIds(page.map { it.id })
                .groupBy { it.canonicalActivityId }
            val copies = db.platformCopyDao().findByCanonicalIds(page.map { it.id })
                .groupBy { it.canonicalActivityId }
            val matchingIds = buildList {
                for (activity in page) {
                if (activity.startTimeEpochMs < fromEpochMs) {
                    reachedHistoryStart = true
                    break
                }
                if (activity.sportType !in sportTypes) continue
                val hasSourceVariant = variants[activity.id].orEmpty().any { variant ->
                    (variant.sourcePlatform ?: Platform.LOCAL_IMPORT) == sourcePlatform
                }
                val hasSourceCopy = sourceAccountId != null &&
                    copies[activity.id].orEmpty().any { it.platformAccountId == sourceAccountId }
                val comesFromSource = matchesManualSyncSource(
                    sourcePlatform = sourcePlatform,
                    hasSourceVariant = hasSourceVariant,
                    hasSourceCopy = hasSourceCopy,
                )
                if (!comesFromSource) continue
                add(activity.id)
                }
            }
            count += enqueueManualBatch(matchingIds, destinationAccountId, destination.platform)
            val last = page.last()
            beforeTime = last.startTimeEpochMs
            beforeId = last.id
            if (page.size < HISTORY_PAGE_SIZE) break
        }
        return count
    }

    suspend fun setRule(
        source: Platform,
        destinationAccountId: Long,
        enabled: Boolean,
        includeHistory: Boolean = false,
    ) {
        val existing = db.syncRuleDao().find(source, destinationAccountId)
        if (!enabled) {
            if (existing != null) db.syncRuleDao().delete(existing.id)
            return
        }
        val ruleId = db.syncRuleDao().upsert(
            SyncRuleEntity(
                id = existing?.id ?: 0,
                sourcePlatform = source,
                destinationAccountId = destinationAccountId,
                enabled = true,
                includeHistory = includeHistory,
                historyStartTimeEpochMs = if (includeHistory) 0 else null,
                createdAtEpochMs = existing?.createdAtEpochMs ?: System.currentTimeMillis(),
            ),
        )
        if (includeHistory && (existing?.includeHistory != true)) {
            planHistory(if (existing?.id == null || existing.id == 0L) ruleId else existing.id)
        }
    }

    suspend fun planHistory(ruleId: Long): Int {
        val rule = db.syncRuleDao().getAll().firstOrNull { it.id == ruleId } ?: return 0
        var count = 0
        var beforeTime = Long.MAX_VALUE
        var beforeId = Long.MAX_VALUE
        while (true) {
            val page = db.canonicalActivityDao().getPageAfter(beforeTime, beforeId, HISTORY_PAGE_SIZE)
            if (page.isEmpty()) break
            val variants = db.activityVariantDao().findByCanonicalIds(page.map { it.id })
                .groupBy { it.canonicalActivityId }
            val sourceIds = page.filter { activity ->
                variants[activity.id].orEmpty().any {
                    (it.sourcePlatform ?: Platform.LOCAL_IMPORT) == rule.sourcePlatform
                }
            }.map { it.id }
            count += planForActivities(sourceIds, rule.sourcePlatform)
            val last = page.last()
            beforeTime = last.startTimeEpochMs
            beforeId = last.id
            if (page.size < HISTORY_PAGE_SIZE) break
        }
        return count
    }

    private suspend fun enqueueManualBatch(
        canonicalActivityIds: List<Long>,
        destinationAccountId: Long,
        destinationPlatform: Platform,
    ): Int {
        if (canonicalActivityIds.isEmpty()) return 0
        val activities = db.canonicalActivityDao().getByIds(canonicalActivityIds)
            .filter { isDeliveryPlanningAllowed(it.dedupStatus) }
        val variants = db.activityVariantDao().findByCanonicalIds(activities.map { it.id })
            .groupBy { it.canonicalActivityId }
        val copied = db.platformCopyDao().findByCanonicalIds(activities.map { it.id })
            .filter { it.platformAccountId == destinationAccountId }
            .mapTo(hashSetOf()) { it.canonicalActivityId }
        val mode = PlatformCapabilityRegistry.capabilities(destinationPlatform).uploadFitMode
        val jobs = activities.mapNotNull { activity ->
            if (activity.id in copied) return@mapNotNull null
            val candidates = variants[activity.id].orEmpty()
            val variant = candidates.firstOrNull { it.id == activity.primaryVariantId }
                ?: candidates.maxByOrNull { it.qualityScore ?: 0 }
                ?: return@mapNotNull null
            DeliveryJobEntity(
                canonicalActivityId = activity.id,
                destinationAccountId = destinationAccountId,
                sourceVariantId = variant.id,
                idempotencyKey = idempotencyKey(variant.fileSha256, destinationAccountId),
                executionMode = mode,
            )
        }
        return if (jobs.isEmpty()) 0 else db.deliveryJobDao().insertAll(jobs).count { it > 0 }
    }

    companion object {
        private const val HISTORY_PAGE_SIZE = 100

        fun idempotencyKey(fileSha256: String, destinationAccountId: Long): String {
            val raw = "$fileSha256:$destinationAccountId".toByteArray()
            return MessageDigest.getInstance("SHA-256").digest(raw)
                .joinToString("") { "%02x".format(it) }
        }
    }
}

internal fun isActivityEligibleForRule(activityStartTimeEpochMs: Long, rule: SyncRuleEntity): Boolean =
    rule.includeHistory || activityStartTimeEpochMs >= rule.createdAtEpochMs

internal fun isDeliveryPlanningAllowed(dedupStatus: DedupStatus): Boolean =
    dedupStatus != DedupStatus.NEEDS_REVIEW

/**
 * “本机导入”表示已经进入 FitSync 本地活动库的全部活动，不受原始平台标签限制。
 * 云平台来源仍需存在对应的平台原始文件或平台副本。
 */
internal fun matchesManualSyncSource(
    sourcePlatform: Platform,
    hasSourceVariant: Boolean,
    hasSourceCopy: Boolean,
): Boolean = sourcePlatform == Platform.LOCAL_IMPORT || hasSourceVariant || hasSourceCopy
