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
            loginUrl = "https://connect.garmin.com/signin/?service=https%3A%2F%2Fconnect.garmin.com%2Fapp%2Fimport-data",
            uploadUrl = "https://connect.garmin.com/app/import-data",
            // 含中国镜像 connect.garmin.cn / sso.garmin.cn：服务端会把中国 IP 从 .com 重定向到 .cn
            allowedDomainSuffixes = setOf("garmin.com", "garmin.cn"),
            origins = setOf(
                "https://connect.garmin.com",
                "https://sso.garmin.com",
                "https://connectapi.garmin.com",
                "https://www.garmin.com",
                "https://accounts.garmin.com",
                "https://connect.garmin.cn",
                "https://sso.garmin.cn",
                "https://connectapi.garmin.cn",
                "https://www.garmin.cn",
            ),
        )
        Platform.COROS -> PlatformWebConfig(
            platform,
            loginUrl = "https://t.coros.com/login",
            uploadUrl = "https://t.coros.com/",
            allowedDomainSuffixes = setOf("coros.com"),
            origins = setOf("https://t.coros.com", "https://training.coros.com"),
        )
        Platform.IGPSPORT -> PlatformWebConfig(
            platform,
            loginUrl = "https://login.passport.igpsport.com/login",
            uploadUrl = "https://app.igpsport.com/sport/history/list",
            // 含中国镜像 app.igpsport.cn：服务端会把中国 IP 从 .com 重定向到 .cn
            allowedDomainSuffixes = setOf("igpsport.com", "igpsport.cn"),
            origins = setOf(
                "https://app.igpsport.com",
                "https://login.passport.igpsport.com",
                "https://app.igpsport.cn",
                "https://login.passport.igpsport.cn",
            ),
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
