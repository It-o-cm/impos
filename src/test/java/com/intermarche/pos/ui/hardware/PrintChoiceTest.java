package com.intermarche.pos.ui.hardware;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests of {@link PrintChoice}: the documents each choice asks for
 * (LC-08-03-02 to LC-08-03-06) and the tolerant parsing of the value posted by
 * the completion modal.
 */
class PrintChoiceTest {

    /**
     * ALL asks for the three documents (LC-08-03-02).
     */
    @Test
    void allAsksForEveryDocument() {
        assertTrue(PrintChoice.ALL.isSaleTicket());
        assertTrue(PrintChoice.ALL.isCardReceipt());
        assertTrue(PrintChoice.ALL.isVoucher());
    }

    /**
     * SALE_TICKET asks for the sale ticket alone (LC-08-03-03).
     */
    @Test
    void saleTicketAsksForTheSaleTicketAlone() {
        assertTrue(PrintChoice.SALE_TICKET.isSaleTicket());
        assertFalse(PrintChoice.SALE_TICKET.isCardReceipt());
        assertFalse(PrintChoice.SALE_TICKET.isVoucher());
    }

    /**
     * CARD_RECEIPT asks for the card receipt alone (LC-08-03-04).
     */
    @Test
    void cardReceiptAsksForTheCardReceiptAlone() {
        assertFalse(PrintChoice.CARD_RECEIPT.isSaleTicket());
        assertTrue(PrintChoice.CARD_RECEIPT.isCardReceipt());
        assertFalse(PrintChoice.CARD_RECEIPT.isVoucher());
    }

    /**
     * VOUCHER asks for the purchase voucher alone (LC-08-03-05).
     */
    @Test
    void voucherAsksForTheVoucherAlone() {
        assertFalse(PrintChoice.VOUCHER.isSaleTicket());
        assertFalse(PrintChoice.VOUCHER.isCardReceipt());
        assertTrue(PrintChoice.VOUCHER.isVoucher());
    }

    /**
     * NONE asks for nothing (LC-08-03-06).
     */
    @Test
    void noneAsksForNothing() {
        assertFalse(PrintChoice.NONE.isSaleTicket());
        assertFalse(PrintChoice.NONE.isCardReceipt());
        assertFalse(PrintChoice.NONE.isVoucher());
    }

    /**
     * Every choice carries an operator-facing label.
     */
    @Test
    void everyChoiceCarriesALabel() {
        for (PrintChoice choice : PrintChoice.values()) {
            assertFalse(choice.getLabel() == null || choice.getLabel().isBlank(),
                    "libellé manquant pour " + choice);
        }
    }

    /**
     * Every choice carries a theme colour modifier for its button.
     */
    @Test
    void everyChoiceCarriesAButtonClass() {
        for (PrintChoice choice : PrintChoice.values()) {
            assertFalse(choice.getButtonClass() == null || choice.getButtonClass().isBlank(),
                    "classe de bouton manquante pour " + choice);
        }
    }

    /**
     * The three readings are told apart by colour: the standard behaviour is
     * the green button, printing nothing is the red one, the partial choices
     * stay neutral.
     */
    @Test
    void theStandardAndTheEmptyChoicesStandOut() {
        assertEquals("btn-success", PrintChoice.ALL.getButtonClass());
        assertEquals("btn-danger", PrintChoice.NONE.getButtonClass());
        assertEquals("btn-muted", PrintChoice.SALE_TICKET.getButtonClass());
        assertEquals("btn-muted", PrintChoice.CARD_RECEIPT.getButtonClass());
        assertEquals("btn-muted", PrintChoice.VOUCHER.getButtonClass());
    }

    /**
     * A missing form value resolves to ALL — a lost value never suppresses a
     * document.
     */
    @Test
    void ofNullFallsBackToAll() {
        assertEquals(PrintChoice.ALL, PrintChoice.of(null));
    }

    /**
     * An unknown value resolves to ALL.
     */
    @Test
    void ofUnknownFallsBackToAll() {
        assertEquals(PrintChoice.ALL, PrintChoice.of("PEUT_ETRE"));
    }

    /**
     * A blank value resolves to ALL.
     */
    @Test
    void ofBlankFallsBackToAll() {
        assertEquals(PrintChoice.ALL, PrintChoice.of("   "));
    }

    /**
     * Parsing ignores the case and the surrounding blanks.
     */
    @Test
    void ofIsCaseInsensitiveAndTrims() {
        assertEquals(PrintChoice.SALE_TICKET, PrintChoice.of("  sale_ticket "));
        assertEquals(PrintChoice.NONE, PrintChoice.of("None"));
    }

    /**
     * Every declared name parses back to its own constant.
     */
    @Test
    void ofParsesEveryDeclaredName() {
        for (PrintChoice choice : PrintChoice.values()) {
            assertEquals(choice, PrintChoice.of(choice.name()));
        }
    }
}
