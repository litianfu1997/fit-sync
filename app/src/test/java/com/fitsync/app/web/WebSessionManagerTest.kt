package com.fitsync.app.web

import com.fitsync.app.domain.model.Platform
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WebSessionManagerTest {
    @Test
    fun onelap_usesRawAuthorizationToken() {
        assertThat(authorizationHeaderValue(Platform.ONELAP, "onelap-token"))
            .isEqualTo("onelap-token")
    }

    @Test
    fun bearerPlatforms_keepBearerScheme() {
        assertThat(authorizationHeaderValue(Platform.GARMIN, "vendor-token"))
            .isEqualTo("Bearer vendor-token")
        assertThat(authorizationHeaderValue(Platform.GARMIN, "Bearer existing"))
            .isEqualTo("Bearer existing")
    }
}
