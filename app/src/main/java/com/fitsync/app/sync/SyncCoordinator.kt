package com.fitsync.app.sync

import android.os.Trace
import android.os.SystemClock
import androidx.room.withTransaction
import com.fitsync.app.connectors.ConnectorRegistry
import com.fitsync.app.connectors.ConnectorCapabilities
import com.fitsync.app.connectors.ConnectorResult
import com.fitsync.app.connectors.FitFile
import com.fitsync.app.connectors.PlatformConnector
import com.fitsync.app.connectors.PlatformCapabilityRegistry
import com.fitsync.app.connectors.RemoteActivitySummary
import com.fitsync.app.connectors.SyncCursor
import com.fitsync.app.connectors.parseAsyncUploadConfirmationToken
import com.fitsync.app.data.db.FitSyncDatabase
import com.fitsync.app.data.db.entity.CanonicalActivityEntity
import com.fitsync.app.data.db.entity.ActivityVariantEntity
import com.fitsync.app.data.db.entity.DeliveryJobEntity
import com.fitsync.app.data.db.entity.PlatformCopyEntity
import com.fitsync.app.data.db.entity.PlatformAccountEntity
import com.fitsync.app.data.db.entity.RemoteActivitySkipEntity
import com.fitsync.app.data.repo.ActivityMutationGate
import com.fitsync.app.data.repo.FitStorageJanitor
import com.fitsync.app.data.repo.ImportOutcome
import com.fitsync.app.data.repo.ImportPreparedFile
import com.fitsync.app.data.repo.ImportService
import com.fitsync.app.data.repo.PlatformCopyUpsertPlan
import com.fitsync.app.data.repo.planPlatformCopyUpsert
import com.fitsync.app.domain.model.AccountStatus
import com.fitsync.app.domain.model.CollectorType
import com.fitsync.app.domain.model.ConnectorExecution
import com.fitsync.app.domain.model.DeliveryJobStatus
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.notification.SyncNotificationManager
import com.fitsync.app.web.PlatformWebConfigs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.math.min
import kotlin.math.abs
import kotlin.math.pow

class SyncCoordinator(
    private val db: FitSyncDatabase,
    private val importService: ImportService,
    private val connectors: ConnectorRegistry,
    val planner: DeliveryPlanner,
    private val notifications: SyncNotificationManager,
    private val mutationGate: ActivityMutationGate = ActivityMutationGate(),
    private val storageJanitor: FitStorageJanitor? = null,
) {
    private val syncMutex = Mutex()
    private val globalNetworkSemaphore = Semaphore(MAX_GLOBAL_NETWORK_TRANSFERS)

    suspend fun runSync(
        force: Boolean = false,
        manualSelection: ManualSyncSelection? = null,
    ): SyncRunSummary = syncMutex.withLock sync@{
        val settings = db.appSettingsDao().get()
        if (!force && settings?.autoSyncEnabled == false) return@sync SyncRunSummary()
        var pulled = 0
        var queued = 0
        val historyDays = settings?.initialHistoryDays ?: 90
        val allAccounts = db.platformAccountDao().getAll()
        val enabledRules = if (manualSelection == null) db.syncRuleDao().getEnabled() else emptyList()
        val destinationAccount = manualSelection?.let { selection ->
            val destination = allAccounts.firstOrNull { it.id == selection.destinationAccountId }
                ?: throw IllegalArgumentException("目标平台账号已不存在")
            require(destination.status == AccountStatus.ACTIVE || destination.status == AccountStatus.EXPERIMENTAL) {
                "目标平台需要先完成登录"
            }
            require(destination.platform != selection.sourcePlatform) { "来源和目标不能相同" }
            require(PlatformCapabilityRegistry.capabilities(destination.platform).canUploadActivity) {
                "目标平台暂不支持活动上传"
            }
            destination
        }
        val activeAccounts = allAccounts.filter { it.status != AccountStatus.DISABLED }
        val sourceAccounts = when {
            manualSelection == null -> activeAccounts
            manualSelection.sourcePlatform == Platform.LOCAL_IMPORT -> emptyList()
            else -> allAccounts.filter {
                it.platform == manualSelection.sourcePlatform &&
                    (it.status == AccountStatus.ACTIVE || it.status == AccountStatus.EXPERIMENTAL)
            }.also {
                require(it.isNotEmpty()) { "来源平台需要先完成登录" }
            }
        }
        val accountsToCheck = when (manualSelection) {
            null -> activeAccounts
            else -> listOfNotNull(destinationAccount) + sourceAccounts
        }.distinctBy { it.id }
        val activeDeliveryDestinationIds = if (manualSelection == null) {
            db.deliveryJobDao().findActiveDestinationAccountIds().toSet()
        } else {
            emptySet()
        }
        val destinationAccountIds = when (manualSelection) {
            null -> enabledRules.mapTo(mutableSetOf()) { it.destinationAccountId } + activeDeliveryDestinationIds
            else -> setOf(manualSelection.destinationAccountId)
        }
        val accountById = accountsToCheck.associateBy { it.id }
        val phases = buildSyncAccountPhases(accountsToCheck.map { it.id }, destinationAccountIds)
        val manualFromEpochMs = manualSelection?.let {
            calculatePullSince(
                nowEpochMs = System.currentTimeMillis(),
                previousSyncEpochMs = null,
                historyDays = historyDays,
                forceFullHistory = true,
            )
        }
        val targetSinceOverrides = buildTargetSinceOverrides(
            destinationAccountIds = destinationAccountIds,
            includeAllHistoryAccountIds = enabledRules
                .filter { it.includeHistory }
                .mapTo(mutableSetOf()) { it.destinationAccountId },
            includeHistoryFromEpochMs = calculatePullSince(
                nowEpochMs = System.currentTimeMillis(),
                previousSyncEpochMs = null,
                historyDays = historyDays,
                forceFullHistory = true,
            ),
            manualDestinationAccountId = manualSelection?.destinationAccountId,
            manualFromEpochMs = manualFromEpochMs,
        )
        // 第一阶段必须完整结束后才进入来源阶段，确保 DeliveryPlanner 看到的是目标端最新副本集合。
        val destinationResults = syncAccounts(
            accounts = phases.destinationAccountIds.mapNotNull(accountById::get),
            historyDays = historyDays,
            force = force,
            notificationsEnabled = settings?.notificationsEnabled != false,
            sinceEpochMsByAccount = targetSinceOverrides,
        )
        val readyDestinationAccountIds = destinationResults
            .filter { it.pull.completedNormally }
            .mapTo(mutableSetOf()) { it.accountId }
        val mayPullSources = manualSelection == null || manualSelection.destinationAccountId in readyDestinationAccountIds
        if (manualSelection == null && readyDestinationAccountIds.isNotEmpty()) {
            queued += mutationGate.withLock {
                enabledRules
                    .filter { isRuleBacklogPlanningAllowed(it, readyDestinationAccountIds) }
                    .sumOf { rule ->
                        // 所有规则都要补扫：目标端上轮不可用时，来源活动可能已拉取但尚未创建投递任务。
                        planner.planRuleBacklog(
                            ruleId = rule.id,
                            allowedDestinationAccountIds = readyDestinationAccountIds,
                        )
                    }
            }
        }
        val sourceResults = if (mayPullSources) {
            syncAccounts(
                accounts = phases.remainingAccountIds.mapNotNull(accountById::get),
                historyDays = historyDays,
                force = force,
                notificationsEnabled = settings?.notificationsEnabled != false,
            )
        } else {
            emptyList()
        }
        val accountResults = destinationResults + sourceResults
        pulled += accountResults.sumOf { it.pull.pulled }
        val failedAccounts = accountResults.count { !it.pull.completedNormally }
        if (manualSelection != null && mayPullSources) {
            queued += mutationGate.withLock {
                planner.planManualSelection(
                    sourcePlatform = manualSelection.sourcePlatform,
                    destinationAccountId = manualSelection.destinationAccountId,
                    sportTypes = manualSelection.sportTypes,
                    fromEpochMs = requireNotNull(manualFromEpochMs),
                )
            }
        } else if (manualSelection == null && readyDestinationAccountIds.isNotEmpty()) {
            accountResults
                .groupBy { it.platform }
                .forEach { (sourcePlatform, results) ->
                    val canonicalIds = results.flatMap { it.pull.importedCanonicalIds }.distinct()
                    if (canonicalIds.isNotEmpty()) {
                        queued += mutationGate.withLock {
                            planner.planForActivities(
                                canonicalActivityIds = canonicalIds,
                                sourcePlatform = sourcePlatform,
                                allowedDestinationAccountIds = readyDestinationAccountIds,
                            )
                        }
                    }
                }
        }
        val deliveryResult = includeUnreadyDestinationRetry(
            result = processDeliveryQueue(readyDestinationAccountIds),
            destinationAccountIds = destinationAccountIds,
            readyDestinationAccountIds = readyDestinationAccountIds,
        )
        runCatching { storageJanitor?.run() }
        val checkedAccountIds = accountResults.mapTo(hashSetOf()) { it.accountId }
        val actionRequiredAccounts = db.platformAccountDao().getAll().count { account ->
            account.needsUserAction && account.id in checkedAccountIds
        }
        SyncRunSummary(
            pulledActivities = pulled,
            queuedDeliveries = queued,
            completedDeliveries = deliveryResult.completed,
            hasMoreDeliveries = deliveryResult.schedule.hasMore,
            nextDeliveryDelayMs = deliveryResult.schedule.delayMs,
            checkedAccounts = checkedAccountIds.size,
            enabledRules = if (manualSelection == null) enabledRules.size else 1,
            actionRequiredAccounts = actionRequiredAccounts,
            failedAccounts = failedAccounts,
        )
    }

    suspend fun runDeliveryBatch(): SyncRunSummary = syncMutex.withLock {
        val settings = db.appSettingsDao().get()
        val destinationAccountIds = db.deliveryJobDao().findActiveDestinationAccountIds().toSet()
        val accounts = db.platformAccountDao().getAll().filter { account ->
            account.id in destinationAccountIds && account.status != AccountStatus.DISABLED
        }
        val targetSinceOverrides = buildTargetSinceOverrides(destinationAccountIds)
        val accountResults = syncAccounts(
            accounts = accounts,
            historyDays = settings?.initialHistoryDays ?: 90,
            force = false,
            notificationsEnabled = settings?.notificationsEnabled != false,
            sinceEpochMsByAccount = targetSinceOverrides,
        )
        val readyDestinationAccountIds = accountResults
            .filter { it.pull.completedNormally }
            .mapTo(mutableSetOf()) { it.accountId }
        val deliveryResult = includeUnreadyDestinationRetry(
            result = processDeliveryQueue(readyDestinationAccountIds),
            destinationAccountIds = destinationAccountIds,
            readyDestinationAccountIds = readyDestinationAccountIds,
        )
        runCatching { storageJanitor?.run() }
        val refreshedAccounts = db.platformAccountDao().getAll().associateBy { it.id }
        SyncRunSummary(
            completedDeliveries = deliveryResult.completed,
            hasMoreDeliveries = deliveryResult.schedule.hasMore,
            nextDeliveryDelayMs = deliveryResult.schedule.delayMs,
            checkedAccounts = accountResults.size,
            actionRequiredAccounts = accountResults.count { refreshedAccounts[it.accountId]?.needsUserAction == true },
            failedAccounts = accountResults.count { !it.pull.completedNormally },
        )
    }

    suspend fun onImported(outcome: ImportOutcome, sourcePlatform: Platform): Int = mutationGate.withLock {
        val canonicalId = when (outcome) {
            is ImportOutcome.Success -> outcome.canonicalActivityId
            is ImportOutcome.DuplicateFile -> outcome.canonicalActivityId
            else -> return@withLock 0
        }
        planner.planForActivity(canonicalId, sourcePlatform)
    }

    suspend fun importRemoteDownloadedFile(
        accountId: Long,
        platform: Platform,
        remoteActivityId: String,
        fitFile: FitFile,
        preferredTitle: String? = null,
    ): ConnectorResult<Long> {
        val imported = importDownloadedFit(
            accountId = accountId,
            platform = platform,
            remoteActivityId = remoteActivityId,
            fitFile = fitFile,
            preferredTitle = preferredTitle,
            remoteUrl = if (platform == Platform.STRAVA) {
                "https://www.strava.com/activities/$remoteActivityId"
            } else {
                null
            },
        )
        if (imported is ConnectorResult.Success) {
            mutationGate.withLock { planner.planForActivity(imported.value, platform) }
        }
        return imported
    }

    suspend fun processDeliveryJobs(allowedDestinationAccountIds: Set<Long>? = null): Int {
        if (allowedDestinationAccountIds != null && allowedDestinationAccountIds.isEmpty()) return 0
        var completed = 0
        val now = System.currentTimeMillis()
        // 恢复上一轮被 Worker 中断（进程被杀/超时）而卡在 UPLOADING 的任务，避免永久卡死。
        // WAITING_USER 不在这里盲目重置：AUTH_EXPIRED/USER_ACTION 的任务只在登录成功后由
        // PlatformAccountRepository 恢复；RETRY_WAIT 由 findRunnable 按 nextRetryAt 退避拾取。
        db.deliveryJobDao().resetStaleUploading(now)
        val preparedJobs = mutationGate.withLock {
            db.withTransaction {
                val onelapInFlightByAccount = mutableMapOf<Long, Int>()
                db.platformAccountDao().getAll()
                    .filter { it.platform == Platform.ONELAP }
                    .forEach { account ->
                        // 目标列表已经匹配到真实活动的任务不再占批次名额，即使其状态尚未在本轮转为成功。
                        onelapInFlightByAccount[account.id] =
                            db.deliveryJobDao().countUnconfirmedUploads(account.id)
                    }
                val runnableJobs = if (allowedDestinationAccountIds == null) {
                    db.deliveryJobDao().findRunnable(now)
                } else {
                    db.deliveryJobDao().findRunnableForAccounts(now, allowedDestinationAccountIds.toList())
                }
                runnableJobs.mapNotNull { candidateJob ->
                val currentJob = db.deliveryJobDao().getById(candidateJob.id) ?: return@mapNotNull null
                val account = db.platformAccountDao().getById(currentJob.destinationAccountId)
                val activity = db.canonicalActivityDao().getById(currentJob.canonicalActivityId)
                val sourceVariant = db.activityVariantDao().getById(currentJob.sourceVariantId)
                val connector = account?.let { connectors.get(it.platform) }
                if (account == null || activity == null || connector == null ||
                    sourceVariant == null || sourceVariant.canonicalActivityId != currentJob.canonicalActivityId
                ) {
                    db.deliveryJobDao().update(
                        currentJob.copy(
                            status = DeliveryJobStatus.DISABLED,
                            errorMessage = "目标账号、本地活动或任务指定的 FIT 副本已不存在",
                            updatedAtEpochMs = now,
                        ),
                    )
                    return@mapNotNull null
                }
                val existingCopy = db.platformCopyDao().findByCanonicalAndAccount(
                    currentJob.canonicalActivityId,
                    currentJob.destinationAccountId,
                )
                if (existingCopy != null) {
                    val submittedByCurrentJob = !currentJob.remoteUploadId.isNullOrBlank()
                    if (submittedByCurrentJob && !existingCopy.createdBySync) {
                        upsertPlatformCopy(
                            canonicalId = currentJob.canonicalActivityId,
                            accountId = currentJob.destinationAccountId,
                            remoteActivityId = existingCopy.remoteActivityId,
                            remoteUrl = existingCopy.remoteUrl,
                            fileSha256 = existingCopy.fileSha256,
                            sourceVariantId = existingCopy.sourceVariantId ?: currentJob.sourceVariantId,
                            createdBySync = true,
                        )
                    }
                    db.deliveryJobDao().update(
                        currentJob.copy(
                            status = if (submittedByCurrentJob) {
                                DeliveryJobStatus.SUCCEEDED
                            } else {
                                DeliveryJobStatus.DUPLICATE_REMOTE
                            },
                            nextRetryAtEpochMs = null,
                            remoteUploadId = null,
                            remoteActivityId = existingCopy.remoteActivityId,
                            userActionUrl = null,
                            errorCode = null,
                            errorMessage = null,
                            updatedAtEpochMs = now,
                        ),
                    )
                    completed++
                    return@mapNotNull null
                }
                val currentMode = connector.detectCapabilities().uploadFitMode
                if (currentMode != ConnectorExecution.BACKGROUND) {
                    db.deliveryJobDao().update(
                        currentJob.copy(
                            executionMode = currentMode,
                            updatedAtEpochMs = now,
                        ),
                    )
                    waitForUser(
                        currentJob.id,
                        account.platform,
                        PlatformWebConfigs.get(account.platform).uploadUrl,
                        "${account.platform.displayName} 不支持后台上传，请在网页中完成 FIT 导入",
                        "FOREGROUND_UPLOAD_REQUIRED",
                    )
                    return@mapNotNull null
                }
                val polling = !currentJob.remoteUploadId.isNullOrBlank()
                if (account.platform == Platform.ONELAP && !polling &&
                    !tryReserveOnelapUploadSlot(onelapInFlightByAccount, account.id)
                ) {
                    // 迈金提交接口不返回真实活动 ID。当前批次尚未在目标列表确认完时，
                    // 每个账号最多保留 10 条待确认上传，满批后延后到下一次目标列表确认。
                    db.deliveryJobDao().update(
                        currentJob.copy(
                            status = DeliveryJobStatus.PENDING,
                            nextRetryAtEpochMs = now + ONELAP_BATCH_CONFIRM_DELAY_MS,
                            errorCode = null,
                            errorMessage = null,
                            updatedAtEpochMs = now,
                        ),
                    )
                    return@mapNotNull null
                }
                db.deliveryJobDao().update(
                    currentJob.copy(
                        status = DeliveryJobStatus.UPLOADING,
                        executionMode = currentMode,
                        updatedAtEpochMs = now,
                    ),
                )
                PreparedDelivery(
                    job = currentJob.copy(executionMode = currentMode),
                    accountId = account.id,
                    activity = activity,
                    sourceVariant = sourceVariant,
                    connector = connector,
                    polling = polling,
                )
                }
            }
        }

        val uploadSemaphores = preparedJobs.map { it.accountId }.distinct()
            .associateWith { Semaphore(MAX_UPLOADS_PER_ACCOUNT) }
        val uploadResults = coroutineScope {
            preparedJobs.map { prepared ->
                async {
                    requireNotNull(uploadSemaphores[prepared.accountId]).withPermit {
                        globalNetworkSemaphore.withPermit {
            // 任何 connector 异常（DB/IO/解析）都转可重试失败，避免 job 永久卡在 UPLOADING。
                            Trace.beginSection("FitSync:upload")
            val result = runCatching {
                if (prepared.polling) {
                    prepared.connector.checkUpload(requireNotNull(prepared.job.remoteUploadId))
                } else {
                    prepared.connector.uploadFit(prepared.activity, prepared.sourceVariant)
                }
            }
                .getOrElse { ConnectorResult.RetryableFailure(null, it.message ?: "上传过程发生异常") }
                .let { uploadResult ->
                    if (uploadResult is ConnectorResult.Success && uploadResult.value.remoteActivityId.isBlank()) {
                        ConnectorResult.RetryableFailure("REMOTE_ID_MISSING", "平台未返回活动 ID，稍后会自动重试")
                    } else {
                        uploadResult
                    }
                }
                            Trace.endSection()
                            prepared to result
                        }
                    }
                }
            }.awaitAll()
        }
        uploadResults.forEach { (prepared, result) ->
            mutationGate.withLock {
                val currentJob = db.deliveryJobDao().getById(prepared.job.id)
                val currentActivity = db.canonicalActivityDao().getById(prepared.job.canonicalActivityId)
                if (currentJob == null || currentActivity == null || currentJob.status != DeliveryJobStatus.UPLOADING) {
                    return@withLock
                }
                val job = currentJob
                val accountId = prepared.accountId
                when (result) {
                    is ConnectorResult.Success -> {
                        saveRemoteCopy(job.canonicalActivityId, accountId, job.sourceVariantId, result.value)
                        db.deliveryJobDao().update(
                            job.copy(
                                status = DeliveryJobStatus.SUCCEEDED,
                                attemptCount = job.attemptCount + 1,
                                nextRetryAtEpochMs = null,
                                remoteActivityId = result.value.remoteActivityId,
                                errorCode = null,
                                errorMessage = null,
                                updatedAtEpochMs = System.currentTimeMillis(),
                            ),
                        )
                        completed++
                    }
                    is ConnectorResult.Duplicate -> {
                        result.value?.let { saveRemoteCopy(job.canonicalActivityId, accountId, job.sourceVariantId, it) }
                        db.deliveryJobDao().update(
                            job.copy(
                                status = DeliveryJobStatus.DUPLICATE_REMOTE,
                                attemptCount = job.attemptCount + 1,
                                nextRetryAtEpochMs = null,
                                remoteActivityId = result.value?.remoteActivityId,
                                updatedAtEpochMs = System.currentTimeMillis(),
                            ),
                        )
                        completed++
                    }
                    is ConnectorResult.Processing -> {
                        db.deliveryJobDao().update(
                            job.copy(
                                status = DeliveryJobStatus.WAITING_REMOTE_PROCESS,
                                attemptCount = job.attemptCount + 1,
                                remoteUploadId = result.remoteUploadId,
                                nextRetryAtEpochMs = System.currentTimeMillis() +
                                    result.pollAfterMs.coerceAtLeast(1_000L),
                                errorCode = null,
                                errorMessage = null,
                                updatedAtEpochMs = System.currentTimeMillis(),
                            ),
                        )
                    }
                    is ConnectorResult.RequiresLogin -> {
                        markAccountNeedsAction(accountId, true)
                        val platform = db.platformAccountDao().getById(accountId)?.platform ?: return@withLock
                        waitForUser(job.id, platform, result.url, result.message, "AUTH_EXPIRED")
                    }
                    is ConnectorResult.RequiresUserAction -> {
                        markAccountNeedsAction(accountId, false)
                        val platform = db.platformAccountDao().getById(accountId)?.platform ?: return@withLock
                        waitForUser(job.id, platform, result.url, result.message, "USER_ACTION_REQUIRED")
                    }
                    is ConnectorResult.RetryableFailure -> {
                        val attempt = job.attemptCount + 1
                        val delay = min(6 * 60 * 60_000L, 15 * 60_000L * 2.0.pow((attempt - 1).coerceAtMost(5)).toLong())
                        db.deliveryJobDao().update(
                            job.copy(
                                status = if (job.remoteUploadId != null) {
                                    DeliveryJobStatus.WAITING_REMOTE_PROCESS
                                } else {
                                    DeliveryJobStatus.RETRY_WAIT
                                },
                                attemptCount = attempt,
                                nextRetryAtEpochMs = System.currentTimeMillis() + delay,
                                errorCode = result.code,
                                errorMessage = result.message,
                                updatedAtEpochMs = System.currentTimeMillis(),
                            ),
                        )
                    }
                    is ConnectorResult.PermanentFailure -> db.deliveryJobDao().update(
                        job.copy(
                            status = DeliveryJobStatus.FAILED,
                            attemptCount = job.attemptCount + 1,
                            errorCode = result.code,
                            errorMessage = result.message,
                            updatedAtEpochMs = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }
        return completed
    }

    suspend fun confirmForegroundJob(
        jobId: Long,
        duplicate: Boolean = false,
        remoteActivityId: String? = null,
        remoteUrl: String? = null,
    ): Boolean = mutationGate.withLock {
        val job = db.deliveryJobDao().getById(jobId) ?: return@withLock false
        if (job.status != DeliveryJobStatus.WAITING_USER || job.executionMode == ConnectorExecution.BACKGROUND) {
            return@withLock false
        }
        if (db.canonicalActivityDao().getById(job.canonicalActivityId) == null ||
            db.activityVariantDao().getById(job.sourceVariantId) == null
        ) return@withLock false
        val cleanRemoteId = remoteActivityId?.trim()?.takeIf(String::isNotEmpty)
        if (cleanRemoteId != null) {
            saveRemoteCopy(
                canonicalId = job.canonicalActivityId,
                accountId = job.destinationAccountId,
                sourceVariantId = job.sourceVariantId,
                copy = com.fitsync.app.connectors.RemoteCopy(
                    remoteActivityId = cleanRemoteId,
                    remoteUrl = remoteUrl,
                    duplicate = duplicate,
                ),
            )
        }
        db.deliveryJobDao().update(
            job.copy(
                status = if (duplicate) DeliveryJobStatus.DUPLICATE_REMOTE else DeliveryJobStatus.SUCCEEDED,
                attemptCount = job.attemptCount + 1,
                remoteActivityId = cleanRemoteId,
                errorCode = null,
                errorMessage = null,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
        notifications.cancel(jobId)
        true
    }

    suspend fun failForegroundJob(jobId: Long, code: String, message: String): Boolean = mutationGate.withLock {
        val job = db.deliveryJobDao().getById(jobId) ?: return@withLock false
        if (job.status != DeliveryJobStatus.WAITING_USER || job.executionMode == ConnectorExecution.BACKGROUND) {
            return@withLock false
        }
        db.deliveryJobDao().update(
            job.copy(
                status = DeliveryJobStatus.FAILED,
                attemptCount = job.attemptCount + 1,
                errorCode = code,
                errorMessage = message,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
        notifications.cancel(jobId)
        true
    }

    suspend fun retryJob(jobId: Long) = mutationGate.withLock {
        val job = db.deliveryJobDao().getById(jobId) ?: return@withLock
        if (db.canonicalActivityDao().getById(job.canonicalActivityId) == null) return@withLock
        db.deliveryJobDao().update(
            job.copy(
                status = DeliveryJobStatus.PENDING,
                nextRetryAtEpochMs = null,
                remoteUploadId = null,
                remoteActivityId = null,
                errorCode = null,
                errorMessage = null,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun retryJobs(ids: List<Long>) = mutationGate.withLock {
        val now = System.currentTimeMillis()
        db.deliveryJobDao().retryByIds(ids, now)
    }

    suspend fun deleteJobs(ids: List<Long>) = mutationGate.withLock {
        db.deliveryJobDao().deleteByIds(ids)
    }

    suspend fun enqueueManual(activityId: Long, destinationAccountId: Long): Long = mutationGate.withLock {
        if (db.canonicalActivityDao().getById(activityId) == null) return@withLock -1L
        planner.enqueueManual(activityId, destinationAccountId)
    }

    suspend fun setRule(
        source: Platform,
        destinationAccountId: Long,
        enabled: Boolean,
        includeHistory: Boolean,
    ) = mutationGate.withLock {
        planner.setRule(source, destinationAccountId, enabled, includeHistory)
    }

    private data class PreparedDelivery(
        val job: DeliveryJobEntity,
        val accountId: Long,
        val activity: CanonicalActivityEntity,
        val sourceVariant: ActivityVariantEntity,
        val connector: PlatformConnector,
        val polling: Boolean,
    )

    /**
     * DAO 每批仍限制 20 条以控制并发与内存，但同一个前台 Worker 会连续取下一批；
     * 到达时间/批次数预算后才交给 WorkManager 持久化续跑，不再要求用户重复点击。
     */
    private suspend fun processDeliveryQueue(
        allowedDestinationAccountIds: Set<Long>? = null,
    ): DeliveryQueueResult {
        if (allowedDestinationAccountIds != null && allowedDestinationAccountIds.isEmpty()) {
            return DeliveryQueueResult(0, DeliverySchedule(false, null))
        }
        val startedAt = SystemClock.elapsedRealtime()
        var completed = 0
        var completedBatches = 0
        var schedule: DeliverySchedule
        do {
            completed += processDeliveryJobs(allowedDestinationAccountIds)
            completedBatches++
            schedule = deliverySchedule(allowedDestinationAccountIds)
        } while (
            shouldContinueDeliveryDrain(
                hasMore = schedule.hasMore,
                nextDelayMs = schedule.delayMs,
                completedBatches = completedBatches,
                elapsedMs = SystemClock.elapsedRealtime() - startedAt,
            )
        )
        return DeliveryQueueResult(completed, schedule)
    }

    private suspend fun deliverySchedule(
        allowedDestinationAccountIds: Set<Long>? = null,
    ): DeliverySchedule {
        if (allowedDestinationAccountIds != null && allowedDestinationAccountIds.isEmpty()) {
            return DeliverySchedule(false, null)
        }
        val now = System.currentTimeMillis()
        val runnableCount = if (allowedDestinationAccountIds == null) {
            db.deliveryJobDao().countRunnable(now)
        } else {
            db.deliveryJobDao().countRunnableForAccounts(now, allowedDestinationAccountIds.toList())
        }
        if (runnableCount > 0) return DeliverySchedule(true, 0)
        val next = if (allowedDestinationAccountIds == null) {
            db.deliveryJobDao().findNextScheduledAt()
        } else {
            db.deliveryJobDao().findNextScheduledAtForAccounts(allowedDestinationAccountIds.toList())
        } ?: return DeliverySchedule(false, null)
        return DeliverySchedule(true, (next - now).coerceAtLeast(0))
    }

    private suspend fun includeUnreadyDestinationRetry(
        result: DeliveryQueueResult,
        destinationAccountIds: Set<Long>,
        readyDestinationAccountIds: Set<Long>,
    ): DeliveryQueueResult {
        val unreadyAccountIds = destinationAccountIds - readyDestinationAccountIds
        if (unreadyAccountIds.isEmpty()) return result
        val activeDestinationIds = db.deliveryJobDao().findActiveDestinationAccountIds().toSet()
        val retryableAccountIds = db.platformAccountDao().getAll()
            .filterTo(hashSetOf()) { account ->
                account.id in unreadyAccountIds &&
                    !account.needsUserAction &&
                    (account.status == AccountStatus.ACTIVE || account.status == AccountStatus.EXPERIMENTAL)
            }
            .mapTo(hashSetOf()) { it.id }
        val hasUnreadyActiveJobs = retryableAccountIds.any { it in activeDestinationIds }
        return result.copy(
            schedule = withUnreadyDestinationRetry(result.schedule, hasUnreadyActiveJobs),
        )
    }

    /**
     * 目标端必须覆盖所有待投递活动；开启“包含历史”时还要覆盖本地最早活动。
     * 返回值会在 [pullAccount] 中与默认增量窗口取更早者，不会缩短已有窗口。
     */
    private suspend fun buildTargetSinceOverrides(
        destinationAccountIds: Set<Long>,
        includeAllHistoryAccountIds: Set<Long> = emptySet(),
        includeHistoryFromEpochMs: Long? = null,
        manualDestinationAccountId: Long? = null,
        manualFromEpochMs: Long? = null,
    ): Map<Long, Long> {
        if (destinationAccountIds.isEmpty()) return emptyMap()
        val historyDestinations = destinationAccountIds intersect includeAllHistoryAccountIds
        val earliestLocalStart = if (historyDestinations.isEmpty()) {
            null
        } else {
            db.canonicalActivityDao().findEarliestStartTime()
        }
        return buildMap {
            destinationAccountIds.forEach { accountId ->
                var earliest = db.deliveryJobDao().findEarliestActiveActivityStart(accountId)
                if (accountId in historyDestinations) {
                    earliest = earlierEpoch(earliest, earliestLocalStart)
                    earliest = earlierEpoch(earliest, includeHistoryFromEpochMs)
                }
                if (accountId == manualDestinationAccountId) {
                    earliest = earlierEpoch(earliest, manualFromEpochMs)
                }
                earliest?.let { put(accountId, targetPrefetchSince(it)) }
            }
        }
    }

    private suspend fun syncAccounts(
        accounts: List<PlatformAccountEntity>,
        historyDays: Int,
        force: Boolean,
        notificationsEnabled: Boolean,
        sinceEpochMsByAccount: Map<Long, Long> = emptyMap(),
    ): List<AccountPullResult> = coroutineScope {
        val accountSemaphore = Semaphore(MAX_CONCURRENT_ACCOUNTS)
        accounts.map { account ->
            async {
                val pull = accountSemaphore.withPermit {
                    syncAccount(
                        account = account,
                        historyDays = historyDays,
                        force = force,
                        notificationsEnabled = notificationsEnabled,
                        sinceEpochMsOverride = sinceEpochMsByAccount[account.id],
                    )
                }
                AccountPullResult(account.id, account.platform, pull)
            }
        }.awaitAll()
    }

    private suspend fun syncAccount(
        account: PlatformAccountEntity,
        historyDays: Int,
        force: Boolean,
        notificationsEnabled: Boolean,
        sinceEpochMsOverride: Long? = null,
    ): PullAccountResult {
        val connector = connectors.get(account.platform) ?: return PullAccountResult(completedNormally = false)
        val capabilities = connector.detectCapabilities()
        val backgroundComparisonAvailable = canCompareTargetInBackground(capabilities)
        if (account.capabilitySnapshot != capabilities.serialize()) {
            db.platformAccountDao().update(
                account.copy(
                    capabilitySnapshot = capabilities.serialize(),
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
        }
        if (capabilities.listActivitiesMode != ConnectorExecution.BACKGROUND) {
            if (force && capabilities.listActivitiesMode != ConnectorExecution.UNSUPPORTED) {
                markAccountNeedsAction(account.id, false)
                if (notificationsEnabled) {
                    notifications.notifyAccountAction(
                        account.id,
                        account.platform,
                        PlatformWebConfigs.get(account.platform).loginUrl,
                        "请在网页中导出 FIT 后分享到本应用",
                    )
                }
            }
            return PullAccountResult(completedNormally = backgroundComparisonAvailable)
        }
        if (capabilities.downloadFitMode != ConnectorExecution.BACKGROUND) {
            markAccountNeedsAction(account.id, false)
            if (force && notificationsEnabled) {
                notifications.notifyAccountAction(
                    account.id,
                    account.platform,
                    PlatformWebConfigs.get(account.platform).loginUrl,
                    "请登录 ${account.platform.displayName} 网页以启用原文件拉取",
                )
            }
            return PullAccountResult(completedNormally = backgroundComparisonAvailable)
        }
        Trace.beginSection("FitSync:pull_${account.platform.name}")
        return try {
            pullAccount(account.id, historyDays, force, sinceEpochMsOverride)
        } finally {
            Trace.endSection()
        }
    }

    private suspend fun pullAccount(
        accountId: Long,
        historyDays: Int,
        forceFullHistory: Boolean,
        sinceEpochMsOverride: Long? = null,
    ): PullAccountResult {
        var account = db.platformAccountDao().getById(accountId) ?: return PullAccountResult()
        val connector = connectors.get(account.platform) ?: return PullAccountResult()
        val now = System.currentTimeMillis()
        val previousSync = account.lastSyncAtEpochMs
        val defaultSince = calculatePullSince(now, previousSync, historyDays, forceFullHistory)
        val since = sinceEpochMsOverride?.coerceAtLeast(0L)?.let { minOf(defaultSince, it) } ?: defaultSince
        // A manual history rescan must restart at page 1; a saved cursor belongs
        // to the previous incremental window and would otherwise skip records. 目标窗口扩大时同理。
        var cursor = if (forceFullHistory || sinceEpochMsOverride != null) {
            null
        } else {
            account.syncCursor?.let(::SyncCursor)
        }
        var pulled = 0
        var pages = 0
        var completedNormally = true
        var reachedWindowEnd = false
        val importedCanonicalIds = linkedSetOf<Long>()
        val accountDownloadSemaphore = Semaphore(MAX_DOWNLOADS_PER_ACCOUNT)
        while (pages < MAX_PULL_PAGES) {
            pages++
            when (val pageResult = globalNetworkSemaphore.withPermit { connector.listActivities(since, cursor) }) {
                is ConnectorResult.Success -> {
                    var abortAccount = false
                    reconcilePendingUploadCopies(account, pageResult.value.items)
                    val remoteIds = pageResult.value.items.map { it.remoteActivityId }
                    val existingRemoteIds = if (remoteIds.isEmpty()) emptySet() else {
                        (
                            db.platformCopyDao().findExistingRemoteIds(account.id, remoteIds) +
                                db.remoteActivitySkipDao().findExistingRemoteIds(account.id, remoteIds)
                            ).toSet()
                    }
                    val pending = pageResult.value.items.filter { it.remoteActivityId !in existingRemoteIds }
                    val importedResults = coroutineScope {
                        pending.map { remote ->
                            async {
                                accountDownloadSemaphore.withPermit {
                                    globalNetworkSemaphore.withPermit {
                                        Trace.beginSection("FitSync:download_import")
                                        try {
                                            remote to downloadAndImport(account.id, account.platform, connector, remote)
                                        } finally {
                                            Trace.endSection()
                                        }
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                    var actionRequest: Triple<Boolean, String, String>? = null
                    for ((remote, imported) in importedResults) {
                        when (imported) {
                            is ConnectorResult.Success -> {
                                pulled++
                                importedCanonicalIds += imported.value
                            }
                            is ConnectorResult.RequiresLogin -> {
                                markAccountNeedsAction(account.id, true)
                                completedNormally = false
                                abortAccount = true
                                if (actionRequest == null) actionRequest = Triple(true, imported.url, imported.message)
                            }
                            is ConnectorResult.RequiresUserAction -> {
                                markAccountNeedsAction(account.id, false)
                                completedNormally = false
                                abortAccount = true
                                if (actionRequest == null) actionRequest = Triple(false, imported.url, imported.message)
                            }
                            is ConnectorResult.PermanentFailure -> {
                                if (imported.code in PERMANENT_REMOTE_SKIP_CODES) {
                                    val skipNow = System.currentTimeMillis()
                                    db.remoteActivitySkipDao().upsert(
                                        RemoteActivitySkipEntity(
                                            platformAccountId = account.id,
                                            remoteActivityId = remote.remoteActivityId,
                                            reasonCode = requireNotNull(imported.code),
                                            errorMessage = imported.message,
                                            createdAtEpochMs = skipNow,
                                            updatedAtEpochMs = skipNow,
                                        ),
                                    )
                                } else {
                                    completedNormally = false
                                    abortAccount = true
                                }
                            }
                            else -> {
                                // 未持久化为 skip 的失败必须停在当前页；已成功导入的条目下次会由 PlatformCopy 跳过。
                                // 若继续推进 cursor，这条远端活动会被永久漏掉。
                                completedNormally = false
                                abortAccount = true
                            }
                        }
                        if (shouldAbortPullAfterItemResult(imported)) abortAccount = true
                    }
                    actionRequest?.let { (_, url, message) ->
                        notifyAccountActionIfEnabled(account.id, account.platform, url, message)
                    }
                    if (abortAccount) break
                    cursor = pageResult.value.nextCursor
                    account = account.copy(
                        syncCursor = cursor?.raw,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    )
                    db.platformAccountDao().update(account)
                    if (!pageResult.value.hasMore || cursor == null) {
                        reachedWindowEnd = true
                        break
                    }
                }
                is ConnectorResult.RequiresLogin -> {
                    markAccountNeedsAction(account.id, true)
                    notifyAccountActionIfEnabled(
                        account.id,
                        account.platform,
                        pageResult.url,
                        pageResult.message,
                    )
                    completedNormally = false
                    break
                }
                is ConnectorResult.RequiresUserAction -> {
                    markAccountNeedsAction(account.id, false)
                    notifyAccountActionIfEnabled(
                        account.id,
                        account.platform,
                        pageResult.url,
                        pageResult.message,
                    )
                    completedNormally = false
                    break
                }
                else -> {
                    completedNormally = false
                    break
                }
            }
        }
        if (pages >= MAX_PULL_PAGES && !reachedWindowEnd) completedNormally = false
        account = db.platformAccountDao().getById(account.id) ?: account
        if (completedNormally) {
            db.platformAccountDao().update(
                account.copy(
                    syncCursor = null,
                    lastSyncAtEpochMs = System.currentTimeMillis(),
                    needsUserAction = false,
                    status = AccountStatus.ACTIVE,
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
        }
        return PullAccountResult(
            pulled = pulled,
            completedNormally = completedNormally,
            importedCanonicalIds = importedCanonicalIds.toList(),
        )
    }

    /**
     * 上传已受理但目标端原文件尚未可下载时，用真实列表项高置信确认任务。
     * 只接受唯一的一对一匹配，并写入列表返回的真实 remoteActivityId；绝不生成合成 ID。
     */
    private suspend fun reconcilePendingUploadCopies(
        account: PlatformAccountEntity,
        remotes: List<RemoteActivitySummary>,
    ) {
        if (remotes.isEmpty()) return
        val candidates = mutableListOf<PendingUploadMatchCandidate>()
        for (job in db.deliveryJobDao().findByDestinationAccount(account.id)) {
            if (job.status != DeliveryJobStatus.WAITING_REMOTE_PROCESS) continue
            val remoteUploadId = job.remoteUploadId ?: continue
            val activity = db.canonicalActivityDao().getById(job.canonicalActivityId) ?: continue
            val variant = db.activityVariantDao().getById(job.sourceVariantId) ?: continue
            val confirmationMarker = parseAsyncUploadConfirmationToken(remoteUploadId)
                ?.takeIf { it.platform == account.platform }
                ?.provisionalRemoteId
            candidates += PendingUploadMatchCandidate(
                jobId = job.id,
                canonicalActivityId = job.canonicalActivityId,
                sourceVariantId = job.sourceVariantId,
                sourceFileSha256 = variant.fileSha256,
                startTimeEpochMs = activity.startTimeEpochMs,
                distanceMeters = activity.distanceMeters,
                title = activity.title,
                confirmationMarker = confirmationMarker,
            )
        }
        val matches = findHighConfidencePendingUploadMatches(remotes, candidates)
        if (matches.isEmpty()) return
        mutationGate.withLock {
            matches.forEach { match ->
                upsertPlatformCopy(
                    canonicalId = match.candidate.canonicalActivityId,
                    accountId = account.id,
                    remoteActivityId = match.remote.remoteActivityId,
                    remoteUrl = match.remote.metadata["remote_url"],
                    fileSha256 = match.candidate.sourceFileSha256,
                    sourceVariantId = match.candidate.sourceVariantId,
                    createdBySync = true,
                )
            }
        }
    }

    private suspend fun downloadAndImport(
        accountId: Long,
        platform: Platform,
        connector: com.fitsync.app.connectors.PlatformConnector,
        remote: RemoteActivitySummary,
    ): ConnectorResult<Long> {
        return when (val downloaded = connector.downloadFit(remote)) {
            is ConnectorResult.Success -> importDownloadedFit(
                accountId = accountId,
                platform = platform,
                remoteActivityId = remote.remoteActivityId,
                fitFile = downloaded.value,
                preferredTitle = remote.name,
                remoteUrl = remote.metadata["remote_url"],
            )
            is ConnectorResult.Duplicate -> ConnectorResult.PermanentFailure(null, "平台返回了重复下载结果")
            is ConnectorResult.Processing -> ConnectorResult.PermanentFailure(null, "平台返回了无效的下载状态")
            is ConnectorResult.RequiresLogin -> downloaded
            is ConnectorResult.RequiresUserAction -> downloaded
            is ConnectorResult.RetryableFailure -> downloaded
            is ConnectorResult.PermanentFailure -> downloaded
        }
    }

    private suspend fun importDownloadedFit(
        accountId: Long,
        platform: Platform,
        remoteActivityId: String,
        fitFile: FitFile,
        preferredTitle: String?,
        remoteUrl: String? = null,
    ): ConnectorResult<Long> {
        val outcome = importService.importStoredFile(
            prepared = ImportPreparedFile(
                file = fitFile.file,
                sha256 = fitFile.sha256,
                displayName = fitFile.displayName,
            ),
            sourcePlatform = platform,
            collectorType = CollectorType.CLOUD,
            remoteActivityId = remoteActivityId,
            preferredTitle = preferredTitle,
        )
        val ids = when (outcome) {
            is ImportOutcome.Success -> outcome.canonicalActivityId to outcome.variantId
            is ImportOutcome.DuplicateFile -> outcome.canonicalActivityId to outcome.variantId
            is ImportOutcome.Invalid -> return ConnectorResult.PermanentFailure(
                "UNSUPPORTED_ORIGINAL_FORMAT",
                outcome.message ?: "下载的 FIT 无效",
            )
            is ImportOutcome.Failed -> return ConnectorResult.RetryableFailure(null, outcome.message ?: "FIT 导入失败")
        }
        return mutationGate.withLock {
            if (db.canonicalActivityDao().getById(ids.first) == null) {
                return@withLock ConnectorResult.PermanentFailure(null, "本地活动已删除")
            }
            upsertPlatformCopy(
                canonicalId = ids.first,
                accountId = accountId,
                remoteActivityId = remoteActivityId,
                remoteUrl = remoteUrl,
                fileSha256 = fitFile.sha256,
                sourceVariantId = ids.second,
                createdBySync = false,
            )
            ConnectorResult.Success(ids.first)
        }
    }

    private suspend fun saveRemoteCopy(
        canonicalId: Long,
        accountId: Long,
        sourceVariantId: Long,
        copy: com.fitsync.app.connectors.RemoteCopy,
    ) {
        if (copy.remoteActivityId.isBlank()) return
        val sha = db.activityVariantDao().getById(sourceVariantId)?.fileSha256
        upsertPlatformCopy(
            canonicalId = canonicalId,
            accountId = accountId,
            remoteActivityId = copy.remoteActivityId,
            remoteUrl = copy.remoteUrl,
            fileSha256 = sha,
            sourceVariantId = sourceVariantId,
            createdBySync = true,
        )
    }

    /** 写入或对齐 PlatformCopy；只保存平台列表或上传响应返回的真实活动 ID。 */
    internal suspend fun upsertPlatformCopy(
        canonicalId: Long,
        accountId: Long,
        remoteActivityId: String,
        remoteUrl: String? = null,
        fileSha256: String? = null,
        sourceVariantId: Long? = null,
        createdBySync: Boolean = false,
    ) {
        require(remoteActivityId.isNotBlank()) { "remoteActivityId 不能为空" }
        val dao = db.platformCopyDao()
        val plan = planPlatformCopyUpsert(
            byRemote = dao.findByRemote(accountId, remoteActivityId),
            byCanonicalAndAccount = dao.findByCanonicalAndAccount(canonicalId, accountId),
            bySha = fileSha256?.let { dao.findBySha256(accountId, it) },
            canonicalId = canonicalId,
            accountId = accountId,
            remoteActivityId = remoteActivityId,
            remoteUrl = remoteUrl,
            fileSha256 = fileSha256,
            sourceVariantId = sourceVariantId,
            createdBySync = createdBySync,
        )
        when (plan) {
            is PlatformCopyUpsertPlan.Update -> dao.update(plan.entity)
            is PlatformCopyUpsertPlan.Insert -> dao.insert(plan.entity)
            PlatformCopyUpsertPlan.NoOp -> Unit
        }
    }

    private suspend fun waitForUser(
        jobId: Long,
        platform: Platform,
        url: String,
        message: String,
        errorCode: String,
    ) {
        val job = db.deliveryJobDao().getById(jobId) ?: return
        db.deliveryJobDao().update(
            job.copy(
                status = DeliveryJobStatus.WAITING_USER,
                userActionUrl = url,
                errorCode = errorCode,
                errorMessage = message,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
        val settings = db.appSettingsDao().get()
        if (settings?.notificationsEnabled != false) notifications.notifyUserAction(jobId, platform, url, message)
    }

    private suspend fun markAccountNeedsAction(accountId: Long, expired: Boolean) {
        val account = db.platformAccountDao().getById(accountId) ?: return
        val capabilitySnapshot = when {
            expired && account.platform == Platform.STRAVA ->
                ConnectorCapabilities(shareImportSupported = true, experimental = true).serialize()
            account.platform == Platform.STRAVA -> {
                val current = ConnectorCapabilities.deserialize(account.capabilitySnapshot)
                    ?: PlatformCapabilityRegistry.capabilities(Platform.STRAVA)
                current.copy(downloadFitMode = ConnectorExecution.MANUAL).serialize()
            }
            else -> account.capabilitySnapshot
        }
        db.platformAccountDao().update(
            account.copy(
                status = when {
                    expired -> AccountStatus.AUTH_EXPIRED
                    account.platform == Platform.STRAVA -> AccountStatus.ACTIVE
                    else -> AccountStatus.NEEDS_USER_ACTION
                },
                needsUserAction = true,
                capabilitySnapshot = capabilitySnapshot,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun notifyAccountActionIfEnabled(
        accountId: Long,
        platform: Platform,
        url: String,
        message: String,
    ) {
        if (db.appSettingsDao().get()?.notificationsEnabled != false) {
            notifications.notifyAccountAction(accountId, platform, url, message)
        }
    }

}

internal fun calculatePullSince(
    nowEpochMs: Long,
    previousSyncEpochMs: Long?,
    historyDays: Int,
    forceFullHistory: Boolean,
): Long = if (previousSyncEpochMs == null || forceFullHistory) {
    nowEpochMs - historyDays.coerceIn(1, 3650).toLong() * DAY_MS
} else {
    previousSyncEpochMs - 48L * HOUR_MS
}

private const val HOUR_MS = 60L * 60 * 1000
private const val DAY_MS = 24L * HOUR_MS
internal const val MAX_CONCURRENT_ACCOUNTS = 2
internal const val MAX_DOWNLOADS_PER_ACCOUNT = 2
internal const val MAX_UPLOADS_PER_ACCOUNT = 2
internal const val MAX_ONELAP_UPLOADS_PER_BATCH = 10
internal const val ONELAP_BATCH_CONFIRM_DELAY_MS = 30_000L
internal const val MAX_GLOBAL_NETWORK_TRANSFERS = 3
internal const val MAX_PULL_PAGES = 500
internal const val MAX_DELIVERY_BATCHES_PER_WORKER = 50
internal const val DELIVERY_DRAIN_BUDGET_MS = 8L * 60 * 1000
internal const val TARGET_PREFETCH_TOLERANCE_MS = 10L * 60 * 1000
internal const val UNREADY_DESTINATION_RETRY_MS = 15L * 60 * 1000

data class SyncRunSummary(
    val pulledActivities: Int = 0,
    val queuedDeliveries: Int = 0,
    val completedDeliveries: Int = 0,
    val hasMoreDeliveries: Boolean = false,
    val nextDeliveryDelayMs: Long? = null,
    val checkedAccounts: Int = 0,
    val enabledRules: Int = 0,
    val actionRequiredAccounts: Int = 0,
    val failedAccounts: Int = 0,
)

internal data class DeliverySchedule(val hasMore: Boolean, val delayMs: Long?)

private data class DeliveryQueueResult(
    val completed: Int,
    val schedule: DeliverySchedule,
)

private data class AccountPullResult(
    val accountId: Long,
    val platform: Platform,
    val pull: PullAccountResult,
)

private data class PullAccountResult(
    val pulled: Int = 0,
    val completedNormally: Boolean = false,
    val importedCanonicalIds: List<Long> = emptyList(),
)

internal data class SyncAccountPhases(
    val destinationAccountIds: List<Long>,
    val remainingAccountIds: List<Long>,
)

/** 目标账号必须处于第一阶段；第二阶段开始前，第一阶段已全部完成预拉取。 */
internal fun buildSyncAccountPhases(
    accountIds: List<Long>,
    destinationAccountIds: Set<Long>,
): SyncAccountPhases {
    val destinations = accountIds.filter { it in destinationAccountIds }
    val destinationSet = destinations.toHashSet()
    return SyncAccountPhases(
        destinationAccountIds = destinations,
        remainingAccountIds = accountIds.filterNot { it in destinationSet },
    )
}

internal fun shouldContinueDeliveryDrain(
    hasMore: Boolean,
    nextDelayMs: Long?,
    completedBatches: Int,
    elapsedMs: Long,
): Boolean = hasMore &&
    (nextDelayMs ?: 0L) <= 0L &&
    completedBatches < MAX_DELIVERY_BATCHES_PER_WORKER &&
    elapsedMs < DELIVERY_DRAIN_BUDGET_MS

internal fun withUnreadyDestinationRetry(
    schedule: DeliverySchedule,
    hasUnreadyActiveJobs: Boolean,
): DeliverySchedule {
    if (!hasUnreadyActiveJobs) return schedule
    val retryDelay = schedule.delayMs?.let { minOf(it, UNREADY_DESTINATION_RETRY_MS) }
        ?: UNREADY_DESTINATION_RETRY_MS
    return DeliverySchedule(hasMore = true, delayMs = retryDelay)
}

internal fun earlierEpoch(current: Long?, candidate: Long?): Long? = when {
    current == null -> candidate
    candidate == null -> current
    else -> minOf(current, candidate)
}

internal fun targetPrefetchSince(activityStartEpochMs: Long): Long =
    (activityStartEpochMs - TARGET_PREFETCH_TOLERANCE_MS).coerceAtLeast(0L)

internal fun tryReserveOnelapUploadSlot(
    inFlightByAccount: MutableMap<Long, Int>,
    accountId: Long,
): Boolean {
    val current = inFlightByAccount[accountId]?.coerceAtLeast(0) ?: 0
    if (current >= MAX_ONELAP_UPLOADS_PER_BATCH) return false
    inFlightByAccount[accountId] = current + 1
    return true
}

internal data class PendingUploadMatchCandidate(
    val jobId: Long,
    val canonicalActivityId: Long,
    val sourceVariantId: Long,
    val sourceFileSha256: String,
    val startTimeEpochMs: Long,
    val distanceMeters: Double?,
    val title: String?,
    val confirmationMarker: String?,
)

internal data class PendingUploadRemoteMatch(
    val remote: RemoteActivitySummary,
    val candidate: PendingUploadMatchCandidate,
)

internal fun findHighConfidencePendingUploadMatches(
    remotes: List<RemoteActivitySummary>,
    candidates: List<PendingUploadMatchCandidate>,
): List<PendingUploadRemoteMatch> {
    val edges = remotes.flatMap { remote ->
        candidates.mapNotNull { candidate ->
            if (isHighConfidencePendingUploadMatch(remote, candidate)) {
                PendingUploadRemoteMatch(remote, candidate)
            } else {
                null
            }
        }
    }
    val remoteCounts = edges.groupingBy { it.remote.remoteActivityId }.eachCount()
    val candidateCounts = edges.groupingBy { it.candidate.jobId }.eachCount()
    return edges.filter {
        remoteCounts[it.remote.remoteActivityId] == 1 && candidateCounts[it.candidate.jobId] == 1
    }
}

private fun isHighConfidencePendingUploadMatch(
    remote: RemoteActivitySummary,
    candidate: PendingUploadMatchCandidate,
): Boolean {
    val externalId = remote.metadata["external_id"]
    if (externalId == "fitsync-${candidate.sourceFileSha256}.fit") return true

    val marker = candidate.confirmationMarker?.takeIf { it.length >= 8 }
    if (marker != null) {
        val remoteMarkers = buildList {
            remote.downloadUrl?.let(::add)
            addAll(remote.metadata.values)
        }
        if (remoteMarkers.any { it.contains(marker, ignoreCase = true) }) return true
    }

    val remoteStart = remote.startTimeEpochMs ?: return false
    if (abs(remoteStart - candidate.startTimeEpochMs) > PENDING_UPLOAD_START_TOLERANCE_MS) return false
    val distanceMatches = candidate.distanceMeters?.takeIf { it > 0.0 }?.let { localDistance ->
        remote.distanceMeters?.takeIf { it > 0.0 }?.let { remoteDistance ->
            abs(localDistance - remoteDistance) <= maxOf(
                PENDING_UPLOAD_DISTANCE_TOLERANCE_METERS,
                localDistance * PENDING_UPLOAD_DISTANCE_TOLERANCE_RATIO,
            )
        }
    } == true
    val titleMatches = normalizePendingUploadTitle(candidate.title)?.let { localTitle ->
        normalizePendingUploadTitle(remote.name) == localTitle
    } == true
    return distanceMatches || titleMatches
}

private fun normalizePendingUploadTitle(value: String?): String? = value
    ?.trim()
    ?.lowercase()
    ?.replace(Regex("\\s+"), " ")
    ?.takeIf { it.length >= 3 }

internal fun canCompareTargetInBackground(capabilities: ConnectorCapabilities): Boolean =
    capabilities.listActivitiesMode == ConnectorExecution.BACKGROUND &&
        capabilities.downloadFitMode == ConnectorExecution.BACKGROUND

internal val PERMANENT_REMOTE_SKIP_CODES = setOf(
    "NO_ORIGINAL_FILE",
    "UNSUPPORTED_ORIGINAL_FORMAT",
)

internal const val PENDING_UPLOAD_START_TOLERANCE_MS = 2 * 60_000L
internal const val PENDING_UPLOAD_DISTANCE_TOLERANCE_METERS = 100.0
internal const val PENDING_UPLOAD_DISTANCE_TOLERANCE_RATIO = 0.005

internal fun shouldAbortPullAfterItemResult(result: ConnectorResult<*>): Boolean = when (result) {
    is ConnectorResult.Success -> false
    is ConnectorResult.PermanentFailure -> result.code !in PERMANENT_REMOTE_SKIP_CODES
    else -> true
}
