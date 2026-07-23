package com.fitsync.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.paging.PagingSource
import com.fitsync.app.data.db.entity.ActivityVariantEntity
import com.fitsync.app.data.db.entity.AppSettingsEntity
import com.fitsync.app.data.db.entity.CanonicalActivityEntity
import com.fitsync.app.data.db.entity.DeliveryJobEntity
import com.fitsync.app.data.db.entity.ImportJobEntity
import com.fitsync.app.data.db.entity.PlatformAccountEntity
import com.fitsync.app.data.db.entity.PlatformCopyEntity
import com.fitsync.app.data.db.entity.RemoteActivitySkipEntity
import com.fitsync.app.data.db.entity.SyncRuleEntity
import com.fitsync.app.domain.model.DedupStatus
import com.fitsync.app.domain.model.DeliveryJobStatus
import com.fitsync.app.domain.model.Platform
import kotlinx.coroutines.flow.Flow

@Dao
interface PlatformAccountDao {
    @Query("SELECT * FROM platform_account ORDER BY platform")
    fun observeAll(): Flow<List<PlatformAccountEntity>>

    @Query("SELECT * FROM platform_account ORDER BY platform")
    suspend fun getAll(): List<PlatformAccountEntity>

    @Query("SELECT * FROM platform_account WHERE id = :id")
    suspend fun getById(id: Long): PlatformAccountEntity?

    @Query("SELECT * FROM platform_account WHERE platform = :platform LIMIT 1")
    suspend fun getByPlatform(platform: Platform): PlatformAccountEntity?

    @Query("SELECT * FROM platform_account WHERE platform = :platform LIMIT 1")
    fun observeByPlatform(platform: Platform): Flow<PlatformAccountEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PlatformAccountEntity): Long

    @Update
    suspend fun update(entity: PlatformAccountEntity)

    @Query("DELETE FROM platform_account WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface CanonicalActivityDao {
    @Query("SELECT * FROM canonical_activity ORDER BY startTimeEpochMs DESC, id DESC")
    fun observeAll(): Flow<List<CanonicalActivityEntity>>

    @Query("SELECT * FROM canonical_activity ORDER BY startTimeEpochMs DESC")
    suspend fun getAll(): List<CanonicalActivityEntity>

    @Query(
        """
        SELECT * FROM canonical_activity
        ORDER BY startTimeEpochMs DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun getPage(limit: Int, offset: Int): List<CanonicalActivityEntity>

    @Query(
        """
        SELECT * FROM canonical_activity
        WHERE startTimeEpochMs < :beforeTime
           OR (startTimeEpochMs = :beforeTime AND id < :beforeId)
        ORDER BY startTimeEpochMs DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun getPageAfter(
        beforeTime: Long = Long.MAX_VALUE,
        beforeId: Long = Long.MAX_VALUE,
        limit: Int = 100,
    ): List<CanonicalActivityEntity>

    @Query(
        """
        SELECT id, title, sportType, startTimeEpochMs, elapsedSeconds, distanceMeters, dedupStatus
        FROM canonical_activity
        WHERE (
            :query = ''
            OR title LIKE '%' || :query || '%' COLLATE NOCASE
            OR (:query LIKE '%骑%' AND sportType IN ('CYCLING', 'INDOOR_CYCLING', 'MTB', 'GRAVEL'))
            OR (:query LIKE '%跑%' AND sportType = 'RUNNING')
          )
          AND (
            :filter = 'ALL'
            OR (:filter = 'CYCLING' AND sportType IN ('CYCLING', 'INDOOR_CYCLING', 'MTB', 'GRAVEL'))
            OR (:filter = 'RUNNING' AND sportType = 'RUNNING')
            OR (:filter = 'REVIEW' AND dedupStatus = 'NEEDS_REVIEW')
          )
        ORDER BY
          CASE WHEN :oldestFirst = 1 THEN startTimeEpochMs END ASC,
          CASE WHEN :oldestFirst = 1 THEN id END ASC,
          CASE WHEN :oldestFirst = 0 THEN startTimeEpochMs END DESC,
          CASE WHEN :oldestFirst = 0 THEN id END DESC
        """,
    )
    fun pagingSource(
        query: String,
        filter: String,
        oldestFirst: Boolean,
    ): PagingSource<Int, ActivityListItem>

    @Query(
        """
        SELECT
          (SELECT COUNT(*) FROM canonical_activity) AS discovered,
          (SELECT COUNT(*) FROM delivery_job
             WHERE status IN ('SUCCEEDED', 'DUPLICATE_REMOTE')) AS synced,
          (SELECT COUNT(*) FROM delivery_job
             WHERE status IN ('PENDING', 'UPLOADING', 'WAITING_REMOTE_PROCESS')) AS pending,
          (SELECT COUNT(*) FROM canonical_activity
             WHERE dedupStatus = 'NEEDS_REVIEW') AS review,
          (SELECT COUNT(*) FROM delivery_job
             WHERE status IN ('FAILED', 'RETRY_WAIT', 'WAITING_USER')) AS failed
        """,
    )
    fun observeHomeSnapshot(): Flow<HomeSnapshot>

    @Query("SELECT * FROM canonical_activity WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<CanonicalActivityEntity>

    @Query("SELECT COUNT(*) FROM canonical_activity")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM canonical_activity WHERE dedupStatus = :status")
    fun observeCountByDedup(status: DedupStatus): Flow<Int>

    @Query("SELECT * FROM canonical_activity WHERE id = :id")
    suspend fun getById(id: Long): CanonicalActivityEntity?

    @Query("SELECT * FROM canonical_activity WHERE id = :id")
    fun observeById(id: Long): Flow<CanonicalActivityEntity?>

    @Query(
        """
        SELECT * FROM canonical_activity
        WHERE startTimeEpochMs BETWEEN :fromMs AND :toMs
        ORDER BY startTimeEpochMs DESC
        """,
    )
    suspend fun findByStartTimeRange(fromMs: Long, toMs: Long): List<CanonicalActivityEntity>

    @Query("SELECT * FROM canonical_activity WHERE reviewCandidateCanonicalId = :candidateId")
    suspend fun findByReviewCandidate(candidateId: Long): List<CanonicalActivityEntity>

    @Insert
    suspend fun insert(entity: CanonicalActivityEntity): Long

    @Update
    suspend fun update(entity: CanonicalActivityEntity)

    @Query("DELETE FROM canonical_activity WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ActivityVariantDao {
    @Query("SELECT * FROM activity_variant WHERE fileSha256 = :sha LIMIT 1")
    suspend fun findBySha256(sha: String): ActivityVariantEntity?

    @Query("SELECT * FROM activity_variant WHERE canonicalActivityId = :canonicalId")
    suspend fun findByCanonical(canonicalId: Long): List<ActivityVariantEntity>

    @Query("SELECT * FROM activity_variant WHERE canonicalActivityId IN (:canonicalIds)")
    suspend fun findByCanonicalIds(canonicalIds: List<Long>): List<ActivityVariantEntity>

    @Query("SELECT * FROM activity_variant WHERE canonicalActivityId = :canonicalId")
    fun observeByCanonical(canonicalId: Long): Flow<List<ActivityVariantEntity>>

    @Query(
        """
        SELECT * FROM activity_variant
        WHERE localFilePath IS NOT NULL AND localFilePath != ''
        """,
    )
    suspend fun findWithLocalFiles(): List<ActivityVariantEntity>

    @Query(
        """
        SELECT * FROM activity_variant
        WHERE parserVersion < :parserVersion
          AND localFilePath != ''
        ORDER BY id
        LIMIT :limit
        """,
    )
    suspend fun findNeedingParserVersion(
        parserVersion: Int,
        limit: Int = 25,
    ): List<ActivityVariantEntity>

    @Query(
        """
        SELECT
          variant.id AS variantId,
          variant.localFilePath AS localFilePath,
          SUM(CASE WHEN job.status IN
            ('PENDING', 'UPLOADING', 'WAITING_USER', 'WAITING_REMOTE_PROCESS', 'RETRY_WAIT')
            THEN 1 ELSE 0 END) AS activeJobCount,
          MAX(CASE WHEN job.status IN ('SUCCEEDED', 'DUPLICATE_REMOTE')
            THEN job.updatedAtEpochMs ELSE NULL END) AS latestSuccessfulDeliveryAtEpochMs
        FROM activity_variant variant
        LEFT JOIN delivery_job job ON job.sourceVariantId = variant.id
        WHERE variant.localFilePath != ''
        GROUP BY variant.id, variant.localFilePath
        """,
    )
    suspend fun findCleanupCandidates(): List<VariantCleanupCandidate>

    @Query("SELECT * FROM activity_variant WHERE id = :id")
    suspend fun getById(id: Long): ActivityVariantEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ActivityVariantEntity): Long

    @Update
    suspend fun update(entity: ActivityVariantEntity)

    @Query("UPDATE activity_variant SET localFilePath = '' WHERE id IN (:ids)")
    suspend fun clearLocalFilePaths(ids: List<Long>)

    @Query("DELETE FROM activity_variant WHERE canonicalActivityId = :canonicalId")
    suspend fun deleteByCanonical(canonicalId: Long)
}

@Dao
interface PlatformCopyDao {
    @Query(
        """
        SELECT * FROM platform_copy
        WHERE platformAccountId = :accountId AND remoteActivityId = :remoteId
        LIMIT 1
        """,
    )
    suspend fun findByRemote(accountId: Long, remoteId: String): PlatformCopyEntity?

    @Query(
        "SELECT remoteActivityId FROM platform_copy " +
            "WHERE platformAccountId = :accountId AND remoteActivityId IN (:remoteIds)",
    )
    suspend fun findExistingRemoteIds(accountId: Long, remoteIds: List<String>): List<String>

    @Query("SELECT * FROM platform_copy WHERE canonicalActivityId = :canonicalId")
    suspend fun findByCanonical(canonicalId: Long): List<PlatformCopyEntity>

    @Query("SELECT * FROM platform_copy WHERE canonicalActivityId IN (:canonicalIds)")
    suspend fun findByCanonicalIds(canonicalIds: List<Long>): List<PlatformCopyEntity>

    @Query("SELECT * FROM platform_copy WHERE canonicalActivityId = :canonicalId")
    fun observeByCanonical(canonicalId: Long): Flow<List<PlatformCopyEntity>>

    @Query(
        """
        SELECT * FROM platform_copy
        WHERE canonicalActivityId = :canonicalId AND platformAccountId = :accountId
        LIMIT 1
        """,
    )
    suspend fun findByCanonicalAndAccount(canonicalId: Long, accountId: Long): PlatformCopyEntity?

    @Query(
        """
        SELECT * FROM platform_copy
        WHERE platformAccountId = :accountId AND fileSha256 = :sha
        LIMIT 1
        """,
    )
    suspend fun findBySha256(accountId: Long, sha: String): PlatformCopyEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: PlatformCopyEntity): Long

    @Update
    suspend fun update(entity: PlatformCopyEntity)

    @Query("DELETE FROM platform_copy WHERE canonicalActivityId = :canonicalId")
    suspend fun deleteByCanonical(canonicalId: Long)

    @Query("DELETE FROM platform_copy WHERE platformAccountId = :accountId")
    suspend fun deleteByAccount(accountId: Long)
}

@Dao
interface RemoteActivitySkipDao {
    @Query(
        "SELECT * FROM remote_activity_skip WHERE platformAccountId = :accountId " +
            "AND remoteActivityId = :remoteId LIMIT 1",
    )
    suspend fun findByRemote(accountId: Long, remoteId: String): RemoteActivitySkipEntity?

    @Query(
        "SELECT remoteActivityId FROM remote_activity_skip " +
            "WHERE platformAccountId = :accountId AND remoteActivityId IN (:remoteIds)",
    )
    suspend fun findExistingRemoteIds(accountId: Long, remoteIds: List<String>): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RemoteActivitySkipEntity): Long

    @Query("DELETE FROM remote_activity_skip WHERE platformAccountId = :accountId")
    suspend fun deleteByAccount(accountId: Long)
}

@Dao
interface DeliveryJobDao {
    @Query("SELECT * FROM delivery_job ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<DeliveryJobEntity>>

    @Query(
        """
        SELECT
          job.id AS id,
          job.canonicalActivityId AS canonicalActivityId,
          job.destinationAccountId AS destinationAccountId,
          job.status AS status,
          job.attemptCount AS attemptCount,
          job.errorCode AS errorCode,
          job.errorMessage AS errorMessage,
          job.updatedAtEpochMs AS updatedAtEpochMs,
          activity.title AS activityTitle,
          activity.sportType AS activitySportType,
          account.platform AS destinationPlatform
        FROM delivery_job job
        LEFT JOIN canonical_activity activity ON activity.id = job.canonicalActivityId
        LEFT JOIN platform_account account ON account.id = job.destinationAccountId
        WHERE
          (:filter = 'waiting' AND job.status IN ('PENDING', 'UPLOADING', 'WAITING_REMOTE_PROCESS'))
          OR (:filter = 'completed' AND job.status IN ('SUCCEEDED', 'DUPLICATE_REMOTE'))
          OR (:filter = 'failed' AND job.status IN ('FAILED', 'WAITING_USER', 'RETRY_WAIT'))
        ORDER BY job.createdAtEpochMs DESC, job.id DESC
        """,
    )
    fun pagingSource(filter: String): PagingSource<Int, SyncJobListItem>

    @Query(
        """
        SELECT COUNT(*) FROM delivery_job
        WHERE status = 'PENDING' OR status = 'UPLOADING'
           OR status = 'WAITING_REMOTE_PROCESS'
        """,
    )
    fun observePendingCount(): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM delivery_job
        WHERE status = 'FAILED' OR status = 'RETRY_WAIT' OR status = 'WAITING_USER'
        """,
    )
    fun observeProblemCount(): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM delivery_job
        WHERE status = 'SUCCEEDED' OR status = 'DUPLICATE_REMOTE'
        """,
    )
    fun observeCompletedCount(): Flow<Int>

    @Query("SELECT * FROM delivery_job WHERE status = 'WAITING_USER' ORDER BY createdAtEpochMs DESC LIMIT :limit")
    fun observeWaitingForUser(limit: Int = 4): Flow<List<DeliveryJobEntity>>

    @Query("SELECT * FROM delivery_job ORDER BY createdAtEpochMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<DeliveryJobEntity>>

    @Query("SELECT * FROM delivery_job WHERE canonicalActivityId = :canonicalId ORDER BY createdAtEpochMs DESC")
    fun observeByCanonical(canonicalId: Long): Flow<List<DeliveryJobEntity>>

    @Query("SELECT * FROM delivery_job WHERE canonicalActivityId = :canonicalId")
    suspend fun findByCanonical(canonicalId: Long): List<DeliveryJobEntity>

    @Query("SELECT * FROM delivery_job WHERE sourceVariantId = :variantId")
    suspend fun findBySourceVariant(variantId: Long): List<DeliveryJobEntity>

    @Query("SELECT * FROM delivery_job WHERE destinationAccountId = :accountId")
    suspend fun findByDestinationAccount(accountId: Long): List<DeliveryJobEntity>

    @Query("SELECT * FROM delivery_job WHERE requestedByRuleId = :ruleId")
    suspend fun findByRequestedRule(ruleId: Long): List<DeliveryJobEntity>

    @Query("SELECT COUNT(*) FROM delivery_job WHERE status = :status")
    fun observeCountByStatus(status: DeliveryJobStatus): Flow<Int>

    @Query("SELECT * FROM delivery_job WHERE status = :status")
    suspend fun findByStatus(status: DeliveryJobStatus): List<DeliveryJobEntity>

    @Query("SELECT * FROM delivery_job WHERE id = :id")
    suspend fun getById(id: Long): DeliveryJobEntity?

    @Query(
        """
        SELECT * FROM delivery_job
        WHERE status = 'PENDING'
           OR (status = 'RETRY_WAIT' AND (nextRetryAtEpochMs IS NULL OR nextRetryAtEpochMs <= :now))
           OR (status = 'WAITING_REMOTE_PROCESS' AND (nextRetryAtEpochMs IS NULL OR nextRetryAtEpochMs <= :now))
        ORDER BY createdAtEpochMs ASC
        LIMIT :limit
        """,
    )
    suspend fun findRunnable(now: Long, limit: Int = 20): List<DeliveryJobEntity>

    @Query(
        """
        SELECT COUNT(*) FROM delivery_job
        WHERE status = 'PENDING'
           OR (status = 'RETRY_WAIT' AND (nextRetryAtEpochMs IS NULL OR nextRetryAtEpochMs <= :now))
           OR (status = 'WAITING_REMOTE_PROCESS' AND (nextRetryAtEpochMs IS NULL OR nextRetryAtEpochMs <= :now))
        """,
    )
    suspend fun countRunnable(now: Long): Int

    @Query(
        "SELECT MIN(nextRetryAtEpochMs) FROM delivery_job " +
            "WHERE status = 'WAITING_REMOTE_PROCESS' OR status = 'RETRY_WAIT'",
    )
    suspend fun findNextScheduledAt(): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: DeliveryJobEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<DeliveryJobEntity>): List<Long>

    @Update
    suspend fun update(entity: DeliveryJobEntity)

    @Query("UPDATE delivery_job SET status = 'DISABLED', updatedAtEpochMs = :now WHERE destinationAccountId = :accountId AND status != 'SUCCEEDED' AND status != 'DUPLICATE_REMOTE'")
    suspend fun disableForAccount(accountId: Long, now: Long = System.currentTimeMillis())

    @Query(
        """
        UPDATE delivery_job
        SET status = 'PENDING', userActionUrl = NULL,
            errorCode = NULL, errorMessage = NULL,
            nextRetryAtEpochMs = NULL, updatedAtEpochMs = :now
        WHERE status = 'WAITING_USER'
          AND destinationAccountId IN (
              SELECT id FROM platform_account WHERE platform = :platform
          )
        """,
    )
    suspend fun resumeJobsForForeground(
        platform: Platform,
        now: Long = System.currentTimeMillis(),
    )

    @Query(
        """
        UPDATE delivery_job
        SET status = 'PENDING', updatedAtEpochMs = :now
        WHERE status = 'UPLOADING' AND updatedAtEpochMs < :staleBefore
        """,
    )
    suspend fun resetStaleUploading(
        now: Long,
        staleBefore: Long = now - 10L * 60 * 1000,
    )

    @Query("DELETE FROM delivery_job WHERE canonicalActivityId = :canonicalId")
    suspend fun deleteByCanonical(canonicalId: Long)

    @Query("DELETE FROM delivery_job WHERE destinationAccountId = :accountId")
    suspend fun deleteByDestinationAccount(accountId: Long)

    @Query("DELETE FROM delivery_job WHERE requestedByRuleId = :ruleId")
    suspend fun deleteByRequestedRule(ruleId: Long)

    @Query("DELETE FROM delivery_job WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("""
        UPDATE delivery_job 
        SET status = 'PENDING', 
            nextRetryAtEpochMs = NULL, 
            errorCode = NULL, 
            errorMessage = NULL, 
            updatedAtEpochMs = :now 
        WHERE id IN (:ids)
    """)
    suspend fun retryByIds(ids: List<Long>, now: Long = System.currentTimeMillis())
}

@Dao
interface SyncRuleDao {
    @Query("SELECT * FROM sync_rule")
    fun observeAll(): Flow<List<SyncRuleEntity>>

    @Query("SELECT * FROM sync_rule WHERE enabled = 1")
    suspend fun getEnabled(): List<SyncRuleEntity>

    @Query("SELECT * FROM sync_rule ORDER BY sourcePlatform, destinationAccountId")
    suspend fun getAll(): List<SyncRuleEntity>

    @Query(
        "SELECT * FROM sync_rule WHERE sourcePlatform = :source AND destinationAccountId = :destinationId LIMIT 1",
    )
    suspend fun find(source: Platform, destinationId: Long): SyncRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SyncRuleEntity): Long

    @Query("DELETE FROM sync_rule WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM sync_rule WHERE sourcePlatform = :source AND destinationAccountId = :destinationId")
    suspend fun delete(source: Platform, destinationId: Long)

    @Query("DELETE FROM sync_rule WHERE destinationAccountId = :destinationId")
    suspend fun deleteByDestination(destinationId: Long)

    @Query("DELETE FROM sync_rule WHERE sourcePlatform = :source")
    suspend fun deleteBySource(source: Platform)
}

@Dao
interface ImportJobDao {
    @Query("SELECT * FROM import_job ORDER BY createdAtEpochMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 5): Flow<List<ImportJobEntity>>

    @Insert
    suspend fun insert(entity: ImportJobEntity): Long

    @Update
    suspend fun update(entity: ImportJobEntity)

    @Query("SELECT * FROM import_job WHERE id = :id")
    suspend fun getById(id: Long): ImportJobEntity?

    @Query("SELECT * FROM import_job WHERE canonicalActivityId = :canonicalId")
    suspend fun findByCanonical(canonicalId: Long): List<ImportJobEntity>

    @Query("DELETE FROM import_job WHERE canonicalActivityId = :canonicalId")
    suspend fun deleteByCanonical(canonicalId: Long)
}

@Dao
interface AppSettingsDao {
    @Query("SELECT * FROM app_settings WHERE id = 1")
    fun observe(): Flow<AppSettingsEntity?>

    @Query("SELECT * FROM app_settings WHERE id = 1")
    suspend fun get(): AppSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AppSettingsEntity)
}
