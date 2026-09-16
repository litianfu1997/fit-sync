package com.fitsync.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.fitsync.app.ui.components.FitTopBar
import com.fitsync.app.ui.theme.FitSpacing

@Composable
fun PrivacyAboutScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        FitTopBar(
            title = "隐私与关于",
            subtitle = "本地优先，不经过自建服务器",
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
                SettingsInfoCard(Icons.Default.Security, "隐私优先的本地架构", "账号会话与活动数据仅保存在本机") {
                    Text(
                        "平台 Cookie、会话令牌和需要自动重登的平台凭据由 WebView 或 Android Keystore 管理。" +
                            "活动库与原始 FIT 只保存在应用沙箱，系统备份已关闭。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                GarminDiagnosticsCard()
            }
            item {
                SettingsInfoCard(Icons.Default.TextFields, "字体授权", "MiSans 字体知识产权许可协议") {
                    Text(
                        "本应用内嵌 MiSans 字体的子集化版本。MiSans 由小米科技有限责任公司发布，" +
                            "依据《MiSans 字体知识产权许可协议》免费商用，可随应用一同分发。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Text(
                    "FitSync 0.2.0",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = FitSpacing.xs, vertical = FitSpacing.sm),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
