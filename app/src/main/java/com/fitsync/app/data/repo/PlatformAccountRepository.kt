package com.fitsync.app.data.repo

import com.fitsync.app.connectors.ConnectorRegistry
import com.fitsync.app.data.db.FitSyncDatabase
import com.fitsync.app.data.db.entity.PlatformAccountEntity
import com.fitsync.app.domain.model.AccountStatus
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.domain.model.SessionState
import com.fitsync.app.web.WebSessionManager

class PlatformAccountRepository(
    private val db: FitSyncDatabase,
    private val connectors: ConnectorRegistry,
    private val sessions: WebSessionManager,
) {
    suspend fun validateAndPersist(platform: Platform): SessionState {
        val connector = connectors.get(platform) ?: return SessionState.NOT_CONNECTED
        val detectedState = connector.validateSession()
        val now = System.currentTimeMillis()
        val existing = db.platformAccountDao().getByPlatform(platform)
        val state = if (
            detectedState == SessionState.EXPIRED &&
            (existing == null || existing.status == AccountStatus.DISABLED)
        ) {
            SessionState.NOT_CONNECTED
        } else {
            detectedState
        }
        val status = when (state) {
            SessionState.AUTHENTICATED -> AccountStatus.ACTIVE
            SessionState.EXPIRED -> AccountStatus.AUTH_EXPIRED
            SessionState.NEEDS_USER_ACTION -> AccountStatus.NEEDS_USER_ACTION
            SessionState.NOT_CONNECTED -> AccountStatus.DISABLED
        }
        val capabilities = connector.detectCapabilities()
        db.platformAccountDao().upsert(
            PlatformAccountEntity(
                id = existing?.id ?: 0,
                platform = platform,
                externalUserId = sessions.externalUserId(platform) ?: existing?.externalUserId,
                displayName = existing?.displayName,
                syncCursor = existing?.syncCursor,
                status = status,
                capabilitySnapshot = capabilities.serialize(),
                lastValidatedAtEpochMs = now,
                lastSyncAtEpochMs = existing?.lastSyncAtEpochMs,
                needsUserAction = state != SessionState.AUTHENTICATED,
                createdAtEpochMs = existing?.createdAtEpochMs ?: now,
                updatedAtEpochMs = now,
            ),
        )
        return state
    }

    suspend fun disconnect(platform: Platform) {
        val account = db.platformAccountDao().getByPlatform(platform)
        sessions.clear(platform)
        if (account != null) {
            db.syncRuleDao().deleteByDestination(account.id)
            db.deliveryJobDao().disableForAccount(account.id)
            db.platformAccountDao().update(
                account.copy(
                    externalUserId = null,
                    displayName = null,
                    syncCursor = null,
                    status = AccountStatus.DISABLED,
                    capabilitySnapshot = null,
                    lastValidatedAtEpochMs = System.currentTimeMillis(),
                    needsUserAction = false,
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
        }
    }
}
