package com.fersaiyan.cyanbridge.shared.devices.eyevue

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EyevuePeerMatchingTest {
    @Test
    fun missingNamesDoNotMatchAnAdvertisedSsid() {
        for (name in listOf(null, "", "   ", "OtherDevice")) {
            assertFalse(matchesEyevuePeerName("DIRECT-Eyevue", name))
        }
        assertFalse(matchesEyevuePeerName("", "Eyevue"))
    }

    @Test
    fun namedPeersMatchEitherVendorNamingConvention() {
        assertTrue(matchesEyevuePeerName("DIRECT-Eyevue", "eyevue"))
        assertTrue(matchesEyevuePeerName("Eyevue", "DIRECT-EYEVUE"))
    }
}
