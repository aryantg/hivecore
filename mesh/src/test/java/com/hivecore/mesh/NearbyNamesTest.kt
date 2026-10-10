package com.hivecore.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NearbyNamesTest {
    @Test fun exactlyOneSideOfEachPairInitiates() {
        val hr = "HR|14334752"; val sales = "Sales|cd0dea5a"; val acct = "Accounts|0a1b2c3d"
        for ((a, b) in listOf(hr to sales, hr to acct, sales to acct)) {
            assertNotEquals("$a / $b", NearbyTransport.initiates(a, b), NearbyTransport.initiates(b, a))
        }
        // The role name does not matter, only the nodeId prefix.
        assertEquals(true, NearbyTransport.initiates("Sales|0000aaaa", "Accounts|ffff0000"))
        assertEquals("cd0dea5a", NearbyTransport.nodePrefixOf(sales))
    }
}
