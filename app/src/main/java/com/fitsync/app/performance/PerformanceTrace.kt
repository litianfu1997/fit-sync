package com.fitsync.app.performance

import android.os.Build
import android.os.Trace
import java.util.concurrent.atomic.AtomicInteger

/** 使用异步 Trace 覆盖可能跨线程恢复的协程区段。 */
internal object PerformanceTrace {
    private val nextCookie = AtomicInteger()

    suspend fun <T> section(name: String, block: suspend () -> T): T {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return block()
        val cookie = nextCookie.incrementAndGet()
        Trace.beginAsyncSection(name, cookie)
        return try {
            block()
        } finally {
            Trace.endAsyncSection(name, cookie)
        }
    }
}
