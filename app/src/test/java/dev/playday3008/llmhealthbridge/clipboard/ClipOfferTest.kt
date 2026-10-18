package dev.playday3008.llmhealthbridge.clipboard

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClipOfferTest {
    @Test fun newClipIsOffered() =
        assertTrue(ClipOffer.shouldOffer(timestamp = 20, label = null, lastSeen = 10))

    @Test fun seenClipIsNot() =
        assertFalse(ClipOffer.shouldOffer(timestamp = 10, label = null, lastSeen = 10))

    @Test fun ownInstructionsAreNeverOffered() = assertFalse(
        ClipOffer.shouldOffer(timestamp = 20, label = ClipOffer.OWN_LABEL, lastSeen = 0)
    )
}
