package com.intermarche.pos.ui.hardware;

import com.intermarche.pos.domain.Address;
import com.intermarche.pos.domain.CashSession;
import com.intermarche.pos.domain.Employee;
import com.intermarche.pos.domain.Store;
import com.intermarche.pos.domain.ticket.CashPayment;
import com.intermarche.pos.domain.ticket.Refund;
import com.intermarche.pos.domain.ticket.RefundLine;
import com.intermarche.pos.domain.ticket.TechnicalEvent;
import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.domain.ticket.TicketLine;
import com.intermarche.pos.domain.ticket.TicketLineValuation;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
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
            assertTrue(out.trim().endsWith("SUIVEZ-NOUS EN LIGNE"));
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
            assertTrue(out.trim().endsWith("A BIENTOT"));
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
     * Wires a live register state carrying a loyalty projection.
     *
     * @param service the service under test
     * @param earnTotal the projected earn, or null
     * @param draftId the draft in progress, or null
     * @param lastClosedId the last closed ticket, or null
     * @return the wired state
     */
    private PosState wireLiveState(TicketPrinterService service, BigDecimal earnTotal,
                                   Long draftId, Long lastClosedId) {
        PosState state = new PosState();
        state.fidelity.assignCard("2990000000019");
        state.fidelity.earnTotal = earnTotal;
        state.payment.ticketDbId = draftId;
        state.lastClosedTicketId = lastClosedId;
        service.posState = state;
        return state;
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
     * WITHOUT a register state the loyalty section is skipped: the printer is
     * also built by hand in tests and by any caller outside CDI, and an
     * absent state simply means "no loyalty section" rather than a failure.
     */
    @Test
    void printTicketSkipsTheLoyaltySectionWithoutARegisterState() {
        TicketPrinterService service = newService();
        service.posState = null;
        Ticket ticket = ticket(0, null);
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
     * The section prints on the ticket the register is CURRENTLY on, with its
     * total and one line per rule — those labels are the only rule data the
     * POS is allowed to print, and they come from imfid, never invented here.
     */
    @Test
    void printTicketPrintsTheLoyaltySectionOnTheCurrentTicket() {
        TicketPrinterService service = newService();
        PosState state = wireLiveState(service, new BigDecimal("1.03"), 1L, null);
        state.fidelity.earnEntries.add(new FidelityState.EarnLine(
                "SOCLE", "Cagnotte socle", new BigDecimal("0.28")));
        state.fidelity.earnEntries.add(new FidelityState.EarnLine(
                "F&L", "Fruits & legumes", new BigDecimal("0.75")));
        Ticket ticket = ticket(0, null);
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
            assertTrue(out.contains("+0,28 E"));
            assertTrue(out.contains("Fruits & legumes"));
        }
    }

    /**
     * The section also prints right AFTER the fiscal close, when the draft is
     * gone and only the last-closed id remains — that is exactly when the
     * cashier hits IMPRIMER TICKET.
     */
    @Test
    void printTicketPrintsTheLoyaltySectionOnTheLastClosedTicket() {
        TicketPrinterService service = newService();
        wireLiveState(service, new BigDecimal("1.03"), null, 1L);
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            assertTrue(captureReceipt(service).contains("CAGNOTTE DU JOUR"));
        }
    }

    /**
     * The DRAFT wins over the last closed id when both are set: the register
     * is on a new sale, and printing an older ticket must not borrow the
     * current projection.
     */
    @Test
    void printTicketPrefersTheDraftOverTheLastClosedTicket() {
        TicketPrinterService service = newService();
        wireLiveState(service, new BigDecimal("1.03"), 2L, 1L);
        Ticket ticket = ticket(0, null);
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
     * A DUPLICATA of an older ticket carries NO section: the earn is not
     * persisted, so borrowing the live projection would print somebody
     * else's cagnotte on this paper. Known and accepted gap.
     */
    @Test
    void printTicketPrintsNoLoyaltySectionOnAnotherTicket() {
        TicketPrinterService service = newService();
        wireLiveState(service, new BigDecimal("1.03"), null, 99L);
        Ticket ticket = ticket(0, null);
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
     * With NEITHER a draft NOR a last closed ticket the section is skipped:
     * there is nothing the projection could belong to.
     */
    @Test
    void printTicketSkipsTheSectionWhenNoTicketIsCurrent() {
        TicketPrinterService service = newService();
        wireLiveState(service, new BigDecimal("1.03"), null, null);
        Ticket ticket = ticket(0, null);
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
     * A NULL projection prints no section (second leg of the guard) — the
     * degraded loyalty display: nothing rather than a figure the register
     * cannot vouch for.
     */
    @Test
    void printTicketPrintsNoSectionWithoutAProjection() {
        TicketPrinterService service = newService();
        wireLiveState(service, null, 1L, null);
        Ticket ticket = ticket(0, null);
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
        wireLiveState(service, BigDecimal.ZERO, 1L, null);
        Ticket ticket = ticket(0, null);
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
     * A projection with NO rule entries prints the total alone: the section
     * header exists, the loop simply adds nothing.
     */
    @Test
    void printTicketPrintsTheSectionTotalWithoutRuleLines() {
        TicketPrinterService service = newService();
        wireLiveState(service, new BigDecimal("1.03"), 1L, null);
        Ticket ticket = ticket(0, null);
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
     * is skipped even on a live projection for the current ticket — the
     * {@code fidelityAdvantagesEnabled()} false arm gates the whole section.
     */
    @Test
    void printTicketSkipsTheLoyaltySectionWhenAdvantagesDisabled() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.fidelityAdvantagesEnabled()).thenReturn(false);
        wireLiveState(service, new BigDecimal("1.03"), 1L, null);
        Ticket ticket = ticket(0, null);
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
}
