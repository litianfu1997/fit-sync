package com.fitsync.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fitsync.app.FitSyncApp
import com.fitsync.app.sync.BackgroundRunGuard
import com.fitsync.app.ui.components.FitTopBar
import com.fitsync.app.ui.theme.FitSpacing

@Composable
fun BackgroundSettingsScreen(onBack: () -> Unit) {
    val settings by FitSyncApp.instance.database.appSettingsDao().observe()
        .collectAsStateWithLifecycle(initialValue = null)
    val context = LocalContext.current
    val guard = remember(context) { BackgroundRunGuard(context.applicationContext) }
    var resumeTick by remember { mutableIntStateOf(0) }
    val status = remember(resumeTick, settings?.autoSyncEnabled) {
        guard.evaluate(settings?.autoSyncEnabled != false)
    }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(Modifier.fillMaxSize()) {
        FitTopBar(
            title = "后台运行",
            subtitle = "确保系统不会中断同步",
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = FitSpacing.screenGutter,
                top = FitSpacing.sm,
                end = FitSpacing.screenGutter,
                bottom = FitSpacing.xxl,
            ),
        ) {
            item {
                BackgroundProtectionCard(
                    status = status,
                    onAllowBackground = {
                        guard.openBackgroundSettings(preferBatteryRequest = true)
                        resumeTick++
                    },
                    onOpenAppDetails = {
                        guard.openAppDetails()
                        resumeTick++
                    },
                    onOpenNotifications = {
                        guard.openNotificationSettings()
                        resumeTick++
                    },
                )
            }
        }
    }
}
