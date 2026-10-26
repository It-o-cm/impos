package com.intermarche.pos.ui.payment;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** LC-07-07-04: verifies administered primary/fallback identity rather than hardcoded tender inference. */
class VerifonePaymentMappingTest {
    /** Known D16 wins over D46, while unknown D16 falls back to D46. */
    @Test
    void primaryThenAlternate() {
        String rules = "D16:3F=TRD;D46:XX=GIFTCARD";
        assertEquals("TRD", VerifonePaymentMapping.resolve(rules, "3F", "XX"));
        assertEquals("GIFTCARD", VerifonePaymentMapping.resolve(rules, "UNKNOWN", "XX"));
        assertEquals("GIFTCARD", VerifonePaymentMapping.resolve(rules, null, "XX"));
    }

    /** Missing, malformed and unknown rules retain the existing card settlement. */
    @Test
    void unknownMappingsKeepCard() {
        assertEquals("CARD", VerifonePaymentMapping.resolve(null, null, null));
        assertEquals("CARD", VerifonePaymentMapping.resolve("", "CB", null));
        assertEquals("CARD", VerifonePaymentMapping.resolve("bad;D16:CB=UNKNOWN;D16:CB=CARD=TRD", "CB", "XX"));
        assertEquals("CARD", VerifonePaymentMapping.resolve("D16:CB=CARD", "UNKNOWN", null));
    }
}
