package com.intermarche.pos.ui.journal;

import com.intermarche.pos.domain.ticket.CardPayment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PaymentTypes}.
 * <p>
 * Branch enumeration (100%): {@code forKey} covers the known-key arm (a class
 * is returned) and the unknown-key arm (null). {@code keys} exposes the six
 * discriminator keys in order.
 */
class PaymentTypesTest {

    /**
     * A known key resolves to its entity class (known arm).
     */
    @Test
    void knownKeyResolvesToClass() {
        assertEquals(CardPayment.class, PaymentTypes.forKey("CARD"));
    }

    /**
     * An unknown key resolves to null (unknown arm).
     */
    @Test
    void unknownKeyResolvesToNull() {
        assertNull(PaymentTypes.forKey("BITCOIN"));
    }

    /**
     * The key set carries the six known methods in display order.
     */
    @Test
    void keysAreTheSixMethods() {
        assertEquals("[CASH, CARD, CHEQUE, VOUCHER, FIDELITY, TR]", PaymentTypes.keys().toString());
        assertTrue(PaymentTypes.keys().contains("TR"));
    }
}
