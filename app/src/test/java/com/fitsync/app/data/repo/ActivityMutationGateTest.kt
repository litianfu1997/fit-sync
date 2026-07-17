package com.fitsync.app.data.repo

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ActivityMutationGateTest {
    @Test
    fun nestedLockIsReentrantWithinSameCoroutine() = runTest {
        val gate = ActivityMutationGate()

        val result = gate.withLock {
            gate.withLock { "完成" }
        }

        assertThat(result).isEqualTo("完成")
    }

    @Test
    fun concurrentMutationWaitsForCurrentMutation() = runTest {
        val gate = ActivityMutationGate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        val first = async {
            gate.withLock {
                order += "first-start"
                entered.complete(Unit)
                release.await()
                order += "first-end"
            }
        }
        entered.await()
        val second = async {
            gate.withLock { order += "second" }
        }

        assertThat(order).containsExactly("first-start")
        release.complete(Unit)
        first.await()
        second.await()

        assertThat(order).containsExactly("first-start", "first-end", "second").inOrder()
    }
}
