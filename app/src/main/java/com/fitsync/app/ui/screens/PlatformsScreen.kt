package com.fitsync.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import com.fitsync.app.connectors.ConnectorCapabilities
import com.fitsync.app.connectors.PlatformCapabilityRegistry
import com.fitsync.app.data.db.entity.PlatformAccountEntity
import com.fitsync.app.domain.model.AccountStatus
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.ui.components.FitCard
import com.fitsync.app.ui.components.FitSkeletonListRow
import com.fitsync.app.ui.components.FitTopBar
import com.fitsync.app.ui.components.PlatformAvatar
import com.fitsync.app.ui.components.StatusPill
import com.fitsync.app.ui.components.StatusTone
import com.fitsync.app.ui.components.accountStatusText
import com.fitsync.app.ui.components.accountStatusTone
import com.fitsync.app.ui.components.executionText
import com.fitsync.app.ui.theme.FitSpacing
import com.fitsync.app.ui.theme.FitType
import com.fitsync.app.web.PlatformWebActivity
import com.fitsync.app.web.PlatformWebConfigs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var expandedPlatform by remember { mutableStateOf<Platform?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val knownPlatforms = if (accountsState == null) {
        emptyList()
    } else {
        PlatformCapabilityRegistry.allKnownPlatforms().sortedBy { if (bound.containsKey(it)) 0 else 1 }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            FitTopBar(
                title = "平台账号",
                subtitle = "登录、验证或断开平台连接",
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
                if (accountsState == null) {
                    items(5, key = { "platform-skeleton-$it" }) {
                        FitSkeletonListRow()
                    }
                } else {
                    item(key = "accounts-summary") {
                        Text(
                            "已连接 ${bound.size} 个 · 可添加 ${knownPlatforms.size - bound.size} 个",
                            style = FitType.dataLabel,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(knownPlatforms, key = { it.name }) { platform ->
                        val account = bound[platform]
                        val caps = ConnectorCapabilities.deserialize(account?.capabilitySnapshot)
                            ?: PlatformCapabilityRegistry.capabilities(platform)
                        PlatformRow(
                            platform = platform,
                            account = account,
                            caps = caps,
                            busy = busyPlatform == platform,
                            expanded = expandedPlatform == platform,
                            dateFormat = dateFormat,
                            onToggle = {
                                expandedPlatform = if (expandedPlatform == platform) null else platform
                            },
                            onPrimaryAction = {
                                when {
                                    platform == Platform.COROS &&
                                        account != null && account.status != AccountStatus.AUTH_EXPIRED -> {
                                        context.startActivity(
                                            PlatformWebActivity.createIntent(
                                                context,
                                                platform,
                                                url = PlatformWebConfigs.get(platform).uploadUrl,
                                            ),
                                        )
                                    }
                                    platform == Platform.IGPSPORT || platform == Platform.COROS -> {
                                        credentialsLogin = platform
                                    }
                                    else -> context.startActivity(
                                        PlatformWebActivity.createIntent(context, platform),
                                    )
                                }
                            },
                            onValidate = {
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
                            onDisconnect = { pendingDisconnect = platform },
                        )
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(FitSpacing.lg),
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

/** 折叠式平台行：默认只有一行，点击才展开操作区。 */
@Composable
private fun PlatformRow(
    platform: Platform,
    account: PlatformAccountEntity?,
    caps: ConnectorCapabilities,
    busy: Boolean,
    expanded: Boolean,
    dateFormat: SimpleDateFormat,
    onToggle: () -> Unit,
    onPrimaryAction: () -> Unit,
    onValidate: () -> Unit,
    onDisconnect: () -> Unit,
) {
    FitCard(
        modifier = Modifier.animateContentSize(animationSpec = tween(durationMillis = 180)),
        contentPadding = PaddingValues(0.dp),
        onClick = onToggle,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = FitSpacing.rowPaddingH, vertical = FitSpacing.rowPaddingV),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FitSpacing.md),
        ) {
            PlatformAvatar(platform, 36.dp)
            Column(Modifier.weight(1f)) {
                Text(platform.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    account?.lastSyncAtEpochMs?.let { "上次同步 ${dateFormat.format(Date(it))}" }
                        ?: if (account == null) "尚未在本机登录" else "等待首次同步",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                StatusPill(
                    account?.let { accountStatusText(it.status) } ?: "未连接",
                    account?.let { accountStatusTone(it.status) } ?: StatusTone.NEUTRAL,
                    compact = true,
                )
            }
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(FitSpacing.iconMd),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .padding(start = FitSpacing.rowPaddingH, end = FitSpacing.rowPaddingH, bottom = FitSpacing.rowPaddingV),
                verticalArrangement = Arrangement.spacedBy(FitSpacing.sm),
            ) {
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
                        "网页登录：${if (account.status == AccountStatus.ACTIVE) "有效" else "需要重新验证"} · 活动自动拉取与上传",
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
                    modifier = Modifier.fillMaxWidth().padding(top = FitSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FitSpacing.sm),
                ) {
                    Button(
                        onClick = onPrimaryAction,
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    ) {
                        Icon(
                            if (account == null || (platform == Platform.COROS && account.status == AccountStatus.AUTH_EXPIRED)) {
                                Icons.AutoMirrored.Filled.Login
                            } else {
                                Icons.Default.OpenInBrowser
                            },
                            contentDescription = null,
                            modifier = Modifier.size(FitSpacing.iconSm),
                        )
                        Text(
                            when {
                                account == null -> "登录平台"
                                platform == Platform.COROS && account.status == AccountStatus.AUTH_EXPIRED -> "重新登录"
                                else -> "打开平台"
                            },
                            modifier = Modifier.padding(start = FitSpacing.xs),
                        )
                    }
                    if (account != null) {
                        OutlinedButton(
                            onClick = onValidate,
                            modifier = Modifier.size(48.dp),
                            contentPadding = PaddingValues(0.dp),
                            enabled = !busy,
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "重新验证",
                                modifier = Modifier.size(FitSpacing.iconSm),
                            )
                        }
                        TextButton(onClick = onDisconnect, enabled = !busy) {
                            Text("断开", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
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
