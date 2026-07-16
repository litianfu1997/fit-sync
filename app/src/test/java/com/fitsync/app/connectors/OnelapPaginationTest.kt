package com.fitsync.app.connectors

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OnelapPaginationTest {
    @Test
    fun `full ten item page continues automatically`() {
        assertThat(nextOnelapCursor(page = 1, itemCount = 10)?.raw).isEqualTo("2")
    }

    @Test
    fun `partial page finishes pagination`() {
        assertThat(nextOnelapCursor(page = 3, itemCount = 9)).isNull()
    }
}
