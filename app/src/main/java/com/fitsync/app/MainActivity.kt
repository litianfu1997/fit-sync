package com.fitsync.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.fitsync.app.ui.FitSyncAppRoot
import com.fitsync.app.ui.theme.FitSyncTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FitSyncTheme {
                FitSyncAppRoot()
            }
        }
        window.decorView.postOnAnimation {
            reportFullyDrawn()
            (application as FitSyncApp).also { app ->
                app.onFirstFrame()
                // 自动在不可见 WebView 中续期 Garmin SSO，会话有效时无需用户打开平台页。
                app.garminSilentSessionRefresher.refreshOnAppStart(this)
            }
        }
    }
}
