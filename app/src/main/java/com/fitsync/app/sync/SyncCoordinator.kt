package com.fitsync.app.sync

import com.fitsync.app.connectors.ConnectorRegistry
import com.fitsync.app.connectors.ConnectorResult
import com.fitsync.app.connectors.RemoteActivitySummary
import com.fitsync.app.connectors.SyncCursor
import com.fitsync.app.data.db.FitSyncDatabase
import com.fitsync.app.data.db.entity.PlatformCopyEntity
import com.fitsync.app.data.repo.ImportOutcome
import com.fitsync.app.data.repo.ImportService
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
) {
    private val mutex = Mutex()

    suspend fun runSync(force: Boolean = false): SyncRunSummary = mutex.withLock {
        val settings = db.appSettingsDao().get()
        if (!force && settings?.autoSyncEnabled == false) return@withLock SyncRunSummary()
        var pulled = 0
        var queued = 0
        db.platformAccountDao().getAll()
            .filter { it.status != AccountStatus.DISABLED }
            .forEach { account ->
                val connector = connectors.get(account.platform) ?: return@forEach
                val listMode = connector.detectCapabilities().listActivitiesMode
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
                val result = pullAccount(
                    account.id,
                    settings?.initialHistoryDays ?: 90,
                    forceFullHistory = force,
                )
                pulled += result.first
                queued += result.second
            }
        val delivered = processDeliveryJobs()
        SyncRunSummary(
            pulledActivities = pulled,
            queuedDeliveries = queued,
            completedDeliveries = delivered,
            hasMoreDeliveries = hasRunnableDeliveries(),
        )
    }

    suspend fun runDeliveryBatch(): SyncRunSummary = mutex.withLock {
        val delivered = processDeliveryJobs()
        SyncRunSummary(
            completedDeliveries = delivered,
            hasMoreDeliveries = hasRunnableDeliveries(),
        )
    }

    suspend fun onImported(outcome: ImportOutcome, sourcePlatform: Platform): Int {
        val canonicalId = when (outcome) {
            is ImportOutcome.Success -> outcome.canonicalActivityId
            is ImportOutcome.DuplicateFile -> outcome.canonicalActivityId
            else -> return 0
        }
        return planner.planForActivity(canonicalId, sourcePlatform)
    }

    suspend fun processDeliveryJobs(): Int {
        var completed = 0
        val now = System.currentTimeMillis()
        db.deliveryJobDao().findRunnable(now).forEach { job ->
            val account = db.platformAccountDao().getById(job.destinationAccountId)
            val activity = db.canonicalActivityDao().getById(job.canonicalActivityId)
            val connector = account?.let { connectors.get(it.platform) }
            if (account == null || activity == null || connector == null) {
                db.deliveryJobDao().update(
                    job.copy(
                        status = DeliveryJobStatus.DISABLED,
                        errorMessage = "目标账号或本地活动已不存在",
                        updatedAtEpochMs = now,
                    ),
                )
                return@forEach
            }

            if (job.executionMode != ConnectorExecution.BACKGROUND) {
                waitForUser(job.id, account.platform, PlatformWebConfigs.get(account.platform).uploadUrl, "请在网页中完成 FIT 上传")
                return@forEach
            }

            db.deliveryJobDao().update(
                job.copy(
                    status = DeliveryJobStatus.UPLOADING,
                    attemptCount = job.attemptCount + 1,
                    updatedAtEpochMs = now,
                ),
            )
            when (val result = connector.uploadFit(activity)) {
                is ConnectorResult.Success -> {
                    saveRemoteCopy(job.canonicalActivityId, account.id, job.sourceVariantId, result.value)
                    db.deliveryJobDao().update(
                        job.copy(
                            status = DeliveryJobStatus.SUCCEEDED,
                            attemptCount = job.attemptCount + 1,
                            remoteActivityId = result.value.remoteActivityId,
                            errorCode = null,
                            errorMessage = null,
                            updatedAtEpochMs = System.currentTimeMillis(),
                        ),
                    )
                    completed++
                }
                is ConnectorResult.Duplicate -> {
                    result.value?.let { saveRemoteCopy(job.canonicalActivityId, account.id, job.sourceVariantId, it) }
                    db.deliveryJobDao().update(
                        job.copy(
                            status = DeliveryJobStatus.DUPLICATE_REMOTE,
                            attemptCount = job.attemptCount + 1,
                            remoteActivityId = result.value?.remoteActivityId,
                            updatedAtEpochMs = System.currentTimeMillis(),
                        ),
                    )
                    completed++
                }
                is ConnectorResult.RequiresLogin -> {
                    markAccountNeedsAction(account.id, true)
                    waitForUser(job.id, account.platform, result.url, result.message)
                }
                is ConnectorResult.RequiresUserAction ->
                    waitForUser(job.id, account.platform, result.url, result.message)
                is ConnectorResult.RetryableFailure -> {
                    val attempt = job.attemptCount + 1
                    val delay = min(6 * 60 * 60_000L, 15 * 60_000L * 2.0.pow((attempt - 1).coerceAtMost(5)).toLong())
                    db.deliveryJobDao().update(
                        job.copy(
                            status = DeliveryJobStatus.RETRY_WAIT,
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
        return completed
    }

    suspend fun confirmForegroundJob(jobId: Long) {
        val job = db.deliveryJobDao().getById(jobId) ?: return
        db.deliveryJobDao().update(
            job.copy(
                status = DeliveryJobStatus.SUCCEEDED,
                errorCode = null,
                errorMessage = null,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
        notifications.cancel(jobId)
    }

    suspend fun retryJob(jobId: Long) {
        val job = db.deliveryJobDao().getById(jobId) ?: return
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

    private suspend fun hasRunnableDeliveries(): Boolean =
        db.deliveryJobDao().countRunnable(System.currentTimeMillis()) > 0

    private suspend fun pullAccount(
        accountId: Long,
        historyDays: Int,
        forceFullHistory: Boolean,
    ): Pair<Int, Int> {
        var account = db.platformAccountDao().getById(accountId) ?: return 0 to 0
        val connector = connectors.get(account.platform) ?: return 0 to 0
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
                    var pageFailed = false
                    for (remote in pageResult.value.items) {
                        if (db.platformCopyDao().findByRemote(account.id, remote.remoteActivityId) != null) continue
                        when (val imported = downloadAndImport(account.id, account.platform, connector, remote)) {
                            is ConnectorResult.Success -> {
                                pulled++
                                queued += planner.planForActivity(imported.value, account.platform)
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
                                pageFailed = true
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
                                pageFailed = true
                            }
                            else -> {
                                completedNormally = false
                                pageFailed = true
                            }
                        }
                        if (pageFailed) break
                    }
                    if (pageFailed) break
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
        return pulled to queued
    }

    private suspend fun downloadAndImport(
        accountId: Long,
        platform: Platform,
        connector: com.fitsync.app.connectors.PlatformConnector,
        remote: RemoteActivitySummary,
    ): ConnectorResult<Long> {
        return when (val downloaded = connector.downloadFit(remote)) {
            is ConnectorResult.Success -> {
                val outcome = importService.importFromFile(
                    downloaded.value.file,
                    downloaded.value.displayName,
                    sourcePlatform = platform,
                    collectorType = CollectorType.CLOUD,
                    remoteActivityId = remote.remoteActivityId,
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
                db.platformCopyDao().insert(
                    PlatformCopyEntity(
                        canonicalActivityId = ids.first,
                        platformAccountId = accountId,
                        remoteActivityId = remote.remoteActivityId,
                        remoteUrl = remote.metadata["remote_url"],
                        fileSha256 = downloaded.value.sha256,
                        sourceVariantId = ids.second,
                    ),
                )
                ConnectorResult.Success(ids.first)
            }
            is ConnectorResult.Duplicate -> ConnectorResult.PermanentFailure(null, "平台返回了重复下载结果")
            is ConnectorResult.RequiresLogin -> downloaded
            is ConnectorResult.RequiresUserAction -> downloaded
            is ConnectorResult.RetryableFailure -> downloaded
            is ConnectorResult.PermanentFailure -> downloaded
        }
    }

    private suspend fun saveRemoteCopy(
        canonicalId: Long,
        accountId: Long,
        sourceVariantId: Long,
        copy: com.fitsync.app.connectors.RemoteCopy,
    ) {
        db.platformCopyDao().insert(
            PlatformCopyEntity(
                canonicalActivityId = canonicalId,
                platformAccountId = accountId,
                remoteActivityId = copy.remoteActivityId,
                remoteUrl = copy.remoteUrl,
                createdBySync = true,
                sourceVariantId = sourceVariantId,
            ),
        )
    }

    private suspend fun waitForUser(jobId: Long, platform: Platform, url: String, message: String) {
        val job = db.deliveryJobDao().getById(jobId) ?: return
        db.deliveryJobDao().update(
            job.copy(
                status = DeliveryJobStatus.WAITING_USER,
                userActionUrl = url,
                errorMessage = message,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
        val settings = db.appSettingsDao().get()
        if (settings?.notificationsEnabled != false) notifications.notifyUserAction(jobId, platform, url, message)
    }

    private suspend fun markAccountNeedsAction(accountId: Long, expired: Boolean) {
        val account = db.platformAccountDao().getById(accountId) ?: return
        db.platformAccountDao().update(
            account.copy(
                status = if (expired) AccountStatus.AUTH_EXPIRED else AccountStatus.NEEDS_USER_ACTION,
                needsUserAction = true,
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
)
