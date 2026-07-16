package com.fitsync.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busyPlatform by remember { mutableStateOf<Platform?>(null) }
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val knownPlatforms = PlatformCapabilityRegistry.allKnownPlatforms()

    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "平台账号", subtitle = "登录状态保存在本机，不保存账号密码")
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(
                            modifier = Modifier.size(46.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                        Column(Modifier.weight(1f)) {
                            Text("已连接 ${bound.size} / ${knownPlatforms.size}", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Cookie 保存在 WebView，必要令牌由 Android Keystore 加密。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
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
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
                                    context.startActivity(PlatformWebActivity.createIntent(context, platform))
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
                                        scope.launch(Dispatchers.IO) {
                                            runCatching { app.platformAccountRepository.validateAndPersist(platform) }
                                            withContext(Dispatchers.Main) { busyPlatform = null }
                                        }
                                    },
                                    enabled = !isBusy,
                                ) {
                                    if (isBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    else Icon(Icons.Default.Refresh, contentDescription = "重新验证", modifier = Modifier.size(18.dp))
                                }
                                TextButton(
                                    onClick = {
                                        busyPlatform = platform
                                        scope.launch(Dispatchers.IO) {
                                            runCatching { app.platformAccountRepository.disconnect(platform) }
                                            withContext(Dispatchers.Main) { busyPlatform = null }
                                        }
                                    },
                                    enabled = !isBusy,
                                ) { Text("断开", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }
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
