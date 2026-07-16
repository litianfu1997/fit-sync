package com.fitsync.app.web

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.fitsync.app.FitSyncApp
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.domain.model.SessionState
import com.fitsync.app.ui.theme.FitSyncTheme
import com.fitsync.app.ui.components.AppTopBar
import com.fitsync.app.ui.components.PlatformAvatar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class PlatformWebActivity : ComponentActivity() {
    private lateinit var platform: Platform
    private var jobId: Long = -1
    private var initialUrl: String = ""
    private var webView: WebView? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var mainFrameLoadFailed = false
    private var statusText by mutableStateOf("请在平台网页中登录，App 不会保存账号密码")

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        fileCallback?.onReceiveValue(uris.takeIf { it.isNotEmpty() }?.toTypedArray())
        fileCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        platform = intent.getStringExtra(EXTRA_PLATFORM)?.let(Platform::valueOf)
            ?: run { finish(); return }
        jobId = intent.getLongExtra(EXTRA_JOB_ID, -1)
        initialUrl = intent.getStringExtra(EXTRA_URL)
            ?: PlatformWebConfigs.get(platform).loginUrl

        setContent {
            FitSyncTheme {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    AppTopBar(
                        title = platform.displayName,
                        subtitle = if (jobId > 0) "网页上传任务" else "安全网页登录",
                        navigationIcon = {
                            IconButton(onClick = { finish() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "关闭")
                            }
                        },
                        actions = { PlatformAvatar(platform, 36.dp) },
                    )
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                text = statusText,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp),
                        shape = MaterialTheme.shapes.medium,
                        tonalElevation = 2.dp,
                    ) {
                        AndroidView(
                            factory = { context -> createWebView(context) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Surface(tonalElevation = 8.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Button(onClick = { validateAndSave() }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null)
                                Text("验证并保存登录", modifier = Modifier.padding(start = 6.dp))
                            }
                            if (jobId > 0) {
                                OutlinedButton(onClick = { confirmUpload() }, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.UploadFile, contentDescription = null)
                                    Text("完成上传", modifier = Modifier.padding(start = 6.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Suppress("SetJavaScriptEnabled")
    private fun createWebView(context: Context): WebView = WebView(context).also { view ->
        webView = view
        view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            userAgentString = "$userAgentString FitSync/1.0"
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                mainFrameLoadFailed = false
                statusText = "正在打开 ${platform.displayName} 登录页…"
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return true
                val allowed = uri.scheme == "https" && PlatformWebConfigs.isAllowed(platform, uri.host)
                if (!allowed) {
                    mainFrameLoadFailed = true
                    val destination = uri.host ?: uri.scheme.orEmpty()
                    statusText = "已阻止不安全跳转：$destination"
                    Toast.makeText(this@PlatformWebActivity, statusText, Toast.LENGTH_SHORT).show()
                }
                return !allowed
            }

            override fun onPageFinished(view: WebView, url: String) {
                CookieManager.getInstance().flush()
                if (!mainFrameLoadFailed) {
                    statusText = "页面已加载；登录完成后点击“验证并保存登录”"
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    mainFrameLoadFailed = true
                    statusText = "登录页加载失败：${error?.description ?: "网络连接异常"}"
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?,
            ) {
                if (request?.isForMainFrame == true) {
                    mainFrameLoadFailed = true
                    statusText = "平台页面返回 HTTP ${errorResponse?.statusCode}"
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.cancel()
                mainFrameLoadFailed = true
                statusText = "平台证书校验失败，已停止加载"
            }

        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                if (jobId > 0) providePendingFitFile() else filePicker.launch(arrayOf("application/vnd.ant.fit", "application/octet-stream"))
                return true
            }
        }
        view.setDownloadListener(DownloadListener { url, _, contentDisposition, mimeType, _ ->
            enqueueDownload(url, contentDisposition, mimeType)
        })
        view.loadUrl(initialUrl)
    }

    private fun validateAndSave() {
        statusText = "正在验证登录状态…"
        captureSession {
            lifecycleScope.launch {
                val state = withContext(Dispatchers.IO) {
                    FitSyncApp.instance.platformAccountRepository.validateAndPersist(platform)
                }
                statusText = when (state) {
                    SessionState.AUTHENTICATED -> "登录状态有效，已经保存"
                    SessionState.EXPIRED -> "登录已失效，请重新登录"
                    SessionState.NOT_CONNECTED -> "未检测到有效登录，请先完成登录"
                    SessionState.NEEDS_USER_ACTION -> "平台需要验证码或其他确认，请在网页中完成"
                }
            }
        }
    }

    private fun captureSession(done: () -> Unit) {
        CookieManager.getInstance().flush()
        if (platform != Platform.ONELAP) {
            done()
            return
        }
        val script = """
            (function(){return JSON.stringify({
              token:localStorage.getItem('token'),
              refresh_token:localStorage.getItem('refresh_token'),
              uid:localStorage.getItem('uid')
            });})()
        """.trimIndent()
        webView?.evaluateJavascript(script) { encoded ->
            runCatching {
                val jsonText = JSONArray("[$encoded]").getString(0)
                val json = JSONObject(jsonText)
                FitSyncApp.instance.webSessionManager.saveOnelapTokens(
                    json.optString("token").takeIf { it.isNotBlank() && it != "null" },
                    json.optString("refresh_token").takeIf { it.isNotBlank() && it != "null" },
                    json.optString("uid").takeIf { it.isNotBlank() && it != "null" },
                )
            }
            done()
        } ?: done()
    }

    private fun providePendingFitFile() {
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) {
                val job = FitSyncApp.instance.database.deliveryJobDao().getById(jobId) ?: return@withContext null
                val variant = FitSyncApp.instance.database.activityVariantDao().getById(job.sourceVariantId)
                    ?: return@withContext null
                val file = File(variant.localFilePath).takeIf(File::isFile) ?: return@withContext null
                FileProvider.getUriForFile(
                    this@PlatformWebActivity,
                    "$packageName.files",
                    file,
                )
            }
            fileCallback?.onReceiveValue(uri?.let { arrayOf(it) })
            fileCallback = null
            if (uri == null) statusText = "找不到待上传的本地 FIT 文件"
        }
    }

    private fun confirmUpload() {
        lifecycleScope.launch(Dispatchers.IO) {
            FitSyncApp.instance.syncCoordinator.confirmForegroundJob(jobId)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@PlatformWebActivity, "已记录为完成", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun enqueueDownload(url: String, contentDisposition: String?, mimeType: String?) {
        if (!PlatformWebConfigs.isAllowed(platform, Uri.parse(url).host)) {
            statusText = "已阻止非平台域名下载"
            return
        }
        val fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
        val request = DownloadManager.Request(Uri.parse(url))
            .setMimeType(mimeType ?: "application/octet-stream")
            .setTitle(fileName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, android.os.Environment.DIRECTORY_DOWNLOADS, fileName)
        FitSyncApp.instance.webSessionManager.cookieHeader(platform)?.let { request.addRequestHeader("Cookie", it) }
        getSystemService(DownloadManager::class.java).enqueue(request)
        statusText = "已开始下载 $fileName；完成后可分享到本应用"
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        webView?.apply {
            stopLoading()
            webChromeClient = null
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_PLATFORM = "platform"
        private const val EXTRA_JOB_ID = "job_id"
        private const val EXTRA_URL = "url"

        fun createIntent(
            context: Context,
            platform: Platform,
            jobId: Long = -1,
            url: String? = null,
        ): Intent = Intent(context, PlatformWebActivity::class.java).apply {
            putExtra(EXTRA_PLATFORM, platform.name)
            putExtra(EXTRA_JOB_ID, jobId)
            putExtra(EXTRA_URL, url ?: PlatformWebConfigs.get(platform).loginUrl)
        }
    }
}
