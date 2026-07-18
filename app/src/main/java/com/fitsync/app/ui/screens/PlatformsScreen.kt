package com.fitsync.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fitsync.app.FitSyncApp
import com.fitsync.app.connectors.PlatformCapabilityRegistry
import com.fitsync.app.domain.model.AccountStatus
import com.fitsync.app.domain.model.ConnectorExecution
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.ui.components.AppTopBar
import com.fitsync.app.ui.components.PlatformAvatar
import com.fitsync.app.ui.components.StatusPill
import com.fitsync.app.ui.components.StatusTone
import com.fitsync.app.ui.components.accountStatusText
import com.fitsync.app.ui.components.accountStatusTone
import com.fitsync.app.ui.components.executionText
import com.fitsync.app.ui.components.platformColor
import com.fitsync.app.web.PlatformWebActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PlatformsScreen() {
    val app = FitSyncApp.instance
    val accounts by app.database.platformAccountDao().observeAll().collectAsState(initial = emptyList())
    val bound = accounts.filter { it.status != AccountStatus.DISABLED }.associateBy { it.platform }
    val activeCount = accounts.count {
        it.status == AccountStatus.ACTIVE || it.status == AccountStatus.EXPERIMENTAL
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busyPlatform by remember { mutableStateOf<Platform?>(null) }
    var pendingDisconnect by remember { mutableStateOf<Platform?>(null) }
    var igpsportLogin by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val knownPlatforms = PlatformCapabilityRegistry.allKnownPlatforms()
        .sortedBy { if (bound.containsKey(it)) 0 else 1 }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(title = "平台连接", subtitle = "连接状态与凭据仅保存在本机")
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF151A22)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Column(Modifier.fillMaxWidth().padding(18.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                        Box(
                            modifier = Modifier.size(46.dp).clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Default.Security, contentDescription = null, tint = Color(0xFFFFB067)) }
                        Column(Modifier.weight(1f)) {
                            Text(
                                "连接进度 $activeCount / ${knownPlatforms.size}",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                            )
                            Text(
                                "登录态由 WebView 与 Android Keystore 安全托管",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.62f),
                            )
                        }
                    }
                        LinearProgressIndicator(
                            progress = {
                                if (knownPlatforms.isEmpty()) 0f else activeCount.toFloat() / knownPlatforms.size
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).clip(CircleShape),
                            color = Color(0xFFF97316),
                            trackColor = Color.White.copy(alpha = 0.12f),
                        )
                    }
                }
            }
            items(knownPlatforms, key = { it.name }) { platform ->
                val caps = PlatformCapabilityRegistry.capabilities(platform)
                val account = bound[platform]
                val isBusy = busyPlatform == platform
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Column(
                        Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PlatformAvatar(platform, 48.dp)
                            Column(Modifier.weight(1f)) {
                                Text(platform.displayName, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    account?.lastSyncAtEpochMs?.let { "上次同步 ${dateFormat.format(Date(it))}" }
                                        ?: if (account == null) "尚未在本机登录" else "等待首次同步",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (account != null) {
                                StatusPill(accountStatusText(account.status), accountStatusTone(account.status))
                            } else {
                                StatusPill("未连接", StatusTone.NEUTRAL)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CapabilityCell("拉取 FIT", caps.downloadFitMode, Icons.Default.CloudDownload, Modifier.weight(1f))
                            CapabilityCell("上传 FIT", caps.uploadFitMode, Icons.Default.CloudUpload, Modifier.weight(1f))
                        }
                        if (caps.experimental) {
                            Text(
                                "该连接使用平台网页接口，页面变化时会自动暂停并要求确认。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        if (account?.needsUserAction == true) {
                            Text(
                                "登录状态或网页流程需要处理，请打开平台完成验证。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = {
                                    if (platform == Platform.IGPSPORT) {
                                        igpsportLogin = true
                                    } else {
                                        context.startActivity(PlatformWebActivity.createIntent(context, platform))
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !isBusy,
                            ) {
                                Icon(
                                    if (account == null) Icons.AutoMirrored.Filled.Login else Icons.Default.OpenInBrowser,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(if (account == null) "登录平台" else "打开平台", modifier = Modifier.padding(start = 6.dp))
                            }
                            if (account != null) {
                                OutlinedButton(
                                    onClick = {
                                        busyPlatform = platform
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) {
                                                runCatching { app.platformAccountRepository.validateAndPersist(platform) }
                                            }
                                            busyPlatform = null
                                            snackbarHostState.showSnackbar(
                                                result.fold(
                                                    onSuccess = { "${platform.displayName} 登录状态已更新" },
                                                    onFailure = { "验证失败：${it.message ?: "请稍后重试"}" },
                                                ),
                                            )
                                        }
                                    },
                                    modifier = Modifier.size(48.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                                    enabled = !isBusy,
                                ) {
                                    if (isBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    else Icon(Icons.Default.Refresh, contentDescription = "重新验证", modifier = Modifier.size(18.dp))
                                }
                                TextButton(
                                    onClick = { pendingDisconnect = platform },
                                    modifier = Modifier.height(48.dp),
                                    enabled = !isBusy,
                                ) { Text("断开", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
    }

    pendingDisconnect?.let { platform ->
        AlertDialog(
            onDismissRequest = { pendingDisconnect = null },
            title = { Text("断开 ${platform.displayName}？") },
            text = { Text("登录状态和未完成任务将停止，但已经导入的本地活动不会删除。") },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDisconnect = null
                        busyPlatform = platform
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { app.platformAccountRepository.disconnect(platform) }
                            }
                            busyPlatform = null
                            snackbarHostState.showSnackbar(
                                result.fold(
                                    onSuccess = { "已断开 ${platform.displayName}" },
                                    onFailure = { "断开失败：${it.message ?: "请稍后重试"}" },
                                ),
                            )
                        }
                    },
                ) { Text("确认断开") }
            },
            dismissButton = { TextButton(onClick = { pendingDisconnect = null }) { Text("取消") } },
        )
    }

    if (igpsportLogin) {
        IgpsportLoginDialog(
            platform = Platform.IGPSPORT,
            onDismiss = { igpsportLogin = false },
            onSubmit = { u, p ->
                app.platformAccountRepository.loginWithCredentials(Platform.IGPSPORT, u, p)
            },
            onResult = {
                igpsportLogin = false
                scope.launch {
                    snackbarHostState.showSnackbar("iGPSPORT 登录成功")
                }
            },
        )
    }
}

@Composable
private fun CapabilityCell(label: String, mode: ConnectorExecution, icon: ImageVector, modifier: Modifier = Modifier) {
    val active = mode != ConnectorExecution.UNSUPPORTED
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier.clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(executionText(mode), style = MaterialTheme.typography.labelLarge, color = color)
        }
    }
}
