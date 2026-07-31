package com.fitsync.app.sync

import com.fitsync.app.connectors.PlatformCapabilityRegistry
import com.fitsync.app.data.db.FitSyncDatabase
import com.fitsync.app.data.db.entity.ActivityVariantEntity
import com.fitsync.app.data.db.entity.CanonicalActivityEntity
import com.fitsync.app.data.db.entity.DeliveryJobEntity
import com.fitsync.app.data.db.entity.PlatformAccountEntity
import com.fitsync.app.data.db.entity.SyncRuleEntity
import com.fitsync.app.domain.model.ConnectorExecution
import com.fitsync.app.domain.model.DedupStatus
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.domain.model.SportType
import java.security.MessageDigest

class DeliveryPlanner(private val db: FitSyncDatabase) {
    suspend fun planForActivity(canonicalActivityId: Long, sourcePlatform: Platform): Int =
        planForActivities(listOf(canonicalActivityId), sourcePlatform)

    suspend fun planForActivities(
        canonicalActivityIds: List<Long>,
        sourcePlatform: Platform,
        allowedDestinationAccountIds: Set<Long>? = null,
    ): Int {
        if (canonicalActivityIds.isEmpty()) return 0
        val activities = db.canonicalActivityDao().getByIds(canonicalActivityIds)
        if (activities.isEmpty()) return 0
        val rules = db.syncRuleDao().getEnabled().filter { rule ->
            rule.sourcePlatform == sourcePlatform &&
                isDestinationReadyForPlanning(rule.destinationAccountId, allowedDestinationAccountIds)
        }
        if (rules.isEmpty()) return 0

        val accountsById = db.platformAccountDao().getAll().associateBy { it.id }
        val targets = resolveRuleTargets(rules, accountsById)
        if (targets.isEmpty()) return 0

        val candidates = loadPlanningCandidates(activities)
        val jobs = buildList {
            candidates.forEach { candidate ->
                targets.forEach targetLoop@ { target ->
                    if (!isActivityEligibleForRule(candidate.activity.startTimeEpochMs, target.rule)) {
                        return@targetLoop
                    }
                    candidate.createJob(target)?.let(::add)
                }
            }
        }
        return insertJobs(jobs)
    }

    suspend fun enqueueManual(canonicalActivityId: Long, destinationAccountId: Long): Long {
        val destination = db.platformAccountDao().getById(destinationAccountId) ?: return -1
        val activity = db.canonicalActivityDao().getById(canonicalActivityId) ?: return -1
        if (db.platformCopyDao().findByCanonicalAndAccount(canonicalActivityId, destinationAccountId) != null) {
            return 0
        }
        val candidate = loadPlanningCandidates(listOf(activity)).singleOrNull() ?: return -1
        return db.deliveryJobDao().insert(
            candidate.createJob(
                destinationAccountId = destination.id,
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
        val accounts = db.platformAccountDao().getAll()
        val destination = accounts.firstOrNull { it.id == destinationAccountId } ?: return 0
        if (destination.platform == sourcePlatform) return 0
        val sourceAccountId = accounts.firstOrNull { it.platform == sourcePlatform }?.id
        val executionMode = PlatformCapabilityRegistry.capabilities(destination.platform).uploadFitMode

        var count = 0
        var beforeTime = Long.MAX_VALUE
        var beforeId = Long.MAX_VALUE
        var reachedHistoryStart = false
        while (!reachedHistoryStart) {
            val page = db.canonicalActivityDao().getPageAfter(beforeTime, beforeId, HISTORY_PAGE_SIZE)
            if (page.isEmpty()) break
            val activitiesInWindow = page.takeWhile { it.startTimeEpochMs >= fromEpochMs }
            reachedHistoryStart = activitiesInWindow.size < page.size
            val jobs = loadPlanningCandidates(activitiesInWindow).mapNotNull { candidate ->
                if (candidate.activity.sportType !in sportTypes ||
                    !candidate.matchesSource(sourcePlatform, sourceAccountId) ||
                    destinationAccountId in candidate.copiedAccountIds
                ) {
                    return@mapNotNull null
                }
                candidate.createJob(destinationAccountId, executionMode)
            }
            count += insertJobs(jobs)

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
        db.syncRuleDao().upsert(
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
    }

    /**
     * 一次分页扫描补齐全部规则的积压任务，避免每条规则重复遍历完整活动库。
     * 未开启 includeHistory 的规则仍由 [isActivityEligibleForRule] 限制为规则创建后的活动。
     */
    suspend fun planRuleBacklogs(
        rules: List<SyncRuleEntity>,
        allowedDestinationAccountIds: Set<Long>? = null,
    ): Int {
        if (rules.isEmpty()) return 0
        val accounts = db.platformAccountDao().getAll()
        val accountsById = accounts.associateBy { it.id }
        val targets = resolveRuleTargets(
            rules = rules.filter { rule ->
                rule.enabled &&
                    isDestinationReadyForPlanning(rule.destinationAccountId, allowedDestinationAccountIds)
            },
            accountsById = accountsById,
        )
        if (targets.isEmpty()) return 0
        val sourceAccountIds = accounts.associate { it.platform to it.id }

        var count = 0
        var beforeTime = Long.MAX_VALUE
        var beforeId = Long.MAX_VALUE
        while (true) {
            val page = db.canonicalActivityDao().getPageAfter(beforeTime, beforeId, HISTORY_PAGE_SIZE)
            if (page.isEmpty()) break
            val candidates = loadPlanningCandidates(page)
            val jobs = buildList {
                candidates.forEach { candidate ->
                    targets.forEach targetLoop@ { target ->
                        val rule = target.rule
                        if (!isActivityEligibleForRule(candidate.activity.startTimeEpochMs, rule) ||
                            !candidate.matchesSource(rule.sourcePlatform, sourceAccountIds[rule.sourcePlatform])
                        ) {
                            return@targetLoop
                        }
                        candidate.createJob(target)?.let(::add)
                    }
                }
            }
            count += insertJobs(jobs)

            val last = page.last()
            beforeTime = last.startTimeEpochMs
            beforeId = last.id
            if (page.size < HISTORY_PAGE_SIZE) break
        }
        return count
    }

    private suspend fun loadPlanningCandidates(
        activities: List<CanonicalActivityEntity>,
    ): List<PlanningCandidate> {
        val eligibleActivities = activities.filter { isDeliveryPlanningAllowed(it.dedupStatus) }
        if (eligibleActivities.isEmpty()) return emptyList()
        val activityIds = eligibleActivities.map { it.id }
        val variantsByCanonical = db.activityVariantDao().findByCanonicalIds(activityIds)
            .groupBy { it.canonicalActivityId }
        val copiesByCanonical = db.platformCopyDao().findByCanonicalIds(activityIds)
            .groupBy { it.canonicalActivityId }

        return eligibleActivities.mapNotNull { activity ->
            val variants = variantsByCanonical[activity.id].orEmpty()
            val selectedVariant = selectDeliveryVariant(activity.primaryVariantId, variants)
                ?: return@mapNotNull null
            PlanningCandidate(
                activity = activity,
                sourceVariant = selectedVariant,
                sourcePlatforms = variants.mapTo(mutableSetOf()) {
                    it.sourcePlatform ?: Platform.LOCAL_IMPORT
                },
                copiedAccountIds = copiesByCanonical[activity.id].orEmpty()
                    .mapTo(mutableSetOf()) { it.platformAccountId },
            )
        }
    }

    private fun resolveRuleTargets(
        rules: List<SyncRuleEntity>,
        accountsById: Map<Long, PlatformAccountEntity>,
    ): List<RuleTarget> = rules.mapNotNull { rule ->
        val destination = accountsById[rule.destinationAccountId] ?: return@mapNotNull null
        if (destination.platform == rule.sourcePlatform) return@mapNotNull null
        RuleTarget(
            rule = rule,
            executionMode = PlatformCapabilityRegistry.capabilities(destination.platform).uploadFitMode,
        )
    }

    private suspend fun insertJobs(jobs: List<DeliveryJobEntity>): Int =
        if (jobs.isEmpty()) 0 else db.deliveryJobDao().insertAll(jobs).count { it > 0 }

    private data class PlanningCandidate(
        val activity: CanonicalActivityEntity,
        val sourceVariant: ActivityVariantEntity,
        val sourcePlatforms: Set<Platform>,
        val copiedAccountIds: Set<Long>,
    ) {
        fun matchesSource(sourcePlatform: Platform, sourceAccountId: Long?): Boolean =
            matchesActivitySource(
                sourcePlatform = sourcePlatform,
                hasSourceVariant = sourcePlatform in sourcePlatforms,
                hasSourceCopy = sourceAccountId != null && sourceAccountId in copiedAccountIds,
            )

        fun createJob(target: RuleTarget): DeliveryJobEntity? {
            if (target.rule.destinationAccountId in copiedAccountIds) return null
            return createJob(
                destinationAccountId = target.rule.destinationAccountId,
                executionMode = target.executionMode,
                requestedByRuleId = target.rule.id,
            )
        }

        fun createJob(
            destinationAccountId: Long,
            executionMode: ConnectorExecution,
            requestedByRuleId: Long? = null,
        ): DeliveryJobEntity = DeliveryJobEntity(
            canonicalActivityId = activity.id,
            destinationAccountId = destinationAccountId,
            sourceVariantId = sourceVariant.id,
            idempotencyKey = idempotencyKey(sourceVariant.fileSha256, destinationAccountId),
            executionMode = executionMode,
            requestedByRuleId = requestedByRuleId,
        )
    }

    private data class RuleTarget(
        val rule: SyncRuleEntity,
        val executionMode: ConnectorExecution,
    )

    companion object {
        private const val HISTORY_PAGE_SIZE = 100
        private const val LOWER_HEX = "0123456789abcdef"

        fun idempotencyKey(fileSha256: String, destinationAccountId: Long): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$fileSha256:$destinationAccountId".toByteArray(Charsets.UTF_8))
            val encoded = CharArray(digest.size * 2)
            digest.forEachIndexed { index, byte ->
                val value = byte.toInt() and 0xFF
                encoded[index * 2] = LOWER_HEX[value ushr 4]
                encoded[index * 2 + 1] = LOWER_HEX[value and 0x0F]
            }
            return String(encoded)
        }
    }
}

internal fun selectDeliveryVariant(
    primaryVariantId: Long?,
    variants: List<ActivityVariantEntity>,
): ActivityVariantEntity? = variants.firstOrNull { it.id == primaryVariantId }
    ?: variants.maxByOrNull { it.qualityScore ?: 0 }

internal fun isActivityEligibleForRule(activityStartTimeEpochMs: Long, rule: SyncRuleEntity): Boolean =
    rule.includeHistory || activityStartTimeEpochMs >= rule.createdAtEpochMs

internal fun isDeliveryPlanningAllowed(dedupStatus: DedupStatus): Boolean =
    dedupStatus != DedupStatus.NEEDS_REVIEW

/**
 * 传入目标账号白名单时，只允许已经完成本轮目标端预拉取的账号创建投递任务。
 */
internal fun isDestinationReadyForPlanning(
    destinationAccountId: Long,
    allowedDestinationAccountIds: Set<Long>?,
): Boolean = allowedDestinationAccountIds == null || destinationAccountId in allowedDestinationAccountIds

/** 规则积压补扫不区分 includeHistory；历史边界由 [isActivityEligibleForRule] 逐活动判断。 */
internal fun isRuleBacklogPlanningAllowed(
    rule: SyncRuleEntity,
    readyDestinationAccountIds: Set<Long>,
): Boolean = rule.enabled && rule.destinationAccountId in readyDestinationAccountIds

/**
 * “本机导入”表示已经进入 FitSync 本地活动库的全部活动，不受原始平台标签限制。
 * 云平台来源仍需存在对应的平台原始文件或平台副本。手动同步与自动规则补扫必须共用该语义。
 */
internal fun matchesActivitySource(
    sourcePlatform: Platform,
    hasSourceVariant: Boolean,
    hasSourceCopy: Boolean,
): Boolean = sourcePlatform == Platform.LOCAL_IMPORT || hasSourceVariant || hasSourceCopy
