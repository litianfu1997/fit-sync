package com.fitsync.app.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.compositionLocalOf

/**
 * 根级 Snackbar：跨页面操作（删除成功、合并完成）必须在页面销毁后仍能提示，
 * 因此宿主提升到 App 根 Scaffold，各屏通过本 CompositionLocal 取用。
 */
val LocalAppSnackbar = compositionLocalOf<SnackbarHostState?> { null }
