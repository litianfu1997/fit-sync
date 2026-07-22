package com.fitsync.app.data.repo

import androidx.room.withTransaction
import com.fitsync.app.connectors.ConnectorRegistry
import com.fitsync.app.connectors.ConnectorCapabilities
import com.fitsync.app.connectors.CredentialsLoginConnector
import com.fitsync.app.data.db.FitSyncDatabase
import com.fitsync.app.data.db.entity.PlatformAccountEntity
import com.fitsync.app.domain.model.AccountStatus
import com.fitsync.app.domain.model.ConnectorExecution
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.domain.model.SessionState
import com.fitsync.app.notification.SyncNotificationManager
import com.fitsync.app.web.WebSessionManager
import com.fitsync.app.strava.StravaOAuthService

class PlatformAccountRepository(
    private val db: FitSyncDatabase,
    private val connectors: ConnectorRegistry,
    private val sessions: WebSessionManager,
    private val stravaOAuthService: StravaOAuthService,
    private val notifications: SyncNotificationManager,
) {
    /**
     * @param detectedOverride WebView 内同源校验等路径已得到的状态；非空时跳过 connector 网络探测。
     */
    suspend fun validateAndPersist(
        platform: Platform,
        detectedOverride: SessionState? = null,
    ): SessionState {
        val connector = connectors.get(platform) ?: return SessionState.NOT_CONNECTED
        val detectedState = detectedOverride ?: connector.validateSession()
        val now = System.currentTimeMillis()
        val existing = db.platformAccountDao().getByPlatform(platform)
        val detectedExternalUserId = sessions.externalUserId(platform)
        val identityChanged = hasPlatformIdentityChanged(existing?.externalUserId, detectedExternalUserId)
        if (existing != null && identityChanged) {
            clearAccountScopedState(existing.id, platform)
        }
        val firstBind = existing == null || existing.status == AccountStatus.DISABLED
        val state = when {
            // WebView 同源校验结果直接落库，不再二次改写
            detectedOverride != null -> detectedOverride
            // 无 Cookie：首次绑定显示为未连接
            detectedState == SessionState.EXPIRED && firstBind && !sessions.hasSession(platform) ->
                SessionState.NOT_CONNECTED
            // 有 Cookie 但后台探活失败：首次绑定勿伪装成「未登录」，引导用户在网页完成校验
            detectedState == SessionState.EXPIRED && firstBind -> SessionState.NEEDS_USER_ACTION
            else -> detectedState
        }
        val status = when (state) {
            SessionState.AUTHENTICATED -> AccountStatus.ACTIVE
            SessionState.EXPIRED -> AccountStatus.AUTH_EXPIRED
            SessionState.NEEDS_USER_ACTION -> AccountStatus.NEEDS_USER_ACTION
            SessionState.NOT_CONNECTED -> AccountStatus.DISABLED
        }
        val capabilities = if (platform == Platform.STRAVA && state != SessionState.AUTHENTICATED) {
            ConnectorCapabilities(shareImportSupported = true, experimental = true)
        } else {
            connector.detectCapabilities()
        }
        val needsUserAction = if (platform == Platform.STRAVA && state == SessionState.AUTHENTICATED) {
            capabilities.downloadFitMode != ConnectorExecution.BACKGROUND
        } else {
            state != SessionState.AUTHENTICATED
        }
        db.platformAccountDao().upsert(
            PlatformAccountEntity(
                id = existing?.id ?: 0,
                platform = platform,
                externalUserId = detectedExternalUserId ?: existing?.externalUserId,
                displayName = if (platform == Platform.STRAVA) {
                    sessions.stravaDisplayName() ?: existing?.displayName?.takeUnless { identityChanged }
                } else {
                    existing?.displayName?.takeUnless { identityChanged }
                },
                syncCursor = existing?.syncCursor?.takeUnless { identityChanged },
                status = status,
                capabilitySnapshot = capabilities.serialize(),
                lastValidatedAtEpochMs = now,
                lastSyncAtEpochMs = existing?.lastSyncAtEpochMs?.takeUnless { identityChanged },
                needsUserAction = needsUserAction,
                createdAtEpochMs = existing?.createdAtEpochMs ?: now,
                updatedAtEpochMs = now,
            ),
        )
        if (state == SessionState.AUTHENTICATED &&
            capabilities.uploadFitMode == ConnectorExecution.BACKGROUND
        ) {
            // 登录（或重新登录）成功：把因 AUTH_EXPIRED 卡在 WAITING_USER 的后台投递任务
            // 重新入队，下次同步后台重试。仅对支持后台上传的平台；前台上传平台保持 WAITING_USER。
            // RETRY_WAIT 的退避不被触碰（resume 只动 WAITING_USER）。
            db.deliveryJobDao().resumeJobsForForeground(platform, now)
        }
        return state
    }

    /**
     * 账号密码登录（iGPSPORT / COROS 中国站：走纯 API 登录而非 WebView）。
     * 成功后由 [validateAndPersist] 落库建账号；失败返回具体 [SessionState] 供 UI 提示。
     */
    suspend fun loginWithCredentials(
        platform: Platform,
        username: String,
        password: String,
    ): SessionState {
        val connector = connectors.get(platform) as? CredentialsLoginConnector
            ?: return SessionState.NOT_CONNECTED
        val state = connector.login(username, password)
        if (state == SessionState.AUTHENTICATED) {
            validateAndPersist(platform, SessionState.AUTHENTICATED)
        }
        return state
    }

    suspend fun disconnect(platform: Platform) {
        val account = db.platformAccountDao().getByPlatform(platform)
        if (platform == Platform.STRAVA && !stravaOAuthService.revoke()) {
            error("Strava 远端撤销失败，本机凭据已保留，请稍后重试")
        }
        sessions.clear(platform)
        if (account != null) {
            clearAccountScopedState(account.id, platform)
            val now = System.currentTimeMillis()
            db.platformAccountDao().update(
                account.copy(
                    externalUserId = null,
                    displayName = null,
                    syncCursor = null,
                    status = AccountStatus.DISABLED,
                    capabilitySnapshot = null,
                    lastValidatedAtEpochMs = now,
                    lastSyncAtEpochMs = null,
                    needsUserAction = false,
                    updatedAtEpochMs = now,
                ),
            )
        }
    }

    private suspend fun clearAccountScopedState(accountId: Long, platform: Platform) {
        val sourceRules = db.syncRuleDao().getAll().filter { it.sourcePlatform == platform }
        val jobs = (
            db.deliveryJobDao().findByDestinationAccount(accountId) +
                sourceRules.flatMap { db.deliveryJobDao().findByRequestedRule(it.id) }
            ).distinctBy { it.id }
        db.withTransaction {
            db.remoteActivitySkipDao().deleteByAccount(accountId)
            db.syncRuleDao().deleteByDestination(accountId)
            sourceRules.forEach { db.deliveryJobDao().deleteByRequestedRule(it.id) }
            db.syncRuleDao().deleteBySource(platform)
            db.platformCopyDao().deleteByAccount(accountId)
            db.deliveryJobDao().deleteByDestinationAccount(accountId)
        }
        jobs.forEach { notifications.cancel(it.id) }
        notifications.cancelAccount(accountId)
    }
}

internal fun hasPlatformIdentityChanged(existingExternalUserId: String?, detectedExternalUserId: String?): Boolean =
    !existingExternalUserId.isNullOrBlank() &&
        !detectedExternalUserId.isNullOrBlank() &&
        existingExternalUserId != detectedExternalUserId
