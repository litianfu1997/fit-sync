package com.fitsync.app.connectors

import com.fitsync.app.domain.model.SessionState
import com.fitsync.app.fit.FitCrc
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
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
    fun forbiddenResponse_refreshesSessionAndRetriesExactRequestOnce() = runTest {
        val first = FakeGarminHttpResponse(403, requestName = "LIST")
        var refreshCalls = 0
        val retriedRequests = mutableListOf<String>()

        val recovered = recoverGarminResponseOnce(
            firstResponse = first,
            responseCode = { it.code },
            responseLocation = { it.location },
            closeFirstResponse = { it.closed = true },
            refreshSession = {
                refreshCalls++
                SessionState.AUTHENTICATED
            },
            retryRequest = {
                retriedRequests += "LIST"
                FakeGarminHttpResponse(200, requestName = "LIST")
            },
        )

        assertThat(recovered.reason).isEqualTo(GarminSessionRefreshReason.FORBIDDEN)
        assertThat(recovered.response.code).isEqualTo(200)
        assertThat(recovered.retried).isTrue()
        assertThat(first.closed).isTrue()
        assertThat(refreshCalls).isEqualTo(1)
        assertThat(retriedRequests).containsExactly("LIST")
    }

    @Test
    fun forbiddenResponse_refreshFailureKeepsOriginalRetryableResponse() = runTest {
        val first = FakeGarminHttpResponse(403, requestName = "DOWNLOAD")
        var retryCalls = 0

        val recovered = recoverGarminResponseOnce(
            firstResponse = first,
            responseCode = { it.code },
            responseLocation = { it.location },
            closeFirstResponse = { it.closed = true },
            refreshSession = { SessionState.EXPIRED },
            retryRequest = {
                retryCalls++
                FakeGarminHttpResponse(200, requestName = "DOWNLOAD")
            },
        )

        assertThat(recovered.response).isSameInstanceAs(first)
        assertThat(recovered.refreshState).isEqualTo(SessionState.EXPIRED)
        assertThat(recovered.retried).isFalse()
        assertThat(first.closed).isFalse()
        assertThat(retryCalls).isEqualTo(0)
    }

    @Test
    fun forbiddenResponse_secondForbiddenDoesNotLoop() = runTest {
        var refreshCalls = 0
        var retryCalls = 0

        val recovered = recoverGarminResponseOnce(
            firstResponse = FakeGarminHttpResponse(403, requestName = "UPLOAD"),
            responseCode = { it.code },
            responseLocation = { it.location },
            closeFirstResponse = { it.closed = true },
            refreshSession = {
                refreshCalls++
                SessionState.AUTHENTICATED
            },
            retryRequest = {
                retryCalls++
                FakeGarminHttpResponse(403, requestName = "UPLOAD")
            },
        )

        assertThat(recovered.response.code).isEqualTo(403)
        assertThat(recovered.retried).isTrue()
        assertThat(refreshCalls).isEqualTo(1)
        assertThat(retryCalls).isEqualTo(1)
    }

    @Test
    fun nonAuthFailures_doNotRefreshSession() = runTest {
        for (code in listOf(429, 500, 503)) {
            var refreshCalls = 0
            var retryCalls = 0
            val recovered = recoverGarminResponseOnce(
                firstResponse = FakeGarminHttpResponse(code, requestName = "LIST"),
                responseCode = { it.code },
                responseLocation = { it.location },
                closeFirstResponse = { it.closed = true },
                refreshSession = {
                    refreshCalls++
                    SessionState.AUTHENTICATED
                },
                retryRequest = {
                    retryCalls++
                    FakeGarminHttpResponse(200, requestName = "LIST")
                },
            )

            assertThat(recovered.response.code).isEqualTo(code)
            assertThat(recovered.reason).isNull()
            assertThat(refreshCalls).isEqualTo(0)
            assertThat(retryCalls).isEqualTo(0)
        }
    }

    @Test
    fun loginRedirect_refreshFailureKeepsDefinitiveAuthResponse() = runTest {
        val first = FakeGarminHttpResponse(
            code = 302,
            location = "https://sso.garmin.cn/portal/sso/zh-CN/sign-in",
            requestName = "LIST",
        )

        val recovered = recoverGarminResponseOnce(
            firstResponse = first,
            responseCode = { it.code },
            responseLocation = { it.location },
            closeFirstResponse = { it.closed = true },
            refreshSession = { SessionState.EXPIRED },
            retryRequest = { FakeGarminHttpResponse(200, requestName = "LIST") },
        )

        assertThat(recovered.reason).isEqualTo(GarminSessionRefreshReason.AUTH_REQUIRED)
        assertThat(recovered.response).isSameInstanceAs(first)
        assertThat(isGarminLoginResponse(recovered.response.code, recovered.response.location)).isTrue()
    }

    @Test
    fun concurrentForbiddenResponses_shareSingleSessionRefresh() = runTest {
        val mutex = Mutex()
        val refreshCalls = AtomicInteger(0)
        var currentJwt = "old-jwt"

        val outcomes = List(6) {
            async {
                refreshGarminSessionOnce(
                    mutex = mutex,
                    jwtBeforeRequest = "old-jwt",
                    currentJwt = { currentJwt },
                    refresh = {
                        refreshCalls.incrementAndGet()
                        delay(10)
                        currentJwt = "new-jwt"
                        SessionState.AUTHENTICATED
                    },
                )
            }
        }.awaitAll()

        assertThat(refreshCalls.get()).isEqualTo(1)
        assertThat(outcomes.count { it.reusedExistingRefresh }).isEqualTo(5)
        assertThat(outcomes.map { it.state }).containsExactlyElementsIn(
            List(6) { SessionState.AUTHENTICATED },
        )
    }

    @Test
    fun pollForbidden_retriesOnlyThePollRequest() = runTest {
        val requestLog = mutableListOf("POLL")

        recoverGarminResponseOnce(
            firstResponse = FakeGarminHttpResponse(403, requestName = "POLL"),
            responseCode = { it.code },
            responseLocation = { it.location },
            closeFirstResponse = { it.closed = true },
            refreshSession = { SessionState.AUTHENTICATED },
            retryRequest = {
                requestLog += "POLL"
                FakeGarminHttpResponse(200, requestName = "POLL")
            },
        )

        assertThat(requestLog).containsExactly("POLL", "POLL").inOrder()
        assertThat(requestLog).doesNotContain("UPLOAD")
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
        assertThat(
            garminImportFailureSummary(
                """{"detailedImportResult":{"successes":[],"failures":[{"messages":[{"code":403,"content":"Upload consent is required"}]}]}}""",
            ),
        ).isEqualTo("code=403 message=Upload consent is required")
    }

    @Test
    fun finalImportRejection_isPermanentFileFailureNotAccountAction() {
        val result = garminImportRejectedFailure("code=500 message=processing failed")

        assertThat(result.code).isEqualTo("GARMIN_IMPORT_REJECTED")
        assertThat(result.message).isEqualTo(
            "Garmin 拒绝导入该文件：code=500 message=processing failed",
        )
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
        ).isEqualTo("https://connect.garmin.cn/gc-api/activity-service/activity/status/123/abc?upload=7")
        assertThat(
            garminStatusApiUrl(
                "https://connectapi.garmin.cn/activity-service/activity/status/123/abc?upload=7",
                domain = "garmin.cn",
            ),
        ).isEqualTo("https://connect.garmin.cn/gc-api/activity-service/activity/status/123/abc?upload=7")
        assertThat(garminStatusApiUrl("https://connectapi.garmin.cn/activity/status/123")).isNull()
        assertThat(garminStatusApiUrl("https://example.org/activity/status/123/abc")).isNull()
        assertThat(garminStatusApiUrl("http://connectapi.garmin.com/activity/status/123")).isNull()
    }

    @Test
    fun cookieMissing_returnsRetryableFailureWhenAccountPreviouslyConnected() {
        val r = garminCookieMissingResult(hasPersistedAccount = true, loginUrl = "https://connect.garmin.cn")
        assertThat(r).isInstanceOf(ConnectorResult.RetryableFailure::class.java)
        assertThat((r as ConnectorResult.RetryableFailure).code).isEqualTo(GARMIN_COOKIE_UNREADABLE_CODE)
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

        val direct = garminDirectSsoUrl("https://connect.garmin.cn/modern/")
        assertThat(direct).startsWith(
            "https://sso.garmin.cn/portal/sso/zh-CN/sign-in?clientId=GarminConnect&service=",
        )
        assertThat(direct).contains("https%3A%2F%2Fconnect.garmin.cn%2Fmodern%2F")
    }

    @Test
    fun safeResponseDetail_keepsErrorButRedactsCredentialsAndUrlQuery() {
        val detail = garminSafeResponseDetail(
            """{"message":"request token=secret failed at https://example.org/path?ticket=abc","code":500}""",
            "application/json",
        )

        assertThat(detail).contains("code=500")
        assertThat(detail).contains("token=[redacted]")
        assertThat(detail).contains("https://example.org/path")
        assertThat(detail).doesNotContain("secret")
        assertThat(detail).doesNotContain("ticket=abc")
    }

    @Test
    fun pollHttp500_stillParsesGarminDetailedImportResult() {
        val state = garminPollImportState(
            500,
            """{"detailedImportResult":{"successes":[{"internalId":23626050000}],"failures":[]}}""",
        )

        assertThat(state?.isFinal).isTrue()
        assertThat(state?.success).isTrue()
        assertThat(state?.remoteActivityId).isEqualTo("23626050000")
    }

    @Test
    fun pollOrdinaryClientError_doesNotTrustBusinessBody() {
        val state = garminPollImportState(
            400,
            """{"detailedImportResult":{"successes":[{"internalId":23626050000}],"failures":[]}}""",
        )

        assertThat(state).isNull()
    }

    @Test
    fun uploadFileName_isStableAsciiFitName() {
        assertThat(garminUploadFileName("0222A3C439D47B72-not-hex"))
            .isEqualTo("FitSync_0222a3c439d47b72.fit")
        assertThat(garminUploadFileName(""))
            .isEqualTo("FitSync_activity.fit")
    }

    @Test
    fun prepareFitBytes_repairsFileCrcWithoutChangingPayload() {
        val bytes = ByteArray(16)
        bytes[0] = 14
        bytes[1] = 0x20
        ".FIT".toByteArray(Charsets.US_ASCII).copyInto(bytes, 8)
        val headerCrc = FitCrc.compute(bytes, 0, 12)
        bytes[12] = (headerCrc and 0xFF).toByte()
        bytes[13] = ((headerCrc ushr 8) and 0xFF).toByte()
        bytes[14] = 0x34
        bytes[15] = 0x12

        val prepared = requireNotNull(prepareGarminFitBytes(bytes))

        assertThat(prepared.fileCrcRepaired).isTrue()
        assertThat(prepared.headerCrcRepaired).isFalse()
        assertThat(prepared.bytes.copyOfRange(0, 14)).isEqualTo(bytes.copyOfRange(0, 14))
        assertThat(FitCrc.readUInt16Le(prepared.bytes, 14))
            .isEqualTo(FitCrc.compute(prepared.bytes, 0, 14))
    }

    @Test
    fun fitCrc_matchesPublishedCrc16IbmCheckValue() {
        assertThat(FitCrc.compute("123456789".toByteArray(Charsets.US_ASCII)))
            .isEqualTo(0xBB3D)
    }

    @Test
    fun ssoRedirect_resolvesAbsoluteAndRelativeLocations() {
        assertThat(
            resolveGarminRedirect(
                "https://connect.garmin.cn/signin/?service=x",
                "https://sso.garmin.cn/sso/signin?service=x",
            ),
        ).isEqualTo("https://sso.garmin.cn/sso/signin?service=x")
        assertThat(
            resolveGarminRedirect(
                "https://sso.garmin.cn/sso/signin?service=x",
                "/sso/login?ticket=abc",
            ),
        ).isEqualTo("https://sso.garmin.cn/sso/login?ticket=abc")
        assertThat(resolveGarminRedirect("https://sso.garmin.cn/sso/", "http://example.org"))
            .isNull()
        assertThat(resolveGarminRedirect("https://sso.garmin.cn/sso/", "https://example.org/steal"))
            .isNull()
    }

    @Test
    fun ssoCookies_areMergedAcrossRedirectHops() {
        val merged = mergeGarminCookieHeader(
            currentHeader = "CASTGC=old; GDID=device",
            setCookieHeaders = listOf(
                "CASTGC=new; Path=/; Secure; HttpOnly",
                "JSESSIONID=session-1; Path=/sso; Secure",
            ),
        )

        assertThat(merged).isEqualTo("CASTGC=new; GDID=device; JSESSIONID=session-1")
        assertThat(
            mergeGarminCookieHeader(merged, listOf("JSESSIONID=; Max-Age=0; Path=/sso")),
        ).isEqualTo("CASTGC=new; GDID=device")
    }

    @Test
    fun csrf_isExtractedFromConnectHtmlOrEmbeddedConfig() {
        assertThat(
            extractGarminCsrfFromHtml("""<meta name="csrf-token" content="csrf-meta-1">"""),
        ).isEqualTo("csrf-meta-1")
        assertThat(
            extractGarminCsrfFromHtml("""window.config={"csrfToken":"csrf-json-2"};"""),
        ).isEqualTo("csrf-json-2")
        assertThat(extractGarminCsrfFromHtml("<html>no token</html>")).isNull()
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

private data class FakeGarminHttpResponse(
    val code: Int,
    val location: String? = null,
    val requestName: String,
    var closed: Boolean = false,
)
