package com.fitsync.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fitsync.app.strava.StravaOAuthService

@Composable
fun StravaCredentialsDialog(
    currentClientId: String?,
    onDismiss: () -> Unit,
    onSaveAndAuthorize: (clientId: String, clientSecret: String) -> Unit,
) {
    var clientId by remember { mutableStateOf(currentClientId.orEmpty()) }
    var clientSecret by remember { mutableStateOf("") }
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("配置 Strava 开发者应用") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "请在 Strava 开发者后台创建应用，把 Callback Domain 填为 localhost。" +
                        "纯客户端模式下 Client Secret 只能保护本机静态存储，无法成为真正的服务器秘密。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("回调 URI：${StravaOAuthService.REDIRECT_URI}", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = clientId,
                    onValueChange = { clientId = it },
                    label = { Text("Client ID") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = clientSecret,
                    onValueChange = { clientSecret = it },
                    label = { Text("Client Secret") },
                    supportingText = { Text("已保存的 Secret 不会回显；更新时请重新输入") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.strava.com/settings/api")))
                    },
                ) { Text("打开 Strava 开发者后台") }
            }
        },
        confirmButton = {
            TextButton(
                enabled = clientId.isNotBlank() && clientSecret.isNotBlank(),
                onClick = { onSaveAndAuthorize(clientId.trim(), clientSecret.trim()) },
            ) { Text("保存并授权") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
