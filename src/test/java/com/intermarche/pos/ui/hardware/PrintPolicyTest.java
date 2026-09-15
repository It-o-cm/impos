package com.intermarche.pos.ui.hardware;

import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.sale.TicketLine;
import com.intermarche.pos.service.PosSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests of {@link PrintPolicy}, the conditional-printing rule (LC-08-03).
 * <p>
 * Each document of the decision is an OR of three legs (the cashier's choice,
 * the administered forced list, the document's own forced rule) and each leg is
 * exercised alone, the other two held false — a leg that silently stopped
 * working would otherwise hide behind its neighbours.
 */
class PrintPolicyTest {

    /**
     * A hand-built settings service: the six conditional-printing accessors are
     * overridden from public fields, so a test states exactly the back-office
     * configuration it exercises and nothing else.
     */
    private static class Settings extends PosSettingsService {

        /** Whether conditional printing is activated. */
        boolean conditional = true;

        /** The documents forced whatever the choice. */
        List<String> forced = new ArrayList<>();

        /** Whether a GLC article forces the sale ticket. */
        boolean forceGlc = false;

        /** Whether a refund forces the credit card receipt. */
        boolean forceCredit = false;

        /** Whether a signature forces the card receipt. */
        boolean forceSignature = false;

        /** Whether a not-completed transaction forces its receipt. */
        boolean forceTna = false;

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean printConditionalEnabled() { return conditional; }

        /**
         * {@inheritDoc}
         */
        @Override
        public List<String> printForcedDocuments() { return forced; }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean printForceTicketGlc() { return forceGlc; }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean printForceCardCredit() { return forceCredit; }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean printForceCardSignature() { return forceSignature; }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean printForceCardTna() { return forceTna; }
    }

    /** The administered configuration under test. */
    private Settings settings;

    /** The rule under test. */
    private PrintPolicy policy;

    /**
     * Wires a policy over a fresh configuration where every forced rule is off.
     */
    @BeforeEach
    void setUp() {
        settings = new Settings();
        policy = new PrintPolicy();
        policy.posSettingsService = settings;
    }

    /**
     * Builds a ticket carrying one live line on the given article.
     *
     * @param product the article of the line, possibly null
     * @param cancelled whether the line is cancelled
     * @return the ticket
     */
    private Ticket ticketWith(Product product, boolean cancelled) {
        Ticket ticket = new Ticket();
        TicketLine line = new TicketLine();
        line.product = product;
        line.cancelled = cancelled;
        ticket.lines.add(line);
        return ticket;
    }

    /**
     * Builds an article under the legal conformity guarantee.
     *
     * @return the GLC article
     */
    private Product glcProduct() {
        Product product = new Product();
        product.attributes.put(ProductAttributeCatalog.LEGAL_WARRANTY, "true");
        return product;
    }

    // --------------------------------------------------
    // Activation
    // --------------------------------------------------

    /**
     * With conditional printing off the decision names every document, whatever
     * the choice: the register keeps its historical behaviour (LC-08-03-01).
     */
    @Test
    void disabledPrintsEverythingWhateverTheChoice() {
        settings.conditional = false;
        PrintPolicy.Decision decision = policy.decide(PrintChoice.NONE, null, false);
        assertTrue(decision.saleTicket());
        assertTrue(decision.cardReceipt());
        assertTrue(decision.voucher());
    }

    /**
     * The activation flag is exposed as-is to the screen.
     */
    @Test
    void conditionalEnabledIsExposed() {
        settings.conditional = true;
        assertTrue(policy.isConditionalEnabled());
        settings.conditional = false;
        assertFalse(policy.isConditionalEnabled());
    }

    // --------------------------------------------------
    // The choice leg
    // --------------------------------------------------

    /**
     * NONE alone prints nothing: no forced rule is armed (LC-08-03-06).
     */
    @Test
    void noneWithoutForcedRulePrintsNothing() {
        PrintPolicy.Decision decision = policy.decide(PrintChoice.NONE, null, false);
        assertTrue(decision.isEmpty());
    }

    /**
     * ALL prints the three documents through the choice leg alone
     * (LC-08-03-02).
     */
    @Test
    void allPrintsEveryDocumentThroughTheChoiceLeg() {
        PrintPolicy.Decision decision = policy.decide(PrintChoice.ALL, null, false);
        assertTrue(decision.saleTicket());
        assertTrue(decision.cardReceipt());
        assertTrue(decision.voucher());
    }

    /**
     * A null choice is the standard behaviour — the cashier who closed without
     * choosing gets everything.
     */
    @Test
    void nullChoiceBehavesAsAll() {
        PrintPolicy.Decision decision = policy.decide(null, null, false);
        assertTrue(decision.saleTicket());
        assertTrue(decision.cardReceipt());
        assertTrue(decision.voucher());
    }

    /**
     * The sale-ticket choice leg alone (LC-08-03-03).
     */
    @Test
    void saleTicketChoiceLegAlone() {
        PrintPolicy.Decision decision = policy.decide(PrintChoice.SALE_TICKET, null, false);
        assertTrue(decision.saleTicket());
        assertFalse(decision.cardReceipt());
        assertFalse(decision.voucher());
    }

    /**
     * The card-receipt choice leg alone (LC-08-03-04).
     */
    @Test
    void cardReceiptChoiceLegAlone() {
        PrintPolicy.Decision decision = policy.decide(PrintChoice.CARD_RECEIPT, null, false);
        assertFalse(decision.saleTicket());
        assertTrue(decision.cardReceipt());
        assertFalse(decision.voucher());
    }

    /**
     * The voucher choice leg alone (LC-08-03-05).
     */
    @Test
    void voucherChoiceLegAlone() {
        PrintPolicy.Decision decision = policy.decide(PrintChoice.VOUCHER, null, false);
        assertFalse(decision.saleTicket());
        assertFalse(decision.cardReceipt());
        assertTrue(decision.voucher());
    }

    // --------------------------------------------------
    // The forced-list leg (LC-08-03-07)
    // --------------------------------------------------

    /**
     * A forced sale ticket prints under NONE, through the forced-list leg
     * alone.
     */
    @Test
    void forcedListLegAddsTheSaleTicket() {
        settings.forced = List.of(PrintPolicy.DOCUMENT_TICKET);
        PrintPolicy.Decision decision = policy.decide(PrintChoice.NONE, null, false);
        assertTrue(decision.saleTicket());
        assertFalse(decision.cardReceipt());
        assertFalse(decision.voucher());
    }

    /**
     * A forced card receipt prints under NONE, through the forced-list leg
     * alone.
     */
    @Test
    void forcedListLegAddsTheCardReceipt() {
        settings.forced = List.of(PrintPolicy.DOCUMENT_CARD);
        PrintPolicy.Decision decision = policy.decide(PrintChoice.NONE, null, false);
        assertFalse(decision.saleTicket());
        assertTrue(decision.cardReceipt());
        assertFalse(decision.voucher());
    }

    /**
     * A forced voucher prints under NONE, through the forced-list leg alone.
     */
    @Test
    void forcedListLegAddsTheVoucher() {
        settings.forced = List.of(PrintPolicy.DOCUMENT_VOUCHER);
        PrintPolicy.Decision decision = policy.decide(PrintChoice.NONE, null, false);
        assertFalse(decision.saleTicket());
        assertFalse(decision.cardReceipt());
        assertTrue(decision.voucher());
    }

    /**
     * A forced list naming another document leaves the decision empty — the
     * membership test really discriminates.
     */
    @Test
    void forcedListLegIgnoresAnUnrelatedDocument() {
        settings.forced = List.of("AUTRE");
        assertTrue(policy.decide(PrintChoice.NONE, null, false).isEmpty());
    }

    // --------------------------------------------------
    // The GLC leg (LC-08-03-09)
    // --------------------------------------------------

    /**
     * A GLC article forces the sale ticket under NONE, through the GLC leg
     * alone.
     */
    @Test
    void glcLegAddsTheSaleTicket() {
        settings.forceGlc = true;
        PrintPolicy.Decision decision =
                policy.decide(PrintChoice.NONE, ticketWith(glcProduct(), false), false);
        assertTrue(decision.saleTicket());
        assertFalse(decision.cardReceipt());
        assertFalse(decision.voucher());
    }

    /**
     * First leg of the GLC guard: the rule is off, the GLC ticket prints
     * nothing.
     */
    @Test
    void glcLegStaysDownWhenTheRuleIsOff() {
        settings.forceGlc = false;
        assertTrue(policy.decide(PrintChoice.NONE, ticketWith(glcProduct(), false), false).isEmpty());
    }

    /**
     * Second leg of the GLC guard: the rule is on but no article is under the
     * guarantee.
     */
    @Test
    void glcLegStaysDownWithoutAGlcArticle() {
        settings.forceGlc = true;
        assertTrue(policy.decide(PrintChoice.NONE, ticketWith(new Product(), false), false).isEmpty());
    }

    /**
     * A cancelled GLC line is outside the sale and forces nothing.
     */
    @Test
    void glcLegIgnoresACancelledLine() {
        settings.forceGlc = true;
        assertTrue(policy.decide(PrintChoice.NONE, ticketWith(glcProduct(), true), false).isEmpty());
    }

    /**
     * A line whose article is gone forces nothing.
     */
    @Test
    void glcLegIgnoresALineWithoutArticle() {
        settings.forceGlc = true;
        assertTrue(policy.decide(PrintChoice.NONE, ticketWith(null, false), false).isEmpty());
    }

    /**
     * An absent ticket forces nothing.
     */
    @Test
    void glcLegIgnoresAnAbsentTicket() {
        settings.forceGlc = true;
        assertTrue(policy.decide(PrintChoice.NONE, null, false).isEmpty());
    }

    /**
     * A ticket with no line collection forces nothing.
     */
    @Test
    void glcLegIgnoresATicketWithoutLines() {
        settings.forceGlc = true;
        Ticket ticket = new Ticket();
        ticket.lines = null;
        assertTrue(policy.decide(PrintChoice.NONE, ticket, false).isEmpty());
    }

    /**
     * A ticket with an empty line collection forces nothing.
     */
    @Test
    void glcLegIgnoresATicketWithNoLine() {
        settings.forceGlc = true;
        assertTrue(policy.decide(PrintChoice.NONE, new Ticket(), false).isEmpty());
    }

    // --------------------------------------------------
    // The signature leg (LC-08-03-11)
    // --------------------------------------------------

    /**
     * A signed card slip forces the card receipt under NONE, through the
     * signature leg alone.
     */
    @Test
    void signatureLegAddsTheCardReceipt() {
        settings.forceSignature = true;
        PrintPolicy.Decision decision = policy.decide(PrintChoice.NONE, null, true);
        assertFalse(decision.saleTicket());
        assertTrue(decision.cardReceipt());
        assertFalse(decision.voucher());
    }

    /**
     * First leg of the signature guard: the rule is off.
     */
    @Test
    void signatureLegStaysDownWhenTheRuleIsOff() {
        settings.forceSignature = false;
        assertTrue(policy.decide(PrintChoice.NONE, null, true).isEmpty());
    }

    /**
     * Second leg of the signature guard: no signature was asked for.
     */
    @Test
    void signatureLegStaysDownWithoutASignature() {
        settings.forceSignature = true;
        assertTrue(policy.decide(PrintChoice.NONE, null, false).isEmpty());
    }

    // --------------------------------------------------
    // The two standalone forced rules
    // --------------------------------------------------

    /**
     * The credit card receipt is forced when activated and the rule is on
     * (LC-08-03-10).
     */
    @Test
    void creditCardReceiptIsForcedWhenBothLegsHold() {
        settings.conditional = true;
        settings.forceCredit = true;
        assertTrue(policy.isCreditCardReceiptForced());
    }

    /**
     * First leg: conditional printing off leaves the credit receipt alone.
     */
    @Test
    void creditCardReceiptIsNotForcedWhenConditionalIsOff() {
        settings.conditional = false;
        settings.forceCredit = true;
        assertFalse(policy.isCreditCardReceiptForced());
    }

    /**
     * Second leg: the rule itself is off.
     */
    @Test
    void creditCardReceiptIsNotForcedWhenTheRuleIsOff() {
        settings.conditional = true;
        settings.forceCredit = false;
        assertFalse(policy.isCreditCardReceiptForced());
    }

    /**
     * The TNA receipt is forced when activated and the rule is on
     * (LC-08-03-12).
     */
    @Test
    void tnaReceiptIsForcedWhenBothLegsHold() {
        settings.conditional = true;
        settings.forceTna = true;
        assertTrue(policy.isTnaReceiptForced());
    }

    /**
     * First leg: conditional printing off leaves the TNA receipt alone.
     */
    @Test
    void tnaReceiptIsNotForcedWhenConditionalIsOff() {
        settings.conditional = false;
        settings.forceTna = true;
        assertFalse(policy.isTnaReceiptForced());
    }

    /**
     * Second leg: the rule itself is off.
     */
    @Test
    void tnaReceiptIsNotForcedWhenTheRuleIsOff() {
        settings.conditional = true;
        settings.forceTna = false;
        assertFalse(policy.isTnaReceiptForced());
    }

    // --------------------------------------------------
    // Decision.isEmpty
    // --------------------------------------------------

    /**
     * An all-false decision is empty.
     */
    @Test
    void decisionIsEmptyWhenNothingPrints() {
        assertTrue(new PrintPolicy.Decision(false, false, false).isEmpty());
    }

    /**
     * First leg of the emptiness guard: the sale ticket alone breaks it.
     */
    @Test
    void decisionIsNotEmptyWithTheSaleTicket() {
        assertFalse(new PrintPolicy.Decision(true, false, false).isEmpty());
    }

    /**
     * Second leg of the emptiness guard: the card receipt alone breaks it.
     */
    @Test
    void decisionIsNotEmptyWithTheCardReceipt() {
        assertFalse(new PrintPolicy.Decision(false, true, false).isEmpty());
    }

    /**
     * Third leg of the emptiness guard: the voucher alone breaks it.
     */
    @Test
    void decisionIsNotEmptyWithTheVoucher() {
        assertFalse(new PrintPolicy.Decision(false, false, true).isEmpty());
    }
}
