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
            (application as FitSyncApp).onFirstFrame()
        }
    }
}
