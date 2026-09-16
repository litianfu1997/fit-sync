package com.fitsync.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fitsync.app.domain.model.AccountStatus
import com.fitsync.app.domain.model.ConnectorExecution
import com.fitsync.app.domain.model.DeliveryJobStatus
import com.fitsync.app.domain.model.ImportJobStatus
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.ui.theme.FitShape
import com.fitsync.app.ui.theme.FitSpacing
import com.fitsync.app.ui.theme.FitTonesOnDark

enum class StatusTone { INFO, SUCCESS, WARNING, ERROR, NEUTRAL }

/**
 * 状态徽标：2dp 小圆角 + 10% 底 + 25% 描边，取代胶囊形状。
 * 遥测仪表质感来自「直角 + 细线」，而不是圆润的消费级气泡。
 */
@Composable
fun StatusPill(
    text: String,
    tone: StatusTone = StatusTone.NEUTRAL,
    compact: Boolean = false,
) {
    // Hero 等内容把 LocalContentColor 设为浅色（说明身处深底），
    // 此时状态色必须切到亮档，否则「可读档」的深色会被深底吞掉。
    val onDark = LocalContentColor.current.luminance() > 0.5f
    val color = statusToneColor(tone, onDark)
    Surface(
        color = color.copy(alpha = if (onDark) 0.16f else 0.07f),
        shape = FitShape.indicator,
        border = BorderStroke(1.dp, color.copy(alpha = if (onDark) 0.32f else 0.25f)),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(
                horizontal = if (compact) FitSpacing.xs else FitSpacing.sm,
                vertical = if (compact) 1.dp else FitSpacing.xxs,
            ),
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
        )
    }
}

@Composable
internal fun statusToneColor(tone: StatusTone, onDark: Boolean = false): Color = if (onDark) {
    when (tone) {
        StatusTone.INFO -> FitTonesOnDark.info
        StatusTone.SUCCESS -> FitTonesOnDark.success
        StatusTone.WARNING -> FitTonesOnDark.warning
        StatusTone.ERROR -> FitTonesOnDark.error
        StatusTone.NEUTRAL -> FitTonesOnDark.neutral
    }
} else {
    when (tone) {
        StatusTone.INFO -> MaterialTheme.colorScheme.tertiary
        StatusTone.SUCCESS -> MaterialTheme.colorScheme.secondary
        StatusTone.WARNING -> MaterialTheme.colorScheme.primary
        StatusTone.ERROR -> MaterialTheme.colorScheme.error
        StatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/** 平台标识：平台品牌色只允许出现在这里。 */
@Composable
fun PlatformAvatar(platform: Platform, size: Dp = 40.dp) {
    val color = platformColor(platform)
    // 品牌色只做底与描边；字母属于文字，浅底压暗、深底提亮才能读清。
    val letterColor = if (LocalContentColor.current.luminance() > 0.5f) {
        lerp(color, Color.White, 0.25f)
    } else {
        lerp(color, Color.Black, 0.32f)
    }
    val shape = FitShape.control
    Box(
        modifier = Modifier.size(size).clip(shape)
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.24f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = platformShortName(platform),
            color = letterColor,
            fontWeight = FontWeight.Bold,
            style = if (size >= 36.dp) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelSmall,
        )
    }
}

/** 来源 → 目标：两个徽标叠压成一个小整体，替代「两个头像夹一支箭头」的拥挤排布。 */
@Composable
fun PlatformRouteAvatar(source: Platform, destination: Platform, size: Dp = 28.dp) {
    val ring = 1.dp
    Box(Modifier.size(width = size * 1.7f + ring * 2, height = size + ring * 2)) {
        PlatformAvatar(source, size)
        Box(
            modifier = Modifier
                .offset(x = size * 0.7f + ring, y = ring)
                .clip(FitShape.control)
                .background(MaterialTheme.colorScheme.surface)
                .padding(ring),
        ) {
            PlatformAvatar(destination, size)
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
