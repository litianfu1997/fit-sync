package com.fitsync.app.domain.model

enum class Platform(val displayName: String) {
    LOCAL_IMPORT("本地导入"),
    ONELAP("迈金 OnelapFit"),
    XINGZHE("行者"),
    GARMIN("Garmin Connect 中国站"),
    IGPSPORT("iGPSPORT"),
    COROS("COROS 高驰"),
    UNKNOWN("未知来源"),
}
