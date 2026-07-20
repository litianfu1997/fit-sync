package com.fitsync.app.strava

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.fitsync.app.FitSyncApp
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.web.PlatformWebActivity
import kotlinx.coroutines.launch

class StravaAuthActivity : androidx.activity.ComponentActivity() {
    private val app: FitSyncApp
        get() = application as FitSyncApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        val callback = intent.data
        if (callback != null && callback.scheme == "com.fitsync.app") {
            complete(callback)
            return
        }
        if (!app.stravaOAuthService.isConfigured()) {
            toast("请先在 FitSync 中配置 Strava Client ID 和 Client Secret")
            finish()
            return
        }
        val authorization = runCatching { app.stravaOAuthService.createAuthorizationUri() }
            .getOrElse {
                toast(it.message ?: "无法发起 Strava 授权")
                finish()
                return
            }
        startActivity(Intent(Intent.ACTION_VIEW, authorization))
    }

    private fun complete(callback: Uri) {
        lifecycleScope.launch {
            when (val result = app.stravaOAuthService.completeAuthorization(callback)) {
                is StravaOAuthResult.Success -> {
                    app.platformAccountRepository.validateAndPersist(Platform.STRAVA)
                    toast("Strava API 授权成功，可继续登录网页启用原文件拉取")
                    startActivity(
                        PlatformWebActivity.createIntent(
                            this@StravaAuthActivity,
                            Platform.STRAVA,
                            url = "https://www.strava.com/login",
                        ),
                    )
                    finish()
                }
                is StravaOAuthResult.Failure -> {
                    toast(result.message)
                    finish()
                }
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, StravaAuthActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
