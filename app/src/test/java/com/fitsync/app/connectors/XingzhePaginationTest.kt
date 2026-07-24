package com.fitsync.app.connectors

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class XingzhePaginationTest {
    @Test
    fun fullPageInsideWindow_continuesPagination() {
        assertThat(shouldContinueXingzhePagination(50, 50)).isTrue()
    }

    @Test
    fun fullPageBeforeWindow_stopsPagination() {
        assertThat(shouldContinueXingzhePagination(50, 0)).isFalse()
    }

    @Test
    fun partialPage_stopsPagination() {
        assertThat(shouldContinueXingzhePagination(49, 49)).isFalse()
    }
}
