package com.fitsync.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.domain.model.SessionState
import kotlinx.coroutines.launch

/**
 * COROS 高驰账号密码登录对话框。
 *
 * COROS 中国站走纯 API 登录（`POST /account/login`，accountType=2 + MD5(密码)），不像 Garmin/迈金/行者
 * 那样用 WebView。账号密码经 [onSubmit] 交给调用方（→ PlatformAccountRepository.loginWithCredentials），
 * 由 [com.fitsync.app.connectors.CorosApiConnector.login] 持久化到 Android Keystore；本对话框不触碰凭据落盘。
 * 仅支持中国站账号（regionId=2）。
 */
@Composable
fun CorosLoginDialog(
    platform: Platform,
    onDismiss: () -> Unit,
    onSubmit: suspend (username: String, password: String) -> SessionState,
    onResult: (SessionState) -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = { Text("登录 ${platform.displayName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "账号密码仅用于后台自动同步，经 Android Keystore 加密保存于本机。仅支持 COROS 中国站账号。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("邮箱 / 账号") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !loading && username.isNotBlank() && password.isNotBlank(),
                onClick = {
                    scope.launch {
                        loading = true
                        error = null
                        val state = onSubmit(username.trim(), password)
                        loading = false
                        if (state == SessionState.AUTHENTICATED) {
                            onResult(state)
                        } else {
                            error = when (state) {
                                SessionState.EXPIRED -> "账号或密码错误"
                                SessionState.NEEDS_USER_ACTION -> "登录失败（账号可能非中国站，或网络异常），请重试"
                                else -> "登录失败，请稍后重试"
                            }
                        }
                    }
                },
            ) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("登录")
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !loading, onClick = onDismiss) { Text("取消") }
        },
    )
}
