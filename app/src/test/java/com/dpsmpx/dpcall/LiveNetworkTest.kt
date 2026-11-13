package com.dpsmpx.dpcall

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dpsmpx.dpcall.util.NetworkTest
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Подробный тест сети на настоящей сети. Только с DPCALL_LIVE_RELAYS=1. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [30])
class LiveNetworkTest {
    @Test
    fun networkTestRuns() {
        assumeTrue(System.getenv("DPCALL_LIVE_RELAYS") == "1")
        val report = NetworkTest.run(ApplicationProvider.getApplicationContext()) {}
        println(report.lines().joinToString("\n") { "LIVE-NT $it" })
    }
}
