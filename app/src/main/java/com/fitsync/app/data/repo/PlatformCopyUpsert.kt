package com.fitsync.app.data.repo

import com.fitsync.app.data.db.entity.PlatformCopyEntity

/**
 * PlatformCopy 对齐计划：兼容历史版本留下的临时 remoteId；后续 list 的真实 ID 必须覆盖同一行。
 */
sealed class PlatformCopyUpsertPlan {
    data class Update(val entity: PlatformCopyEntity) : PlatformCopyUpsertPlan()
    data class Insert(val entity: PlatformCopyEntity) : PlatformCopyUpsertPlan()
    data object NoOp : PlatformCopyUpsertPlan()
}

fun planPlatformCopyUpsert(
    byRemote: PlatformCopyEntity?,
    byCanonicalAndAccount: PlatformCopyEntity?,
    bySha: PlatformCopyEntity?,
    canonicalId: Long,
    accountId: Long,
    remoteActivityId: String,
    remoteUrl: String? = null,
    fileSha256: String? = null,
    sourceVariantId: Long? = null,
    createdBySync: Boolean = false,
): PlatformCopyUpsertPlan {
    if (byRemote != null) {
        val patched = byRemote.copy(
            canonicalActivityId = canonicalId,
            remoteUrl = remoteUrl ?: byRemote.remoteUrl,
            fileSha256 = fileSha256 ?: byRemote.fileSha256,
            sourceVariantId = sourceVariantId ?: byRemote.sourceVariantId,
            createdBySync = byRemote.createdBySync || createdBySync,
        )
        return if (patched == byRemote) PlatformCopyUpsertPlan.NoOp else PlatformCopyUpsertPlan.Update(patched)
    }
    if (byCanonicalAndAccount != null) {
        return PlatformCopyUpsertPlan.Update(
            byCanonicalAndAccount.copy(
                remoteActivityId = remoteActivityId,
                remoteUrl = remoteUrl ?: byCanonicalAndAccount.remoteUrl,
                fileSha256 = fileSha256 ?: byCanonicalAndAccount.fileSha256,
                sourceVariantId = sourceVariantId ?: byCanonicalAndAccount.sourceVariantId,
                createdBySync = byCanonicalAndAccount.createdBySync || createdBySync,
            ),
        )
    }
    if (bySha != null) {
        return PlatformCopyUpsertPlan.Update(
            bySha.copy(
                canonicalActivityId = canonicalId,
                remoteActivityId = remoteActivityId,
                remoteUrl = remoteUrl ?: bySha.remoteUrl,
                sourceVariantId = sourceVariantId ?: bySha.sourceVariantId,
                createdBySync = bySha.createdBySync || createdBySync,
            ),
        )
    }
    return PlatformCopyUpsertPlan.Insert(
        PlatformCopyEntity(
            canonicalActivityId = canonicalId,
            platformAccountId = accountId,
            remoteActivityId = remoteActivityId,
            remoteUrl = remoteUrl,
            fileSha256 = fileSha256,
            createdBySync = createdBySync,
            sourceVariantId = sourceVariantId,
        ),
    )
}
