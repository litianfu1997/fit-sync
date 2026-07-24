package com.fitsync.app.connectors

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GarminConnectorTest {
    @Test
    fun remoteCopy_rejectsBlankActivityId() {
        assertThat(garminRemoteCopyOrNull(null, duplicate = true)).isNull()
        assertThat(garminRemoteCopyOrNull("   ", duplicate = false)).isNull()
        assertThat(garminRemoteCopyOrNull("123", duplicate = false)?.remoteActivityId).isEqualTo("123")
    }

    @Test
    fun gcApiRequest_usesChinaProxyHeaders() {
        val request = buildGarminGcApiRequest(
            url = "https://connect.garmin.cn/gc-api/activitylist-service/activities/search/activities?start=0&limit=1",
            cookieHeader = "JWT_WEB=current",
            csrf = "csrf-token",
            userAgent = "FitSync-Test",
            body = null,
        )

        assertThat(request.header("DI-Backend")).isEqualTo("connectapi.garmin.cn")
        assertThat(request.header("Cookie")).isEqualTo("JWT_WEB=current")
        assertThat(request.header("Connect-Csrf-Token")).isEqualTo("csrf-token")
        assertThat(request.header("Origin")).isEqualTo("https://connect.garmin.cn")
        assertThat(request.method).isEqualTo("GET")
    }

    @Test
    fun apiCookie_prefersCurrentWebViewJwtAndFallsBackToStoredJwt() {
        assertThat(
            selectGarminApiCookie(
                liveCookie = "locale=zh-CN; JWT_WEB=current",
                storedCookie = "JWT_WEB=old",
                storedJwt = "fallback",
            ),
        ).isEqualTo("locale=zh-CN; JWT_WEB=current")
        assertThat(
            selectGarminApiCookie(
                liveCookie = "locale=zh-CN",
                storedCookie = "tracking=1",
                storedJwt = "fallback",
            ),
        ).isEqualTo("JWT_WEB=fallback")
    }

    @Test
    fun loginResponse_onlyTreatsDefinitiveAuthFailuresAsExpired() {
        assertThat(isGarminLoginResponse(401, null)).isTrue()
        assertThat(isGarminLoginResponse(302, "https://sso.garmin.cn/sso/signin")).isTrue()
        assertThat(isGarminLoginResponse(302, "/signin/")).isTrue()
        assertThat(isGarminLoginResponse(403, null)).isFalse()
        assertThat(isGarminLoginResponse(503, null)).isFalse()
    }

    @Test
    fun uploadUrl_usesChinaGcApiProxy() {
        // 中国站后台上传走 connect.garmin.cn 同源 gc-api 代理（实测稳定），不走 connectapi 直连。
        assertThat(garminUploadUrl("garmin.com"))
            .isEqualTo("https://connect.garmin.cn/gc-api/upload-service/upload")
        assertThat(garminUploadUrl("garmin.cn"))
            .isEqualTo("https://connect.garmin.cn/gc-api/upload-service/upload")
    }

    @Test
    fun parseImportState_initialAcceptedResponseIsPending() {
        val state = parseGarminImportState(
            """
            {"detailedImportResult":{"uploadId":123,"successes":[],"failures":[]}}
            """.trimIndent(),
        )

        assertThat(state?.isFinal).isFalse()
        assertThat(state?.remoteActivityId).isNull()
    }

    @Test
    fun parseImportState_completedResponseReturnsActivityId() {
        val state = parseGarminImportState(
            """
            {"detailedImportResult":{"successes":[{"internalId":23626050000}],"failures":[]}}
            """.trimIndent(),
        )

        assertThat(state?.isFinal).isTrue()
        assertThat(state?.remoteActivityId).isEqualTo("23626050000")
        assertThat(state?.duplicate).isFalse()
    }

    @Test
    fun parseImportState_duplicateFailureIsRecognized() {
        val state = parseGarminImportState(
            """
            {"detailedImportResult":{"successes":[],"failures":[{"internalId":42,"messages":[{"code":"duplicate.activity"}]}]}}
            """.trimIndent(),
        )

        assertThat(state?.isFinal).isTrue()
        assertThat(state?.remoteActivityId).isEqualTo("42")
        assertThat(state?.duplicate).isTrue()
    }

    @Test
    fun parseImportState_realDuplicateUsesIntegerCode202() {
        // 真实 Garmin 重复上传返回 HTTP 409，messages[].code 是整数 202、content 含 "Duplicate"。
        val state = parseGarminImportState(
            """
            {"detailedImportResult":{"uploadId":"","successes":[],"failures":[{"internalId":20242598711,"messages":[{"code":202,"content":"Duplicate Activity."}]}]}}
            """.trimIndent(),
        )

        assertThat(state?.isFinal).isTrue()
        assertThat(state?.remoteActivityId).isEqualTo("20242598711")
        assertThat(state?.duplicate).isTrue()
    }

    @Test
    fun parseImportState_realSuccessCarriesUploadId() {
        val state = parseGarminImportState(
            """
            {"detailedImportResult":{"uploadId":212157427938,"uploadUuid":{"uuid":"abc"},"owner":2591602,"successes":[{"internalId":23626050000}],"failures":[]}}
            """.trimIndent(),
        )

        assertThat(state?.isFinal).isTrue()
        assertThat(state?.remoteActivityId).isEqualTo("23626050000")
        assertThat(state?.duplicate).isFalse()
        assertThat(state?.success).isTrue()
        assertThat(state?.uploadId).isEqualTo(212157427938L)
    }

    @Test
    fun parseImportState_nonDuplicateFailureIsFinalButNotSuccess() {
        // 例如 EU consent 拒绝：failures 非 duplicate，是终态但既不算成功也不算重复。
        val state = parseGarminImportState(
            """
            {"detailedImportResult":{"successes":[],"failures":[{"internalId":1,"messages":[{"code":403,"content":"The user is from EU location, but upload consent is not yet granted"}]}]}}
            """.trimIndent(),
        )

        assertThat(state?.isFinal).isTrue()
        assertThat(state?.success).isFalse()
        assertThat(state?.duplicate).isFalse()
    }

    @Test
    fun parseActivities_extractsIdNameSportDistanceAndStart() {
        val activities = requireNotNull(parseGarminActivities(
            """[{"activityId":535949177,"activityName":"Paris 虚拟骑行","startTimeGMT":"2025-11-04 10:58:33","activityType":{"typeKey":"virtual_ride"},"distance":90760.74},{"activityId":123,"activityName":"晨跑","startTimeGMT":"2025-01-01 00:00:00","activityType":{"typeKey":"running"},"distance":5000}]""",
        ))

        assertThat(activities).hasSize(2)
        val first = activities[0]
        assertThat(first.remoteActivityId).isEqualTo("535949177")
        assertThat(first.name).isEqualTo("Paris 虚拟骑行")
        assertThat(first.sportType).isEqualTo("virtual_ride")
        assertThat(first.distanceMeters).isEqualTo(90760.74)
        assertThat(first.startTimeEpochMs).isNotNull()
        assertThat(first.startTimeEpochMs).isGreaterThan(1_700_000_000_000L) // 2023 年之后
    }

    @Test
    fun parseActivities_distinguishesValidEmptyFromMalformedResponse() {
        assertThat(parseGarminActivities("[]")).isEmpty()
        assertThat(parseGarminActivities("{\"error\":\"login required\"}")).isNull()
    }

    @Test
    fun statusLocation_isNormalizedOnlyFromMatchingGarminHost() {
        assertThat(
            garminStatusApiUrl(
                "https://connectapi.garmin.com/activity-service/activity/status/123/abc",
            ),
        ).isEqualTo("https://connectapi.garmin.com/activity-service/activity/status/123/abc")
        assertThat(
            garminStatusApiUrl(
                "https://connect.garmin.cn/gc-api/activity-service/activity/status/123/abc?upload=7",
                domain = "garmin.cn",
            ),
        ).isEqualTo("https://connectapi.garmin.cn/activity-service/activity/status/123/abc?upload=7")
        assertThat(garminStatusApiUrl("https://connectapi.garmin.cn/activity/status/123")).isNull()
        assertThat(garminStatusApiUrl("https://example.org/activity/status/123/abc")).isNull()
        assertThat(garminStatusApiUrl("http://connectapi.garmin.com/activity/status/123")).isNull()
    }

    @Test
    fun cookieMissing_returnsRetryableFailureWhenAccountPreviouslyConnected() {
        val r = garminCookieMissingResult(hasPersistedAccount = true, loginUrl = "https://connect.garmin.cn")
        assertThat(r).isInstanceOf(ConnectorResult.RetryableFailure::class.java)
        assertThat((r as ConnectorResult.RetryableFailure).code).isEqualTo("GARMIN_COOKIE_UNREADABLE")
    }

    @Test
    fun cookieMissing_returnsRequiresLoginWhenNeverConnected() {
        val r = garminCookieMissingResult(hasPersistedAccount = false, loginUrl = "https://connect.garmin.cn")
        assertThat(r).isInstanceOf(ConnectorResult.RequiresLogin::class.java)
        assertThat((r as ConnectorResult.RequiresLogin).url).isEqualTo("https://connect.garmin.cn")
    }

    @Test
    fun apiCookie_returnsNullWhenAllSourcesEmpty() {
        assertThat(selectGarminApiCookie(liveCookie = null, storedCookie = null, storedJwt = null)).isNull()
        assertThat(selectGarminApiCookie(liveCookie = "", storedCookie = "", storedJwt = "")).isNull()
    }

    @Test
    fun loginResponse_normalModernRedirectIsNotExpired() {
        assertThat(isGarminLoginResponse(302, "https://connect.garmin.cn/modern/")).isFalse()
    }

    @Test
    fun ssoEntryUrl_encodesServiceParam() {
        val url = garminSsoEntryUrl("https://connect.garmin.cn/modern/")
        assertThat(url).startsWith("https://connect.garmin.cn/signin/?service=")
        assertThat(url).contains("https%3A%2F%2Fconnect.garmin.cn%2Fmodern%2F")
    }

    @Test
    fun extractJwtWeb_picksFirstJwtFromSetCookieHeaders() {
        assertThat(
            extractJwtWebFromHeaders(listOf("locale=zh; Path=/", "JWT_WEB=eyJabc; Path=/; Secure; HttpOnly")),
        ).isEqualTo("eyJabc")
        assertThat(extractJwtWebFromHeaders(listOf("JWT_WEB=\"quoted\"; Path=/"))).isEqualTo("quoted")
        assertThat(extractJwtWebFromHeaders(listOf("OTHER=x"))).isNull()
        assertThat(extractJwtWebFromHeaders(emptyList())).isNull()
    }

    @Test
    fun isGarminSsoHost_detectsSsoDomain() {
        assertThat(isGarminSsoHost("https://sso.garmin.cn/sso/signin")).isTrue()
        assertThat(isGarminSsoHost("https://connect.garmin.cn/modern/")).isFalse()
        assertThat(isGarminSsoHost("not a url")).isFalse()
    }

    @Test
    fun ssoCookie_prefersLiveCastgc() {
        val choice = selectGarminSsoCookie(liveSso = "GDID=x; CASTGC=live-ticket", storedSso = "CASTGC=stored-ticket")
        assertThat(choice.source).isEqualTo("LIVE")
        assertThat(choice.cookie).contains("CASTGC=live-ticket")
    }

    @Test
    fun ssoCookie_fallsBackToStoredWhenLiveLacksCastgc() {
        val choice = selectGarminSsoCookie(liveSso = "GDID=x", storedSso = "CASTGC=stored-ticket")
        assertThat(choice.source).isEqualTo("STORED")
        assertThat(choice.cookie).isEqualTo("CASTGC=stored-ticket")
    }

    @Test
    fun ssoCookie_treatsBlankLiveAsMissing() {
        val choice = selectGarminSsoCookie(liveSso = "", storedSso = "CASTGC=stored-ticket")
        assertThat(choice.source).isEqualTo("STORED")
    }

    @Test
    fun ssoCookie_prefersLiveWhenBothHaveCastgc() {
        val choice = selectGarminSsoCookie(liveSso = "CASTGC=live", storedSso = "CASTGC=stored")
        assertThat(choice.source).isEqualTo("LIVE")
        assertThat(choice.cookie).isEqualTo("CASTGC=live")
    }

    @Test
    fun ssoCookie_returnsNoneWhenNeitherHasCastgc() {
        val choice = selectGarminSsoCookie(liveSso = "GDID=x", storedSso = "GDID=y")
        assertThat(choice.source).isEqualTo("NONE")
        // 尽力一搏：优先 live 的非空串
        assertThat(choice.cookie).isEqualTo("GDID=x")
    }

    @Test
    fun ssoCookie_returnsNullWhenEverythingEmpty() {
        val choice = selectGarminSsoCookie(liveSso = null, storedSso = null)
        assertThat(choice.source).isEqualTo("NONE")
        assertThat(choice.cookie).isNull()
    }

    @Test
    fun extractCookieValue_picksNamedValueFromSetCookie() {
        assertThat(
            extractCookieValueFromSetCookie(
                listOf("OTHER=x; Path=/", "CASTGC=abc; Path=/; Secure; HttpOnly"),
                "CASTGC",
            ),
        ).isEqualTo("abc")
        assertThat(extractCookieValueFromSetCookie(emptyList(), "CASTGC")).isNull()
    }
}
