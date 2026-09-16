package com.fitsync.app.web

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.app.AlertDialog
import android.os.Bundle
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import com.fitsync.app.domain.model.DeliveryJobStatus
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.domain.model.SessionState
import com.fitsync.app.sync.SyncScheduler
import com.fitsync.app.ui.theme.FitShape
import com.fitsync.app.ui.theme.FitSpacing
import com.fitsync.app.ui.theme.FitSyncTheme
import com.fitsync.app.ui.components.FitTopBar
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
    private var grantedUploadUri: Uri? = null
    private var mainFrameLoadFailed = false
    private var garminAutoValidationInFlight = false
    private var garminAutoValidationCompleted = false
    private var statusText by mutableStateOf("请在平台网页中登录；本页仅保存 Cookie/会话令牌，不收集网页里输入的密码")
    private var uploadFileProvided by mutableStateOf(false)
    private var uploadConfirmationBusy by mutableStateOf(false)

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        fileCallback?.onReceiveValue(uris.takeIf { it.isNotEmpty() }?.toTypedArray())
        fileCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        platform = intent.getStringExtra(EXTRA_PLATFORM)?.let(Platform::valueOf)
            ?: run { finish(); return }
        jobId = intent.getLongExtra(EXTRA_JOB_ID, -1)
        val config = PlatformWebConfigs.get(platform)
        initialUrl = intent.getStringExtra(EXTRA_URL) ?: config.loginUrl
        if (platform == Platform.STRAVA) {
            statusText = "请登录 Strava 网页后点“验证并保存登录”；若 Google 登录被 WebView 拒绝，请改用 Strava 邮箱密码。"
        }

        setContent {
            FitSyncTheme {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    FitTopBar(
                        title = platform.displayName,
                        subtitle = when {
                            jobId <= 0 -> "安全网页登录"
                            else -> "网页上传任务"
                        },
                        onBack = { finish() },
                        backContentDescription = "关闭",
                        actions = { PlatformAvatar(platform, 28.dp) },
                        modifier = Modifier.statusBarsPadding(),
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = FitSpacing.md, vertical = FitSpacing.sm),
                        shape = FitShape.control,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = FitSpacing.md, vertical = FitSpacing.sm),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(FitSpacing.sm),
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(FitSpacing.iconSm),
                            )
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
                            // Garmin 已改为后台上传：登录页只保留「验证并保存登录」（捕获 JWT_WEB 后后台自动续跑），
                            // 不再显示前台上传专用的「核对上传结果」按钮。
                            val showForegroundUpload = jobId > 0 && platform != Platform.GARMIN
                            Button(
                                onClick = { validateAndSave() },
                                modifier = if (showForegroundUpload) Modifier.weight(1f) else Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null)
                                Text("验证并保存登录", modifier = Modifier.padding(start = 6.dp))
                            }
                            if (showForegroundUpload) {
                                OutlinedButton(
                                    onClick = { confirmUpload() },
                                    enabled = uploadFileProvided && !uploadConfirmationBusy,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(Icons.Default.UploadFile, contentDescription = null)
                                    Text(
                                        if (uploadConfirmationBusy) "正在核对" else "完成上传",
                                        modifier = Modifier.padding(start = 6.dp),
                                    )
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
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            if (platform == Platform.GARMIN) {
                // Garmin 官方仅提供桌面网页导入入口；移动 UA 可能隐藏 Import Data 文件控件。
                userAgentString = GARMIN_DESKTOP_USER_AGENT
                useWideViewPort = true
                loadWithOverviewMode = true
            } else {
                userAgentString = "$userAgentString FitSync/1.0"
            }
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                mainFrameLoadFailed = false
                statusText = "正在打开 ${platform.displayName} 网页…"
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return true
                val allowed = uri.scheme == "https" && PlatformWebConfigs.isAllowed(platform, uri.host)
                if (!allowed) {
                    mainFrameLoadFailed = true
                    val destination = uri.host ?: uri.scheme.orEmpty()
                    // 顶部信息条已同步更新且常驻，无需再弹 Toast 重复反馈。
                    statusText = "已阻止不安全跳转：$destination"
                }
                return !allowed
            }

            override fun onPageFinished(view: WebView, url: String) {
                CookieManager.getInstance().flush()
                FitSyncApp.instance.webSessionManager.rememberWebViewUrl(platform, url)
                if (!mainFrameLoadFailed) {
                    statusText = if (jobId > 0) {
                        if (platform == Platform.GARMIN && isGarminLoginUrl(url)) {
                            "请先完成 Garmin 登录；登录后页面会自动返回 Import Data"
                        } else if (platform == Platform.GARMIN) {
                            "请点击网页中的文件选择按钮，应用会提供本任务的 FIT；导入后点击底部“核对上传结果”"
                        } else {
                            "请点击网页中的文件选择按钮，应用会提供本任务的 FIT；导入成功后点击底部“完成上传”"
                        }
                    } else {
                        "页面已加载；登录完成后点击“验证并保存登录”"
                    }
                }
                if (platform == Platform.GARMIN &&
                    shouldAutoCaptureGarminSession(url, mainFrameLoadFailed) &&
                    !garminAutoValidationInFlight &&
                    !garminAutoValidationCompleted
                ) {
                    // SSO 仍有效时，打开 Connect 会自动换发 JWT_WEB；页面落地后立即保存，
                    // 不再要求用户额外点击“验证并保存登录”。
                    garminAutoValidationInFlight = true
                    statusText = "检测到 Garmin 登录状态，正在自动保存并恢复同步…"
                    validateGarminFromWebView(autoTriggered = true)
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
                uploadFileProvided = false
                if (jobId > 0) {
                    providePendingFitFile()
                } else {
                    filePicker.launch(arrayOf("application/vnd.ant.fit", "application/octet-stream"))
                }
                return true
            }

            // 部分人机验证 SDK 会用 window.open() 弹窗。开启 setSupportMultipleWindows(true) 后
            // 必须实现本回调，否则 window.open() 返回 null，验证直接失败。这里创建临时子 WebView，
            // 把目标 URL 转交给主 WebView 加载（复用 shouldOverrideUrlLoading 的白名单），再 sendToTarget。
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?,
            ): Boolean {
                val parent = view ?: return false
                val child = WebView(parent.context).apply {
                    settings.javaScriptEnabled = true
                    webChromeClient = parent.webChromeClient
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val uri = request?.url
                            val allowed = uri != null &&
                                uri.scheme == "https" &&
                                PlatformWebConfigs.isAllowed(platform, uri.host)
                            if (allowed) {
                                parent.loadUrl(uri.toString())
                            } else {
                                val destination = uri?.host ?: uri?.scheme.orEmpty()
                                // 顶部信息条已同步更新且常驻，无需再弹 Toast 重复反馈。
                                statusText = "已阻止弹窗跳转：$destination"
                            }
                            view?.destroy()
                            return true
                        }
                    }
                }
                (resultMsg?.obj as? WebView.WebViewTransport)?.webView = child
                resultMsg?.sendToTarget()
                return true
            }

            // 兜底渲染页面的 alert / confirm / prompt 对话框（部分验证或登录流程会用到）。
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                AlertDialog.Builder(this@PlatformWebActivity)
                    .setMessage(message)
                    .setPositiveButton("确定") { _, _ -> result?.confirm() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                AlertDialog.Builder(this@PlatformWebActivity)
                    .setMessage(message)
                    .setPositiveButton("确定") { _, _ -> result?.confirm() }
                    .setNegativeButton("取消") { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?,
            ): Boolean {
                val input = EditText(this@PlatformWebActivity).apply { setText(defaultValue) }
                AlertDialog.Builder(this@PlatformWebActivity)
                    .setMessage(message)
                    .setView(input)
                    .setPositiveButton("确定") { _, _ -> result?.confirm(input.text.toString()) }
                    .setNegativeButton("取消") { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
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
        CookieManager.getInstance().flush()
        FitSyncApp.instance.webSessionManager.rememberWebViewUrl(platform, webView?.url)
        when (platform) {
            Platform.GARMIN -> validateGarminFromWebView(autoTriggered = false)
            Platform.ONELAP -> captureOnelapTokens {
                persistDetectedState(detectedOverride = null)
            }
            else -> persistDetectedState(detectedOverride = null)
        }
    }

    private fun persistDetectedState(detectedOverride: SessionState?) {
        lifecycleScope.launch {
            val sessions = FitSyncApp.instance.webSessionManager
            val state = withContext(Dispatchers.IO) {
                FitSyncApp.instance.platformAccountRepository.validateAndPersist(
                    platform = platform,
                    detectedOverride = detectedOverride,
                )
            }
            val cookieInfo = sessions.cookieDiagnostic(platform)
            val page = webView?.url?.let { runCatching { java.net.URI(it).host }.getOrNull() } ?: "?"
            statusText = when (state) {
                SessionState.AUTHENTICATED -> "登录状态有效，已经保存"
                SessionState.EXPIRED -> "登录授权已失效，请重新登录（页面 $page；$cookieInfo）"
                SessionState.NOT_CONNECTED ->
                    "未检测到有效登录。请确认已登录并跳转到 Connect 主站后再点验证（页面 $page；$cookieInfo）"
                SessionState.NEEDS_USER_ACTION ->
                    "网页会话校验未通过，请在页面完成验证码/二次确认后重试（页面 $page；$cookieInfo）"
            }
            if (state == SessionState.AUTHENTICATED) {
                // 登录恢复后需要重新执行目标预拉取与规则积压补扫，不能只续跑已有投递任务。
                SyncScheduler.enqueueAutoNow(this@PlatformWebActivity)
            }
        }
    }

    private fun captureOnelapTokens(done: () -> Unit) {
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

    /** Garmin 登录页成功跳回中国站 Connect 后即可确认；不依赖厂商可能随时改名的 Cookie。 */
    private fun validateGarminFromWebView(autoTriggered: Boolean) {
        val script = """
            (function(){
              var host = location.host || '';
              var path = location.pathname || '';
              var onSso = /sso\.garmin\.cn/i.test(host);
              var onConnect = /connect\.garmin\.cn/i.test(host);
              var onLoginPage = /\/signin\/?$/i.test(path) || /\/portal\/sso\//i.test(path);
              return JSON.stringify({
                ok: onConnect && !onLoginPage,
                onSso: onSso,
                csrf: (document.querySelector('meta[name="csrf-token"]') || {}).content || ''
              });
            })()
        """.trimIndent()
        val view = webView
        if (view == null) {
            garminAutoValidationInFlight = false
            if (!autoTriggered) persistDetectedState(detectedOverride = null)
            return
        }
        view.evaluateJavascript(script) { encoded ->
            val sessions = FitSyncApp.instance.webSessionManager
            sessions.rememberWebViewUrl(platform, view.url)
            CookieManager.getInstance().flush()
            val proof = runCatching {
                val jsonText = JSONArray("[$encoded]").getString(0)
                JSONObject(jsonText)
            }.getOrNull()
            val pageReady = proof?.optBoolean("ok") == true
            val onSso = proof?.optBoolean("onSso") == true
            when {
                pageReady -> {
                    val captured = captureGarminSession(proof?.optString("csrf"))
                    if (autoTriggered) {
                        garminAutoValidationInFlight = false
                        if (!captured) {
                            statusText = "Garmin 页面已打开，但尚未拿到新的登录令牌；可等待页面刷新或手动验证"
                            return@evaluateJavascript
                        }
                        garminAutoValidationCompleted = true
                    }
                    persistDetectedState(
                        detectedOverride = if (captured) SessionState.AUTHENTICATED
                        else SessionState.NEEDS_USER_ACTION,
                    )
                }
                autoTriggered -> {
                    // 自动探测失败不改写账号状态，保留手动验证作为兜底。
                    garminAutoValidationInFlight = false
                }
                onSso -> persistDetectedState(detectedOverride = SessionState.EXPIRED)
                sessions.hasSession(platform) -> persistDetectedState(detectedOverride = SessionState.NEEDS_USER_ACTION)
                else -> persistDetectedState(detectedOverride = SessionState.NOT_CONNECTED)
            }
        }
    }

    /** 抓取并持久化 Garmin 会话；成功返回 true，未抓到 JWT_WEB 返回 false
     * （调用方据此把登录判定降级为 NEEDS_USER_ACTION，避免「标记已登录实则无凭据」）。 */
    private fun captureGarminSession(connectCsrf: String?): Boolean {
        val view = webView ?: return false
        return persistGarminSessionFromWebView(
            view = view,
            sessions = FitSyncApp.instance.webSessionManager,
            connectCsrf = connectCsrf,
        )
    }

    private fun providePendingFitFile() {
        lifecycleScope.launch {
            statusText = "正在校验待上传 FIT…"
            val preparation = withContext(Dispatchers.IO) {
                val app = FitSyncApp.instance
                val job = app.database.deliveryJobDao().getById(jobId)
                    ?: return@withContext GarminUploadPreparation.Rejected(
                        "GARMIN_JOB_MISSING",
                        "同步任务已不存在",
                    )
                if (job.status != DeliveryJobStatus.WAITING_USER) {
                    return@withContext GarminUploadPreparation.Rejected(
                        "GARMIN_JOB_NOT_READY",
                        "同步任务当前不能执行网页上传",
                    )
                }
                val variant = app.database.activityVariantDao().getById(job.sourceVariantId)
                    ?: return@withContext GarminUploadPreparation.Rejected(
                        "GARMIN_VARIANT_MISSING",
                        "待上传的活动文件记录已不存在",
                    )
                if (platform == Platform.GARMIN) {
                    app.garminUploadFilePreparer.prepare(jobId, variant)
                } else {
                    val file = File(variant.localFilePath).takeIf(File::isFile)
                        ?: return@withContext GarminUploadPreparation.Rejected(
                            "FOREGROUND_FILE_MISSING",
                            "找不到待上传的本地 FIT 文件",
                        )
                    GarminUploadPreparation.Ready(file, variant.recordCount ?: 0)
                }
            }
            when (preparation) {
                is GarminUploadPreparation.Ready -> deliverPreparedFile(preparation)
                is GarminUploadPreparation.Rejected -> {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = null
                    uploadFileProvided = false
                    statusText = preparation.message
                    if (platform == Platform.GARMIN) {
                        withContext(Dispatchers.IO) {
                            FitSyncApp.instance.syncCoordinator.failForegroundJob(
                                jobId,
                                preparation.code,
                                preparation.message,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun deliverPreparedFile(preparation: GarminUploadPreparation.Ready) {
        val callback = fileCallback
        if (callback == null) {
            if (platform == Platform.GARMIN) FitSyncApp.instance.garminUploadFilePreparer.cleanup(jobId)
            return
        }
        val uri = runCatching {
            FileProvider.getUriForFile(this, "$packageName.files", preparation.file)
        }.getOrElse { error ->
            callback.onReceiveValue(null)
            fileCallback = null
            statusText = error.message ?: "无法授权 ${platform.displayName} 网页读取 FIT 文件"
            return
        }
        grantedUploadUri?.takeIf { it != uri }?.let { previous ->
            runCatching { revokeUriPermission(previous, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            grantedUploadUri = null
        }
        val webViewPackage = WebView.getCurrentWebViewPackage()?.packageName
        val readable = runCatching {
            if (webViewPackage.isNullOrBlank()) return@runCatching false
            grantUriPermission(webViewPackage, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            contentResolver.openInputStream(uri)?.use { input ->
                val header = ByteArray(12)
                input.read(header) == header.size && String(header, 8, 4, Charsets.US_ASCII) == ".FIT"
            } == true
        }.getOrDefault(false)
        if (!readable) {
            runCatching { revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            callback.onReceiveValue(null)
            fileCallback = null
            statusText = "WebView 无法读取待上传 FIT，请重试"
            return
        }
        grantedUploadUri = uri
        callback.onReceiveValue(arrayOf(uri))
        fileCallback = null
        uploadFileProvided = true
        statusText = buildString {
            append("FIT 已交给 ${platform.displayName} 网页")
            if (preparation.recordCount > 0) append("（${preparation.recordCount} 个数据点）")
            append("；请完成网页导入并等待结果，然后点“核对上传结果”")
        }
    }

    private fun confirmUpload() {
        if (!uploadFileProvided) {
            statusText = "请先在网页中选择并导入本任务的 FIT"
            return
        }
        if (platform != Platform.GARMIN) {
            completeForegroundJob(GarminImportEvidence(GarminImportEvidenceType.SUCCESS))
            return
        }
        val view = webView ?: run {
            statusText = "网页已关闭，无法核对 Garmin 结果"
            return
        }
        uploadConfirmationBusy = true
        statusText = "正在核对 Garmin 网页导入结果…"
        view.evaluateJavascript(GARMIN_IMPORT_EVIDENCE_SCRIPT) { encoded ->
            val snapshot = runCatching {
                val jsonText = JSONArray("[$encoded]").getString(0)
                JSONObject(jsonText)
            }.getOrNull()
            val evidence = classifyGarminImportPage(
                pageUrl = snapshot?.optString("href"),
                pageText = snapshot?.optString("text"),
                activityUrl = snapshot?.optString("activityUrl"),
            )
            uploadConfirmationBusy = false
            when (evidence.type) {
                GarminImportEvidenceType.SUCCESS,
                GarminImportEvidenceType.DUPLICATE,
                -> completeForegroundJob(evidence)

                GarminImportEvidenceType.FAILURE -> {
                    statusText = "Garmin 网页显示导入失败，请按页面提示处理后重新选择 FIT"
                }

                GarminImportEvidenceType.UNKNOWN -> showUnverifiedGarminResultDialog()
            }
        }
    }

    private fun showUnverifiedGarminResultDialog() {
        statusText = "尚未检测到 Garmin 的成功或重复提示"
        AlertDialog.Builder(this)
            .setTitle("尚未检测到导入成功")
            .setMessage("请先在 Garmin 网页点击 Import/导入并等待结果。只有页面已经明确显示导入成功时，才选择“页面已显示成功”。")
            .setPositiveButton("页面已显示成功") { _, _ ->
                completeForegroundJob(GarminImportEvidence(GarminImportEvidenceType.SUCCESS))
            }
            .setNegativeButton("继续等待", null)
            .show()
    }

    private fun completeForegroundJob(evidence: GarminImportEvidence) {
        uploadConfirmationBusy = true
        lifecycleScope.launch(Dispatchers.IO) {
            val confirmed = FitSyncApp.instance.syncCoordinator.confirmForegroundJob(
                jobId = jobId,
                duplicate = evidence.type == GarminImportEvidenceType.DUPLICATE,
                remoteActivityId = evidence.remoteActivityId,
                remoteUrl = evidence.remoteUrl,
            )
            withContext(Dispatchers.Main) {
                uploadConfirmationBusy = false
                if (confirmed) {
                    // 有意保留 Toast：紧随其后就会 finish() 本页，Snackbar 没有宿主可以存活。
                    Toast.makeText(
                        this@PlatformWebActivity,
                        if (evidence.type == GarminImportEvidenceType.DUPLICATE) {
                            "${platform.displayName} 已存在该活动"
                        } else {
                            "${platform.displayName} 上传已确认"
                        },
                        Toast.LENGTH_SHORT,
                    ).show()
                    finish()
                } else {
                    statusText = "同步任务状态已变化，未记录为完成"
                }
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
        grantedUploadUri?.let { uri ->
            runCatching { revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        grantedUploadUri = null
        if (::platform.isInitialized && platform == Platform.GARMIN && jobId > 0) {
            FitSyncApp.instance.garminUploadFilePreparer.cleanup(jobId)
        }
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
        private const val GARMIN_DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val GARMIN_IMPORT_EVIDENCE_SCRIPT = """
            (function(){
              var text = ((document.body && document.body.innerText) || '').replace(/\s+/g, ' ').slice(0, 30000);
              var activityUrl = '';
              var links = document.querySelectorAll('a[href]');
              for (var i = 0; i < links.length; i++) {
                var href = links[i].href || '';
                if (/https:\/\/connect\.garmin\.cn\/(?:modern\/|app\/)?activity\/\d+/i.test(href)) {
                  activityUrl = href;
                  break;
                }
              }
              return JSON.stringify({href: location.href || '', text: text, activityUrl: activityUrl});
            })()
        """

        private fun isGarminLoginUrl(url: String): Boolean = runCatching {
            val uri = java.net.URI(url)
            val host = uri.host.orEmpty()
            val path = uri.path.orEmpty()
            host.equals("sso.garmin.cn", ignoreCase = true) ||
                path.contains("/signin", ignoreCase = true) ||
                path.contains("/portal/sso", ignoreCase = true)
        }.getOrDefault(false)

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

/** 仅在 Garmin 中国站 Connect 主页面成功落地后自动抓取会话；SSO/登录页仍等待用户完成操作。 */
internal fun shouldAutoCaptureGarminSession(url: String, mainFrameLoadFailed: Boolean): Boolean {
    if (mainFrameLoadFailed) return false
    return runCatching {
        val uri = java.net.URI(url)
        val host = uri.host.orEmpty()
        val path = uri.path.orEmpty()
        host.equals("connect.garmin.cn", ignoreCase = true) &&
            !path.contains("/signin", ignoreCase = true) &&
            !path.contains("/portal/sso", ignoreCase = true)
    }.getOrDefault(false)
}

/** 从可见或隐藏 WebView 抓取 Garmin 中国站会话，并加密持久化供后台同步使用。 */
internal fun persistGarminSessionFromWebView(
    view: WebView,
    sessions: WebSessionManager,
    connectCsrf: String?,
): Boolean {
    val cookieManager = CookieManager.getInstance()
    val cookieHeader = sequenceOf(
        "https://connect.garmin.cn/",
        view.url,
    ).mapNotNull(cookieManager::getCookie).joinToString("; ")
    val jwtWeb = cookieValue(cookieHeader, "JWT_WEB") ?: return false
    val csrf = connectCsrf?.takeIf { it.isNotBlank() } ?: sessions.garminCsrf()
    val ssoCookieHeader = sequenceOf(
        "https://sso.garmin.cn/",
        "https://sso.garmin.cn/sso/",
    ).mapNotNull(cookieManager::getCookie)
        .joinToString("; ")
        .takeIf { it.isNotBlank() }
    sessions.saveGarminSession(
        jwtWeb = jwtWeb,
        csrf = csrf,
        domain = "garmin.cn",
        userAgent = view.settings.userAgentString,
        cookieHeader = cookieHeader,
        ssoCookieHeader = ssoCookieHeader,
    )
    return true
}
