package com.fitsync.app.strava

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StravaOAuthServiceTest {
    @Test
    fun requiredScopes_acceptsAllPermissionsInAnyOrder() {
        assertThat(
            StravaOAuthService.hasRequiredScopes("activity:write,read,activity:read_all"),
        ).isTrue()
    }

    @Test
    fun requiredScopes_rejectsMissingReadAllOrWrite() {
        assertThat(StravaOAuthService.hasRequiredScopes("read,activity:write")).isFalse()
        assertThat(StravaOAuthService.hasRequiredScopes("read,activity:read_all")).isFalse()
        assertThat(StravaOAuthService.hasRequiredScopes(null)).isFalse()
    }
}
