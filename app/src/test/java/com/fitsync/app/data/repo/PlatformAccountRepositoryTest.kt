package com.fitsync.app.data.repo

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlatformAccountRepositoryTest {
    @Test
    fun identityChange_requiresTwoKnownDifferentUserIds() {
        assertThat(hasPlatformIdentityChanged("old-user", "new-user")).isTrue()
        assertThat(hasPlatformIdentityChanged("same-user", "same-user")).isFalse()
        assertThat(hasPlatformIdentityChanged(null, "new-user")).isFalse()
        assertThat(hasPlatformIdentityChanged("old-user", null)).isFalse()
    }
}
