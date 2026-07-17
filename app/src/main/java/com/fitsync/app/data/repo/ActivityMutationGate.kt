package com.fitsync.app.data.repo

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

class ActivityMutationGate {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T {
        val current = currentCoroutineContext()[ActivityMutationContext]
        if (current?.gate === this) return block()

        return mutex.withLock {
            withContext(ActivityMutationContext(this)) { block() }
        }
    }

    private class ActivityMutationContext(
        val gate: ActivityMutationGate,
    ) : AbstractCoroutineContextElement(ActivityMutationContext) {
        companion object Key : CoroutineContext.Key<ActivityMutationContext>
    }
}
