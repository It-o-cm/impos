package com.intermarche.pos.ui.hardware.terminal;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** LC-07: verifies responses from the documented WCL simulator, without physical proof. */
class VerifoneOutcomeTest {
    /** Preserves both receipt frames and reads the actual application, server and signature tags. */
    @Test
    void acceptedMetadataAndFrames() {
        TerminalOutcome result = TerminalOutcome.ofAmount(java.math.BigDecimal.ONE);
        result.readVerifone("{RS}01{D16}CB{D46}3F{VWAY}2{D1C}AUTH{D32}3{D1S}123511{D17}000123{D3L} CLIENT\n--/-- MERCHANT\n{F}");
        assertEquals("CB", result.meanCode);
        assertEquals("3F", result.alternateMeanCode);
        assertEquals("2", result.server);
        assertEquals("AUTH", result.authorizationNumber);
        assertTrue(result.degradedMode);
        assertTrue(result.signatureRequired);
        assertNull(result.entryMode);
        assertEquals(" CLIENT\n", result.customerReceiptFrame);
        assertEquals(" MERCHANT\n", result.merchantReceiptFrame);
        assertNull(result.tnaFrame);
    }

    /** Missing optional tags and a normal-mode marker preserve safe defaults. */
    @Test
    void absentMetadata() {
        TerminalOutcome result = new TerminalOutcome();
        result.readVerifone(null);
        result.readVerifone("{RS}00{D32}0{D1S}123");
        assertFalse(result.degradedMode);
        assertFalse(result.signatureRequired);
        assertNull(result.customerReceiptFrame);
        assertNull(result.server);
        assertNull(result.meanCode);
    }

    /** Signature digit eight is accepted while other values leave the signature flag false. */
    @Test
    void signatureDigits() {
        TerminalOutcome eight = new TerminalOutcome();
        eight.readVerifone("{D1S}1238");
        assertTrue(eight.signatureRequired);
        TerminalOutcome normal = new TerminalOutcome();
        normal.readVerifone("{D1S}1231");
        assertFalse(normal.signatureRequired);
        assertFalse(normal.degradedMode);
    }

    /** Declined single-frame receipts become TNA frames; malformed or absent decisions do not. */
    @Test
    void refusalFrames() {
        for (String code : new String[] {"10", "99", "-1"}) {
            TerminalOutcome result = new TerminalOutcome();
            result.readVerifone("{RS}" + code + "{D3L}REFUSED\n");
            assertEquals("REFUSED\n", result.tnaFrame);
            assertEquals("REFUSED\n", result.customerReceiptFrame);
            assertNull(result.merchantReceiptFrame);
        }
        for (String response : new String[] {"{D3L}UNDECIDED", "{RS}X{D3L}UNDECIDED"}) {
            TerminalOutcome result = new TerminalOutcome();
            result.readVerifone(response);
            assertNull(result.tnaFrame);
        }
    }
    /** LC-07-06-02: D10 is the actual partial amount; malformed cents must never be booked. */
    @Test
    void actualSettledAmount() {
        var outcome = TerminalOutcome.ofAmount(new java.math.BigDecimal("15.00"));
        outcome.readVerifone("{RS}01{D10}500");
        assertEquals(new java.math.BigDecimal("5.00"), outcome.amount);
        assertThrows(IllegalArgumentException.class, () -> outcome.readVerifone("{D10}oops"));
    }

    /** LC-07-17-05: additional donation ticket tags retain their exact text and ordering. */
    @Test
    void additionalDonationFrames() {
        var result = new TerminalOutcome();
        result.readVerifone("{RS}01{D3L}CLIENT\n--/--MERCHANT\n{D3L}DONATION\n{D3L}EXTRA");
        assertEquals("CLIENT\n", result.customerReceiptFrame);
        assertEquals("MERCHANT\n", result.merchantReceiptFrame);
        assertEquals(java.util.List.of("DONATION\n", "EXTRA"), result.additionalReceiptFrames);
    }

    /** LC-07-17-05: a second final-position ticket tag keeps the complete trailing frame. */
    @Test
    void trailingAdditionalFrameIsPreserved() {
        var result = new TerminalOutcome();
        result.readVerifone("{RS}01{D3L}CLIENT{D3L}DONATION");
        assertEquals(java.util.List.of("DONATION"), result.additionalReceiptFrames);
    }

}
