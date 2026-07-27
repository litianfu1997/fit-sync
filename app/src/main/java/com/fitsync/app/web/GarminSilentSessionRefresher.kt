package com.fitsync.app.web

import android.graphics.Bitmap
import android.net.http.SslError
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.fitsync.app.data.db.FitSyncDatabase
import com.fitsync.app.data.repo.PlatformAccountRepository
import com.fitsync.app.domain.model.AccountStatus
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.domain.model.SessionState
import com.fitsync.app.sync.SyncScheduler
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 冷启动时用不可见 WebView 自动执行 Garmin 中国站现有 SSO 会话续登。
 *
 * Garmin 当前 `/signin/` 依赖 JavaScript 跳转且可能经过 Cloudflare，纯 OkHttp CAS 链只能作为后台兜底；
 * 此处复用系统 WebView 的既有 Cookie，无需用户打开平台页或输入账号密码。
 */
class GarminSilentSessionRefresher(
    private val db: FitSyncDatabase,
    private val sessions: WebSessionManager,
    private val accounts: PlatformAccountRepository,
) {
    private val refreshing = AtomicBoolean(false)

    fun refreshOnAppStart(activity: ComponentActivity) {
        if (!refreshing.compareAndSet(false, true)) return
        activity.lifecycleScope.launch {
            val shouldRefresh = withContext(Dispatchers.IO) {
                val settings = db.appSettingsDao().get()
                val account = db.platformAccountDao().getByPlatform(Platform.GARMIN)
                settings?.autoSyncEnabled != false &&
                    account != null &&
                    account.status != AccountStatus.DISABLED
            }
            if (!shouldRefresh) {
                refreshing.set(false)
                return@launch
            }
            runCatching { startHiddenRefresh(activity) }
                .onFailure { refreshing.set(false) }
        }
    }

    @Suppress("SetJavaScriptEnabled")
    private fun startHiddenRefresh(activity: ComponentActivity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: run {
            refreshing.set(false)
            return
        }
        val webView = WebView(activity).apply {
            visibility = View.INVISIBLE
            layoutParams = ViewGroup.LayoutParams(1, 1)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                userAgentString = GARMIN_DESKTOP_USER_AGENT
            }
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        root.addView(webView)

        var completed = false
        var timeoutJob: Job? = null
        fun finish(captured: Boolean) {
            if (completed) return
            completed = true
            timeoutJob?.cancel()
            runCatching { root.removeView(webView) }
            webView.stopLoading()
            webView.webViewClient = WebViewClient()
            webView.destroy()
            refreshing.set(false)
            if (captured) {
                activity.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        accounts.validateAndPersist(Platform.GARMIN, SessionState.AUTHENTICATED)
                    }
                    // 新 JWT/CSRF 已保存，立即重跑完整同步以补扫并恢复此前暂停的任务。
                    SyncScheduler.enqueueAutoNow(activity.applicationContext)
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                sessions.rememberWebViewUrl(Platform.GARMIN, url)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return true
                return uri.scheme != "https" || !PlatformWebConfigs.isAllowed(Platform.GARMIN, uri.host)
            }

            override fun onPageFinished(view: WebView, url: String) {
                sessions.rememberWebViewUrl(Platform.GARMIN, url)
                CookieManager.getInstance().flush()
                if (!shouldAutoCaptureGarminSession(url, mainFrameLoadFailed = false)) return
                view.evaluateJavascript(GARMIN_SESSION_PROOF_SCRIPT) { encoded ->
                    val proof = runCatching {
                        JSONObject(JSONArray("[$encoded]").getString(0))
                    }.getOrNull()
                    val captured = proof?.optBoolean("ok") == true &&
                        persistGarminSessionFromWebView(
                            view = view,
                            sessions = sessions,
                            connectCsrf = proof.optString("csrf"),
                        )
                    finish(captured)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) finish(false)
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.cancel()
                finish(false)
            }
        }
        timeoutJob = activity.lifecycleScope.launch {
            try {
                delay(REFRESH_TIMEOUT_MS)
            } finally {
                // Activity 销毁导致协程取消时也要释放 WebView，并允许下次启动重试。
                finish(false)
            }
        }
        webView.loadUrl(PlatformWebConfigs.get(Platform.GARMIN).loginUrl)
    }

    companion object {
        private const val REFRESH_TIMEOUT_MS = 30_000L
        private const val GARMIN_DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val GARMIN_SESSION_PROOF_SCRIPT = """
            (function(){
              var host = location.host || '';
              var path = location.pathname || '';
              var onConnect = /connect\.garmin\.cn/i.test(host);
              var onLoginPage = /\/signin\/?$/i.test(path) || /\/portal\/sso\//i.test(path);
              return JSON.stringify({
                ok: onConnect && !onLoginPage,
                csrf: (document.querySelector('meta[name="csrf-token"]') || {}).content || ''
              });
            })()
        """
    }
}
