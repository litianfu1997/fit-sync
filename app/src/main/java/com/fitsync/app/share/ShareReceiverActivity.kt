package com.fitsync.app.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fitsync.app.FitSyncApp
import com.fitsync.app.data.repo.ImportOutcome
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.ui.components.AppTopBar
import com.fitsync.app.ui.theme.FitSyncTheme
import com.fitsync.app.ui.theme.FitRed
import com.fitsync.app.ui.theme.FitTeal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = extractUris(intent)

        setContent {
            FitSyncTheme {
                var message by remember { mutableStateOf("正在安全复制并校验 FIT…") }
                var done by remember { mutableStateOf(false) }
                var successful by remember { mutableStateOf(true) }

                LaunchedEffect(uris) {
                    if (uris.isEmpty()) {
                        message = "没有收到可导入的文件"
                        successful = false
                        done = true
                        return@LaunchedEffect
                    }
                    val results = withContext(Dispatchers.IO) {
                        uris.map { uri ->
                            val outcome = FitSyncApp.instance.importService.importFromUri(
                                uri,
                                sourcePlatform = Platform.LOCAL_IMPORT,
                            )
                            FitSyncApp.instance.syncCoordinator.onImported(outcome, Platform.LOCAL_IMPORT)
                            outcome
                        }
                    }
                    successful = results.none { it is ImportOutcome.Invalid || it is ImportOutcome.Failed }
                    message = results.joinToString("\n") { formatOutcome(it) }
                    done = true
                }

                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    AppTopBar(title = "导入 FIT", subtitle = "文件只会保存在本机应用沙箱")
                    Column(
                        modifier = Modifier.fillMaxSize().padding(20.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(
                                    modifier = Modifier.size(72.dp).clip(CircleShape).background(
                                        when {
                                            !done -> MaterialTheme.colorScheme.primaryContainer
                                            successful -> FitTeal.copy(alpha = 0.14f)
                                            else -> FitRed.copy(alpha = 0.14f)
                                        },
                                    ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (!done) CircularProgressIndicator()
                                    else Icon(
                                        if (successful) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                                        contentDescription = null,
                                        tint = if (successful) FitTeal else FitRed,
                                        modifier = Modifier.size(36.dp),
                                    )
                                }
                                Text(
                                    if (!done) "正在导入" else if (successful) "导入完成" else "部分文件未导入",
                                    modifier = Modifier.padding(top = 18.dp),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                Text(
                                    message,
                                    modifier = Modifier.padding(top = 8.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                                if (done) {
                                    Button(onClick = { finish() }, modifier = Modifier.fillMaxWidth().padding(top = 22.dp)) {
                                        Icon(Icons.Default.FileDownload, contentDescription = null)
                                        Text("完成", modifier = Modifier.padding(start = 8.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun extractUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            }
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
    }

    private fun formatOutcome(outcome: ImportOutcome): String = when (outcome) {
        is ImportOutcome.Success -> "导入成功 · 质量 ${outcome.qualityScore}"
        is ImportOutcome.DuplicateFile -> "文件已存在 · 活动 #${outcome.canonicalActivityId}"
        is ImportOutcome.Invalid -> "无效文件 · ${outcome.message ?: outcome.validationStatus}"
        is ImportOutcome.Failed -> "导入失败 · ${outcome.message.orEmpty()}"
    }
}
