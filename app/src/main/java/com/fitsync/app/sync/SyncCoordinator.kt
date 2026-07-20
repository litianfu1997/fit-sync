package com.fitsync.app.sync

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
import com.fitsync.app.data.db.entity.DeliveryJobEntity
import com.fitsync.app.data.db.entity.PlatformCopyEntity
import com.fitsync.app.data.db.entity.RemoteActivitySkipEntity
import com.fitsync.app.data.repo.ActivityMutationGate
import com.fitsync.app.data.repo.FitStorageJanitor
import com.fitsync.app.data.repo.ImportOutcome
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
import kotlinx.coroutines.sync.withLock
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

    suspend fun runSync(force: Boolean = false): SyncRunSummary = syncMutex.withLock sync@{
        val settings = db.appSettingsDao().get()
        if (!force && settings?.autoSyncEnabled == false) return@sync SyncRunSummary()
        var pulled = 0
        var queued = 0
        var failedAccounts = 0
        val enabledRuleCount = db.syncRuleDao().getEnabled().size
        val accounts = db.platformAccountDao().getAll().filter { it.status != AccountStatus.DISABLED }
        accounts.forEach { account ->
            val connector = connectors.get(account.platform)
            if (connector == null) {
                failedAccounts++
                return@forEach
            }
            val capabilities = connector.detectCapabilities()
            if (account.capabilitySnapshot != capabilities.serialize()) {
                db.platformAccountDao().update(
                    account.copy(
                        capabilitySnapshot = capabilities.serialize(),
                        updatedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
            }
            val listMode = capabilities.listActivitiesMode
            if (listMode != ConnectorExecution.BACKGROUND) {
                if (force && listMode != ConnectorExecution.UNSUPPORTED) {
                    val url = PlatformWebConfigs.get(account.platform).loginUrl
                    markAccountNeedsAction(account.id, false)
                    if (settings?.notificationsEnabled != false) {
                        notifications.notifyAccountAction(
                            account.id,
                            account.platform,
                            url,
                            "请在网页中导出 FIT 后分享到本应用",
                        )
                    }
                }
                return@forEach
            }
            if (capabilities.downloadFitMode != ConnectorExecution.BACKGROUND) {
                markAccountNeedsAction(account.id, false)
                if (force && settings?.notificationsEnabled != false) {
                    notifications.notifyAccountAction(
                        account.id,
                        account.platform,
                        PlatformWebConfigs.get(account.platform).loginUrl,
                        "请登录 ${account.platform.displayName} 网页以启用原文件拉取",
                    )
                }
                return@forEach
            }
            val result = pullAccount(
                account.id,
                settings?.initialHistoryDays ?: 90,
                forceFullHistory = force,
            )
            pulled += result.pulled
            queued += result.queued
            if (!result.completedNormally) failedAccounts++
        }
        val delivered = processDeliveryJobs()
        runCatching { storageJanitor?.run() }
        val actionRequiredAccounts = db.platformAccountDao().getAll().count { it.needsUserAction }
        val deliverySchedule = deliverySchedule()
        SyncRunSummary(
            pulledActivities = pulled,
            queuedDeliveries = queued,
            completedDeliveries = delivered,
            hasMoreDeliveries = deliverySchedule.hasMore,
            nextDeliveryDelayMs = deliverySchedule.delayMs,
            checkedAccounts = accounts.size,
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
        db.deliveryJobDao().findRunnable(now).forEach { candidateJob ->
            val prepared = mutationGate.withLock {
                val currentJob = db.deliveryJobDao().getById(candidateJob.id) ?: return@withLock null
                val account = db.platformAccountDao().getById(currentJob.destinationAccountId)
                val activity = db.canonicalActivityDao().getById(currentJob.canonicalActivityId)
                val connector = account?.let { connectors.get(it.platform) }
                if (account == null || activity == null || connector == null) {
                    db.deliveryJobDao().update(
                        currentJob.copy(
                            status = DeliveryJobStatus.DISABLED,
                            errorMessage = "目标账号或本地活动已不存在",
                            updatedAtEpochMs = now,
                        ),
                    )
                    return@withLock null
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
                    return@withLock null
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
                    connector = connector,
                    polling = !currentJob.remoteUploadId.isNullOrBlank(),
                )
            } ?: return@forEach

            // 任何 connector 异常（DB/IO/解析）都转可重试失败，避免 job 永久卡在 UPLOADING。
            val result = runCatching {
                if (prepared.polling) {
                    prepared.connector.checkUpload(requireNotNull(prepared.job.remoteUploadId))
                } else {
                    prepared.connector.uploadFit(prepared.activity)
                }
            }
                .getOrElse { ConnectorResult.RetryableFailure(null, it.message ?: "上传过程发生异常") }
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
        val connector: PlatformConnector,
        val polling: Boolean,
    )

    private suspend fun deliverySchedule(): DeliverySchedule {
        val now = System.currentTimeMillis()
        if (db.deliveryJobDao().countRunnable(now) > 0) return DeliverySchedule(true, 0)
        val next = db.deliveryJobDao().findNextScheduledAt() ?: return DeliverySchedule(false, null)
        return DeliverySchedule(true, (next - now).coerceAtLeast(0))
    }

    private suspend fun pullAccount(
        accountId: Long,
        historyDays: Int,
        forceFullHistory: Boolean,
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
        while (pages++ < 100) {
            when (val pageResult = connector.listActivities(since, cursor)) {
                is ConnectorResult.Success -> {
                    var abortAccount = false
                    for (remote in pageResult.value.items) {
                        if (db.platformCopyDao().findByRemote(account.id, remote.remoteActivityId) != null) continue
                        if (db.remoteActivitySkipDao().findByRemote(account.id, remote.remoteActivityId) != null) continue
                        when (val imported = downloadAndImport(account.id, account.platform, connector, remote)) {
                            is ConnectorResult.Success -> {
                                pulled++
                                queued += mutationGate.withLock {
                                    if (db.canonicalActivityDao().getById(imported.value) == null) 0
                                    else planner.planForActivity(imported.value, account.platform)
                                }
                            }
                            is ConnectorResult.RequiresLogin -> {
                                markAccountNeedsAction(account.id, true)
                                notifyAccountActionIfEnabled(
                                    account.id,
                                    account.platform,
                                    imported.url,
                                    imported.message,
                                )
                                completedNormally = false
                                abortAccount = true
                            }
                            is ConnectorResult.RequiresUserAction -> {
                                markAccountNeedsAction(account.id, false)
                                notifyAccountActionIfEnabled(
                                    account.id,
                                    account.platform,
                                    imported.url,
                                    imported.message,
                                )
                                completedNormally = false
                                abortAccount = true
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
                                }
                            }
                            else -> {
                                // 单条永久/可重试失败：跳过本条继续拉同页，不中断整页；下次同步再试
                                completedNormally = false
                            }
                        }
                        if (abortAccount) break
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
        val outcome = importService.importFromFile(
            fitFile.file,
            fitFile.displayName,
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

private val PERMANENT_REMOTE_SKIP_CODES = setOf(
    "NO_ORIGINAL_FILE",
    "UNSUPPORTED_ORIGINAL_FORMAT",
)
