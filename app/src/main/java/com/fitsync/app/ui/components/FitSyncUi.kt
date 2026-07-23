package com.fitsync.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fitsync.app.domain.model.AccountStatus
import com.fitsync.app.domain.model.ConnectorExecution
import com.fitsync.app.domain.model.DeliveryJobStatus
import com.fitsync.app.domain.model.ImportJobStatus
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.ui.theme.FitBlue
import com.fitsync.app.ui.theme.FitOrange
import com.fitsync.app.ui.theme.FitRed
import com.fitsync.app.ui.theme.FitTeal

enum class StatusTone { INFO, SUCCESS, WARNING, ERROR, NEUTRAL }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    subtitle: String? = null,
    navigationIcon: @Composable (() -> Unit) = {},
    actions: @Composable (() -> Unit) = {},
) {
    TopAppBar(
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge)
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = navigationIcon,
        actions = { actions() },
        windowInsets = WindowInsets(0, 0, 0, 0),
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
fun SectionHeader(title: String, subtitle: String? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            subtitle?.let {
                Text(
                    it,
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun StatusPill(
    text: String,
    tone: StatusTone = StatusTone.NEUTRAL,
    compact: Boolean = false,
) {
    val color = when (tone) {
        StatusTone.INFO -> FitBlue
        StatusTone.SUCCESS -> FitTeal
        StatusTone.WARNING -> FitOrange
        StatusTone.ERROR -> FitRed
        StatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = color.copy(alpha = 0.13f), shape = CircleShape) {
        Text(
            text = text,
            modifier = Modifier.padding(
                horizontal = if (compact) 7.dp else 10.dp,
                vertical = if (compact) 2.dp else 4.dp,
            ),
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
        )
    }
}

@Composable
fun MetricCard(
    label: String,
    value: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Card(
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                .padding(15.dp),
        ) {
            Box(
                modifier = Modifier.size(34.dp).clip(CircleShape).background(accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(value, style = MaterialTheme.typography.headlineMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onClick != null) {
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = "查看详情",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun PlatformAvatar(platform: Platform, size: Dp = 46.dp) {
    val color = platformColor(platform)
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(size * 0.3f))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.24f), RoundedCornerShape(size * 0.3f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = platformShortName(platform),
            color = color,
            fontWeight = FontWeight.Bold,
            style = if (size >= 44.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.Inbox,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                .padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.size(56.dp).clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(14.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (actionLabel != null && onAction != null) {
                Button(onClick = onAction, modifier = Modifier.padding(top = 16.dp)) { Text(actionLabel) }
            }
        }
    }
}

fun platformColor(platform: Platform): Color = when (platform) {
    Platform.ONELAP -> Color(0xFF396AF4)
    Platform.XINGZHE -> Color(0xFF00A878)
    Platform.GARMIN -> Color(0xFF1887C9)
    Platform.COROS -> Color(0xFFF2673A)
    Platform.IGPSPORT -> Color(0xFF8A5CF5)
    Platform.STRAVA -> Color(0xFFFC5200)
    Platform.LOCAL_IMPORT -> Color(0xFF647089)
    Platform.UNKNOWN -> Color(0xFF8992A5)
}

fun platformShortName(platform: Platform): String = when (platform) {
    Platform.ONELAP -> "迈"
    Platform.XINGZHE -> "行"
    Platform.GARMIN -> "G"
    Platform.COROS -> "C"
    Platform.IGPSPORT -> "iG"
    Platform.STRAVA -> "S"
    Platform.LOCAL_IMPORT -> "本"
    Platform.UNKNOWN -> "?"
}

fun accountStatusText(status: AccountStatus): String = when (status) {
    AccountStatus.ACTIVE -> "已连接"
    AccountStatus.AUTH_EXPIRED -> "登录失效"
    AccountStatus.NEEDS_USER_ACTION -> "等待处理"
    AccountStatus.DISABLED -> "未连接"
    AccountStatus.EXPERIMENTAL -> "实验性"
}

fun accountStatusTone(status: AccountStatus): StatusTone = when (status) {
    AccountStatus.ACTIVE -> StatusTone.SUCCESS
    AccountStatus.AUTH_EXPIRED -> StatusTone.ERROR
    AccountStatus.NEEDS_USER_ACTION -> StatusTone.WARNING
    AccountStatus.DISABLED -> StatusTone.NEUTRAL
    AccountStatus.EXPERIMENTAL -> StatusTone.INFO
}

fun executionText(mode: ConnectorExecution): String = when (mode) {
    ConnectorExecution.BACKGROUND -> "自动"
    ConnectorExecution.FOREGROUND_WEB -> "网页"
    ConnectorExecution.MANUAL -> "手动"
    ConnectorExecution.UNSUPPORTED -> "不可用"
}

fun deliveryStatusText(status: DeliveryJobStatus): String = when (status) {
    DeliveryJobStatus.PENDING -> "等待同步"
    DeliveryJobStatus.UPLOADING -> "正在上传"
    DeliveryJobStatus.WAITING_USER -> "需要处理"
    DeliveryJobStatus.WAITING_REMOTE_PROCESS -> "平台处理中"
    DeliveryJobStatus.SUCCEEDED -> "同步完成"
    DeliveryJobStatus.DUPLICATE_REMOTE -> "平台已存在"
    DeliveryJobStatus.RETRY_WAIT -> "等待重试"
    DeliveryJobStatus.FAILED -> "同步失败"
    DeliveryJobStatus.DISABLED -> "已停止"
}

fun deliveryStatusTone(status: DeliveryJobStatus): StatusTone = when (status) {
    DeliveryJobStatus.SUCCEEDED, DeliveryJobStatus.DUPLICATE_REMOTE -> StatusTone.SUCCESS
    DeliveryJobStatus.FAILED -> StatusTone.ERROR
    DeliveryJobStatus.WAITING_USER, DeliveryJobStatus.RETRY_WAIT -> StatusTone.WARNING
    DeliveryJobStatus.PENDING, DeliveryJobStatus.UPLOADING, DeliveryJobStatus.WAITING_REMOTE_PROCESS -> StatusTone.INFO
    DeliveryJobStatus.DISABLED -> StatusTone.NEUTRAL
}

fun importStatusText(status: ImportJobStatus): String = when (status) {
    ImportJobStatus.RECEIVED -> "已接收"
    ImportJobStatus.COPYING -> "正在复制"
    ImportJobStatus.VALIDATING -> "正在校验"
    ImportJobStatus.PARSING -> "正在解析"
    ImportJobStatus.DEDUPLICATING -> "正在去重"
    ImportJobStatus.READY -> "导入完成"
    ImportJobStatus.NEEDS_REVIEW -> "需要复核"
    ImportJobStatus.INVALID -> "文件无效"
    ImportJobStatus.DUPLICATE_FILE -> "文件已存在"
}
