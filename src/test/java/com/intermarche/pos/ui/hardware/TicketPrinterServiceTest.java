package com.intermarche.pos.ui.hardware;

import com.intermarche.pos.domain.store.Address;
import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.store.Store;
import com.intermarche.pos.domain.payment.CashPayment;
import com.intermarche.pos.domain.payment.FidelityPayment;
import com.intermarche.pos.domain.sale.Refund;
import com.intermarche.pos.domain.sale.RefundLine;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.sale.TicketLine;
import com.intermarche.pos.domain.sale.TicketFidelityLine;
import com.intermarche.pos.domain.sale.TicketLineValuation;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.fidelity.FidelityState;
import com.intermarche.pos.ui.payment.PaymentState;
import com.intermarche.pos.ui.ticket.TicketState;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import com.intermarche.pos.domain.setting.DocumentTemplate;
import com.intermarche.pos.service.DocumentTemplateService;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.service.TechnicalEventService;

/**
 * Unit tests for {@link TicketPrinterService}.
 * <p>
 * The service renders receipts from persisted entities and pushes the result
 * to the {@link HardwareService}. The two collaborators ({@link HardwareService}
 * and {@link TechnicalEventService}) are Mockito mocks assigned to the
 * package-private injection fields; the Panache static access of {@code
 * printTicket} ({@code Ticket.findById}, {@code TicketLineValuation.list}) and
 * {@code printRefund} ({@code Refund.findById}, {@code Ticket.findById}) is
 * intercepted with {@link org.mockito.Mockito#mockStatic} on
 * {@link PanacheEntityBase}, since the entities are not bytecode-enhanced under
 * plain {@code mvn test}. The printed ticket entity is a Mockito mock so its
 * {@code persist()} is a no-op while its public fields are read and mutated
 * directly; every other entity (store, cashier, session, refund, lines,
 * valuations) is a plain instance with its public fields set. No database and
 * no Quarkus context is booted. The rendered text is captured with an
 * {@link ArgumentCaptor} and asserted on absolute expected substrings.
 * <p>
 * Every branch of the five public methods and the private helpers is covered:
 * {@code printTicket} (missing ticket, original vs numbered duplicata, the
 * three product-label and advantage-label arms, the valuation present/absent
 * and delta/no-delta arms, the advantage-label truncation arms, the digital
 * key present/absent arms), {@code printSessionReport} (X snapshot, Z closing
 * with and without a closing date, refunds present/absent), {@code printRefund}
 * (missing refund, missing original, store present/absent, address
 * present/absent, label truncation, method present/absent, the four
 * {@code refundMethodLabel} switch arms), {@code printTrainingReceipt} (the
 * {@code center} full-width arm and both {@code formatLine} spacing arms) and
 * {@code printRefundVoucher} (encodable vs plain). JaCoCo branch count is
 * reported by the per-class workflow after {@code mvn verify}.
 */
class TicketPrinterServiceTest {

    /** A fixed timestamp used across the receipts. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 2, 10, 30);

    /**
     * Builds a service instance with the two collaborators mocked.
     *
     * @return a ready-to-use service with mocked collaborators
     */
    private TicketPrinterService newService() {
        TicketPrinterService service = new TicketPrinterService();
        service.hardwareService = mock(HardwareService.class);
        service.technicalEventService = mock(TechnicalEventService.class);
        // Back-office parameters at their defaults: no EAN on paper. The
        // fidelity advantages default to ON, the pre-existing behavior the
        // loyalty-section cases rely on (BO-10-03-15).
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.fidelityAdvantagesEnabled()).thenReturn(true);
        // VAT breakdown defaults to ON, the pre-existing behavior every ticket
        // case relies on (BO-10-06-04).
        when(service.posSettingsService.vatBreakdownEnabled()).thenReturn(true);
        return service;
    }

    /**
     * Builds a persisted cashier with the given identity.
     *
     * @return a plain cashier whose {@code getFullName} yields "Jean Dupont"
     */
    private Employee cashier() {
        Employee cashier = new Employee();
        cashier.firstName = "Jean";
        cashier.lastName = "Dupont";
        return cashier;
    }

    /**
     * Builds a store carrying a name and a city.
     *
     * @return a plain store with an address whose city is "LYON"
     */
    private Store store() {
        Store store = new Store();
        store.name = "MAGASIN LYON";
        Address address = new Address();
        address.city = "LYON";
        store.address = address;
        return store;
    }

    /**
     * Builds a mocked ticket with the common header, cashier, store, totals and
     * empty line and payment lists.
     *
     * @param printCount the number of prior prints (>=1 means duplicata)
     * @param digitalKey the digital receipt key, or null
     * @return the configured mocked ticket
     */
    private Ticket ticket(int printCount, String digitalKey) {
        Ticket ticket = mock(Ticket.class);
        ticket.id = 1L;
        ticket.printCount = printCount;
        ticket.ticketNumber = "C04-00000001";
        ticket.creationDate = NOW;
        ticket.store = store();
        ticket.cashier = cashier();
        ticket.totalIncludingTax = new BigDecimal("12.00");
        ticket.totalVat = new BigDecimal("2.00");
        ticket.digitalKey = digitalKey;
        ticket.lines = new ArrayList<>();
        ticket.payments = new ArrayList<>();
        ticket.fidelityLines = new ArrayList<>();
        return ticket;
    }

    /**
     * Builds a persisted ticket line.
     *
     * @param uid the stable line uid
     * @param label the product label
     * @param quantity the quantity
     * @param unitPrice the unit price
     * @param totalPrice the line total
     * @return the configured line
     */
    private TicketLine line(String uid, String label, String quantity, String unitPrice, String totalPrice) {
        TicketLine line = new TicketLine();
        line.lineUid = uid;
        line.productLabel = label;
        line.quantity = new BigDecimal(quantity);
        line.unitPrice = new BigDecimal(unitPrice);
        line.totalPrice = new BigDecimal(totalPrice);
        line.vatRate = new BigDecimal("0.2000");
        return line;
    }

    /**
     * Builds a per-line valuation trace.
     *
     * @param uid the valued line uid
     * @param localTotal the register-local total
     * @param valuedTotal the engine-valued total
     * @param advantageLabel the advantage label, or null
     * @param offerLabel the offer label, or null
     * @return the configured valuation
     */
    private TicketLineValuation valuation(String uid, String localTotal, String valuedTotal,
            String advantageLabel, String offerLabel) {
        TicketLineValuation valuation = new TicketLineValuation();
        valuation.lineUid = uid;
        valuation.localTotal = new BigDecimal(localTotal);
        valuation.valuedTotal = new BigDecimal(valuedTotal);
        valuation.advantageLabel = advantageLabel;
        valuation.offerLabel = offerLabel;
        return valuation;
    }

    /**
     * Captures the single receipt text pushed to the printer by the service.
     *
     * @param service the service whose printer mock is inspected
     * @return the rendered receipt text
     */
    private String captureReceipt(TicketPrinterService service) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(service.hardwareService).printReceipt(captor.capture());
        verify(service.hardwareService).cutPaper();
        return captor.getValue();
    }

    /**
     * Builds a refund line.
     *
     * @param label the product label
     * @param quantity the refunded quantity
     * @param price the unit price
     * @return the configured refund line
     */
    private RefundLine refundLine(String label, String quantity, String price) {
        RefundLine line = new RefundLine();
        line.productLabel = label;
        line.quantity = new BigDecimal(quantity);
        line.price = new BigDecimal(price);
        line.vatRate = new BigDecimal("0.2000");
        return line;
    }

    /**
     * Builds a persisted refund pointing to the given original ticket id.
     *
     * @param originalTicketId the refunded ticket id
     * @param method the refund method, or null
     * @param line the single refund line
     * @return the configured refund
     */
    private Refund refund(Long originalTicketId, Refund.RefundMethod method, RefundLine line) {
        Refund refund = new Refund();
        refund.id = 7L;
        refund.creationDate = NOW;
        refund.totalAmount = new BigDecimal("6.00");
        refund.originalTicketId = originalTicketId;
        refund.refundMethod = method;
        refund.lines = new ArrayList<>(Collections.singletonList(line));
        return refund;
    }

    /**
     * Builds the refund the administered-layout cases run on: one line of
     * butter, settled in cash, against the sale the {@code ticket} fixture
     * builds.
     *
     * @return the refund
     */
    private Refund refund() {
        Refund refund = refund(1L, Refund.RefundMethod.CASH,
                refundLine("BEURRE", "2", "2.00"));
        refund.refundNumber = "C04-R000012";
        refund.terminalId = "C04";
        refund.totalAmount = new BigDecimal("4.00");
        return refund;
    }

    // --------------------------------------------------
    // printTicket
    // --------------------------------------------------

    /**
     * Covers the missing-ticket guard of {@code printTicket}: the finder
     * resolves to null, so the method throws and nothing is printed.
     */
    @Test
    void printTicketThrowsWhenTicketMissing() {
        TicketPrinterService service = newService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(null);
            assertThrows(IllegalArgumentException.class, () -> service.printTicket(1L));
            verifyNoInteractions(service.hardwareService);
            verifyNoInteractions(service.technicalEventService);
        }
    }

    /**
     * Covers the original-print arm of {@code printTicket}: {@code printCount}
     * is 0 (not a duplicata), the label is short (no truncation), no valuation
     * matches the line (absent-valuation arm) and the digital key is null. The
     * print is counted, the ticket persisted and nothing is journaled.
     */
    @Test
    void printTicketPrintsOriginalWithoutDuplicataOrDigitalKey() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        ticket.payments.add(new CashPayment(new BigDecimal("12.00"), new BigDecimal("12.00")));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            assertEquals(1, ticket.printCount);
            verify(ticket, times(1)).persist();
            verifyNoInteractions(service.technicalEventService);
            String out = captureReceipt(service);
            assertTrue(out.contains("INTERMARCHE"));
            assertTrue(out.contains("MAGASIN LYON"));
            assertTrue(out.contains("Vendeur: Jean Dupont"));
            assertTrue(out.contains("CASH"));
            assertFalse(out.contains("DUPLICATA"));
            assertFalse(out.contains("Votre ticket en ligne"));
        }
    }

    /**
     * A cancelled article (lot C4, BO-04-01-16) is kept on the ticket only as a
     * journal witness: {@code printTicket} skips it in the line list AND in the
     * per-rate VAT ventilation (the two {@code line.cancelled} true arms), so
     * its label never prints and it moves no VAT bucket.
     */
    @Test
    void printTicketOmitsACancelledArticle() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        TicketLine cancelled = line("U2", "PRODUIT ANNULE", "1", "5.00", "5.00");
        cancelled.cancelled = true;
        cancelled.vatRate = new BigDecimal("0.0550");
        ticket.lines.add(cancelled);
        ticket.payments.add(new CashPayment(new BigDecimal("12.00"), new BigDecimal("12.00")));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.contains("PAIN"));
            assertFalse(out.contains("PRODUIT ANNULE"));
            assertTrue(out.contains("TVA 20"));
            assertFalse(out.contains("TVA 5,5"));
        }
    }

    /**
     * With the VAT breakdown disabled (BO-10-06-04), the sale ticket carries no
     * per-rate ventilation line at all — the false arm of the new gate.
     */
    @Test
    void printTicketOmitsVatBreakdownWhenDisabled() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.vatBreakdownEnabled()).thenReturn(false);
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        ticket.payments.add(new CashPayment(new BigDecimal("2.00"), new BigDecimal("2.00")));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.contains("PAIN"));
            assertFalse(out.contains("TVA 20"));
        }
    }

    /**
     * Covers the numbered-duplicata arm of {@code printTicket}: {@code
     * printCount} is 1 (duplicata n°1), the label is longer than 20 characters
     * (truncation arm), a valuation with a non-zero delta and a non-null
     * advantage label longer than 26 characters is printed (advantage-label and
     * truncation arms), and the digital key is present. The duplicata is
     * journaled.
     */
    @Test
    void printTicketPrintsNumberedDuplicataWithValuationAndDigitalKey() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(1, "ABCD1234EF567890");
        ticket.lines.add(line("U1", "PRODUIT AVEC UN NOM TRES LONG", "1", "10.00", "8.00"));
        List<TicketLineValuation> valuations = new ArrayList<>();
        valuations.add(valuation("U1", "10.00", "8.00",
                "AVANTAGE FIDELITE EXCEPTIONNEL DE LA SEMAINE", "OFFRE"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L)).thenReturn(valuations);
            service.printTicket(1L);
            assertEquals(2, ticket.printCount);
            verify(ticket, times(1)).persist();
            verify(service.technicalEventService).log(
                    TechnicalEvent.EventType.DUPLICATA_PRINTED, "C04-00000001 n°1");
            String out = captureReceipt(service);
            assertTrue(out.contains("*** DUPLICATA N°1 ***"));
            assertTrue(out.contains("PRODUIT AVEC UN NOM"));
            assertTrue(out.contains("AVANTAGE FIDELITE EXCEPTIO"));
            assertTrue(out.contains("Votre ticket en ligne"));
            assertTrue(out.contains("/t/1/ABCD1234EF567890"));
        }
    }

    // --- printCardReceipt: what it printed, and how much of it (LC-08-03-11) ---

    /**
     * A card payment yields one slip, and the method says so — the count is what
     * lets DERNIER : DUPLICATA CB tell a printed duplicate from a silent one
     * (card-payment arm).
     */
    @Test
    void printCardReceiptCountsTheSlipItPrinted() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        com.intermarche.pos.domain.payment.CardPayment card =
                new com.intermarche.pos.domain.payment.CardPayment(new BigDecimal("12.00"));
        card.authorizationNumber = "A1234";
        ticket.payments.add(card);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            assertEquals(1, service.printCardReceipt(1L, false, "DUPLICATA"));
        }
        assertTrue(captureReceipt(service).contains("DUPLICATA"));
    }

    /**
     * A sale settled without a card prints nothing and counts nothing — the
     * loop's continue arm, and the case the operator must be told about.
     */
    @Test
    void printCardReceiptCountsNothingWithoutACardPayment() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        com.intermarche.pos.domain.payment.CashPayment cash =
                new com.intermarche.pos.domain.payment.CashPayment(
                        new BigDecimal("12.00"), new BigDecimal("12.00"));
        ticket.payments.add(cash);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            assertEquals(0, service.printCardReceipt(1L, false, "DUPLICATA"));
        }
        verifyNoInteractions(service.hardwareService);
    }

    /**
     * An unknown ticket prints nothing and counts nothing (ticket-null arm).
     */
    @Test
    void printCardReceiptCountsNothingOnAnUnknownTicket() {
        TicketPrinterService service = newService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(7L)).thenReturn(null);
            assertEquals(0, service.printCardReceipt(7L, false, "DUPLICATA"));
        }
        verifyNoInteractions(service.hardwareService);
    }

    /**
     * Returns the last line of a receipt that is printed in clear — the
     * identification barcode directive and the blank lines around it excluded.
     *
     * @param receipt the rendered receipt
     * @return the last non-blank, non-directive line, trimmed
     */
    private String lastPrintedTextLine(String receipt) {
        String[] lines = receipt.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.isEmpty() && !line.startsWith("[[BARCODE")) {
                return line;
            }
        }
        return "";
    }

    /**
     * Covers the administered ticket messages of {@code printTicket}
     * (BO-03-08-03 / BO-03-08-05): a non-blank header message is printed under
     * the store address and a non-blank footer message after the courtesy line
     * (the true arm of both {@code != null && !isBlank()} guards).
     */
    @Test
    void printTicketPrintsAdministeredHeaderAndFooterMessages() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.ticketHeaderMessage()).thenReturn("PROMO DU JOUR");
        when(service.posSettingsService.ticketFooterMessage()).thenReturn("SUIVEZ-NOUS EN LIGNE");
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.contains("PROMO DU JOUR"));
            assertTrue(out.contains("SUIVEZ-NOUS EN LIGNE"));
            // The receipt ends on the identification barcode (LC-08-01-03); the
            // footer message is the last thing PRINTED IN CLEAR before it.
            assertTrue(out.trim().endsWith("[[BARCODE C04-00000001]]"));
            assertTrue(out.indexOf("SUIVEZ-NOUS EN LIGNE") < out.indexOf("[[BARCODE"));
            assertTrue(out.indexOf("A BIENTOT") < out.indexOf("SUIVEZ-NOUS EN LIGNE"));
        }
    }

    /**
     * Covers the blank-message arm of {@code printTicket} (BO-03-08-03): a
     * header and a footer message that are non-null but blank are NOT printed
     * (the false arm of the {@code !isBlank()} operand); the city is followed
     * directly by the separator and the receipt still ends on the courtesy line.
     */
    @Test
    void printTicketSkipsBlankHeaderAndFooterMessages() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.ticketHeaderMessage()).thenReturn("   ");
        when(service.posSettingsService.ticketFooterMessage()).thenReturn("   ");
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.contains("LYON\n" + "-".repeat(42)));
            // Nothing between the courtesy line and the identification barcode:
            // a blank footer message prints no line at all.
            assertTrue(out.trim().endsWith("[[BARCODE C04-00000001]]"));
            assertEquals("A BIENTOT", lastPrintedTextLine(out));
        }
    }

    /**
     * BO-10-02-34: with the article-code display ON (display.show-ean), a line
     * carrying a non-empty EAN prints it under the label, while a null-EAN and
     * an empty-EAN line print none — the three arms of the
     * {@code showEan() && ean != null && !ean.isEmpty()} guard.
     */
    @Test
    void printTicketPrintsArticleEanWhenEnabled() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.showEan()).thenReturn(true);
        Ticket ticket = ticket(0, null);
        TicketLine withEan = line("U1", "PAIN", "1", "2.00", "2.00");
        withEan.ean = "3017620422003";
        TicketLine nullEan = line("U2", "LAIT", "1", "1.00", "1.00");
        nullEan.ean = null;
        TicketLine emptyEan = line("U3", "SEL", "1", "0.50", "0.50");
        emptyEan.ean = "";
        ticket.lines.add(withEan);
        ticket.lines.add(nullEan);
        ticket.lines.add(emptyEan);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.contains("3017620422003"));
        }
    }

    /**
     * BO-03-03-19: with the article-code display OFF, the very same line prints
     * its label and its total but NOT its EAN — the false arm of
     * {@code display.show-ean}.
     *
     * <p>Without this case the setting is only ever read as true, and hard-wiring
     * the guard to a literal {@code true} would leave the suite green. It is the
     * pair with the case above that proves the key is administered rather than
     * decorative.
     */
    @Test
    void printTicketOmitsArticleEanWhenDisabled() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.showEan()).thenReturn(false);
        Ticket ticket = ticket(0, null);
        TicketLine withEan = line("U1", "PAIN", "1", "2.00", "2.00");
        withEan.ean = "3017620422003";
        ticket.lines.add(withEan);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertFalse(out.contains("3017620422003"));
            assertTrue(out.contains("PAIN"));
        }
    }

    /**
     * Covers the offer-label fallback arm of {@code printTicket}: the valuation
     * has a non-zero delta, a null advantage label and a non-null offer label
     * shorter than 26 characters, so the offer label is printed untruncated.
     */
    @Test
    void printTicketFallsBackToOfferLabelForAdvantage() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "LAIT", "1", "5.00", "5.00"));
        List<TicketLineValuation> valuations = new ArrayList<>();
        valuations.add(valuation("U1", "5.00", "6.00", null, "OFFRE PROMO"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L)).thenReturn(valuations);
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.contains("OFFRE PROMO"));
        }
    }

    /**
     * Covers the default-label arm of {@code printTicket}: the valuation has a
     * non-zero delta but both the advantage and offer labels are null, so the
     * fallback "AVANTAGE" label is printed.
     */
    @Test
    void printTicketUsesDefaultAdvantageLabelWhenLabelsNull() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "CAFE", "1", "5.00", "5.00"));
        List<TicketLineValuation> valuations = new ArrayList<>();
        valuations.add(valuation("U1", "5.00", "7.00", null, null));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L)).thenReturn(valuations);
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.contains("AVANTAGE"));
        }
    }

    /**
     * Covers the zero-delta arm of {@code printTicket}: a valuation matches the
     * line but its valued total equals its local total, so no advantage delta
     * is printed even though the valuation is present.
     */
    @Test
    void printTicketPrintsNoAdvantageWhenDeltaIsZero() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "EAU", "1", "5.00", "5.00"));
        List<TicketLineValuation> valuations = new ArrayList<>();
        valuations.add(valuation("U1", "5.00", "5.00", "AVANTAGE", "OFFRE"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L)).thenReturn(valuations);
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertFalse(out.contains("AVANTAGE"));
        }
    }

    // --------------------------------------------------
    // printSessionReport
    // --------------------------------------------------

    /**
     * Builds a session for a report.
     *
     * @param closingDate the closing timestamp, or null
     * @return the configured session
     */
    private CashSession session(LocalDateTime closingDate) {
        CashSession session = new CashSession();
        session.sessionNumber = "S-001";
        session.terminalId = "C04";
        session.openingDate = NOW;
        session.closingDate = closingDate;
        session.openingFloat = new BigDecimal("100.00");
        session.countedAmount = new BigDecimal("150.00");
        session.variance = new BigDecimal("0.00");
        session.withdrawnAmount = new BigDecimal("50.00");
        return session;
    }

    /**
     * Covers the X-snapshot arm of {@code printSessionReport}: the report is not
     * closing, the refunds total is zero (no refunds line) and the closing block
     * is skipped.
     */
    @Test
    void printSessionReportPrintsXSnapshot() {
        TicketPrinterService service = newService();
        CashSessionService.SessionReport report = new CashSessionService.SessionReport();
        report.session = session(null);
        report.closing = false;
        report.ticketCount = 3;
        report.totalIncludingTax = new BigDecimal("300.00");
        report.totalsByMethod = new LinkedHashMap<>();
        report.totalsByMethod.put("CASH", new BigDecimal("200.00"));
        // BO-03-02-25: the report says which tenders it details; a hand-built
        // fixture must say it too, or the print has nothing to state.
        report.detailedMethods.add("CASH");
        report.theoreticalCash = new BigDecimal("300.00");
        report.totalRefunds = BigDecimal.ZERO;
        service.printSessionReport(report);
        String out = captureReceipt(service);
        assertTrue(out.contains("RAPPORT X - LECTURE"));
        assertTrue(out.contains("CASH"));
        assertFalse(out.contains("ECART"));
        assertFalse(out.contains("Fermée"));
        assertFalse(out.contains("Remboursements"));
    }

    /**
     * Covers the Z-closing arm of {@code printSessionReport}: the report closes
     * the session, the closing date is present (chained closing-date arm), the
     * refunds total is positive (refunds line printed) and the counted amount,
     * variance and withdrawal are printed.
     */
    @Test
    void printSessionReportPrintsZClosingWithClosingDateAndRefunds() {
        TicketPrinterService service = newService();
        CashSessionService.SessionReport report = new CashSessionService.SessionReport();
        report.session = session(NOW.plusHours(8));
        report.closing = true;
        report.ticketCount = 5;
        report.totalIncludingTax = new BigDecimal("500.00");
        report.totalsByMethod = new LinkedHashMap<>();
        report.totalsByMethod.put("CARD", new BigDecimal("400.00"));
        report.detailedMethods.add("CARD");
        report.theoreticalCash = new BigDecimal("250.00");
        report.totalRefunds = new BigDecimal("5.00");
        service.printSessionReport(report);
        String out = captureReceipt(service);
        assertTrue(out.contains("RAPPORT Z - CLOTURE"));
        assertTrue(out.contains("Fermée"));
        assertTrue(out.contains("Remboursements"));
        assertTrue(out.contains("ECART"));
        assertTrue(out.contains("Prelevement"));
    }

    /**
     * Covers the Z-closing arm of {@code printSessionReport} with a null closing
     * date: the report closes the session but the session carries no closing
     * date, so the "Fermée" line is skipped while the closing block is still
     * printed, and the refunds total is zero.
     */
    @Test
    void printSessionReportPrintsZClosingWithoutClosingDate() {
        TicketPrinterService service = newService();
        CashSessionService.SessionReport report = new CashSessionService.SessionReport();
        report.session = session(null);
        report.closing = true;
        report.ticketCount = 0;
        report.totalIncludingTax = BigDecimal.ZERO;
        report.totalsByMethod = new LinkedHashMap<>();
        report.theoreticalCash = new BigDecimal("100.00");
        report.totalRefunds = BigDecimal.ZERO;
        service.printSessionReport(report);
        String out = captureReceipt(service);
        assertTrue(out.contains("RAPPORT Z - CLOTURE"));
        assertFalse(out.contains("Fermée"));
        assertTrue(out.contains("ECART"));
        assertFalse(out.contains("Remboursements"));
    }

    // --------------------------------------------------
    // printRefund
    // --------------------------------------------------

    /**
     * Covers the missing-refund guard of {@code printRefund}: the finder
     * resolves to null, so the method throws and nothing is printed.
     */
    @Test
    void printRefundThrowsWhenRefundMissing() {
        TicketPrinterService service = newService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Refund.findById(5L)).thenReturn(null);
            assertThrows(IllegalArgumentException.class, () -> service.printRefund(5L));
            verifyNoInteractions(service.hardwareService);
        }
    }

    /**
     * Covers the missing-original and no-method arms of {@code printRefund}: the
     * original ticket has vanished (header and "Ticket Original" skipped) and no
     * refund method is set (Mode line skipped), with a short line label.
     */
    @Test
    void printRefundHandlesMissingOriginalAndNoMethod() {
        TicketPrinterService service = newService();
        Refund refund = refund(2L, null, refundLine("YAOURT", "1", "6.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Refund.findById(5L)).thenReturn(refund);
            mocked.when(() -> Ticket.findById(2L)).thenReturn(null);
            service.printRefund(5L);
            String out = captureReceipt(service);
            assertTrue(out.contains("TICKET DE RETOUR"));
            assertTrue(out.contains("TOTAL REMBOURSE"));
            assertFalse(out.contains("INTERMARCHE"));
            assertFalse(out.contains("Ticket Original"));
            assertFalse(out.contains("Mode"));
        }
    }

    /**
     * With the VAT breakdown disabled (BO-10-06-04), the refund ticket carries
     * no per-rate ventilation line — the false arm of the refund-side gate.
     */
    @Test
    void printRefundOmitsVatBreakdownWhenDisabled() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.vatBreakdownEnabled()).thenReturn(false);
        Refund refund = refund(2L, null, refundLine("YAOURT", "1", "6.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Refund.findById(5L)).thenReturn(refund);
            mocked.when(() -> Ticket.findById(2L)).thenReturn(null);
            service.printRefund(5L);
            String out = captureReceipt(service);
            assertTrue(out.contains("TOTAL REMBOURSE"));
            assertFalse(out.contains("  TVA "));
        }
    }

    /**
     * Covers the full-header and CARD arms of {@code printRefund}: the original
     * ticket, its store and the store address are all present (header and city
     * printed), the line label is longer than 20 characters (truncation arm),
     * and the CARD refund method is printed.
     */
    @Test
    void printRefundPrintsFullHeaderWithCardMethod() {
        TicketPrinterService service = newService();
        Refund refund = refund(2L, Refund.RefundMethod.CARD,
                refundLine("ARTICLE AVEC LIBELLE TRES LONG", "1", "6.00"));
        Ticket original = new Ticket();
        original.ticketNumber = "C04-00000009";
        original.store = store();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Refund.findById(5L)).thenReturn(refund);
            mocked.when(() -> Ticket.findById(2L)).thenReturn(original);
            service.printRefund(5L);
            String out = captureReceipt(service);
            assertTrue(out.contains("INTERMARCHE"));
            assertTrue(out.contains("MAGASIN LYON"));
            assertTrue(out.contains("LYON"));
            assertTrue(out.contains("Ticket Original: C04-00000009"));
            assertTrue(out.contains("ARTICLE AVEC LIBELLE"));
            assertTrue(out.contains("CARTE BANCAIRE"));
        }
    }

    /**
     * Covers the null-store arm of {@code printRefund}: the original ticket is
     * present but carries no store, so the store header is skipped, and the CASH
     * refund method is printed.
     */
    @Test
    void printRefundHandlesNullStoreWithCashMethod() {
        TicketPrinterService service = newService();
        Refund refund = refund(2L, Refund.RefundMethod.CASH, refundLine("PAIN", "1", "6.00"));
        Ticket original = new Ticket();
        original.ticketNumber = "C04-00000009";
        original.store = null;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Refund.findById(5L)).thenReturn(refund);
            mocked.when(() -> Ticket.findById(2L)).thenReturn(original);
            service.printRefund(5L);
            String out = captureReceipt(service);
            assertFalse(out.contains("INTERMARCHE"));
            assertTrue(out.contains("Ticket Original: C04-00000009"));
            assertTrue(out.contains("ESPECES"));
        }
    }

    /**
     * Covers the null-address arm of {@code printRefund}: the original store is
     * present but its address is null, so the city line is skipped, and the
     * VOUCHER refund method is printed.
     */
    @Test
    void printRefundHandlesNullAddressWithVoucherMethod() {
        TicketPrinterService service = newService();
        Refund refund = refund(2L, Refund.RefundMethod.VOUCHER, refundLine("SEL", "1", "6.00"));
        Ticket original = new Ticket();
        original.ticketNumber = "C04-00000009";
        Store store = new Store();
        store.name = "MAGASIN LYON";
        store.address = null;
        original.store = store;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Refund.findById(5L)).thenReturn(refund);
            mocked.when(() -> Ticket.findById(2L)).thenReturn(original);
            service.printRefund(5L);
            String out = captureReceipt(service);
            assertTrue(out.contains("INTERMARCHE"));
            assertTrue(out.contains("MAGASIN LYON"));
            assertTrue(out.contains("BON D'ACHAT"));
        }
    }

    /**
     * Covers the LOYALTY arm of {@code refundMethodLabel} through {@code
     * printRefund}: the refund method is LOYALTY, printed as "CAGNOTTE".
     */
    @Test
    void printRefundPrintsLoyaltyMethodLabel() {
        TicketPrinterService service = newService();
        Refund refund = refund(2L, Refund.RefundMethod.LOYALTY, refundLine("THE", "1", "6.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Refund.findById(5L)).thenReturn(refund);
            mocked.when(() -> Ticket.findById(2L)).thenReturn(null);
            service.printRefund(5L);
            String out = captureReceipt(service);
            assertTrue(out.contains("CAGNOTTE"));
        }
    }

    // --------------------------------------------------
    // printTrainingReceipt
    // --------------------------------------------------

    /**
     * Covers {@code printTrainingReceipt} and, through it, the full-width arm of
     * {@code center} (the star banner spans the whole width) and both spacing
     * arms of {@code formatLine} (a normal item leaves positive spacing, a
     * 40-character label overflows and clamps the spacing to one).
     */
    @Test
    void printTrainingReceiptRendersBannersAndClampedSpacing() {
        TicketPrinterService service = newService();
        PosState state = new PosState();
        state.ticket.items.add(new TicketState.TicketItem(
                "3000", null, "PAIN", new BigDecimal("2.00"), new BigDecimal("1"), new BigDecimal("0.2000")));
        state.ticket.items.add(new TicketState.TicketItem(
                "4000", null, "ARTICLE AVEC UN LIBELLE VRAIMENT TRES LONG",
                new BigDecimal("5.00"), new BigDecimal("1"), new BigDecimal("0.2000")));
        state.payment.payments.add(new PaymentState.PaymentEntry("CB", new BigDecimal("7.00")));
        service.printTrainingReceipt(state);
        String out = captureReceipt(service);
        assertTrue(out.contains("MODE FORMATION"));
        assertTrue(out.contains("TICKET NON VALABLE"));
        assertTrue(out.contains("*** FORMATION - SANS VALEUR ***"));
        assertTrue(out.contains("ARTICLE AVEC UN LIBELLE VRAIMENT TRES LONG"));
        assertTrue(out.contains("CB"));
    }

    // --------------------------------------------------
    // Whole-ticket discount and loyalty section
    // --------------------------------------------------

    /**
     * Freezes a loyalty projection on a sale, the way the closing does.
     *
     * @param ticket the sale to freeze it on
     * @param earnTotal the displayed earn, or null when none was displayed
     * @param labels the advantage labels, each credited one cent more than the
     *        one before it
     */
    private void freeze(Ticket ticket, BigDecimal earnTotal, String... labels) {
        ticket.fidelityCard = "2990000000019";
        ticket.fidelityEarnTotal = earnTotal;
        ticket.fidelityLines = new ArrayList<>();
        for (int index = 0; index < labels.length; index++) {
            ticket.fidelityLines.add(new TicketFidelityLine("R" + index, labels[index],
                    new BigDecimal("0.2" + index)));
        }
    }

    /**
     * A persisted ticket carrying a whole-ticket discount prints its REMISE
     * TICKET line, negative and above the totals — the customer must see the
     * manager's gesture, not just a total that happens to be lower.
     */
    @Test
    void printTicketPrintsTheWholeTicketDiscountLine() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.globalDiscountApplied = new BigDecimal("1.50");
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.contains("REMISE TICKET"));
            assertTrue(out.contains("-1,50 E"));
        }
    }

    /**
     * An ordinary sale prints NO discount line (guard false arm).
     */
    @Test
    void printTicketPrintsNoDiscountLineWithoutAGesture() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.globalDiscountApplied = null;
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            assertFalse(captureReceipt(service).contains("REMISE TICKET"));
        }
    }

    /**
     * The section prints what the CLOSING FROZE on the sale, with its total and
     * one line per rule — those labels are the only rule data the POS is
     * allowed to print, and they come from imfid, never invented here.
     */
    @Test
    void printTicketPrintsTheLoyaltySectionFrozenOnTheSale() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        freeze(ticket, new BigDecimal("1.03"), "Cagnotte socle", "Fruits & legumes");
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.contains("CAGNOTTE DU JOUR"));
            assertTrue(out.contains("+1,03 E"));
            assertTrue(out.contains("Cagnotte socle"));
            assertTrue(out.contains("+0,20 E"));
            assertTrue(out.contains("Fruits & legumes"));
            assertTrue(out.contains("+0,21 E"));
        }
    }

    /**
     * A DUPLICATA carries the section too, which is the whole point of freezing
     * it: the paper reissued at the welcome desk states the cagnotte the
     * customer was shown at the till, not the one of whatever sale the register
     * happens to be on.
     */
    @Test
    void printTicketPrintsTheLoyaltySectionOnAduplicata() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(1, null);
        freeze(ticket, new BigDecimal("1.03"), "Cagnotte socle");
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.contains("DUPLICATA"));
            assertTrue(out.contains("CAGNOTTE DU JOUR"));
        }
    }

    /**
     * A sale carrying NO frozen projection prints no section (second leg of the
     * guard) — the degraded loyalty display: nothing rather than a figure the
     * register cannot vouch for.
     */
    @Test
    void printTicketPrintsNoSectionWithoutAProjection() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        freeze(ticket, null);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            assertFalse(captureReceipt(service).contains("CAGNOTTE DU JOUR"));
        }
    }

    /**
     * A ZERO projection prints no section either (third leg): a cart absorbed
     * by an offer earns nothing, and "CAGNOTTE DU JOUR +0,00 E" would look
     * like a defect to the customer.
     */
    @Test
    void printTicketPrintsNoSectionOnAZeroProjection() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        freeze(ticket, BigDecimal.ZERO);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            assertFalse(captureReceipt(service).contains("CAGNOTTE DU JOUR"));
        }
    }

    /**
     * A projection with NO rule lines prints the total alone: the section
     * header exists, the loop simply adds nothing.
     */
    @Test
    void printTicketPrintsTheSectionTotalWithoutRuleLines() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        freeze(ticket, new BigDecimal("1.03"));
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.contains("CAGNOTTE DU JOUR"));
            assertTrue(out.contains("+1,03 E"));
        }
    }

    /**
     * BO-10-03-15: with the fidelity advantages DISABLED, the CAGNOTTE section
     * is skipped even on a frozen projection — the
     * {@code fidelityAdvantagesEnabled()} false arm gates the whole section.
     */
    @Test
    void printTicketSkipsTheLoyaltySectionWhenAdvantagesDisabled() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.fidelityAdvantagesEnabled()).thenReturn(false);
        Ticket ticket = ticket(0, null);
        freeze(ticket, new BigDecimal("1.03"), "Cagnotte socle");
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            assertFalse(captureReceipt(service).contains("CAGNOTTE DU JOUR"));
        }
    }

    // --------------------------------------------------
    // The loyalty zone a layout reads (BO-03-03-25/-29/-30/-31/-32)
    // --------------------------------------------------

    /**
     * The zone of a sale made WITH a card states the card, the earn, its lines,
     * the balance read at attachment and the amount settled on it.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theloyaltyZoneStatesWhatTheProgrammeSaid() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        freeze(ticket, new BigDecimal("1.03"), "Cagnotte socle");
        ticket.fidelityAvailableBalance = new BigDecimal("42.30");
        ticket.payments.add(new FidelityPayment(new BigDecimal("3.00")));
        Map<String, Object> zone = (Map<String, Object>)
                service.saleDocumentData(ticket, false, 0).get("fidelity");
        assertEquals("2990000000019", zone.get("card"));
        assertEquals(true, zone.get("present"));
        assertEquals("1,03", zone.get("earnTotal"));
        assertEquals("42,30", zone.get("availableBalance"));
        assertEquals("3,00", zone.get("usedAmount"));
        assertEquals(false, zone.get("unavailable"));
        assertEquals("", zone.get("message"));
        List<Map<String, Object>> lines = (List<Map<String, Object>>) zone.get("lines");
        assertEquals(1, lines.size());
        assertEquals("R0", lines.get(0).get("ruleCode"));
        assertEquals("Cagnotte socle", lines.get(0).get("label"));
        assertEquals("0,20", lines.get(0).get("amount"));
    }

    /**
     * A sale made WITHOUT a card states an absent card and no figure: the null
     * arms of the earn, the balance and the card guard, all three at once.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theloyaltyZoneOfAsaleWithoutAcardStatesNothing() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.fidelityLines = new ArrayList<>();
        Map<String, Object> zone = (Map<String, Object>)
                service.saleDocumentData(ticket, false, 0).get("fidelity");
        assertEquals("", zone.get("card"));
        assertEquals(false, zone.get("present"));
        assertEquals("", zone.get("earnTotal"));
        assertEquals("", zone.get("availableBalance"));
        assertEquals("0,00", zone.get("usedAmount"));
        assertTrue(((List<?>) zone.get("lines")).isEmpty());
    }

    /**
     * A BLANK card is no card: the second leg of the presence guard, which a
     * card column emptied by a correction would otherwise pass.
     */
    @Test
    @SuppressWarnings("unchecked")
    void ablankCardIsNoCard() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.fidelityCard = "   ";
        ticket.fidelityLines = new ArrayList<>();
        Map<String, Object> zone = (Map<String, Object>)
                service.saleDocumentData(ticket, false, 0).get("fidelity");
        assertEquals(false, zone.get("present"));
    }

    /**
     * A sale carrying NO payment list at all settles nothing rather than
     * exploding — the null arm of the settlement sum, which the store node and
     * the hand-built printer of these tests both reach.
     */
    @Test
    @SuppressWarnings("unchecked")
    void asaleWithoutApaymentListSettlesNothing() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.fidelityLines = new ArrayList<>();
        ticket.payments = null;
        Map<String, Object> zone = (Map<String, Object>)
                service.saleDocumentData(ticket, false, 0).get("fidelity");
        assertEquals("0,00", zone.get("usedAmount"));
    }

    /**
     * Several loyalty settlements sum into ONE figure, and a settlement of
     * another kind is left out — the two arms of the settlement filter, plus
     * the null-amount arm a half-registered payment would produce.
     */
    @Test
    @SuppressWarnings("unchecked")
    void severalLoyaltySettlementsSumIntoOneFigure() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.fidelityLines = new ArrayList<>();
        ticket.payments.add(new FidelityPayment(new BigDecimal("3.00")));
        ticket.payments.add(new FidelityPayment(new BigDecimal("1.50")));
        ticket.payments.add(new FidelityPayment(null));
        ticket.payments.add(new CashPayment(new BigDecimal("7.50"), new BigDecimal("10.00")));
        Map<String, Object> zone = (Map<String, Object>)
                service.saleDocumentData(ticket, false, 0).get("fidelity");
        assertEquals("4,50", zone.get("usedAmount"));
    }

    /**
     * BO-03-03-29: a HOLDER's sale made while the loyalty service was silent
     * carries the administered message, its {@code {carte}} token replaced.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aholderIsToldTheProgrammeWasSilent() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.fidelityOfflineMessage())
                .thenReturn("CARTE {carte} - FIDELITE INDISPONIBLE");
        Ticket ticket = ticket(0, null);
        freeze(ticket, null);
        ticket.fidelityUnavailable = true;
        Map<String, Object> zone = (Map<String, Object>)
                service.saleDocumentData(ticket, false, 0).get("fidelity");
        assertEquals(true, zone.get("unavailable"));
        assertEquals("CARTE 2990000000019 - FIDELITE INDISPONIBLE", zone.get("message"));
    }

    /**
     * BO-03-03-30: a sale made WITHOUT a card while the service was silent
     * carries the OTHER message — the two say different things, so they are two
     * parameters and not one.
     */
    @Test
    @SuppressWarnings("unchecked")
    void anonHolderIsToldSomethingElse() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.fidelityOfflineMessageNoCard())
                .thenReturn("PRESENTEZ VOTRE CARTE LA PROCHAINE FOIS");
        Ticket ticket = ticket(0, null);
        ticket.fidelityLines = new ArrayList<>();
        ticket.fidelityUnavailable = true;
        Map<String, Object> zone = (Map<String, Object>)
                service.saleDocumentData(ticket, false, 0).get("fidelity");
        assertEquals("PRESENTEZ VOTRE CARTE LA PROCHAINE FOIS", zone.get("message"));
    }

    /**
     * A shop that clears the parameter says nothing, and so does one whose
     * parameter is absent — the blank and null arms of that guard.
     */
    @Test
    @SuppressWarnings("unchecked")
    void ashopThatClearsTheMessageSaysNothing() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.fidelityOfflineMessage()).thenReturn("   ", (String) null);
        Ticket ticket = ticket(0, null);
        freeze(ticket, null);
        ticket.fidelityUnavailable = true;
        assertEquals("", ((Map<String, Object>)
                service.saleDocumentData(ticket, false, 0).get("fidelity")).get("message"));
        assertEquals("", ((Map<String, Object>)
                service.saleDocumentData(ticket, false, 0).get("fidelity")).get("message"));
    }

    /**
     * A line that carries no label prints an empty one rather than the word
     * null — the null arm of the label accessor.
     */
    @Test
    @SuppressWarnings("unchecked")
    void alineWithoutAlabelPrintsAnEmptyOne() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.fidelityCard = "2990000000019";
        ticket.fidelityLines = new ArrayList<>();
        ticket.fidelityLines.add(new TicketFidelityLine(null, null, null));
        Map<String, Object> zone = (Map<String, Object>)
                service.saleDocumentData(ticket, false, 0).get("fidelity");
        List<Map<String, Object>> lines = (List<Map<String, Object>>) zone.get("lines");
        assertEquals("", lines.get(0).get("ruleCode"));
        assertEquals("", lines.get(0).get("label"));
        assertEquals("0,00", lines.get(0).get("amount"));
    }

    // --------------------------------------------------
    // The loyalty settlement slip (BO-03-03-10)
    // --------------------------------------------------

    /**
     * An unknown sale prints no slip — the first leg of that guard.
     */
    @Test
    void anunknownSalePrintsNoLoyaltySlip() {
        TicketPrinterService service = newService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(9L)).thenReturn(null);
            assertEquals(0, service.printLoyaltyReceipt(9L));
        }
        verify(service.hardwareService, never()).printReceipt(anyString());
    }

    /**
     * A sale settled WITHOUT the loyalty balance prints no slip either — the
     * second leg: the caller does not have to know whether the cagnotte was
     * used.
     */
    @Test
    void asaleWithoutAloyaltySettlementPrintsNoSlip() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.fidelityLines = new ArrayList<>();
        ticket.payments.add(new CashPayment(new BigDecimal("12.00"), new BigDecimal("12.00")));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            assertEquals(0, service.printLoyaltyReceipt(1L));
        }
        verify(service.hardwareService, never()).printReceipt(anyString());
    }

    /**
     * A settlement taken on the balance prints ONE slip stating what was taken,
     * from which card, against which sale, with the balance it was taken from
     * and a line for the customer to sign.
     */
    @Test
    void aloyaltySettlementPrintsItsSlip() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        freeze(ticket, new BigDecimal("1.03"));
        ticket.fidelityAvailableBalance = new BigDecimal("42.30");
        ticket.payments.add(new FidelityPayment(new BigDecimal("3.00")));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            assertEquals(1, service.printLoyaltyReceipt(1L));
        }
        String out = captureReceipt(service);
        assertTrue(out.contains("PAIEMENT FIDELITE"));
        assertTrue(out.contains("PAIEMENT TOTAL FID"));
        assertTrue(out.contains("3,00 E"));
        assertTrue(out.contains("2990000000019"));
        assertTrue(out.contains("C04-00000001"));
        assertTrue(out.contains("MAGASIN LYON"));
        assertTrue(out.contains("SOLDE AVANT"));
        assertTrue(out.contains("42,30 E"));
        assertTrue(out.contains("SIGNATURE DU CLIENT"));
        verify(service.hardwareService).cutPaper();
    }

    /**
     * A slip for a sale carrying no store, no register, no date and no balance
     * still comes out — the four null arms of its optional lines, which a sale
     * ingested from an older register can all present at once.
     */
    @Test
    void aslipPrintsWithoutItsOptionalLines() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.store = null;
        ticket.terminalId = null;
        ticket.creationDate = null;
        ticket.fidelityAvailableBalance = null;
        ticket.fidelityLines = new ArrayList<>();
        ticket.payments.add(new FidelityPayment(new BigDecimal("3.00")));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            assertEquals(1, service.printLoyaltyReceipt(1L));
        }
        String out = captureReceipt(service);
        assertTrue(out.contains("PAIEMENT TOTAL FID"));
        assertFalse(out.contains("SOLDE AVANT"));
        assertFalse(out.contains("Caisse :"));
        assertFalse(out.contains("Date   :"));
    }

    /**
     * An ADMINISTERED layout replaces the slip entirely, and the register cuts
     * the paper all the same — the true arm of that hook.
     */
    @Test
    void anadministeredLayoutReplacesTheSlip() {
        TicketPrinterService service = newService();
        service.documentTemplateService = mock(DocumentTemplateService.class);
        when(service.documentTemplateService.render(
                eq(DocumentTemplate.DocumentType.LOYALTY_RECEIPT), any()))
                .thenReturn("MISE EN PAGE MAGASIN");
        Ticket ticket = ticket(0, null);
        ticket.fidelityLines = new ArrayList<>();
        ticket.payments.add(new FidelityPayment(new BigDecimal("3.00")));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            assertEquals(1, service.printLoyaltyReceipt(1L));
        }
        assertEquals("MISE EN PAGE MAGASIN", captureReceipt(service));
        verify(service.hardwareService).cutPaper();
    }

    /**
     * The slip a layout reads names the sale, the settled amount and the whole
     * loyalty zone — the contract the back office's preview pane advertises.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theslipALayoutReadsNamesTheSettlement() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        freeze(ticket, new BigDecimal("1.03"));
        Map<String, Object> data = service.loyaltyDocumentData(ticket, new BigDecimal("3.00"));
        assertEquals("C04-00000001",
                ((Map<String, Object>) data.get("document")).get("number"));
        assertEquals("3,00", ((Map<String, Object>) data.get("settlement")).get("amount"));
        assertEquals("2990000000019",
                ((Map<String, Object>) data.get("fidelity")).get("card"));
        assertEquals("Jean Dupont", data.get("operator"));
        assertEquals("02/08/2026", data.get("date"));
    }

    /**
     * The same slip built from a sale carrying no cashier and no date states
     * empty strings rather than failing — the null arms of those two accessors.
     */
    @Test
    void theslipOfAsaleWithoutAcashierStatesEmptyStrings() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.cashier = null;
        ticket.creationDate = null;
        ticket.fidelityLines = new ArrayList<>();
        Map<String, Object> data = service.loyaltyDocumentData(ticket, new BigDecimal("3.00"));
        assertEquals("", data.get("operator"));
        assertEquals("", data.get("date"));
        assertEquals("", data.get("time"));
    }

    /**
     * The TRAINING receipt prints the whole-ticket discount too — a trainee
     * must see the same paper a customer would get.
     */
    @Test
    void printTrainingReceiptPrintsTheWholeTicketDiscount() {
        TicketPrinterService service = newService();
        PosState state = new PosState();
        state.ticket.items.add(new TicketState.TicketItem(
                "3000", null, "PAIN", new BigDecimal("2.00"), BigDecimal.ONE, new BigDecimal("0.2000")));
        state.ticket.globalDiscountApplied = new BigDecimal("0.50");
        service.printTrainingReceipt(state);
        String out = captureReceipt(service);
        assertTrue(out.contains("REMISE TICKET"));
        assertTrue(out.contains("-0,50 E"));
    }

    /**
     * WITHOUT a gesture the training receipt prints no discount line either.
     */
    @Test
    void printTrainingReceiptPrintsNoDiscountLineWithoutAGesture() {
        TicketPrinterService service = newService();
        PosState state = new PosState();
        state.ticket.items.add(new TicketState.TicketItem(
                "3000", null, "PAIN", new BigDecimal("2.00"), BigDecimal.ONE, new BigDecimal("0.2000")));
        service.printTrainingReceipt(state);
        assertFalse(captureReceipt(service).contains("REMISE TICKET"));
    }

    // --------------------------------------------------
    // printLoyaltyCredit
    // --------------------------------------------------

    /**
     * The voluntary loyalty refund hands the customer a printed PROOF: the
     * title, the credited amount signed as a credit, and the notice that the
     * balance follows within minutes — the register never writes that balance
     * itself, so this slip is the customer's only in-hand evidence.
     */
    @Test
    void printLoyaltyCreditPrintsTheProof() {
        TicketPrinterService service = newService();
        service.printLoyaltyCredit(new BigDecimal("9.99"));
        String out = captureReceipt(service);
        assertTrue(out.contains("INTERMARCHE"));
        assertTrue(out.contains("REMBOURSEMENT EN CAGNOTTE"));
        assertTrue(out.contains("CREDIT CARTE"));
        assertTrue(out.contains("+9,99 E"));
        assertTrue(out.contains("(visible sur la carte sous quelques minutes)"));
    }

    /**
     * The amount is formatted like every other figure on paper — two
     * decimals, French comma.
     */
    @Test
    void printLoyaltyCreditFormatsTheAmountInFrenchNotation() {
        TicketPrinterService service = newService();
        service.printLoyaltyCredit(new BigDecimal("12.5"));
        assertTrue(captureReceipt(service).contains("+12,50 E"));
    }

    // --------------------------------------------------
    // printRefundVoucher
    // --------------------------------------------------

    /**
     * {@code printRefundVoucher} prints the credit note at its REGISTRY
     * number: AVOIR title, amount, issue date, the number as given (a pure
     * identifier — no amount encoding, no cap) and the registry notice.
     */
    @Test
    void printRefundVoucherPrintsRegistryNumber() {
        TicketPrinterService service = newService();
        Refund refund = new Refund();
        refund.id = 12L;
        refund.creationDate = NOW;
        refund.totalAmount = new BigDecimal("9.99");
        service.printRefundVoucher(refund, "297000000000077");
        String out = captureReceipt(service);
        assertTrue(out.contains("AVOIR"));
        assertTrue(out.contains("9,99 E"));
        assertTrue(out.contains("N° 297000000000077"));
        assertTrue(out.contains("(scannable en caisse - solde au registre)"));
    }

    /**
     * {@code printRefundVoucher} prints amounts ABOVE the historical 99,99 €
     * encoded cap just the same: the registry number carries no amount, so
     * nothing limits the printable credit note.
     */
    @Test
    void printRefundVoucherHasNoAmountCap() {
        TicketPrinterService service = newService();
        Refund refund = new Refund();
        refund.id = 12L;
        refund.creationDate = NOW;
        refund.totalAmount = new BigDecimal("150.00");
        service.printRefundVoucher(refund, "297000000000078");
        String out = captureReceipt(service);
        assertTrue(out.contains("AVOIR"));
        assertTrue(out.contains("150,00 E"));
        assertTrue(out.contains("N° 297000000000078"));
    }

    // --------------------------------------------------
    // printGiftCardVoucher
    // --------------------------------------------------

    /**
     * {@code printGiftCardVoucher} prints the freshly issued card: CARTE
     * CADEAU title, the loaded amount, the registry number and the registry
     * notice — the customer's proof after the fiscal moment.
     */
    @Test
    void printGiftCardVoucherPrintsNumberAndAmount() {
        TicketPrinterService service = newService();
        service.printGiftCardVoucher("296000000000042", new BigDecimal("50.00"));
        String out = captureReceipt(service);
        assertTrue(out.contains("CARTE CADEAU"));
        assertTrue(out.contains("50,00 E"));
        assertTrue(out.contains("N° 296000000000042"));
        assertTrue(out.contains("(scannable en caisse - solde au registre)"));
    }

    // --------------------------------------------------
    // printChangeVoucher (BO-03-02-16)
    // --------------------------------------------------

    /**
     * The X/Z report states line by line only the tenders the back office puts
     * in detail, sums the others, and names the bank deposit and the fidelity
     * share when the store administered tenders for them
     * (BO-03-02-21/25/30).
     */
    @Test
    void printSessionReportFollowsTheAdministeredReportingRules() {
        TicketPrinterService service = newService();
        CashSessionService.SessionReport report = new CashSessionService.SessionReport();
        report.session = session(null);
        report.closing = false;
        report.ticketCount = 3;
        report.totalIncludingTax = new BigDecimal("300.00");
        report.totalsByMethod = new LinkedHashMap<>();
        report.totalsByMethod.put("CASH", new BigDecimal("200.00"));
        report.totalsByMethod.put("CARD", new BigDecimal("100.00"));
        report.detailedMethods.add("CASH");
        report.otherMethodsTotal = new BigDecimal("100.00");
        report.bankDepositTotal = new BigDecimal("100.00");
        report.fidelityReportedTotal = new BigDecimal("200.00");
        report.theoreticalCash = new BigDecimal("300.00");
        report.totalRefunds = BigDecimal.ZERO;
        service.printSessionReport(report);
        String out = captureReceipt(service);
        assertTrue(out.contains("CASH"));
        assertFalse(out.contains("CARD"));
        assertTrue(out.contains("Autres reglements"));
        assertTrue(out.contains("Remise en banque"));
        assertTrue(out.contains("Dont remontee fidelite"));
    }

    /**
     * A report with nothing to say about the bank or fidelity says nothing: a
     * nil line would state something the store never administered — the other
     * arm of both guards (BO-03-02-21/30).
     */
    @Test
    void printSessionReportStaysSilentOnWhatIsNotAdministered() {
        TicketPrinterService service = newService();
        CashSessionService.SessionReport report = new CashSessionService.SessionReport();
        report.session = session(null);
        report.closing = false;
        report.totalsByMethod = new LinkedHashMap<>();
        report.totalsByMethod.put("CASH", new BigDecimal("200.00"));
        report.detailedMethods.add("CASH");
        report.theoreticalCash = new BigDecimal("300.00");
        report.totalRefunds = BigDecimal.ZERO;
        service.printSessionReport(report);
        String out = captureReceipt(service);
        assertFalse(out.contains("Autres reglements"));
        assertFalse(out.contains("Remise en banque"));
        assertFalse(out.contains("Dont remontee fidelite"));
    }

    // --------------------------------------------------
    // Administered document layouts (BO-03-03)
    // --------------------------------------------------

    /**
     * Wires the service to a REAL renderer over a real Qute engine, so a case
     * about an administered layout asserts the layout and not a stand-in.
     *
     * @param service the service to wire
     * @return the renderer, for the case to stub its referential lookup
     */
    private com.intermarche.pos.service.DocumentTemplateService wireRenderer(
            TicketPrinterService service) {
        com.intermarche.pos.service.DocumentTemplateService renderer =
                new com.intermarche.pos.service.DocumentTemplateService(
                        io.quarkus.qute.Engine.builder().addDefaults().build());
        service.documentTemplateService = renderer;
        return renderer;
    }

    /**
     * Builds an administered layout of one document.
     *
     * @param type the document it lays out
     * @param source the Qute source
     * @return the administered template
     */
    private com.intermarche.pos.domain.setting.DocumentTemplate layout(
            com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType type,
            String source) {
        com.intermarche.pos.domain.setting.DocumentTemplate template =
                new com.intermarche.pos.domain.setting.DocumentTemplate();
        template.code = type.name();
        template.label = type.getLabel();
        template.documentType = type;
        template.active = true;
        template.width = 42;
        template.source = source;
        return template;
    }

    /**
     * Stubs the referential so one document type is administered and the others
     * are not.
     *
     * @param panache the active Panache static mock
     * @param template the administered layout
     */
    private void stubAdministeredLayout(
            org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache,
            com.intermarche.pos.domain.setting.DocumentTemplate template) {
        panache.when(() -> com.intermarche.pos.domain.setting.DocumentTemplate.list(
                        "active = true and documentType = ?1 order by priority, code",
                        template.documentType))
                .thenReturn(List.of(template));
    }

    /**
     * An administered layout REPLACES the built-in receipt, and reads the sale
     * through the very keys the back office's preview advertises.
     */
    @Test
    void anAdministeredLayoutReplacesTheBuiltInReceipt() {
        TicketPrinterService service = newService();
        wireRenderer(service);
        Ticket ticket = ticket(0, null);
        ticket.terminalId = "C04";
        ticket.lines.add(line("u1", "LAIT", "2", "3.00", "6.00"));
        ticket.payments.add(new CashPayment(new BigDecimal("12.00"), new BigDecimal("12.00")));
        com.intermarche.pos.domain.setting.DocumentTemplate template = layout(
                com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.SALE_RECEIPT,
                "{store.name}|{document.number}|{terminal}|{operator}"
                        + "{#for l in lines}|{l.label} {l.quantity}x{l.unitPrice}={l.total}{/for}"
                        + "|TTC {totals.includingTax}|HT {totals.excludingTax}"
                        + "{#for p in payments}|{p.key} {p.amount}{/for}");
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubAdministeredLayout(panache, template);
            String rendered = service.renderTicket(ticket, false, 0);
            assertEquals("MAGASIN LYON|C04-00000001|C04|Jean Dupont"
                    + "|LAIT 2,00x3,00=6,00|TTC 12,00|HT 10,00|CASH 12,00", rendered);
        }
    }

    /**
     * A SECOND administered layout states the very same sale differently, which
     * is what proves the receipt is read from the referential.
     */
    @Test
    void aSecondAdministeredLayoutStatesTheSameSaleDifferently() {
        TicketPrinterService service = newService();
        wireRenderer(service);
        Ticket ticket = ticket(0, null);
        com.intermarche.pos.domain.setting.DocumentTemplate template = layout(
                com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.SALE_RECEIPT,
                "TOTAL {totals.includingTax}");
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubAdministeredLayout(panache, template);
            assertEquals("TOTAL 12,00", service.renderTicket(ticket, false, 0));
        }
    }

    /**
     * A shop that administers NO layout gets the built-in receipt, character
     * for character — the fallback the whole design rests on.
     */
    @Test
    void anUnadministeredDocumentKeepsTheBuiltInReceipt() {
        TicketPrinterService service = newService();
        wireRenderer(service);
        Ticket ticket = ticket(0, null);
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            panache.when(() -> com.intermarche.pos.domain.setting.DocumentTemplate.list(
                            "active = true and documentType = ?1 order by priority, code",
                            com.intermarche.pos.domain.setting.DocumentTemplate
                                    .DocumentType.SALE_RECEIPT))
                    .thenReturn(List.of());
            String rendered = service.renderTicket(ticket, false, 0);
            assertTrue(rendered.contains("MERCI DE VOTRE VISITE"));
        }
    }

    /**
     * A layout that blows up at render time costs the built-in receipt and not
     * the sale: the paramétreur's mistake never stops a till printing.
     */
    @Test
    void aBrokenLayoutFallsBackToTheBuiltInReceipt() {
        TicketPrinterService service = newService();
        wireRenderer(service);
        Ticket ticket = ticket(0, null);
        com.intermarche.pos.domain.setting.DocumentTemplate template = layout(
                com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.SALE_RECEIPT,
                "{#for c in document.number}{c}{/for}");
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubAdministeredLayout(panache, template);
            assertTrue(service.renderTicket(ticket, false, 0).contains("MERCI DE VOTRE VISITE"));
        }
    }

    /**
     * A printer built by hand — no renderer wired at all — prints its own
     * receipt, which is the null leg of the layout guard.
     */
    @Test
    void aPrinterWithoutARendererPrintsItsOwnReceipt() {
        TicketPrinterService service = newService();
        assertNull(service.documentTemplateService);
        Ticket ticket = ticket(0, null);
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            assertTrue(service.renderTicket(ticket, false, 0).contains("MERCI DE VOTRE VISITE"));
        }
    }

    /**
     * The sale a layout reads carries the duplicate banner, the VAT ventilation
     * and the digital-receipt path — the keys the built-in receipt prints and a
     * layout must be able to print too.
     */
    @Test
    void theSaleDocumentCarriesWhatTheReceiptStates() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(1, "ABCD1234EF567890");
        ticket.terminalId = "C04";
        ticket.lines.add(line("u1", "LAIT", "2", "3.00", "6.00"));
        ticket.globalDiscountApplied = new BigDecimal("1.50");
        Map<String, Object> data = service.saleDocumentData(ticket, true, 2);

        @SuppressWarnings("unchecked")
        Map<String, Object> document = (Map<String, Object>) data.get("document");
        assertEquals(Boolean.TRUE, document.get("duplicate"));
        assertEquals("2", document.get("duplicateNumber"));
        assertEquals("/t/1/ABCD1234EF567890", document.get("digitalPath"));

        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) data.get("totals");
        assertEquals("12,00", totals.get("includingTax"));
        assertEquals("10,00", totals.get("excludingTax"));
        assertEquals("1,50", totals.get("discount"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> vatRows = (List<Map<String, Object>>) data.get("vatRows");
        assertEquals(1, vatRows.size());
        assertEquals("20,00%", vatRows.get(0).get("rate"));
    }

    /**
     * A cancelled article is outside the sale for a layout exactly as it is for
     * the built-in receipt (BO-04-01-16).
     */
    @Test
    void aCancelledArticleIsOutsideTheDocument() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        TicketLine cancelled = line("u1", "ANNULE", "1", "5.00", "5.00");
        cancelled.cancelled = true;
        ticket.lines.add(cancelled);
        ticket.lines.add(line("u2", "LAIT", "1", "3.00", "3.00"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines =
                (List<Map<String, Object>>) service.saleDocumentData(ticket, false, 0).get("lines");
        assertEquals(1, lines.size());
        assertEquals("LAIT", lines.get(0).get("label"));
    }

    /**
     * A sale carrying no store, no cashier, no date, no lines and no payments
     * still describes itself: a layout must not be the thing that fails a print
     * — every null leg of the builder, in one case.
     */
    @Test
    void aBareSaleStillDescribesItself() {
        TicketPrinterService service = newService();
        Ticket ticket = mock(Ticket.class);
        ticket.id = 7L;
        Map<String, Object> data = service.saleDocumentData(ticket, false, 0);
        assertEquals("", data.get("terminal"));
        assertEquals("", data.get("operator"));
        assertEquals("", data.get("date"));
        assertEquals("", data.get("time"));
        @SuppressWarnings("unchecked")
        Map<String, Object> store = (Map<String, Object>) data.get("store");
        assertEquals("", store.get("name"));
        assertEquals("", store.get("city"));
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) data.get("totals");
        assertEquals("0,00", totals.get("includingTax"));
        assertTrue(((List<?>) data.get("lines")).isEmpty());
        assertTrue(((List<?>) data.get("payments")).isEmpty());
    }

    /**
     * A store without an address describes itself with empty parts rather than
     * refusing — the second leg of the store guard.
     */
    @Test
    void aStoreWithoutAnAddressDescribesItself() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.store.address = null;
        @SuppressWarnings("unchecked")
        Map<String, Object> store =
                (Map<String, Object>) service.saleDocumentData(ticket, false, 0).get("store");
        assertEquals("MAGASIN LYON", store.get("name"));
        assertEquals("", store.get("city"));
        assertEquals("", store.get("postalCode"));
    }

    /**
     * The rounding is handed to a layout with the sign the PAPER reads, not the
     * one the ledger stores (LC-07-03-06).
     */
    @Test
    void theRoundingIsHandedOverWithTheSignThePaperReads() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.payments.add(new com.intermarche.pos.domain.payment.RoundingPayment(
                new BigDecimal("0.02")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> payments = (List<Map<String, Object>>)
                service.saleDocumentData(ticket, false, 0).get("payments");
        assertEquals("-0,02", payments.get(0).get("amount"));
    }

    /**
     * The X and the Z are two documents: a layout administered for the closing
     * does not lay out the reading, and the other way round.
     */
    @Test
    void theReadingAndTheClosingAreTwoDocuments() {
        TicketPrinterService service = newService();
        wireRenderer(service);
        CashSessionService.SessionReport report = new CashSessionService.SessionReport();
        report.session = session(null);
        report.closing = false;
        report.totalsByMethod = new LinkedHashMap<>();
        report.totalsByMethod.put("CASH", new BigDecimal("200.00"));
        report.detailedMethods.add("CASH");
        report.theoreticalCash = new BigDecimal("300.00");
        report.totalRefunds = BigDecimal.ZERO;
        com.intermarche.pos.domain.setting.DocumentTemplate closing = layout(
                com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.Z_REPORT,
                "Z {session.number}");
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubAdministeredLayout(panache, closing);
            panache.when(() -> com.intermarche.pos.domain.setting.DocumentTemplate.list(
                            "active = true and documentType = ?1 order by priority, code",
                            com.intermarche.pos.domain.setting.DocumentTemplate
                                    .DocumentType.X_REPORT))
                    .thenReturn(List.of());
            service.printSessionReport(report);
            assertTrue(captureReceipt(service).contains("RAPPORT X - LECTURE"));
        }

        TicketPrinterService closingService = newService();
        wireRenderer(closingService);
        report.closing = true;
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubAdministeredLayout(panache, closing);
            closingService.printSessionReport(report);
            assertEquals("Z S-001", captureReceipt(closingService));
        }
    }

    /**
     * The session a layout reads carries the tenders the report DETAILS and the
     * lump total of the rest (BO-03-02-25, BO-03-03).
     */
    @Test
    void theSessionDocumentCarriesWhatTheReportDetails() {
        TicketPrinterService service = newService();
        CashSessionService.SessionReport report = new CashSessionService.SessionReport();
        report.session = session(NOW);
        report.closing = true;
        report.ticketCount = 12;
        report.totalsByMethod = new LinkedHashMap<>();
        report.totalsByMethod.put("CASH", new BigDecimal("200.00"));
        report.totalsByMethod.put("CARD", new BigDecimal("100.00"));
        report.detailedMethods.add("CASH");
        report.otherMethodsTotal = new BigDecimal("100.00");
        report.bankDepositTotal = new BigDecimal("100.00");
        report.theoreticalCash = new BigDecimal("300.00");
        report.totalRefunds = BigDecimal.ZERO;
        Map<String, Object> data = service.sessionDocumentData(report);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tenders = (List<Map<String, Object>>) data.get("tenders");
        assertEquals(1, tenders.size());
        assertEquals("CASH", tenders.get(0).get("label"));

        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) data.get("totals");
        assertEquals("12", totals.get("ticketCount"));
        assertEquals("100,00", totals.get("otherTenders"));
        assertEquals("100,00", totals.get("bankDeposit"));
        assertEquals("100,00", totals.get("openingFloat"));

        @SuppressWarnings("unchecked")
        Map<String, Object> session = (Map<String, Object>) data.get("session");
        assertEquals("S-001", session.get("number"));
        assertEquals(Boolean.TRUE, session.get("closing"));
    }

    /**
     * A report whose session carries nothing still describes itself — the null
     * legs of the session builder.
     */
    @Test
    void aBareReportStillDescribesItself() {
        TicketPrinterService service = newService();
        CashSessionService.SessionReport report = new CashSessionService.SessionReport();
        Map<String, Object> data = service.sessionDocumentData(report);
        assertEquals("", data.get("terminal"));
        assertEquals("", data.get("operator"));
        @SuppressWarnings("unchecked")
        Map<String, Object> session = (Map<String, Object>) data.get("session");
        assertEquals("", session.get("number"));
        assertEquals("", session.get("openedAt"));
        assertEquals("", session.get("closedAt"));
    }

    /**
     * An administered layout replaces the gift-card voucher and the credit-note
     * voucher alike, each reading its own instrument.
     */
    @Test
    void anAdministeredLayoutReplacesTheVouchers() {
        TicketPrinterService giftService = newService();
        wireRenderer(giftService);
        com.intermarche.pos.domain.setting.DocumentTemplate gift = layout(
                com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.GIFT_CARD_VOUCHER,
                "CADEAU {instrument.number} {instrument.amount}");
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubAdministeredLayout(panache, gift);
            giftService.printGiftCardVoucher("296000000000042", new BigDecimal("50.00"));
            assertEquals("CADEAU 296000000000042 50,00", captureReceipt(giftService));
        }

        TicketPrinterService changeService = newService();
        wireRenderer(changeService);
        com.intermarche.pos.domain.setting.DocumentTemplate note = layout(
                com.intermarche.pos.domain.setting.DocumentTemplate
                        .DocumentType.CREDIT_NOTE_VOUCHER,
                "AVOIR {instrument.number} {instrument.amount}");
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubAdministeredLayout(panache, note);
            changeService.printChangeVoucher("297000000000042", new BigDecimal("30.00"));
            assertEquals("AVOIR 297000000000042 30,00", captureReceipt(changeService));
        }
    }

    /**
     * An administered layout replaces the refund receipt, and reads the return
     * through its own keys.
     */
    @Test
    void anAdministeredLayoutReplacesTheRefundReceipt() {
        TicketPrinterService service = newService();
        wireRenderer(service);
        Refund refund = refund();
        Ticket original = ticket(0, null);
        com.intermarche.pos.domain.setting.DocumentTemplate template = layout(
                com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.REFUND_RECEIPT,
                "{store.name}|RETOUR {document.number} SUR {document.originalNumber}"
                        + " PAR {document.method}"
                        + "{#for l in lines}|{l.label} {l.total}{/for}"
                        + "|TOTAL {totals.includingTax}");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Refund.findById(9L)).thenReturn(refund);
            panache.when(() -> Ticket.findById(refund.originalTicketId)).thenReturn(original);
            stubAdministeredLayout(panache, template);
            service.printRefund(9L);
            assertEquals("MAGASIN LYON|RETOUR C04-R000012 SUR C04-00000001 PAR ESPECES"
                    + "|BEURRE 4,00|TOTAL 4,00", captureReceipt(service));
        }
    }

    /**
     * A refund whose original sale could not be loaded still describes itself —
     * the null leg of the original guard.
     */
    @Test
    void aRefundWithoutItsOriginalStillDescribesItself() {
        TicketPrinterService service = newService();
        Map<String, Object> data = service.refundDocumentData(refund(), null);
        @SuppressWarnings("unchecked")
        Map<String, Object> document = (Map<String, Object>) data.get("document");
        assertEquals("", document.get("originalNumber"));
        @SuppressWarnings("unchecked")
        Map<String, Object> store = (Map<String, Object>) data.get("store");
        assertEquals("", store.get("name"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> vatRows = (List<Map<String, Object>>) data.get("vatRows");
        assertEquals(1, vatRows.size());
    }

    /**
     * A refund carrying no method, no date and no line describes itself all the
     * same — the remaining null legs of the refund builder.
     */
    @Test
    void aBareRefundStillDescribesItself() {
        TicketPrinterService service = newService();
        Refund bare = mock(Refund.class);
        bare.lines = new ArrayList<>();
        Map<String, Object> data = service.refundDocumentData(bare, null);
        assertEquals("", data.get("date"));
        assertEquals("", data.get("time"));
        @SuppressWarnings("unchecked")
        Map<String, Object> document = (Map<String, Object>) data.get("document");
        assertEquals("", document.get("method"));
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) data.get("totals");
        assertEquals("", totals.get("includingTax"));
        assertTrue(((List<?>) data.get("lines")).isEmpty());
    }

    /**
     * An administered layout replaces the withdrawal ticket, counts included,
     * and the transfer ticket, which names its two ends instead.
     */
    @Test
    void anAdministeredLayoutReplacesTheMovementTickets() {
        TicketPrinterService withdrawal = newService();
        wireRenderer(withdrawal);
        com.intermarche.pos.domain.setting.DocumentTemplate taken = layout(
                com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.WITHDRAWAL_TICKET,
                "{movement.label} {movement.tender} {movement.amount} PAR {operator} SUR {terminal}"
                        + "{#for c in counts}|{c.label} {c.amount}{/for}");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubAdministeredLayout(panache, taken);
            withdrawal.printWithdrawalTicket("CASH", new BigDecimal("500.00"),
                    List.<String[]>of(new String[] {"Billets 50", "8"}), "MARIE", "C04");
            assertEquals("PRELEVEMENT CASH 500,00 PAR MARIE SUR C04|Billets 50 8",
                    captureReceipt(withdrawal));
        }

        TicketPrinterService transfer = newService();
        wireRenderer(transfer);
        com.intermarche.pos.domain.setting.DocumentTemplate moved = layout(
                com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.TRANSFER_TICKET,
                "{movement.label} {movement.from}>{movement.to} {movement.amount}");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubAdministeredLayout(panache, moved);
            transfer.printTransferTicket("CASH", "CHEQUE", new BigDecimal("120.00"),
                    "MARIE", "C04");
            assertEquals("TRANSFERT REGLEMENT CASH>CHEQUE 120,00", captureReceipt(transfer));
        }
    }

    /**
     * A movement carrying no tender, no ends, no amount, no counts, no operator
     * and no register still describes itself — the null legs of the movement
     * builder.
     */
    @Test
    void aBareMovementStillDescribesItself() {
        TicketPrinterService service = newService();
        Map<String, Object> data = service.movementDocumentData(
                null, null, null, null, null, null, null, null);
        assertEquals("", data.get("terminal"));
        assertEquals("", data.get("operator"));
        @SuppressWarnings("unchecked")
        Map<String, Object> movement = (Map<String, Object>) data.get("movement");
        assertEquals("", movement.get("label"));
        assertEquals("", movement.get("tender"));
        assertEquals("", movement.get("from"));
        assertEquals("", movement.get("to"));
        assertEquals("", movement.get("amount"));
        assertTrue(((List<?>) data.get("counts")).isEmpty());
    }

    /**
     * An administered layout replaces both card slips, each stating its own
     * kind — the credit and the abandoned debit are one document type with two
     * things to say.
     */
    @Test
    void anAdministeredLayoutReplacesTheCardSlips() {
        TicketPrinterService credit = newService();
        wireRenderer(credit);
        com.intermarche.pos.domain.setting.DocumentTemplate slip = layout(
                com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.CARD_RECEIPT,
                "{card.kind} {card.amount} {terminal}");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubAdministeredLayout(panache, slip);
            credit.printCardCreditReceipt(refund());
            assertEquals("CREDIT 4,00 C04", captureReceipt(credit));
        }

        TicketPrinterService abandoned = newService();
        wireRenderer(abandoned);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubAdministeredLayout(panache, slip);
            abandoned.printCardTnaReceipt(new BigDecimal("4.60"), null);
            assertEquals("ABANDON DEBIT 4,60 ", captureReceipt(abandoned));
        }
    }

    /**
     * A card slip carrying no amount, no frame, no register and no moment still
     * describes itself — the null legs of the card builder.
     */
    @Test
    void aBareCardSlipStillDescribesItself() {
        TicketPrinterService service = newService();
        Map<String, Object> data = service.cardDocumentData(null, null, null, null, null);
        assertEquals("", data.get("terminal"));
        assertEquals("", data.get("date"));
        @SuppressWarnings("unchecked")
        Map<String, Object> card = (Map<String, Object>) data.get("card");
        assertEquals("", card.get("kind"));
        assertEquals("", card.get("amount"));
        assertEquals("", card.get("frame"));
    }

    /**
     * An instrument carrying no number and no amount still describes itself —
     * the null legs of the instrument builder.
     */
    @Test
    void aBareInstrumentStillDescribesItself() {
        TicketPrinterService service = newService();
        @SuppressWarnings("unchecked")
        Map<String, Object> instrument = (Map<String, Object>)
                service.instrumentDocumentData(null, null).get("instrument");
        assertEquals("", instrument.get("number"));
        assertEquals("", instrument.get("amount"));
    }

    /**
     * {@code printChangeVoucher} says on its face that the note is CHANGE and
     * not a refund, and carries the registry number and the amount.
     */
    @Test
    void printChangeVoucherSaysItIsChange() {
        TicketPrinterService service = newService();
        service.printChangeVoucher("297000000000042", new BigDecimal("30.00"));
        String out = captureReceipt(service);
        assertTrue(out.contains("AVOIR - RENDU DE MONNAIE"));
        assertTrue(out.contains("30,00 E"));
        assertTrue(out.contains("N° 297000000000042"));
        assertTrue(out.contains("(scannable en caisse - solde au registre)"));
    }

    // --- printParkedTicket ---

    /**
     * Captures the receipt text pushed by a method that prints without cutting
     * the paper (the parked ticket and the operator badge).
     *
     * @param service the service whose printer mock is inspected
     * @return the rendered receipt text
     */
    private String captureRaw(TicketPrinterService service) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(service.hardwareService).printReceipt(captor.capture());
        return captor.getValue();
    }

    /**
     * {@code printParkedTicket} renders the header, the resume number, each
     * cart line — truncating a label longer than 20 characters (truncation
     * arm) and keeping a short one (no-truncation arm) — the waiting total and
     * the scan invitation.
     */
    @Test
    void printParkedTicketRendersHeaderLinesAndInvitation() {
        TicketPrinterService service = newService();
        Ticket draft = ticket(0, null);
        draft.lines.add(line("A", "UN LIBELLE ARTICLE TRES LONG A COUPER", "2", "1.00", "2.00"));
        draft.lines.add(line("B", "LAIT", "1", "1.50", "1.50"));
        service.printParkedTicket(draft);
        String out = captureRaw(service);
        assertTrue(out.contains("TICKET EN ATTENTE"));
        assertTrue(out.contains("C04-00000001"));
        assertTrue(out.contains("UN LIBELLE ARTICLE T"));
        assertFalse(out.contains("UN LIBELLE ARTICLE TRES LONG A COUPER"));
        assertTrue(out.contains("LAIT"));
        assertTrue(out.contains("TOTAL EN ATTENTE"));
        assertTrue(out.contains("SCANNEZ CE NUMERO POUR REPRENDRE"));
    }

    /**
     * {@code printParkedTicket} omits a cancelled article (lot C4): the parked
     * receipt shows the live cart only, never a conserved witness (the
     * {@code line.cancelled} true arm of the parked loop).
     */
    @Test
    void printParkedTicketOmitsACancelledArticle() {
        TicketPrinterService service = newService();
        Ticket draft = ticket(0, null);
        draft.lines.add(line("A", "LAIT", "1", "1.50", "1.50"));
        TicketLine cancelled = line("B", "PRODUIT ANNULE", "1", "5.00", "5.00");
        cancelled.cancelled = true;
        draft.lines.add(cancelled);
        service.printParkedTicket(draft);
        String out = captureRaw(service);
        assertTrue(out.contains("LAIT"));
        assertFalse(out.contains("PRODUIT ANNULE"));
    }

    // --- printOperatorBadge ---

    /**
     * {@code printOperatorBadge} renders the badge with the operator's login
     * and its badge id when present (badge-id present arm).
     */
    @Test
    void printOperatorBadgeRendersBadgeId() {
        TicketPrinterService service = newService();
        Employee employee = new Employee();
        employee.loginName = "jdupont";
        employee.badgeId = "12341234";
        service.printOperatorBadge(employee);
        String out = captureRaw(service);
        assertTrue(out.contains("BADGE OPERATEUR"));
        assertTrue(out.contains("jdupont"));
        assertTrue(out.contains("12341234"));
        assertTrue(out.contains("SCANNEZ OU SAISISSEZ CE NUMERO"));
    }

    /**
     * {@code printOperatorBadge} prints a dash for a missing badge id
     * (badge-id null arm).
     */
    @Test
    void printOperatorBadgeRendersDashWithoutBadgeId() {
        TicketPrinterService service = newService();
        Employee employee = new Employee();
        employee.loginName = "jdupont";
        employee.badgeId = null;
        service.printOperatorBadge(employee);
        String out = captureRaw(service);
        assertTrue(out.contains("BADGE"));
        assertTrue(out.contains("jdupont"));
        assertTrue(out.contains("-"));
    }

    // --------------------------------------------------
    // renderTicket — logo, family grouping, payment kinds
    // --------------------------------------------------

    /**
     * Covers the printed-logo arm of {@code renderTicket} (the {@code !logo.isEmpty()}
     * true arm): with a till that carries a logo, {@link ReceiptLogo#directive()}
     * yields a non-empty directive, printed as its own line above the store name.
     */
    @Test
    void printTicketPrintsTheLogoDirectiveWhenPresent() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedStatic<ReceiptLogo> logo = mockStatic(ReceiptLogo.class)) {
            logo.when(ReceiptLogo::directive).thenReturn("[[LOGO abcd]]");
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.startsWith("[[LOGO abcd]]\n"));
        }
    }

    /**
     * Covers the no-logo arm of {@code renderTicket} (the {@code !logo.isEmpty()}
     * false arm): a till with no printable logo — {@link ReceiptLogo#directive()}
     * empty — prints no logo line, the store name being the first line. The real
     * classpath carries a logo file in this build, so the empty case is forced with a
     * stub.
     */
    @Test
    void printTicketOmitsTheLogoLineWhenAbsent() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedStatic<ReceiptLogo> logo = mockStatic(ReceiptLogo.class)) {
            logo.when(ReceiptLogo::directive).thenReturn("");
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertFalse(out.contains("[[LOGO"));
            assertTrue(out.startsWith(" ".repeat((42 - "INTERMARCHE".length()) / 2) + "INTERMARCHE\n"));
        }
    }

    /**
     * Covers the family-grouping arms of {@code renderTicket} (LC-08-01-07): with the
     * FAMILY order administered, {@code groupByFamily} is true and a heading prints
     * the first time a family appears (the {@code !family.equals(printedFamily)} true
     * arm), NOT for a second consecutive line of the same family (its false arm), and
     * again for a second family — BOULANGERIE thus appears exactly once though two
     * lines carry it.
     */
    @Test
    void printTicketGroupsLinesUnderFamilyHeadings() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.ticketLineOrder()).thenReturn("FAMILY");
        Ticket ticket = ticket(0, null);
        TicketLine a = line("U1", "PAIN", "1", "2.00", "2.00");
        a.familyLabel = "BOULANGERIE";
        TicketLine b = line("U2", "BAGUETTE", "1", "1.00", "1.00");
        b.familyLabel = "BOULANGERIE";
        TicketLine c = line("U3", "LAIT", "1", "1.00", "1.00");
        c.familyLabel = "CREMERIE";
        ticket.lines.add(a);
        ticket.lines.add(b);
        ticket.lines.add(c);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.contains("BOULANGERIE"));
            assertTrue(out.contains("CREMERIE"));
            assertEquals(out.indexOf("BOULANGERIE"), out.lastIndexOf("BOULANGERIE"));
        }
    }

    /**
     * Covers the foreign-currency settlement arms of {@code renderTicket}
     * (LC-07-14-05): a DEVISE payment prints its euro value, the foreign amount and
     * currency, and its rate when set (the {@code exchangeRate == null ? "" :
     * toPlainString()} else arm and the non-null arm of {@code safe}); a second devise
     * with a null rate and a null currency code covers the null arm of that ternary
     * and the null arm of {@code safe}.
     */
    @Test
    void printTicketPrintsForeignCurrencySettlements() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        com.intermarche.pos.domain.payment.ForeignCurrencyPayment chf =
                new com.intermarche.pos.domain.payment.ForeignCurrencyPayment(new BigDecimal("10.00"));
        chf.currencyCode = "CHF";
        chf.foreignAmount = new BigDecimal("9.50");
        chf.exchangeRate = new BigDecimal("1.0531");
        com.intermarche.pos.domain.payment.ForeignCurrencyPayment noRate =
                new com.intermarche.pos.domain.payment.ForeignCurrencyPayment(new BigDecimal("2.00"));
        noRate.currencyCode = null;
        noRate.foreignAmount = new BigDecimal("2.00");
        noRate.exchangeRate = null;
        ticket.payments.add(chf);
        ticket.payments.add(noRate);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.contains("DEVISE CHF"));
            assertTrue(out.contains("9,50 CHF  TAUX 1.0531"));
            assertTrue(out.contains("  2,00   TAUX \n"));
        }
    }

    /**
     * Covers the rounding-settlement arm of {@code renderTicket} (LC-07-03-06): an
     * ARRONDI payment prints with the OPPOSITE sign of the stored ledger amount — a
     * ledger {@code +0,02} reads on paper as {@code -0,02}.
     */
    @Test
    void printTicketPrintsRoundingWithInvertedSign() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        ticket.payments.add(
                new com.intermarche.pos.domain.payment.RoundingPayment(new BigDecimal("0.02")));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.contains("ARRONDI"));
            assertTrue(out.contains("-0,02 E"));
        }
    }

    /**
     * Covers the customer-credit settlement arms of {@code renderTicket}
     * (LC-07-09-06): a CREDIT payment names its debtor under its own line with the
     * sale day, the account number and the account name (the non-null arms of both
     * account ternaries); a second credit with a null number and a null name covers
     * their empty-string arms. The sale day is present (the {@code creationDate ==
     * null} false arm); its true arm is unreachable here, the header formats the date
     * unconditionally beforehand.
     */
    @Test
    void printTicketPrintsCreditSettlementDebtorLines() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        com.intermarche.pos.domain.payment.CreditPayment full =
                new com.intermarche.pos.domain.payment.CreditPayment(new BigDecimal("10.00"));
        full.accountNumber = "CPT-42";
        full.accountName = "MAIRIE DE LYON";
        com.intermarche.pos.domain.payment.CreditPayment blank =
                new com.intermarche.pos.domain.payment.CreditPayment(new BigDecimal("2.00"));
        blank.accountNumber = null;
        blank.accountName = null;
        ticket.payments.add(full);
        ticket.payments.add(blank);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.contains("CREDIT"));
            assertTrue(out.contains("02/08/2026  COMPTE CPT-42"));
            assertTrue(out.contains("MAIRIE DE LYON"));
            assertTrue(out.contains("02/08/2026  COMPTE \n"));
        }
    }

    // --------------------------------------------------
    // printRenderedTicket
    // --------------------------------------------------

    /**
     * {@code printRenderedTicket} prints a store-node-rendered ticket verbatim and
     * cuts the paper (the non-blank arm of the {@code content == null ||
     * content.isBlank()} guard).
     */
    @Test
    void printRenderedTicketPrintsGivenContentVerbatim() {
        TicketPrinterService service = newService();
        service.printRenderedTicket("RENDERED ELSEWHERE");
        assertEquals("RENDERED ELSEWHERE", captureReceipt(service));
    }

    /**
     * {@code printRenderedTicket} prints nothing on null content (the {@code content
     * == null} true arm of the guard).
     */
    @Test
    void printRenderedTicketPrintsNothingOnNullContent() {
        TicketPrinterService service = newService();
        service.printRenderedTicket(null);
        verifyNoInteractions(service.hardwareService);
    }

    /**
     * {@code printRenderedTicket} prints nothing on blank content (the {@code content
     * != null} arm followed by {@code isBlank()} true).
     */
    @Test
    void printRenderedTicketPrintsNothingOnBlankContent() {
        TicketPrinterService service = newService();
        service.printRenderedTicket("   ");
        verifyNoInteractions(service.hardwareService);
    }

    // --------------------------------------------------
    // printTicketIdentityBarcode
    // --------------------------------------------------

    /**
     * {@code printTicketIdentityBarcode} prints the number in clear and its barcode
     * directive and cuts the paper (the ticket-present arm).
     */
    @Test
    void printTicketIdentityBarcodePrintsNumberAndBarcode() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            service.printTicketIdentityBarcode(1L);
        }
        String out = captureReceipt(service);
        assertTrue(out.contains("IDENTIFIANT TICKET"));
        assertTrue(out.contains("C04-00000001"));
        assertTrue(out.contains("[[BARCODE C04-00000001]]"));
    }

    /**
     * {@code printTicketIdentityBarcode} prints nothing on an unknown ticket (the
     * ticket-null arm).
     */
    @Test
    void printTicketIdentityBarcodePrintsNothingOnUnknownTicket() {
        TicketPrinterService service = newService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(9L)).thenReturn(null);
            service.printTicketIdentityBarcode(9L);
        }
        verifyNoInteractions(service.hardwareService);
    }

    // --------------------------------------------------
    // printCardReceipt — opposite arms
    // --------------------------------------------------

    /**
     * Covers the opposite arms of {@code printCardReceipt} and {@code cardHeader}: a
     * ticket with no store (the {@code store != null} false arm → null store name and
     * the {@code storeName != null} false arm), a null mention (the {@code mention !=
     * null} false arm), a first card with a null authorization number (the {@code
     * authorizationNumber != null} false arm) and degraded mode ON (its true arm), a
     * second card with a blank authorization number (the {@code !isBlank()} false arm)
     * and degraded mode OFF, and a required signature (its true arm): two slips print,
     * carrying MODE DEGRADE and a SIGNATURE DU CLIENT block but no store name, no
     * mention and no AUTORISATION line.
     */
    @Test
    void printCardReceiptRendersDegradedSignatureSlipsWithoutStoreOrAuth() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.store = null;
        com.intermarche.pos.domain.payment.CardPayment nullAuth =
                new com.intermarche.pos.domain.payment.CardPayment(new BigDecimal("12.00"));
        nullAuth.authorizationNumber = null;
        nullAuth.degradedMode = true;
        com.intermarche.pos.domain.payment.CardPayment blankAuth =
                new com.intermarche.pos.domain.payment.CardPayment(new BigDecimal("3.00"));
        blankAuth.authorizationNumber = "   ";
        blankAuth.degradedMode = false;
        ticket.payments.add(nullAuth);
        ticket.payments.add(blankAuth);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            assertEquals(2, service.printCardReceipt(1L, true, null));
        }
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(service.hardwareService, times(2)).printReceipt(captor.capture());
        verify(service.hardwareService, times(2)).cutPaper();
        String out = String.join("\n", captor.getAllValues());
        assertTrue(out.contains("MODE DEGRADE"));
        assertTrue(out.contains("SIGNATURE DU CLIENT"));
        assertFalse(out.contains("AUTORISATION"));
        assertFalse(out.contains("MAGASIN LYON"));
        assertFalse(out.contains("***"));
    }

    // --------------------------------------------------
    // printCardCreditReceipt / printCardTnaReceipt
    // --------------------------------------------------

    /**
     * {@code printCardCreditReceipt} prints the refund's card-credit slip
     * (LC-08-03-10): the CREDIT mention (via {@code cardHeader} with a null store
     * name), the terminal id, the date and the credited amount.
     */
    @Test
    void printCardCreditReceiptPrintsCreditSlip() {
        TicketPrinterService service = newService();
        Refund refund = new Refund();
        refund.terminalId = "C04";
        refund.creationDate = NOW;
        refund.totalAmount = new BigDecimal("6.00");
        service.printCardCreditReceipt(refund);
        String out = captureReceipt(service);
        assertTrue(out.contains("TICKET CARTE BANCAIRE"));
        assertTrue(out.contains("*** CREDIT ***"));
        assertTrue(out.contains("Caisse : C04"));
        assertTrue(out.contains("MONTANT CREDITE"));
        assertTrue(out.contains("6,00 E"));
    }

    /**
     * {@code printCardTnaReceipt} with a terminal TNA frame prints it verbatim and,
     * the frame already ending on a newline, appends none (the {@code frame != null &&
     * !isBlank()} true arm and the {@code endsWith("\n")} true arm); a non-null amount
     * beside a frame is NOT printed as a line.
     */
    @Test
    void printCardTnaReceiptPrintsFrameEndingWithNewline() {
        TicketPrinterService service = newService();
        service.printCardTnaReceipt(new BigDecimal("5.00"), "TNA-FRAME\n");
        String out = captureReceipt(service);
        assertTrue(out.contains("*** ABANDON DEBIT ***"));
        assertTrue(out.contains("TNA-FRAME\n"));
        assertFalse(out.contains("MONTANT"));
    }

    /**
     * {@code printCardTnaReceipt} with a frame that does NOT end on a newline appends
     * one (the {@code endsWith("\n")} false arm).
     */
    @Test
    void printCardTnaReceiptAppendsNewlineToFrameWithout() {
        TicketPrinterService service = newService();
        service.printCardTnaReceipt(null, "TNA-FRAME");
        assertTrue(captureReceipt(service).contains("TNA-FRAME\n"));
    }

    /**
     * {@code printCardTnaReceipt} with a blank frame but a non-null amount prints the
     * register's own slip (the {@code !isBlank()} false arm, then the {@code amount !=
     * null} true arm).
     */
    @Test
    void printCardTnaReceiptPrintsAmountWhenNoFrame() {
        TicketPrinterService service = newService();
        service.printCardTnaReceipt(new BigDecimal("5.00"), "   ");
        String out = captureReceipt(service);
        assertTrue(out.contains("MONTANT"));
        assertTrue(out.contains("5,00 E"));
    }

    /**
     * {@code printCardTnaReceipt} with neither frame nor amount prints only the header
     * (the {@code frame != null} false arm and the {@code amount != null} false arm) —
     * a bare TNA proof.
     */
    @Test
    void printCardTnaReceiptPrintsBareHeaderWithoutFrameOrAmount() {
        TicketPrinterService service = newService();
        service.printCardTnaReceipt(null, null);
        String out = captureReceipt(service);
        assertTrue(out.contains("*** ABANDON DEBIT ***"));
        assertFalse(out.contains("MONTANT"));
    }

    // --------------------------------------------------
    // printExchangeVoucher
    // --------------------------------------------------

    /**
     * {@code printExchangeVoucher} prints nothing on an unknown ticket (the
     * ticket-null arm).
     */
    @Test
    void printExchangeVoucherPrintsNothingOnUnknownTicket() {
        TicketPrinterService service = newService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(9L)).thenReturn(null);
            service.printExchangeVoucher(9L, null);
        }
        verifyNoInteractions(service.hardwareService);
    }

    /**
     * {@code printExchangeVoucher} with no selection prints the whole sale
     * (LC-08-05-10): every sold article with its quantity and NO amount, with the
     * store, the terminal id and the date all present (their true arms), a label
     * longer than 30 chars truncated (its true arm) and a cancelled article skipped
     * (its true arm). A null {@code lineIds} makes {@code whole} true through the
     * {@code == null} arm, so {@code !whole} short-circuits the per-line filter.
     */
    @Test
    void printExchangeVoucherPrintsWholeSaleWhenNoSelection() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.terminalId = "C04";
        TicketLine longLabel = line("U1", "ARTICLE AVEC UN LIBELLE VRAIMENT TRES LONG", "2", "1.00", "2.00");
        longLabel.id = 10L;
        TicketLine cancelled = line("U2", "ANNULE", "1", "5.00", "5.00");
        cancelled.id = 11L;
        cancelled.cancelled = true;
        ticket.lines.add(longLabel);
        ticket.lines.add(cancelled);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            service.printExchangeVoucher(1L, null);
        }
        String out = captureReceipt(service);
        assertTrue(out.contains("BON POUR ECHANGE"));
        assertTrue(out.contains("MAGASIN LYON"));
        assertTrue(out.contains("Caisse : C04"));
        assertTrue(out.contains("Date   : 02/08/2026"));
        assertTrue(out.contains("ARTICLE AVEC UN LIBELLE"));
        assertFalse(out.contains("TRES LONG"));
        assertFalse(out.contains("ANNULE"));
        assertTrue(out.contains("AUCUN MONTANT NE FIGURE SUR CE BON"));
    }

    /**
     * {@code printExchangeVoucher} treats an EMPTY selection as the whole sale
     * (LC-08-05-10): a non-null but empty {@code lineIds} makes {@code whole} true
     * through the {@code isEmpty()} arm, so every sold article prints.
     */
    @Test
    void printExchangeVoucherTreatsEmptySelectionAsWhole() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        TicketLine a = line("U1", "PAIN", "1", "2.00", "2.00");
        a.id = 30L;
        ticket.lines.add(a);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            service.printExchangeVoucher(1L, java.util.Collections.emptySet());
        }
        assertTrue(captureReceipt(service).contains("PAIN"));
    }

    /**
     * {@code printExchangeVoucher} with a selection narrows to the named lines
     * (LC-08-05-12): a non-empty {@code lineIds} makes {@code whole} false, a line
     * whose id is in the set is kept and one whose id is absent is dropped (both arms
     * of the {@code !whole && !contains} filter); the store, terminal id and date are
     * all absent (their false arms) and a short label is not truncated (its false arm).
     */
    @Test
    void printExchangeVoucherNarrowsToSelectedLines() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.store = null;
        ticket.terminalId = null;
        ticket.creationDate = null;
        TicketLine kept = line("U1", "PAIN", "1", "2.00", "2.00");
        kept.id = 20L;
        TicketLine dropped = line("U2", "LAIT", "1", "1.00", "1.00");
        dropped.id = 21L;
        ticket.lines.add(kept);
        ticket.lines.add(dropped);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            service.printExchangeVoucher(1L, java.util.Set.of(20L));
        }
        String out = captureReceipt(service);
        assertTrue(out.contains("PAIN"));
        assertFalse(out.contains("LAIT"));
        assertFalse(out.contains("MAGASIN LYON"));
        assertFalse(out.contains("Caisse"));
        assertFalse(out.contains("Date"));
    }
}
