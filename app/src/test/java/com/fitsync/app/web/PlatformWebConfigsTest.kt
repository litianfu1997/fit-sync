package com.fitsync.app.web

import com.fitsync.app.domain.model.Platform
import com.google.common.truth.Truth.assertThat
import java.net.URI
import org.junit.Test

class PlatformWebConfigsTest {
    @Test
    fun everyLoginEntry_isHttpsAndAllowed() {
        listOf(
            Platform.ONELAP,
            Platform.XINGZHE,
            Platform.GARMIN,
            Platform.COROS,
            Platform.IGPSPORT,
        ).forEach { platform ->
            val uri = URI(PlatformWebConfigs.get(platform).loginUrl)

            assertThat(uri.scheme).isEqualTo("https")
            assertThat(PlatformWebConfigs.isAllowed(platform, uri.host)).isTrue()
        }
    }

    @Test
    fun vendorAuthenticationRedirects_areAllowed() {
        assertThat(PlatformWebConfigs.isAllowed(Platform.GARMIN, "sso.garmin.com")).isTrue()
        assertThat(PlatformWebConfigs.isAllowed(Platform.IGPSPORT, "login.passport.igpsport.com")).isTrue()
        assertThat(PlatformWebConfigs.isAllowed(Platform.XINGZHE, "www.imxingzhe.com")).isTrue()
    }

    @Test
    fun lookalikeDomains_areRejected() {
        assertThat(PlatformWebConfigs.isAllowed(Platform.GARMIN, "garmin.com.example.org")).isFalse()
        assertThat(PlatformWebConfigs.isAllowed(Platform.IGPSPORT, "fakeigpsport.com")).isFalse()
        assertThat(PlatformWebConfigs.isAllowed(Platform.XINGZHE, null)).isFalse()
    }
}
