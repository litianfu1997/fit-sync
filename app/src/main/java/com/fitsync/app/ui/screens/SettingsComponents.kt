package com.fitsync.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Troubleshoot
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.fitsync.app.connectors.GarminAuthDiagnostics
import com.fitsync.app.sync.BackgroundRunGuard
import com.fitsync.app.sync.BackgroundRunStatus
import com.fitsync.app.ui.components.FitCard
import com.fitsync.app.ui.components.SettingsDivider
import com.fitsync.app.ui.components.SettingsRow
import com.fitsync.app.ui.components.StatusPill
import com.fitsync.app.ui.components.StatusTone
import com.fitsync.app.ui.theme.FitSpacing

/** 设置页信息卡：标题行 + 正文内容，图标为 20dp 裸线性图标。 */
@Composable
internal fun SettingsInfoCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    FitCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FitSpacing.md),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(FitSpacing.iconMd),
            )
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(Modifier.padding(top = FitSpacing.md), content = content)
    }
}

/** 存储策略选项：保留 radio 语义，视觉并入设置行体系。 */
@Composable
internal fun StorageOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    recommended: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = FitSpacing.sm, vertical = FitSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FitSpacing.sm),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FitSpacing.sm),
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (recommended) StatusPill("推荐", StatusTone.SUCCESS, compact = true)
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 后台运行保护：状态行用 SettingsRow(trailing = StatusPill) 表达。 */
@Composable
internal fun BackgroundProtectionCard(
    status: BackgroundRunStatus,
    onAllowBackground: () -> Unit,
    onOpenAppDetails: () -> Unit,
    onOpenNotifications: () -> Unit,
) {
    FitCard(contentPadding = PaddingValues(0.dp)) {
        SettingsRow(
            title = "电池优化",
            subtitle = if (status.batteryOptimizationIgnored) "已允许无限制" else "受系统省电限制",
            checked = null,
            trailing = {
                StatusPill(
                    if (status.batteryOptimizationIgnored) "正常" else "需检查",
                    if (status.batteryOptimizationIgnored) StatusTone.SUCCESS else StatusTone.WARNING,
                )
            },
        )
        SettingsDivider()
        SettingsRow(
            title = "后台运行",
            subtitle = if (status.isBackgroundRestricted) "已被系统限制后台" else "正常",
            trailing = {
                StatusPill(
                    if (status.isBackgroundRestricted) "需检查" else "正常",
                    if (status.isBackgroundRestricted) StatusTone.WARNING else StatusTone.SUCCESS,
                )
            },
        )
        SettingsDivider()
        SettingsRow(
            title = "通知权限",
            subtitle = if (status.notificationsGranted) "已开启" else "未开启",
            trailing = {
                StatusPill(
                    if (status.notificationsGranted) "已开启" else "未开启",
                    if (status.notificationsGranted) StatusTone.SUCCESS else StatusTone.WARNING,
                )
            },
        )
        Column(Modifier.padding(FitSpacing.cardPadding)) {
            Text(
                BackgroundRunGuard.oemSummary(status.brand),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(
                modifier = Modifier.padding(top = FitSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(FitSpacing.xs),
            ) {
                BackgroundRunGuard.oemChecklist(status.brand).forEach { line ->
                    Text(
                        "· $line",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(
                onClick = onAllowBackground,
                modifier = Modifier.fillMaxWidth().padding(top = FitSpacing.md),
            ) {
                Text(if (status.needsAttention) "允许后台运行" else "检查系统设置")
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = FitSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(FitSpacing.sm),
            ) {
                OutlinedButton(onClick = onOpenAppDetails, modifier = Modifier.weight(1f)) {
                    Text("应用详情")
                }
                if (!status.notificationsGranted) {
                    OutlinedButton(onClick = onOpenNotifications, modifier = Modifier.weight(1f)) {
                        Text("通知设置")
                    }
                }
            }
        }
    }
}

/** Garmin 登录诊断：默认折叠，需要时才展开等宽文本段。 */
@Composable
internal fun GarminDiagnosticsCard() {
    val clipboard = LocalClipboardManager.current
    var events by remember { mutableStateOf(GarminAuthDiagnostics.snapshot()) }
    var expanded by remember { mutableStateOf(false) }
    FitCard(
        modifier = Modifier.animateContentSize(animationSpec = tween(durationMillis = 180)),
        contentPadding = PaddingValues(0.dp),
    ) {
        SettingsRow(
            title = "Garmin 登录诊断",
            subtitle = "记录后台判定「登录失效」的真实原因，便于反馈",
            icon = Icons.Default.Troubleshoot,
            onClick = { expanded = !expanded },
            showChevron = false,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (events.isNotEmpty()) {
                        Text(
                            "${events.size} 条",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "收起" else "查看最近记录",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(FitSpacing.iconMd),
                    )
                }
            },
        )
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(start = FitSpacing.rowPaddingH, end = FitSpacing.rowPaddingH, bottom = FitSpacing.md)) {
                if (events.isEmpty()) {
                    Text(
                        "暂无事件。下次同步触发 Garmin 登录判定后会出现在这里。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "共 ${events.size} 条（仅本机记录，不含敏感凭据）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = FitSpacing.xs),
                    )
                    events.forEach { ev ->
                        Text(
                            formatGarminDiagEvent(ev),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                }
                Row(
                    modifier = Modifier.padding(top = FitSpacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(FitSpacing.sm),
                ) {
                    OutlinedButton(onClick = { events = GarminAuthDiagnostics.snapshot() }) { Text("刷新") }
                    OutlinedButton(
                        enabled = events.isNotEmpty(),
                        onClick = {
                            clipboard.setText(AnnotatedString(events.joinToString("\n") { formatGarminDiagEvent(it) }))
                        },
                    ) { Text("复制") }
                    OutlinedButton(
                        enabled = events.isNotEmpty(),
                        onClick = {
                            GarminAuthDiagnostics.clear()
                            events = emptyList()
                        },
                    ) { Text("清除") }
                }
            }
        }
    }
}

private fun formatGarminDiagEvent(ev: GarminAuthDiagnostics.Event): String {
    val time = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
        .format(java.util.Date(ev.timestampEpochMs))
    val code = ev.httpCode?.let { " code=$it" }.orEmpty()
    val src = ev.cookieSource.takeIf { it.isNotBlank() }?.let { " src=$it" }.orEmpty()
    val jwt = if (ev.setCookieHasJwtWeb) " setCookie=JWT" else ""
    val loc = ev.locationHost?.takeIf { it.isNotBlank() }?.let { " loc=$it" }.orEmpty()
    val acct = if (ev.hadPersistedAccount) " hadAccount" else ""
    return "$time ${ev.operation}/${ev.phase}=${ev.outcome}$code$src$jwt$loc$acct"
}
