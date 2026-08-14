package com.fitsync.app.ui.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fitsync.app.FitSyncApp
import com.fitsync.app.connectors.PlatformCapabilityRegistry
import com.fitsync.app.connectors.ConnectorCapabilities
import com.fitsync.app.data.db.entity.PlatformAccountEntity
import com.fitsync.app.domain.model.AccountStatus
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.ui.components.AppTopBar
import com.fitsync.app.ui.components.PlatformAvatar
import com.fitsync.app.ui.components.StatusPill
import com.fitsync.app.ui.components.StatusTone
import com.fitsync.app.ui.components.accountStatusText
import com.fitsync.app.ui.components.accountStatusTone
import com.fitsync.app.ui.components.executionText
import com.fitsync.app.web.PlatformWebActivity
import com.fitsync.app.web.PlatformWebConfigs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PlatformsScreen(onBack: () -> Unit = {}) {
    val app = FitSyncApp.instance
    val accountsState by app.database.platformAccountDao().observeAll()
        .collectAsStateWithLifecycle(initialValue = null)
    val accounts = accountsState.orEmpty()
    val bound = accounts.filter { it.status != AccountStatus.DISABLED }.associateBy { it.platform }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busyPlatform by remember { mutableStateOf<Platform?>(null) }
    var pendingDisconnect by remember { mutableStateOf<Platform?>(null) }
    var credentialsLogin by remember { mutableStateOf<Platform?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val knownPlatforms = if (accountsState == null) {
        emptyList()
    } else {
        PlatformCapabilityRegistry.allKnownPlatforms().sortedBy { if (bound.containsKey(it)) 0 else 1 }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = "平台账号",
                subtitle = "登录、验证或断开平台连接",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            if (accountsState == null) {
                item(key = "accounts-loading") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            } else {
                item(key = "accounts-summary") {
                    Text(
                        "已连接 ${bound.size} 个 · 可添加 ${knownPlatforms.size - bound.size} 个",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(knownPlatforms, key = { it.name }) { platform ->
                val account = bound[platform]
                val caps = ConnectorCapabilities.deserialize(account?.capabilitySnapshot)
                    ?: PlatformCapabilityRegistry.capabilities(platform)
                val isBusy = busyPlatform == platform
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Column(
                        Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PlatformAvatar(platform, 44.dp)
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
                        Text(
                            "拉取 ${executionText(caps.downloadFitMode)} · 上传 ${executionText(caps.uploadFitMode)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (caps.experimental && account != null) {
                            Text(
                                experimentalConnectorDescription(platform),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        if (platform == Platform.STRAVA && account != null) {
                            Text(
                                "网页登录：${if (account.status == AccountStatus.ACTIVE) "有效" else "需要重新验证"} · " +
                                    "活动自动拉取与上传",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                    if (platform == Platform.COROS &&
                                        account != null && account.status != AccountStatus.AUTH_EXPIRED
                                    ) {
                                        context.startActivity(
                                            PlatformWebActivity.createIntent(
                                                context,
                                                platform,
                                                url = PlatformWebConfigs.get(platform).uploadUrl,
                                            ),
                                        )
                                    } else if (platform == Platform.IGPSPORT || platform == Platform.COROS) {
                                        credentialsLogin = platform
                                    } else {
                                        context.startActivity(PlatformWebActivity.createIntent(context, platform))
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !isBusy,
                            ) {
                                Icon(
                                    if (account == null ||
                                        (platform == Platform.COROS && account.status == AccountStatus.AUTH_EXPIRED)
                                    ) Icons.AutoMirrored.Filled.Login else Icons.Default.OpenInBrowser,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    when {
                                        account == null -> "登录平台"
                                        platform == Platform.COROS && account.status == AccountStatus.AUTH_EXPIRED -> "重新登录"
                                        else -> "打开平台"
                                    },
                                    modifier = Modifier.padding(start = 6.dp),
                                )
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

    credentialsLogin?.let { p ->
        if (p == Platform.COROS) {
            CorosLoginDialog(
                platform = p,
                onDismiss = { credentialsLogin = null },
                onSubmit = { u, pw ->
                    app.platformAccountRepository.loginWithCredentials(p, u, pw)
                },
                onResult = {
                    credentialsLogin = null
                    scope.launch {
                        snackbarHostState.showSnackbar("${p.displayName} 登录成功")
                    }
                },
            )
        } else {
            IgpsportLoginDialog(
                platform = p,
                onDismiss = { credentialsLogin = null },
                onSubmit = { u, pw ->
                    app.platformAccountRepository.loginWithCredentials(p, u, pw)
                },
                onResult = {
                    credentialsLogin = null
                    scope.launch {
                        snackbarHostState.showSnackbar("${p.displayName} 登录成功")
                    }
                },
            )
        }
    }

}

private fun experimentalConnectorDescription(platform: Platform): String = when (platform) {
    Platform.STRAVA -> "不使用 Strava API；网页登录一次后，后台会从官网活动页自动拉取原始 FIT 并自动上传。"
    Platform.COROS, Platform.IGPSPORT ->
        "使用实验性后台 API；平台协议变化时会暂停同步并提示重新验证。"
    Platform.XINGZHE, Platform.GARMIN ->
        "该连接使用平台网页接口，页面变化时会自动暂停并要求确认。"
    else -> "该连接仍在实验阶段，平台能力变化时会暂停同步并提示处理。"
}
