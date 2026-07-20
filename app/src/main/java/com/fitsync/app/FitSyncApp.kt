package com.fitsync.app

import android.app.Application
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
import com.fitsync.app.web.GarminUploadFilePreparer
import com.fitsync.app.web.WebSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FitSyncApp : Application() {

    lateinit var database: FitSyncDatabase
        private set
    lateinit var fitFileStore: FitFileStore
        private set
    lateinit var fitEngine: FitEngine
        private set
    lateinit var garminUploadFilePreparer: GarminUploadFilePreparer
        private set
    lateinit var importService: ImportService
        private set
    lateinit var activityDeletionService: ActivityDeletionService
        private set
    lateinit var activityMergeService: ActivityMergeService
        private set
    lateinit var fitStorageJanitor: FitStorageJanitor
        private set
    lateinit var webSessionManager: WebSessionManager
        private set
    lateinit var connectorRegistry: ConnectorRegistry
        private set
    lateinit var platformAccountRepository: PlatformAccountRepository
        private set
    lateinit var syncCoordinator: SyncCoordinator
        private set
    lateinit var syncNotificationManager: SyncNotificationManager
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        database = FitSyncDatabase.getInstance(this)
        fitFileStore = FitFileStore(this)
        fitEngine = FitEngine()
        garminUploadFilePreparer = GarminUploadFilePreparer(this, fitEngine)
        val activityMutationGate = ActivityMutationGate()
        webSessionManager = WebSessionManager(SecureSessionStore(this)).also { it.prepareCookies() }
        importService = ImportService(
            db = database,
            fileStore = fitFileStore,
            fitEngine = fitEngine,
            mutationGate = activityMutationGate,
        )
        connectorRegistry = ConnectorRegistry(
            db = database,
            fileStore = fitFileStore,
            sessionManager = webSessionManager,
        )
        syncNotificationManager = SyncNotificationManager(this).also { it.createChannel() }
        activityDeletionService = ActivityDeletionService(
            db = database,
            fileStore = fitFileStore,
            notifications = syncNotificationManager,
            mutationGate = activityMutationGate,
        )
        activityMergeService = ActivityMergeService(
            db = database,
            notifications = syncNotificationManager,
            mutationGate = activityMutationGate,
        )
        fitStorageJanitor = FitStorageJanitor(
            db = database,
            fileStore = fitFileStore,
            mutationGate = activityMutationGate,
        )
        val planner = DeliveryPlanner(database)
        syncCoordinator = SyncCoordinator(
            db = database,
            importService = importService,
            connectors = connectorRegistry,
            planner = planner,
            notifications = syncNotificationManager,
            mutationGate = activityMutationGate,
            storageJanitor = fitStorageJanitor,
        )
        platformAccountRepository = PlatformAccountRepository(
            db = database,
            connectors = connectorRegistry,
            sessions = webSessionManager,
        )
        applicationScope.launch {
            try {
                importService.reparseStoredActivitiesIfNeeded()
            } catch (_: Exception) {
                // A failed metadata refresh must not prevent background sync scheduling.
            }
            try {
                fitStorageJanitor.run()
            } catch (_: Exception) {
                // 启动清理失败不阻塞后台同步
            }
            val settings = database.appSettingsDao().get()
            SyncScheduler.schedule(
                this@FitSyncApp,
                settings?.autoSyncEnabled != false,
                settings?.syncIntervalMinutes ?: 15,
            )
        }
    }

    companion object {
        lateinit var instance: FitSyncApp
            private set
    }
}
