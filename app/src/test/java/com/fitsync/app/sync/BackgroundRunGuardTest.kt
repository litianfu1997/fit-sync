package com.fitsync.app.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BackgroundRunGuardTest {
    @Test
    fun resolveOemBrand_mapsCommonManufacturers() {
        assertThat(BackgroundRunGuard.resolveOemBrand("Xiaomi", "Redmi")).isEqualTo(OemBrand.XIAOMI)
        assertThat(BackgroundRunGuard.resolveOemBrand("POCO", "poco")).isEqualTo(OemBrand.XIAOMI)
        assertThat(BackgroundRunGuard.resolveOemBrand("HUAWEI", "HUAWEI")).isEqualTo(OemBrand.HUAWEI)
        assertThat(BackgroundRunGuard.resolveOemBrand("HONOR", "honor")).isEqualTo(OemBrand.HONOR)
        assertThat(BackgroundRunGuard.resolveOemBrand("OPPO", "OPPO")).isEqualTo(OemBrand.OPPO)
        assertThat(BackgroundRunGuard.resolveOemBrand("OnePlus", "OnePlus")).isEqualTo(OemBrand.OPPO)
        assertThat(BackgroundRunGuard.resolveOemBrand("realme", "realme")).isEqualTo(OemBrand.OPPO)
        assertThat(BackgroundRunGuard.resolveOemBrand("vivo", "vivo")).isEqualTo(OemBrand.VIVO)
        assertThat(BackgroundRunGuard.resolveOemBrand("iQOO", "iQOO")).isEqualTo(OemBrand.VIVO)
        assertThat(BackgroundRunGuard.resolveOemBrand("samsung", "samsung")).isEqualTo(OemBrand.SAMSUNG)
        assertThat(BackgroundRunGuard.resolveOemBrand("Google", "google")).isEqualTo(OemBrand.OTHER)
    }

    @Test
    fun computeNeedsAttention_onlyWhenAutoSyncAndRestricted() {
        assertThat(
            BackgroundRunGuard.computeNeedsAttention(
                autoSyncEnabled = true,
                batteryOptimizationIgnored = false,
                isBackgroundRestricted = false,
                notificationsGranted = true,
            ),
        ).isTrue()
        assertThat(
            BackgroundRunGuard.computeNeedsAttention(
                autoSyncEnabled = true,
                batteryOptimizationIgnored = true,
                isBackgroundRestricted = true,
                notificationsGranted = true,
            ),
        ).isTrue()
        assertThat(
            BackgroundRunGuard.computeNeedsAttention(
                autoSyncEnabled = true,
                batteryOptimizationIgnored = true,
                isBackgroundRestricted = false,
                notificationsGranted = false,
            ),
        ).isTrue()
        assertThat(
            BackgroundRunGuard.computeNeedsAttention(
                autoSyncEnabled = true,
                batteryOptimizationIgnored = true,
                isBackgroundRestricted = false,
                notificationsGranted = true,
            ),
        ).isFalse()
        assertThat(
            BackgroundRunGuard.computeNeedsAttention(
                autoSyncEnabled = false,
                batteryOptimizationIgnored = false,
                isBackgroundRestricted = true,
                notificationsGranted = false,
            ),
        ).isFalse()
    }

    @Test
    fun oemChecklist_isNonEmptyForAllBrands() {
        OemBrand.entries.forEach { brand ->
            assertThat(BackgroundRunGuard.oemChecklist(brand)).isNotEmpty()
            assertThat(BackgroundRunGuard.oemSummary(brand)).isNotEmpty()
        }
    }
}
