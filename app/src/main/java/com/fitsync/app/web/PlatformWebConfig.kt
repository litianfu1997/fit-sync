package com.fitsync.app.web

import com.fitsync.app.domain.model.Platform

data class PlatformWebConfig(
    val platform: Platform,
    val loginUrl: String,
    val uploadUrl: String,
    val allowedDomainSuffixes: Set<String>,
    val origins: Set<String>,
)

object PlatformWebConfigs {
    fun get(platform: Platform): PlatformWebConfig = when (platform) {
        Platform.ONELAP -> PlatformWebConfig(
            platform,
            loginUrl = "https://otm.onelap.cn/recordPage",
            uploadUrl = "https://otm.onelap.cn/recordPage",
            allowedDomainSuffixes = setOf("onelap.cn"),
            origins = setOf("https://otm.onelap.cn"),
        )
        Platform.XINGZHE -> PlatformWebConfig(
            platform,
            // Without the trailing slash the vendor redirects to an http:// URL,
            // which is intentionally rejected by the secure WebView.
            loginUrl = "https://www.imxingzhe.com/login/?router_back=%2Fworkouts",
            uploadUrl = "https://www.imxingzhe.com/upload/fit",
            allowedDomainSuffixes = setOf("imxingzhe.com", "xingzhe.com"),
            origins = setOf("https://www.imxingzhe.com"),
        )
        Platform.GARMIN -> PlatformWebConfig(
            platform,
            loginUrl = "https://connect.garmin.cn/signin/?service=https%3A%2F%2Fconnect.garmin.cn%2Fapp%2Fimport-data",
            uploadUrl = "https://connect.garmin.cn/app/import-data",
            allowedDomainSuffixes = setOf("garmin.cn"),
            origins = setOf(
                "https://connect.garmin.cn",
                "https://sso.garmin.cn",
                "https://www.garmin.cn",
            ),
        )
        Platform.COROS -> PlatformWebConfig(
            platform,
            loginUrl = "https://t.coros.com/login",
            // 活动列表页同时提供“导入数据”和“导出数据”，作为 COROS 的统一网页操作入口。
            uploadUrl = "https://t.coros.com/admin/views/activities",
            allowedDomainSuffixes = setOf("coros.com"),
            origins = setOf("https://t.coros.com", "https://training.coros.com"),
        )
        Platform.IGPSPORT -> PlatformWebConfig(
            platform,
            loginUrl = "https://login.passport.igpsport.cn/login",
            uploadUrl = "https://app.igpsport.cn/sport/history/list",
            // 当前只接入中国站；后台 API 仍使用官方中国站后端 prod.zh.igpsport.com。
            allowedDomainSuffixes = setOf("igpsport.cn"),
            origins = setOf(
                "https://app.igpsport.cn",
                "https://login.passport.igpsport.cn",
            ),
        )
        Platform.STRAVA -> PlatformWebConfig(
            platform,
            loginUrl = "https://www.strava.com/login",
            uploadUrl = "https://www.strava.com/dashboard",
            allowedDomainSuffixes = setOf("strava.com"),
            origins = setOf("https://www.strava.com"),
        )
        Platform.LOCAL_IMPORT, Platform.UNKNOWN -> error("$platform 没有网页账号")
    }

    /**
     * 人机验证 / 风控第三方域名（阿里云验证码 AliyunCaptcha、高德定位）。
     *
     * 登录页用这些域加载验证码滑块（iframe 形式），必须放行；否则 WebView 会把验证码
     * iframe 当"不安全跳转"拦截，滑块弹不出，前端显示"安全验证失败"。
     * 平台自有的 API/数据域名仍由 [PlatformWebConfig.allowedDomainSuffixes] 严格限制。
     */
    private val captchaDomainSuffixes = setOf(
        "alicdn.com",   // 阿里云验证码前端 SDK / 滑块图（g.alicdn.com、o.alicdn.com）
        "aliyuncs.com", // 验证码 API、云设备认证（captcha-open / cloudauth-device / static-captcha）
        "amap.com",     // 高德地图（验证码风控定位）
        // Garmin / 国际站登录常见风控与人机验证
        "cloudflare.com",
        "cloudflareinsights.com",
        "recaptcha.net",
        "google.com",       // accounts.google.com 社交登录、www.google.com/recaptcha
        "gstatic.com",
        "googleapis.com",
        "apple.com",        // Sign in with Apple
        "appleid.apple.com",
    )

    fun isAllowed(platform: Platform, host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        if (captchaDomainSuffixes.any { host == it || host.endsWith(".$it") }) return true
        return get(platform).allowedDomainSuffixes.any { suffix ->
            host == suffix || host.endsWith(".$suffix")
        }
    }
}
