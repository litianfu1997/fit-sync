package com.fitsync.app.web

import com.fitsync.app.domain.model.Platform
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WebSessionManagerTest {

    @Test
    fun garminSessionCookie_acceptsKnownAndSessionLikeNamesOnly() {
        assertThat(hasGarminSessionCookie("JWT_WEB=abc; _ga=tracking")).isTrue()
        assertThat(hasGarminSessionCookie("GARMIN-SSO=abc; locale=zh-CN")).isTrue()
        assertThat(hasGarminSessionCookie("_ga=tracking; cookie-consent=yes")).isFalse()
        assertThat(hasGarminSessionCookie(null)).isFalse()
    }

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

    @Test
    fun sessionCookieScore_boostsSessionLikeNames() {
        assertThat(sessionCookieScore("SESSIONID=a; path=/")).isGreaterThan(
            sessionCookieScore("locale=zh; consent=1"),
        )
        assertThat(sessionCookieScore("")).isEqualTo(0)
    }

    @Test
    fun cookieValue_extractsJwtWebCaseInsensitively() {
        val header = "locale=zh; jwt_web=header.payload.signature; consent=1"

        assertThat(cookieValue(header, "JWT_WEB")).isEqualTo("header.payload.signature")
        assertThat(cookieValue(header, "missing")).isNull()
        assertThat(cookieValue(null, "JWT_WEB")).isNull()
    }

}
