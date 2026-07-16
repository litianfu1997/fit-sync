package com.fitsync.app.sync

import com.fitsync.app.connectors.PlatformCapabilityRegistry
import com.fitsync.app.data.db.FitSyncDatabase
import com.fitsync.app.data.db.entity.DeliveryJobEntity
import com.fitsync.app.data.db.entity.SyncRuleEntity
import com.fitsync.app.domain.model.Platform
import java.security.MessageDigest

class DeliveryPlanner(private val db: FitSyncDatabase) {
    suspend fun planForActivity(canonicalActivityId: Long, sourcePlatform: Platform): Int {
        val activity = db.canonicalActivityDao().getById(canonicalActivityId) ?: return 0
        val variant = activity.primaryVariantId?.let { db.activityVariantDao().getById(it) }
            ?: db.activityVariantDao().findByCanonical(canonicalActivityId).maxByOrNull { it.qualityScore ?: 0 }
            ?: return 0
        val rules = db.syncRuleDao().getEnabled().filter { it.sourcePlatform == sourcePlatform }
        var created = 0
        rules.forEach { rule ->
            if (!rule.includeHistory && activity.createdAtEpochMs < rule.createdAtEpochMs) return@forEach
            val destination = db.platformAccountDao().getById(rule.destinationAccountId) ?: return@forEach
            if (destination.platform == sourcePlatform) return@forEach
            if (db.platformCopyDao().findByCanonicalAndAccount(canonicalActivityId, destination.id) != null) return@forEach
            val mode = PlatformCapabilityRegistry.capabilities(destination.platform).uploadFitMode
            val id = db.deliveryJobDao().insert(
                DeliveryJobEntity(
                    canonicalActivityId = canonicalActivityId,
                    destinationAccountId = destination.id,
                    sourceVariantId = variant.id,
                    idempotencyKey = idempotencyKey(variant.fileSha256, destination.id),
                    executionMode = mode,
                    requestedByRuleId = rule.id,
                ),
            )
            if (id > 0) created++
        }
        return created
    }

    suspend fun enqueueManual(canonicalActivityId: Long, destinationAccountId: Long): Long {
        val activity = db.canonicalActivityDao().getById(canonicalActivityId) ?: return -1
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
        db.canonicalActivityDao().getAll().forEach { activity ->
            val variants = db.activityVariantDao().findByCanonical(activity.id)
            if (variants.any { (it.sourcePlatform ?: Platform.LOCAL_IMPORT) == rule.sourcePlatform }) {
                count += planForActivity(activity.id, rule.sourcePlatform)
            }
        }
        return count
    }

    companion object {
        fun idempotencyKey(fileSha256: String, destinationAccountId: Long): String {
            val raw = "$fileSha256:$destinationAccountId".toByteArray()
            return MessageDigest.getInstance("SHA-256").digest(raw)
                .joinToString("") { "%02x".format(it) }
        }
    }
}
