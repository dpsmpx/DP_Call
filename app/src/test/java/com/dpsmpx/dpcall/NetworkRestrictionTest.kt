package com.dpsmpx.dpcall

import com.dpsmpx.dpcall.call.NetworkRestriction
import com.dpsmpx.dpcall.call.Restriction
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkRestrictionTest {
    @Test
    fun relaysDownButWhitelistedSiteOpens() {
        assertEquals(Restriction.MOBILE_WHITELIST, NetworkRestriction.classify(whitelistedReachable = true, cellular = true))
        assertEquals(Restriction.RELAYS_BLOCKED, NetworkRestriction.classify(whitelistedReachable = true, cellular = false))
    }

    @Test
    fun nothingOpensMeansSimplyOffline() {
        assertEquals(Restriction.NONE, NetworkRestriction.classify(whitelistedReachable = false, cellular = true))
        assertEquals(Restriction.NONE, NetworkRestriction.classify(whitelistedReachable = false, cellular = false))
    }
}
