package com.fitsync.app.sync

import android.os.Trace
import androidx.room.withTransaction
import com.fitsync.app.connectors.ConnectorRegistry
import com.fitsync.app.connectors.ConnectorCapabilities
import com.fitsync.app.connectors.ConnectorResult
import com.fitsync.app.connectors.FitFile
import com.fitsync.app.connectors.PlatformConnector
import com.fitsync.app.connectors.PlatformCapabilityRegistry
import com.fitsync.app.connectors.RemoteActivitySummary
import com.fitsync.app.connectors.SyncCursor
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
        var failedAccounts = 0
        val historyDays = settings?.initialHistoryDays ?: 90
        val allAccounts = db.platformAccountDao().getAll()
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
        val accounts = when {
            manualSelection == null -> allAccounts.filter { it.status != AccountStatus.DISABLED }
            manualSelection.sourcePlatform == Platform.LOCAL_IMPORT -> emptyList()
            else -> allAccounts.filter {
                it.platform == manualSelection.sourcePlatform &&
                    (it.status == AccountStatus.ACTIVE || it.status == AccountStatus.EXPERIMENTAL)
            }.also {
                require(it.isNotEmpty()) { "来源平台需要先完成登录" }
            }
        }
        val enabledRuleCount = if (manualSelection == null) db.syncRuleDao().getEnabled().size else 1
        val accountResults = coroutineScope {
            val accountSemaphore = Semaphore(MAX_CONCURRENT_ACCOUNTS)
            accounts.map { account ->
                async {
                    accountSemaphore.withPermit {
                        syncAccount(
                            account = account,
                            historyDays = historyDays,
                            force = force,
                            planWithRules = manualSelection == null,
                            notificationsEnabled = settings?.notificationsEnabled != false,
                        )
                    }
                }
            }.awaitAll()
        }
        pulled += accountResults.sumOf { it.pulled }
        queued += accountResults.sumOf { it.queued }
        failedAccounts += accountResults.count { !it.completedNormally }
        if (manualSelection != null) {
            val fromEpochMs = calculatePullSince(
                nowEpochMs = System.currentTimeMillis(),
                previousSyncEpochMs = null,
                historyDays = historyDays,
                forceFullHistory = true,
            )
            queued += mutationGate.withLock {
                planner.planManualSelection(
                    sourcePlatform = manualSelection.sourcePlatform,
                    destinationAccountId = manualSelection.destinationAccountId,
                    sportTypes = manualSelection.sportTypes,
                    fromEpochMs = fromEpochMs,
                )
            }
        }
        val delivered = processDeliveryJobs()
        runCatching { storageJanitor?.run() }
        val actionRequiredAccounts = db.platformAccountDao().getAll().count { account ->
            account.needsUserAction && (
                manualSelection == null ||
                    account.id == destinationAccount?.id ||
                    accounts.any { it.id == account.id }
                )
        }
        val deliverySchedule = deliverySchedule()
        SyncRunSummary(
            pulledActivities = pulled,
            queuedDeliveries = queued,
            completedDeliveries = delivered,
            hasMoreDeliveries = deliverySchedule.hasMore,
            nextDeliveryDelayMs = deliverySchedule.delayMs,
            checkedAccounts = if (manualSelection?.sourcePlatform == Platform.LOCAL_IMPORT) 1 else accounts.size,
            enabledRules = enabledRuleCount,
            actionRequiredAccounts = actionRequiredAccounts,
            failedAccounts = failedAccounts,
        )
    }

    suspend fun runDeliveryBatch(): SyncRunSummary = syncMutex.withLock {
        val delivered = processDeliveryJobs()
        runCatching { storageJanitor?.run() }
        val deliverySchedule = deliverySchedule()
        SyncRunSummary(
            completedDeliveries = delivered,
            hasMoreDeliveries = deliverySchedule.hasMore,
            nextDeliveryDelayMs = deliverySchedule.delayMs,
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

    suspend fun processDeliveryJobs(): Int {
        var completed = 0
        val now = System.currentTimeMillis()
        // 恢复上一轮被 Worker 中断（进程被杀/超时）而卡在 UPLOADING 的任务，避免永久卡死。
        // WAITING_USER 不在这里盲目重置：AUTH_EXPIRED/USER_ACTION 的任务只在登录成功后由
        // PlatformAccountRepository 恢复；RETRY_WAIT 由 findRunnable 按 nextRetryAt 退避拾取。
        db.deliveryJobDao().resetStaleUploading(now)
        val preparedJobs = mutationGate.withLock {
            db.withTransaction {
                db.deliveryJobDao().findRunnable(now).mapNotNull { candidateJob ->
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
                    polling = !currentJob.remoteUploadId.isNullOrBlank(),
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
                errorCode = null,
                errorMessage = null,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
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

    private suspend fun deliverySchedule(): DeliverySchedule {
        val now = System.currentTimeMillis()
        if (db.deliveryJobDao().countRunnable(now) > 0) return DeliverySchedule(true, 0)
        val next = db.deliveryJobDao().findNextScheduledAt() ?: return DeliverySchedule(false, null)
        return DeliverySchedule(true, (next - now).coerceAtLeast(0))
    }

    private suspend fun syncAccount(
        account: PlatformAccountEntity,
        historyDays: Int,
        force: Boolean,
        planWithRules: Boolean,
        notificationsEnabled: Boolean,
    ): PullAccountResult {
        val connector = connectors.get(account.platform) ?: return PullAccountResult(completedNormally = false)
        val capabilities = connector.detectCapabilities()
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
            return PullAccountResult(completedNormally = true)
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
            return PullAccountResult(completedNormally = true)
        }
        Trace.beginSection("FitSync:pull_${account.platform.name}")
        return try {
            pullAccount(account.id, historyDays, force, planWithRules)
        } finally {
            Trace.endSection()
        }
    }

    private suspend fun pullAccount(
        accountId: Long,
        historyDays: Int,
        forceFullHistory: Boolean,
        planWithRules: Boolean = true,
    ): PullAccountResult {
        var account = db.platformAccountDao().getById(accountId) ?: return PullAccountResult()
        val connector = connectors.get(account.platform) ?: return PullAccountResult()
        val now = System.currentTimeMillis()
        val previousSync = account.lastSyncAtEpochMs
        val since = calculatePullSince(now, previousSync, historyDays, forceFullHistory)
        // A manual history rescan must restart at page 1; a saved cursor belongs
        // to the previous incremental window and would otherwise skip records.
        var cursor = if (forceFullHistory) null else account.syncCursor?.let(::SyncCursor)
        var pulled = 0
        var queued = 0
        var pages = 0
        var completedNormally = true
        val accountDownloadSemaphore = Semaphore(MAX_DOWNLOADS_PER_ACCOUNT)
        while (pages++ < 100) {
            when (val pageResult = globalNetworkSemaphore.withPermit { connector.listActivities(since, cursor) }) {
                is ConnectorResult.Success -> {
                    var abortAccount = false
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
                    val successfulCanonicalIds = mutableListOf<Long>()
                    var actionRequest: Triple<Boolean, String, String>? = null
                    for ((remote, imported) in importedResults) {
                        when (imported) {
                            is ConnectorResult.Success -> {
                                pulled++
                                successfulCanonicalIds += imported.value
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
                    if (successfulCanonicalIds.isNotEmpty() && planWithRules) {
                        queued += mutationGate.withLock {
                            planner.planForActivities(successfulCanonicalIds.distinct(), account.platform)
                        }
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
                    if (!pageResult.value.hasMore || cursor == null) break
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
        if (pages >= 100 && cursor != null) completedNormally = false
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
        return PullAccountResult(pulled, queued, completedNormally)
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
                outcome.validationStatus.name,
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

    /**
     * 写入或对齐 PlatformCopy。
     * 上传路径可能先写入合成 remoteId（如 COROS md5 / iGP ossId），
     * 后续 list 拉到真实 ID 时需覆盖同一 (canonical, account) 行，避免重复下载循环。
     */
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
internal const val MAX_GLOBAL_NETWORK_TRANSFERS = 3

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

private data class DeliverySchedule(val hasMore: Boolean, val delayMs: Long?)

private data class PullAccountResult(
    val pulled: Int = 0,
    val queued: Int = 0,
    val completedNormally: Boolean = false,
)

internal val PERMANENT_REMOTE_SKIP_CODES = setOf(
    "NO_ORIGINAL_FILE",
    "UNSUPPORTED_ORIGINAL_FORMAT",
)

internal fun shouldAbortPullAfterItemResult(result: ConnectorResult<*>): Boolean = when (result) {
    is ConnectorResult.Success -> false
    is ConnectorResult.PermanentFailure -> result.code !in PERMANENT_REMOTE_SKIP_CODES
    else -> true
}
