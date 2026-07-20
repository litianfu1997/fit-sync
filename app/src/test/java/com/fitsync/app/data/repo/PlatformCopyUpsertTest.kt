package com.fitsync.app.data.repo

import com.fitsync.app.data.db.entity.PlatformCopyEntity
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlatformCopyUpsertTest {

    @Test
    fun provisionalRemoteId_isReplacedByRealId_onSameCanonicalAccount() {
        val existing = PlatformCopyEntity(
            id = 1,
            canonicalActivityId = 10,
            platformAccountId = 2,
            remoteActivityId = "provisional-md5",
            fileSha256 = "abc",
            createdBySync = true,
            sourceVariantId = 5,
        )
        val plan = planPlatformCopyUpsert(
            byRemote = null,
            byCanonicalAndAccount = existing,
            bySha = null,
            canonicalId = 10,
            accountId = 2,
            remoteActivityId = "real-activity-99",
            fileSha256 = "abc",
            sourceVariantId = 5,
        )
        val update = plan as PlatformCopyUpsertPlan.Update
        assertThat(update.entity.id).isEqualTo(1)
        assertThat(update.entity.remoteActivityId).isEqualTo("real-activity-99")
        assertThat(update.entity.fileSha256).isEqualTo("abc")
    }

    @Test
    fun knownRemoteId_isNoOpWhenUnchanged() {
        val existing = PlatformCopyEntity(
            id = 3,
            canonicalActivityId = 10,
            platformAccountId = 2,
            remoteActivityId = "real-1",
            fileSha256 = "sha",
        )
        val plan = planPlatformCopyUpsert(
            byRemote = existing,
            byCanonicalAndAccount = existing,
            bySha = existing,
            canonicalId = 10,
            accountId = 2,
            remoteActivityId = "real-1",
            fileSha256 = "sha",
        )
        assertThat(plan).isEqualTo(PlatformCopyUpsertPlan.NoOp)
    }

    @Test
    fun knownRemoteId_patchesMissingSha() {
        val existing = PlatformCopyEntity(
            id = 3,
            canonicalActivityId = 10,
            platformAccountId = 2,
            remoteActivityId = "real-1",
            fileSha256 = null,
        )
        val plan = planPlatformCopyUpsert(
            byRemote = existing,
            byCanonicalAndAccount = null,
            bySha = null,
            canonicalId = 10,
            accountId = 2,
            remoteActivityId = "real-1",
            fileSha256 = "new-sha",
            sourceVariantId = 7,
        )
        val update = plan as PlatformCopyUpsertPlan.Update
        assertThat(update.entity.fileSha256).isEqualTo("new-sha")
        assertThat(update.entity.sourceVariantId).isEqualTo(7)
    }

    @Test
    fun matchBySha_realignsRemoteId() {
        val existing = PlatformCopyEntity(
            id = 8,
            canonicalActivityId = 10,
            platformAccountId = 2,
            remoteActivityId = "old",
            fileSha256 = "same-sha",
        )
        val plan = planPlatformCopyUpsert(
            byRemote = null,
            byCanonicalAndAccount = null,
            bySha = existing,
            canonicalId = 10,
            accountId = 2,
            remoteActivityId = "new-id",
            fileSha256 = "same-sha",
        )
        val update = plan as PlatformCopyUpsertPlan.Update
        assertThat(update.entity.remoteActivityId).isEqualTo("new-id")
    }

    @Test
    fun noExisting_insertsNewRow() {
        val plan = planPlatformCopyUpsert(
            byRemote = null,
            byCanonicalAndAccount = null,
            bySha = null,
            canonicalId = 10,
            accountId = 2,
            remoteActivityId = "r1",
            fileSha256 = "s1",
            createdBySync = true,
            sourceVariantId = 3,
        )
        val insert = plan as PlatformCopyUpsertPlan.Insert
        assertThat(insert.entity.canonicalActivityId).isEqualTo(10)
        assertThat(insert.entity.platformAccountId).isEqualTo(2)
        assertThat(insert.entity.remoteActivityId).isEqualTo("r1")
        assertThat(insert.entity.createdBySync).isTrue()
    }
}
