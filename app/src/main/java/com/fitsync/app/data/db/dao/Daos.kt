package com.fitsync.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.fitsync.app.data.db.entity.ActivityVariantEntity
import com.fitsync.app.data.db.entity.AppSettingsEntity
import com.fitsync.app.data.db.entity.CanonicalActivityEntity
import com.fitsync.app.data.db.entity.DeliveryJobEntity
import com.fitsync.app.data.db.entity.ImportJobEntity
import com.fitsync.app.data.db.entity.PlatformAccountEntity
import com.fitsync.app.data.db.entity.PlatformCopyEntity
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
    @Query(
        """
        SELECT activity.id,
               activity.sportType,
               activity.startTimeEpochMs,
               activity.endTimeEpochMs,
               activity.elapsedSeconds,
               activity.movingSeconds,
               activity.distanceMeters,
               activity.elevationGain,
               activity.avgHeartRate,
               activity.title,
               activity.activityFingerprint,
               activity.dedupStatus,
               activity.primaryVariantId,
               activity.createdAtEpochMs
        FROM canonical_activity AS activity
        ORDER BY COALESCE(
                     (
                         SELECT MAX(imported_at.createdAtEpochMs)
                         FROM import_job AS imported_at
                         WHERE imported_at.canonicalActivityId = activity.id
                     ),
                     activity.createdAtEpochMs
                 ) DESC,
                 activity.id DESC
        """,
    )
    fun observeAll(): Flow<List<CanonicalActivityEntity>>

    @Query("SELECT * FROM canonical_activity ORDER BY startTimeEpochMs DESC")
    suspend fun getAll(): List<CanonicalActivityEntity>

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

    @Query("SELECT * FROM activity_variant WHERE canonicalActivityId = :canonicalId")
    fun observeByCanonical(canonicalId: Long): Flow<List<ActivityVariantEntity>>

    @Query("SELECT * FROM activity_variant WHERE id = :id")
    suspend fun getById(id: Long): ActivityVariantEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ActivityVariantEntity): Long

    @Update
    suspend fun update(entity: ActivityVariantEntity)

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

    @Query("SELECT * FROM platform_copy WHERE canonicalActivityId = :canonicalId")
    suspend fun findByCanonical(canonicalId: Long): List<PlatformCopyEntity>

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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: PlatformCopyEntity): Long

    @Query("DELETE FROM platform_copy WHERE canonicalActivityId = :canonicalId")
    suspend fun deleteByCanonical(canonicalId: Long)
}

@Dao
interface DeliveryJobDao {
    @Query("SELECT * FROM delivery_job ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<DeliveryJobEntity>>

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

    @Query("SELECT * FROM delivery_job WHERE status = 'WAITING_USER' ORDER BY createdAtEpochMs DESC")
    fun observeWaitingForUser(): Flow<List<DeliveryJobEntity>>

    @Query("SELECT * FROM delivery_job ORDER BY createdAtEpochMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<DeliveryJobEntity>>

    @Query("SELECT * FROM delivery_job WHERE canonicalActivityId = :canonicalId ORDER BY createdAtEpochMs DESC")
    fun observeByCanonical(canonicalId: Long): Flow<List<DeliveryJobEntity>>

    @Query("SELECT * FROM delivery_job WHERE canonicalActivityId = :canonicalId")
    suspend fun findByCanonical(canonicalId: Long): List<DeliveryJobEntity>

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
        """,
    )
    suspend fun countRunnable(now: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: DeliveryJobEntity): Long

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
}

@Dao
interface ImportJobDao {
    @Query("SELECT * FROM import_job ORDER BY createdAtEpochMs DESC LIMIT 50")
    fun observeRecent(): Flow<List<ImportJobEntity>>

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
