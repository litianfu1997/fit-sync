package com.fitsync.app.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = PACKAGE_NAME,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.pkg(PACKAGE_NAME).depth(0)), 5_000)
        device.findObject(By.text("活动"))?.click()
        device.waitForIdle()
        repeat(5) {
            device.swipe(
                device.displayWidth / 2,
                device.displayHeight * 3 / 4,
                device.displayWidth / 2,
                device.displayHeight / 4,
                12,
            )
        }
        device.findObject(By.textContains("骑行"))?.click()
        device.waitForIdle()
    }
}

private const val PACKAGE_NAME = "com.fitsync.app"
