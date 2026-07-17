package com.fitsync.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fitsync.app.FitSyncApp
import com.fitsync.app.data.db.entity.AppSettingsEntity
import com.fitsync.app.domain.model.FitStoragePolicy
import com.fitsync.app.sync.SyncScheduler
import com.fitsync.app.ui.components.AppTopBar
import com.fitsync.app.ui.components.StatusPill
import com.fitsync.app.ui.components.StatusTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen() {
    val app = FitSyncApp.instance
    val settings by app.database.appSettingsDao().observe().collectAsState(initial = null)
    val current = settings ?: AppSettingsEntity()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = androidx.compose.runtime.remember { SnackbarHostState() }

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
            AppTopBar(title = "偏好设置", subtitle = "自动化、存储与隐私")
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
            item {
                SettingsCard(Icons.Default.AutoAwesome, "自动同步", "Android 会根据网络和电量安排后台任务") {
                    SettingSwitchRow(
                        title = "后台增量同步",
                        subtitle = "目标周期 15 分钟，实际时间由系统决定",
                        checked = current.autoSyncEnabled,
                        onCheckedChange = { save(current.copy(autoSyncEnabled = it)) },
                    )
                    SettingSwitchRow(
                        title = "需要操作时通知",
                        subtitle = "登录失效、验证码或网页上传时提醒",
                        checked = current.notificationsEnabled,
                        onCheckedChange = { save(current.copy(notificationsEnabled = it)) },
                        icon = Icons.Default.NotificationsActive,
                    )
                }
            }
            item {
                SettingsCard(Icons.Default.History, "首次历史范围", "新绑定账号第一次拉取的时间跨度") {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(30, 90, 180, 365).forEach { days ->
                            FilterChip(
                                modifier = Modifier.height(44.dp),
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
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        OutlinedButton(
                            enabled = current.initialHistoryDays > 1,
                            onClick = { save(current.copy(initialHistoryDays = (current.initialHistoryDays - 30).coerceAtLeast(1))) },
                        ) { Text("−30") }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${current.initialHistoryDays} 天", style = MaterialTheme.typography.titleLarge)
                            Text("当前设置", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedButton(
                            enabled = current.initialHistoryDays < 3650,
                            onClick = { save(current.copy(initialHistoryDays = (current.initialHistoryDays + 30).coerceAtMost(3650))) },
                        ) { Text("+30") }
                    }
                    Text(
                        "后续增量同步固定使用 48 小时重叠窗口，避免平台延迟导致漏单。",
                        modifier = Modifier.padding(top = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                SettingsCard(Icons.Default.Storage, "原始 FIT 存储", "选择同步完成后的本地文件保留方式") {
                    StorageOption(
                        title = "长期保存",
                        subtitle = "保留所有原始 FIT，便于随时补发",
                        selected = current.storagePolicy == FitStoragePolicy.KEEP_FOREVER,
                        onClick = { save(current.copy(storagePolicy = FitStoragePolicy.KEEP_FOREVER)) },
                    )
                    StorageOption(
                        title = "成功 7 天后删除",
                        subtitle = "兼顾补发窗口和存储空间，推荐",
                        selected = current.storagePolicy == FitStoragePolicy.DELETE_AFTER_7_DAYS,
                        onClick = { save(current.copy(storagePolicy = FitStoragePolicy.DELETE_AFTER_7_DAYS)) },
                        recommended = true,
                    )
                    StorageOption(
                        title = "同步成功立即删除",
                        subtitle = "最节省空间，之后无法从本地重新上传",
                        selected = current.storagePolicy == FitStoragePolicy.DELETE_AFTER_SYNC,
                        onClick = { save(current.copy(storagePolicy = FitStoragePolicy.DELETE_AFTER_SYNC)) },
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { app.fitFileStore.cleanupTemporaryFiles() }
                                }
                                snackbarHostState.showSnackbar(
                                    result.fold(
                                        onSuccess = { "临时下载文件已清理" },
                                        onFailure = { "清理失败：${it.message ?: "请稍后重试"}" },
                                    ),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) {
                        Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("清理下载临时文件", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF151A22)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier.size(44.dp).clip(CircleShape)
                                .background(Color(0xFF22C55E).copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = Color(0xFF6EE7A0))
                        }
                        Column(Modifier.weight(1f)) {
                            Text("隐私优先的本地架构", style = MaterialTheme.typography.titleMedium, color = Color.White)
                            Text(
                                "FitSync 不保存平台账号密码，不使用自建服务器。Cookie、加密令牌、活动库与 FIT 文件都只存在应用沙箱，系统备份也已关闭。",
                                modifier = Modifier.padding(top = 4.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.66f),
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    "FitSync 0.1.0 · 本地优先五平台同步",
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
    }
}

@Composable
private fun SettingsCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier.size(38.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.padding(top = 12.dp), content = content)
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun StorageOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    recommended: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                if (recommended) StatusPill("推荐", StatusTone.SUCCESS)
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
