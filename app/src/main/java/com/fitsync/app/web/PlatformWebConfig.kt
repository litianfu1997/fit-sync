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
            allowedDomainSuffixes = setOf("garmin.com"),
            origins = setOf("https://connect.garmin.com", "https://sso.garmin.com"),
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
            allowedDomainSuffixes = setOf("igpsport.com"),
            origins = setOf("https://app.igpsport.com", "https://login.passport.igpsport.com"),
        )
        Platform.LOCAL_IMPORT, Platform.UNKNOWN -> error("$platform 没有网页账号")
    }

    fun isAllowed(platform: Platform, host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        return get(platform).allowedDomainSuffixes.any { suffix ->
            host == suffix || host.endsWith(".$suffix")
        }
    }
}
