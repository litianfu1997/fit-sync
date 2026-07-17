package com.fitsync.app

import android.app.Application
import com.fitsync.app.data.db.FitSyncDatabase
import com.fitsync.app.data.file.FitFileStore
import com.fitsync.app.data.repo.ActivityDeletionService
import com.fitsync.app.data.repo.ActivityMutationGate
import com.fitsync.app.data.repo.ImportService
import com.fitsync.app.data.repo.PlatformAccountRepository
import com.fitsync.app.connectors.ConnectorRegistry
import com.fitsync.app.fit.FitEngine
import com.fitsync.app.notification.SyncNotificationManager
import com.fitsync.app.security.SecureSessionStore
import com.fitsync.app.sync.DeliveryPlanner
import com.fitsync.app.sync.SyncCoordinator
import com.fitsync.app.sync.SyncScheduler
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
    lateinit var importService: ImportService
        private set
    lateinit var activityDeletionService: ActivityDeletionService
        private set
    lateinit var webSessionManager: WebSessionManager
        private set
    lateinit var connectorRegistry: ConnectorRegistry
        private set
    lateinit var platformAccountRepository: PlatformAccountRepository
        private set
    lateinit var syncCoordinator: SyncCoordinator
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        database = FitSyncDatabase.getInstance(this)
        fitFileStore = FitFileStore(this)
        val activityMutationGate = ActivityMutationGate()
        webSessionManager = WebSessionManager(SecureSessionStore(this)).also { it.prepareCookies() }
        importService = ImportService(
            db = database,
            fileStore = fitFileStore,
            fitEngine = FitEngine(),
            mutationGate = activityMutationGate,
        )
        connectorRegistry = ConnectorRegistry(
            db = database,
            fileStore = fitFileStore,
            sessionManager = webSessionManager,
        )
        val notifications = SyncNotificationManager(this).also { it.createChannel() }
        activityDeletionService = ActivityDeletionService(
            db = database,
            fileStore = fitFileStore,
            notifications = notifications,
            mutationGate = activityMutationGate,
        )
        val planner = DeliveryPlanner(database)
        syncCoordinator = SyncCoordinator(
            db = database,
            importService = importService,
            connectors = connectorRegistry,
            planner = planner,
            notifications = notifications,
            mutationGate = activityMutationGate,
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
