package com.fitsync.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fitsync.app.FitSyncApp
import com.fitsync.app.data.db.entity.AppSettingsEntity
import com.fitsync.app.domain.model.FitStoragePolicy
import com.fitsync.app.sync.SyncScheduler
import com.fitsync.app.ui.components.FitTopBar
import com.fitsync.app.ui.components.SettingsDivider
import com.fitsync.app.ui.theme.FitSpacing
import com.fitsync.app.ui.theme.FitType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun StorageSettingsScreen(onBack: () -> Unit) {
    val app = FitSyncApp.instance
    val settings by app.database.appSettingsDao().observe()
        .collectAsStateWithLifecycle(initialValue = null)
    val current = settings ?: AppSettingsEntity()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    fun save(next: AppSettingsEntity) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    app.database.appSettingsDao().upsert(next)
                    SyncScheduler.schedule(context, next.autoSyncEnabled, next.syncIntervalMinutes)
                }
            }
            result.exceptionOrNull()?.let {
                snackbarHostState.showSnackbar("保存失败：${it.message ?: "请稍后重试"}")
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            FitTopBar(
                title = "数据与存储",
                subtitle = "控制拉取范围与原始 FIT 保留",
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
                verticalArrangement = Arrangement.spacedBy(FitSpacing.md),
            ) {
                item {
                    SettingsInfoCard(Icons.Default.History, "首次历史范围", "新绑定账号第一次拉取的时间跨度") {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(FitSpacing.sm),
                        ) {
                            listOf(30, 90, 180, 365).forEach { days ->
                                FilterChip(
                                    modifier = Modifier.height(36.dp),
                                    selected = current.initialHistoryDays == days,
                                    onClick = { save(current.copy(initialHistoryDays = days)) },
                                    label = { Text("$days 天") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    ),
                                )
                            }
                        }
                        Text(
                            "当前设置 ${current.initialHistoryDays} 天",
                            modifier = Modifier.padding(top = FitSpacing.sm),
                            style = FitType.dataLabel,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "后续增量同步固定使用 48 小时重叠窗口，避免平台延迟导致漏单。",
                            modifier = Modifier.padding(top = FitSpacing.xs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item {
                    SettingsInfoCard(Icons.Default.Storage, "原始 FIT 存储", "清理文件不会删除活动记录与平台映射") {
                        StorageOption(
                            title = "长期保存",
                            subtitle = "永不自动删除原始 FIT，便于随时补发",
                            selected = current.storagePolicy == FitStoragePolicy.KEEP_FOREVER,
                            onClick = { save(current.copy(storagePolicy = FitStoragePolicy.KEEP_FOREVER)) },
                        )
                        StorageOption(
                            title = "成功 7 天后删除",
                            subtitle = "无未完成任务并满 7 天后清理原始文件",
                            selected = current.storagePolicy == FitStoragePolicy.DELETE_AFTER_7_DAYS,
                            onClick = { save(current.copy(storagePolicy = FitStoragePolicy.DELETE_AFTER_7_DAYS)) },
                            recommended = true,
                        )
                        StorageOption(
                            title = "同步成功后删除",
                            subtitle = "成功投递且无未完成任务后立即清理",
                            selected = current.storagePolicy == FitStoragePolicy.DELETE_AFTER_SYNC,
                            onClick = { save(current.copy(storagePolicy = FitStoragePolicy.DELETE_AFTER_SYNC)) },
                        )
                        SettingsDivider(startInset = FitSpacing.sm)
                        Button(
                            onClick = {
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching {
                                            app.fitFileStore.cleanupTemporaryFiles()
                                            app.osMapBootstrap.clearTileCache()
                                            // 放最后：runCatching 的结果要保留 janitor 的统计
                                            app.fitStorageJanitor.run()
                                        }
                                    }
                                    snackbarHostState.showSnackbar(
                                        result.fold(
                                            onSuccess = { janitor ->
                                                "已按设置清理 ${janitor.deletedFiles} 个原始文件，并清空地图缓存"
                                            },
                                            onFailure = { "清理失败：${it.message ?: "请稍后重试"}" },
                                        ),
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = FitSpacing.md),
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(FitSpacing.iconSm))
                            Text("立即清理文件", modifier = Modifier.padding(start = FitSpacing.sm))
                        }
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(FitSpacing.lg),
        )
    }
}
