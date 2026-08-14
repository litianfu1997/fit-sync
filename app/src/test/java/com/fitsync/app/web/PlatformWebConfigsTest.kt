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
            Platform.STRAVA,
        ).forEach { platform ->
            val uri = URI(PlatformWebConfigs.get(platform).loginUrl)

            assertThat(uri.scheme).isEqualTo("https")
            assertThat(PlatformWebConfigs.isAllowed(platform, uri.host)).isTrue()
        }
    }

    @Test
    fun vendorAuthenticationRedirects_areAllowed() {
        assertThat(PlatformWebConfigs.isAllowed(Platform.GARMIN, "connect.garmin.cn")).isTrue()
        assertThat(PlatformWebConfigs.isAllowed(Platform.GARMIN, "sso.garmin.cn")).isTrue()
        assertThat(PlatformWebConfigs.isAllowed(Platform.GARMIN, "connect.garmin.com")).isFalse()
        assertThat(PlatformWebConfigs.isAllowed(Platform.GARMIN, "sso.garmin.com")).isFalse()
        assertThat(PlatformWebConfigs.isAllowed(Platform.IGPSPORT, "app.igpsport.cn")).isTrue()
        assertThat(PlatformWebConfigs.isAllowed(Platform.IGPSPORT, "login.passport.igpsport.cn")).isTrue()
        assertThat(PlatformWebConfigs.isAllowed(Platform.IGPSPORT, "app.igpsport.com")).isFalse()
        assertThat(PlatformWebConfigs.isAllowed(Platform.IGPSPORT, "login.passport.igpsport.com")).isFalse()
        assertThat(PlatformWebConfigs.isAllowed(Platform.XINGZHE, "www.imxingzhe.com")).isTrue()
    }

    @Test
    fun garminSessionAutoCapture_onlyRunsOnSuccessfulConnectLandingPage() {
        assertThat(shouldAutoCaptureGarminSession("https://connect.garmin.cn/modern/", false)).isTrue()
        assertThat(shouldAutoCaptureGarminSession("https://connect.garmin.cn/app/activity/1", false)).isTrue()
        assertThat(shouldAutoCaptureGarminSession("https://connect.garmin.cn/signin/?service=x", false)).isFalse()
        assertThat(shouldAutoCaptureGarminSession("https://sso.garmin.cn/sso/signin", false)).isFalse()
        assertThat(shouldAutoCaptureGarminSession("https://connect.garmin.cn/modern/", true)).isFalse()
        assertThat(shouldAutoCaptureGarminSession("not-a-url", false)).isFalse()
    }

    @Test
    fun igpsportWebEntries_useChinaSiteOnly() {
        val config = PlatformWebConfigs.get(Platform.IGPSPORT)

        assertThat(config.loginUrl).startsWith("https://login.passport.igpsport.cn/")
        assertThat(config.uploadUrl).startsWith("https://app.igpsport.cn/")
        assertThat(config.origins).containsExactly(
            "https://app.igpsport.cn",
            "https://login.passport.igpsport.cn",
        )
    }

    @Test
    fun corosWebEntry_usesActivityListForImportAndExport() {
        val config = PlatformWebConfigs.get(Platform.COROS)

        assertThat(config.uploadUrl)
            .isEqualTo("https://t.coros.com/admin/views/activities")
        assertThat(PlatformWebConfigs.isAllowed(Platform.COROS, URI(config.uploadUrl).host)).isTrue()
    }

    @Test
    fun stravaWebEntry_usesOfficialFileUploadPage() {
        val config = PlatformWebConfigs.get(Platform.STRAVA)

        assertThat(config.uploadUrl).isEqualTo("https://www.strava.com/upload/select")
        assertThat(PlatformWebConfigs.isAllowed(Platform.STRAVA, URI(config.uploadUrl).host)).isTrue()
    }

    @Test
    fun aliyunCaptchaHosts_areAllowed() {
        // 阿里云验证码（AliyunCaptcha）滑块通过这些第三方域加载，必须放行，否则 iframe 被当跳转拦截。
        assertThat(PlatformWebConfigs.isAllowed(Platform.ONELAP, "g.alicdn.com")).isTrue()
        assertThat(PlatformWebConfigs.isAllowed(Platform.ONELAP, "o.alicdn.com")).isTrue()
        assertThat(PlatformWebConfigs.isAllowed(Platform.ONELAP, "static-captcha.aliyuncs.com")).isTrue()
        assertThat(PlatformWebConfigs.isAllowed(Platform.ONELAP, "axpp9h.captcha-open.aliyuncs.com")).isTrue()
        assertThat(PlatformWebConfigs.isAllowed(Platform.ONELAP, "cloudauth-device-dualstack.cn-shanghai.aliyuncs.com")).isTrue()
        assertThat(PlatformWebConfigs.isAllowed(Platform.ONELAP, "webapi.amap.com")).isTrue()
        // 跨平台共享：行者登录同样能加载阿里云验证码
        assertThat(PlatformWebConfigs.isAllowed(Platform.XINGZHE, "g.alicdn.com")).isTrue()
        // 平台自有域仍放行
        assertThat(PlatformWebConfigs.isAllowed(Platform.ONELAP, "otm.onelap.cn")).isTrue()
        // 与验证无关的域仍拒绝（含形似域）
        assertThat(PlatformWebConfigs.isAllowed(Platform.ONELAP, "evil.com")).isFalse()
        assertThat(PlatformWebConfigs.isAllowed(Platform.ONELAP, "alicdn.com.evil.org")).isFalse()
    }

    @Test
    fun lookalikeDomains_areRejected() {
        assertThat(PlatformWebConfigs.isAllowed(Platform.GARMIN, "garmin.com.example.org")).isFalse()
        assertThat(PlatformWebConfigs.isAllowed(Platform.GARMIN, "garmin.cn.example.org")).isFalse()
        assertThat(PlatformWebConfigs.isAllowed(Platform.IGPSPORT, "fakeigpsport.com")).isFalse()
        assertThat(PlatformWebConfigs.isAllowed(Platform.IGPSPORT, "fakeigpsport.cn")).isFalse()
        assertThat(PlatformWebConfigs.isAllowed(Platform.XINGZHE, null)).isFalse()
    }
}
