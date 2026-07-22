package com.fitsync.app

import android.app.Application
import android.os.Trace
import androidx.work.Configuration
import com.fitsync.app.data.db.FitSyncDatabase
import com.fitsync.app.data.file.FitFileStore
import com.fitsync.app.data.repo.ActivityDeletionService
import com.fitsync.app.data.repo.ActivityMergeService
import com.fitsync.app.data.repo.ActivityMutationGate
import com.fitsync.app.data.repo.FitStorageJanitor
import com.fitsync.app.data.repo.ImportService
import com.fitsync.app.data.repo.PlatformAccountRepository
import com.fitsync.app.connectors.ConnectorRegistry
import com.fitsync.app.fit.FitEngine
import com.fitsync.app.notification.SyncNotificationManager
import com.fitsync.app.security.SecureSessionStore
import com.fitsync.app.sync.DeliveryPlanner
import com.fitsync.app.sync.SyncCoordinator
import com.fitsync.app.sync.SyncScheduler
import com.fitsync.app.sync.MaintenanceScheduler
import com.fitsync.app.web.GarminUploadFilePreparer
import com.fitsync.app.web.WebSessionManager
import com.fitsync.app.strava.StravaOAuthService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class FitSyncApp : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    // Kotlin 的默认 lazy 使用同步锁，首次访问时只会构造一次，后台 Worker 与界面可安全共享。
    val database: FitSyncDatabase by lazy { FitSyncDatabase.getInstance(this) }
    val fitFileStore: FitFileStore by lazy { FitFileStore(this) }
    val fitEngine: FitEngine by lazy { FitEngine() }
    val garminUploadFilePreparer: GarminUploadFilePreparer by lazy {
        GarminUploadFilePreparer(this, fitEngine)
    }
    private val activityMutationGate: ActivityMutationGate by lazy { ActivityMutationGate() }
    val webSessionManager: WebSessionManager by lazy {
        WebSessionManager(SecureSessionStore(this)).also { it.prepareCookies() }
    }
    val stravaOAuthService: StravaOAuthService by lazy { StravaOAuthService(webSessionManager) }
    val importService: ImportService by lazy {
        ImportService(
            db = database,
            fileStore = fitFileStore,
            fitEngine = fitEngine,
            mutationGate = activityMutationGate,
        )
    }
    val connectorRegistry: ConnectorRegistry by lazy {
        ConnectorRegistry(
            db = database,
            fileStore = fitFileStore,
            sessionManager = webSessionManager,
            stravaOAuthService = stravaOAuthService,
        )
    }
    val syncNotificationManager: SyncNotificationManager by lazy {
        SyncNotificationManager(this).also { it.createChannel() }
    }
    private val planner: DeliveryPlanner by lazy { DeliveryPlanner(database) }
    val activityDeletionService: ActivityDeletionService by lazy {
        ActivityDeletionService(
            db = database,
            fileStore = fitFileStore,
            notifications = syncNotificationManager,
            mutationGate = activityMutationGate,
        )
    }
    val activityMergeService: ActivityMergeService by lazy {
        ActivityMergeService(
            db = database,
            notifications = syncNotificationManager,
            planner = planner,
            mutationGate = activityMutationGate,
        )
    }
    val fitStorageJanitor: FitStorageJanitor by lazy {
        FitStorageJanitor(
            db = database,
            fileStore = fitFileStore,
            mutationGate = activityMutationGate,
        )
    }
    val syncCoordinator: SyncCoordinator by lazy {
        SyncCoordinator(
            db = database,
            importService = importService,
            connectors = connectorRegistry,
            planner = planner,
            notifications = syncNotificationManager,
            mutationGate = activityMutationGate,
            storageJanitor = fitStorageJanitor,
        )
    }
    val platformAccountRepository: PlatformAccountRepository by lazy {
        PlatformAccountRepository(
            db = database,
            connectors = connectorRegistry,
            sessions = webSessionManager,
            stravaOAuthService = stravaOAuthService,
            notifications = syncNotificationManager,
        )
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val firstFrameHandled = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        Trace.beginSection("FitSync:app_onCreate")
        try {
            instance = this
        } finally {
            Trace.endSection()
        }
    }

    /** 首屏提交后再初始化后台调度，避免数据库、WorkManager 和维护任务进入启动关键路径。 */
    fun onFirstFrame() {
        if (!firstFrameHandled.compareAndSet(false, true)) return
        applicationScope.launch {
            runCatching { SyncScheduler.ensureScheduled(this@FitSyncApp) }
            MaintenanceScheduler.enqueue(this@FitSyncApp)
        }
    }

    companion object {
        lateinit var instance: FitSyncApp
            private set
    }
}
