package com.intermarche.pos.ui.hardware;

import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.payment.CashPayment;
import com.intermarche.pos.domain.payment.FidelityPayment;
import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.sale.*;
import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.domain.setting.DocumentTemplate;
import com.intermarche.pos.domain.store.Address;
import com.intermarche.pos.domain.store.Store;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.service.DocumentTemplateService;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.payment.PaymentState;
import com.intermarche.pos.ui.ticket.TicketState;
import com.intermarche.pos.ui.TestMessages;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
        // The operator messages come from the French bundle, so every
        // assertion below keeps the literal sentence the register shows
        // (LC-13-06).
        service.i18n = TestMessages.french();
        service.hardwareService = mock(HardwareService.class);
        service.technicalEventService = mock(TechnicalEventService.class);
        // Back-office parameters at their defaults: no EAN on paper. The
        // fidelity advantages default to ON, the pre-existing behavior the
        // loyalty-section cases rely on (BO-10-03-15).
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.parkingReceiptDetailed()).thenReturn(true);
        when(service.posSettingsService.cashDepositPrint()).thenReturn(true);
        when(service.posSettingsService.ticketBarcodeEnabled()).thenReturn(true);
        when(service.posSettingsService.fidelityAdvantagesEnabled()).thenReturn(true);
        // VAT breakdown defaults to ON, the pre-existing behavior every ticket
        // case relies on (BO-10-06-04).
        when(service.posSettingsService.vatBreakdownEnabled()).thenReturn(true);
        when(service.posSettingsService.vatTableMode()).thenReturn("COMBINED");
        // The currency defaults of the catalogue, which print exactly what the
        // register printed before the symbol became administrable: the symbol
        // E, after the amount (BO-10-07-04/-05/-06).
        when(service.posSettingsService.currencySymbolPrinted()).thenReturn(true);
        when(service.posSettingsService.currencySymbol()).thenReturn("E");
        when(service.posSettingsService.currencySymbolPosition()).thenReturn("RIGHT");
        // ONE handover slip here, which is the single-copy behaviour every
        // pre-existing collection-voucher case asserts: captureReceipt verifies a
        // SINGLE print. The two-copy cases stub the count themselves.
        when(service.posSettingsService.collectVoucherCopies()).thenReturn(1);
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
            silenceCatalog(mocked);
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
            // LC-07-03-06: le reglement est nomme en francais sur le papier, pas
            // par le nom de la classe Java.
            assertTrue(out.contains("ESPECES"));
            assertFalse(out.contains("CASH"));
            assertFalse(out.contains("DUPLICATA"));
            assertFalse(out.contains("Votre ticket en ligne"));
        }
    }

    /**
     * The per-rate VAT row holds on one line on a monolingual ticket, exactly as
     * before, and takes two lines inside the paper's 42 columns on a bilingual
     * one (LC-13-06-11), the labels' padding collapsed.
     */
    @Test
    void theVatRowStaysWithinThePaperOnABilingualTicket() {
        for (boolean bilingual : new boolean[]{false, true}) {
            TicketPrinterService service = newService();
            if (bilingual) {
                service.i18n = TestMessages.bilingual("nl");
            }
            Ticket ticket = ticket(0, null);
            ticket.lines.add(line("U1", "PAIN", "1", "12.00", "12.00"));
            ticket.payments.add(new CashPayment(new BigDecimal("12.00"), new BigDecimal("12.00")));
            try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
                silenceCatalog(mocked);
                mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
                mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                        .thenReturn(new ArrayList<TicketLineValuation>());
                service.printTicket(1L);
                String out = captureReceipt(service);
                for (String printed : out.split("\n")) {
                    if (!printed.startsWith("[[")) {
                        assertTrue(printed.length() <= 42, printed);
                    }
                }
                if (bilingual) {
                    assertTrue(out.contains("\n  BTW / TVA 20,00%\n"), out);
                    assertTrue(out.contains("EXCL / HT 10,00  BTW / TVA 2,00\n"), out);
                } else {
                    assertTrue(out.contains("  TVA 20,00%            HT 10,00  TVA 2,00\n"), out);
                }
            }
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
            silenceCatalog(mocked);
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
     * THE CHANGE IS PRINTED under the cash line: what the customer handed over
     * and what came back, so the figures are checkable after they have left the
     * lane (correctif besoins métier du 08/10, onglet ARTICLES).
     */
    @Test
    void printTicketPrintsTheChangeUnderTheCashLine() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "PAIN", "1", "24.60", "24.60"));
        ticket.payments.add(new CashPayment(new BigDecimal("24.60"), new BigDecimal("50.00")));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.contains("DONNE"), "the amount handed over must be printed");
            assertTrue(out.contains("50,00"), "the amount handed over must carry its figure");
            assertTrue(out.contains("RENDU MONNAIE"), "the change must be named");
            assertTrue(out.contains("25,40"), "the change must carry its figure");
        }
    }

    /**
     * LC-07-04-07/08: the cash a card cashback gave back is named on the
     * receipt and keeps its sign, so the settlements add up to the total.
     */
    @Test
    void printTicketNamesTheCashbackGivenBackInCash() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "PAIN", "1", "12.00", "12.00"));
        ticket.payments.add(new com.intermarche.pos.domain.payment.CardPayment(new BigDecimal("32.00")));
        ticket.payments.add(new CashPayment(new BigDecimal("-20.00"), null));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.lines().anyMatch(l -> l.startsWith("CASHBACK ESPECES") && l.endsWith("-20,00 E")),
                    "the cashback must name itself with its signed amount: " + out);
            assertFalse(out.contains("RENDU MONNAIE"), "a cashback is not a change on a tender");
        }
    }

    /**
     * LC-07-04-07/08: the negative cash of a net refund keeps naming itself
     * as cash: only a sale carries a cashback.
     */
    @Test
    void printTicketKeepsTheCashLabelOnANetRefund() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.totalIncludingTax = new BigDecimal("-5.00");
        ticket.lines.add(line("U1", "PAIN", "1", "-5.00", "-5.00"));
        ticket.payments.add(new CashPayment(new BigDecimal("-5.00"), null));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertFalse(out.contains("CASHBACK"), "a refund is no cashback: " + out);
            assertTrue(out.lines().anyMatch(l -> l.startsWith("ESPECES") && l.endsWith("-5,00 E")), out);
        }
    }

    /**
     * AN EXACT SUM PRINTS NEITHER LINE: a receipt that says "handed over 24,60,
     * change 0,00" reads as a defect, not as a courtesy (the tendered-equals-
     * applied arm of the gate).
     */
    @Test
    void printTicketPrintsNoChangeOnAnExactSum() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "PAIN", "1", "24.60", "24.60"));
        ticket.payments.add(new CashPayment(new BigDecimal("24.60"), new BigDecimal("24.60")));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertFalse(out.contains("DONNE"), "an exact sum hands nothing back");
            assertFalse(out.contains("RENDU MONNAIE"), "an exact sum hands nothing back");
        }
    }

    /**
     * A CASH SETTLEMENT THAT RECORDED NO TENDER prints neither line either: a
     * recovered draft and an older sale both carry a null tendered amount, and
     * the register states no change it cannot vouch for (the nullity arm).
     */
    @Test
    void printTicketPrintsNoChangeWithoutARecordedTender() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "PAIN", "1", "24.60", "24.60"));
        CashPayment cash = new CashPayment(new BigDecimal("24.60"), null);
        ticket.payments.add(cash);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertFalse(out.contains("DONNE"), "no recorded tender, no printed change");
            assertFalse(out.contains("RENDU MONNAIE"), "no recorded tender, no printed change");
        }
    }

    /**
     * EVERY SETTLEMENT METHOD IS NAMED IN FRENCH ON THE PAPER — one arm of the
     * tender switch per method, all twelve on a single ticket so no arm is
     * asserted on faith ({@code LC-07-03-06}).
     *
     * <p>The label used to be the Java class name with "Payment" chopped off and
     * upper-cased: {@code CASH} on a French receipt, and {@code SUPPLIERGIFTCARD}
     * on a gift-card settlement. Each assertion below also checks that the
     * English word is GONE, because a mapping that adds the French label without
     * replacing the derived one would read as passing.
     */
    @Test
    void printTicketNamesEverySettlementMethodInFrench() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        ticket.payments.add(new CashPayment(new BigDecimal("1.00"), new BigDecimal("1.00")));
        ticket.payments.add(new com.intermarche.pos.domain.payment.CardPayment(new BigDecimal("1.00")));
        ticket.payments.add(new com.intermarche.pos.domain.payment.ChequePayment(new BigDecimal("1.00")));
        ticket.payments.add(new com.intermarche.pos.domain.payment.VoucherPayment(
                new BigDecimal("1.00"), null, "B1"));
        ticket.payments.add(new FidelityPayment(new BigDecimal("1.00")));
        ticket.payments.add(new com.intermarche.pos.domain.payment.TicketRestoPayment(new BigDecimal("1.00")));
        ticket.payments.add(new com.intermarche.pos.domain.payment.CreditPayment(new BigDecimal("1.00")));
        ticket.payments.add(new com.intermarche.pos.domain.payment.BackupPayment(new BigDecimal("1.00")));
        ticket.payments.add(new com.intermarche.pos.domain.payment.TrdCardPayment(new BigDecimal("1.00")));
        ticket.payments.add(new com.intermarche.pos.domain.payment.RestoFlashPayment(new BigDecimal("1.00")));
        ticket.payments.add(new com.intermarche.pos.domain.payment.SupplierGiftCardPayment(new BigDecimal("1.00")));
        ticket.payments.add(new com.intermarche.pos.domain.payment.SogecPayment(new BigDecimal("1.00")));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.contains("ESPECES"), "Cash");
            assertTrue(out.contains("CARTE BANCAIRE"), "Card");
            assertTrue(out.contains("CHEQUE"), "Cheque");
            assertTrue(out.contains("BON D'ACHAT"), "Voucher");
            assertTrue(out.contains("CAGNOTTE"), "Fidelity");
            assertTrue(out.contains("TICKET RESTO"), "TicketResto");
            assertTrue(out.contains("CREDIT CLIENT"), "Credit");
            assertTrue(out.contains("SECOURS MONETIQUE"), "Backup");
            assertTrue(out.contains("CARTE TRD"), "TrdCard");
            assertTrue(out.contains("RESTO FLASH"), "RestoFlash");
            assertTrue(out.contains("CARTE CADEAU"), "SupplierGiftCard");
            assertTrue(out.contains("BON SOGEC"), "Sogec");
            assertFalse(out.contains("CASH"), "the class name must be gone");
            assertFalse(out.contains("VOUCHER"), "the class name must be gone");
            assertFalse(out.contains("FIDELITY"), "the class name must be gone");
            assertFalse(out.contains("TICKETRESTO"), "the class name must be gone");
            assertFalse(out.contains("BACKUP"), "the class name must be gone");
            assertFalse(out.contains("TRDCARD"), "the class name must be gone");
            assertFalse(out.contains("RESTOFLASH"), "the class name must be gone");
            assertFalse(out.contains("SUPPLIERGIFTCARD"), "the class name must be gone");
            assertFalse(out.contains("SOGECPAYMENT"), "the class name must be gone");
        }
    }

    /**
     * A settlement class this version has no word for falls back to its bare
     * type name — the switch's default arm. Ugly and visible beats a blank
     * beside a justified amount, and it names what has to be added.
     */
    @Test
    void printTicketFallsBackToTheTypeNameForAnUnknownMethod() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        ticket.payments.add(new UnknownTenderPayment(new BigDecimal("2.00")));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            assertTrue(captureReceipt(service).contains("UNKNOWNTENDER"));
        }
    }

    /**
     * A settlement method this version knows nothing about, used to exercise the
     * default arm of the tender switch.
     */
    private static class UnknownTenderPayment extends com.intermarche.pos.domain.payment.TicketPayment {

        /**
         * Settles the given amount.
         *
         * @param amount the settled amount
         */
        UnknownTenderPayment(BigDecimal amount) {
            super(amount);
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
        when(service.posSettingsService.vatTableMode()).thenReturn("AMOUNT_ONLY");
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        ticket.payments.add(new CashPayment(new BigDecimal("2.00"), new BigDecimal("2.00")));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
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
            silenceCatalog(mocked);
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
     * A discount whose engine placement is LINE prints under its article, the
     * label indented by two spaces (spec §1, the LINE arm): the delta 8,00−10,00
     * reads as −2,00 € right below the line.
     */
    @Test
    void printTicketPrintsLinePlacedAdvantageUnderTheArticle() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "PRODUIT", "1", "10.00", "8.00"));
        List<TicketLineValuation> valuations = new ArrayList<>();
        TicketLineValuation valuation = valuation("U1", "10.00", "8.00", "DISC", "OFFRE");
        valuation.advantagePrintMode = "LINE";
        valuations.add(valuation);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L)).thenReturn(valuations);
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.contains("\n  DISC"));
            assertTrue(out.contains("-2,00 E"));
        }
    }

    /**
     * A discount whose engine placement is absent prints in the footer, not
     * indented (spec §1, Option B default): the same −2,00 € appears without the
     * two-space indent, below the totals separator.
     */
    @Test
    void printTicketPrintsFooterPlacedAdvantageWhenPlacementAbsent() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "PRODUIT", "1", "10.00", "8.00"));
        List<TicketLineValuation> valuations = new ArrayList<>();
        valuations.add(valuation("U1", "10.00", "8.00", "DISC", "OFFRE"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L)).thenReturn(valuations);
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.contains("\nDISC"));
            assertTrue(!out.contains("\n  DISC"));
            assertTrue(out.indexOf("DISC") < out.indexOf("TOTAL TTC"));
        }
    }

    /**
     * The engine offer messages print in the footer, before the courtesy line
     * (spec §3): the retained text appears on the receipt and above "MERCI DE
     * VOTRE VISITE".
     */
    @Test
    void printTicketPrintsEngineMessagesInTheFooter() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.messages = new ArrayList<>(List.of(
                new com.intermarche.pos.domain.sale.TicketMessage(
                        "BELOW_TRIGGER", "PROFITEZ DE NOS OFFRES", "FOOTER")));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.contains("PROFITEZ DE NOS OFFRES"));
            assertTrue(out.indexOf("PROFITEZ DE NOS OFFRES") < out.indexOf("MERCI DE VOTRE VISITE"));
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
     * LC-08-05-07: where the integrated monetique returned its own receipt, the
     * duplicate reprints that very paper under the DUPLICATA mention, and
     * archives it as the customer copy.
     */
    @Test
    void printCardReceiptDuplicatesTheTerminalReceipt() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        com.intermarche.pos.domain.payment.CardPayment card =
                new com.intermarche.pos.domain.payment.CardPayment(new BigDecimal("12.00"));
        card.customerReceipt = "CARTE BANCAIRE\n**** **** **** 4242\nMONTANT 12,00 EUR\n";
        ticket.payments.add(card);
        service.customerDocumentService = mock(com.intermarche.pos.ui.customer.CustomerDocumentService.class);
        String expected = " ".repeat(12) + "*** DUPLICATA ***\n" + card.customerReceipt;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            assertEquals(1, service.printCardReceipt(1L, false, "DUPLICATA"));
        }
        verify(service.hardwareService).printReceipt(expected);
        verify(service.hardwareService).cutPaper();
        verify(service.customerDocumentService).archive(
                com.intermarche.pos.domain.sale.CustomerDocument.Kind.CARD, "C04-00000001", expected);
    }

    /**
     * LC-08-05-07: without a mention, the terminal receipt prints exactly as
     * the terminal composed it.
     */
    @Test
    void printCardReceiptPrintsTheTerminalReceiptVerbatimWithoutMention() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        com.intermarche.pos.domain.payment.CardPayment card =
                new com.intermarche.pos.domain.payment.CardPayment(new BigDecimal("12.00"));
        card.customerReceipt = "CARTE BANCAIRE\n";
        ticket.payments.add(card);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            assertEquals(1, service.printCardReceipt(1L, false, null));
        }
        verify(service.hardwareService).printReceipt("CARTE BANCAIRE\n");
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
     * BO-03-06-55: the store may reserve a RANGE for its gates by prefixing the
     * printed barcode. The ticket number is kept whatever the prefix — it is
     * what a return or a claim is looked up by — and the prefix is trimmed.
     */
    @Test
    void theReceiptBarcodeCarriesTheAdministeredPrefix() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.ticketBarcodePrefix()).thenReturn("  29  ");
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            assertTrue(captureReceipt(service).trim().endsWith("[[BARCODE 29C04-00000001]]"));
        }
    }

    /**
     * No prefix administered — null or blank — leaves the barcode carrying the
     * ticket number alone, which is what every receipt printed before the key
     * existed (both arms of the guard).
     */
    @Test
    void theReceiptBarcodeKeepsTheNumberAloneWithoutAPrefix() {
        for (String administered : new String[] {null, "   "}) {
            TicketPrinterService service = newService();
            when(service.posSettingsService.ticketBarcodePrefix()).thenReturn(administered);
            Ticket ticket = ticket(0, null);
            ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
            try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
                mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
                mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                        .thenReturn(new ArrayList<TicketLineValuation>());
                service.printTicket(1L);
                assertTrue(captureReceipt(service).trim().endsWith("[[BARCODE C04-00000001]]"));
            }
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
            silenceCatalog(mocked);
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
            silenceCatalog(mocked);
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
            silenceCatalog(mocked);
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
            silenceCatalog(mocked);
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
     * Builds a catalog product carrying its own article-code flag
     * (BO-10-02-34).
     *
     * @param flag the value of CODE_ARTICLE_ON_SCREEN
     * @return the product
     */
    private Product productShowingCode(String flag) {
        Product product = new Product();
        product.attributes = new HashMap<>();
        product.attributes.put(
                com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog.CODE_ON_SCREEN, flag);
        return product;
    }

    /**
     * Renders a ticket holding the given lines with the Panache statics
     * silenced.
     *
     * @param service the printer
     * @param lines the lines of the sale
     * @return the rendered receipt
     */
    private String renderWithLines(TicketPrinterService service, TicketLine... lines) {
        Ticket ticket = ticket(0, null);
        ticket.lines.addAll(List.of(lines));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            return service.renderTicket(ticket, false, 0);
        }
    }

    /**
     * BO-10-02-34: an article whose own flag says NO keeps its code off the
     * receipt even where the shop prints codes — the article decides.
     */
    @Test
    void anArticleFlaggedOffPrintsNoCodeDespiteTheShopParameter() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.showEan()).thenReturn(true);
        TicketLine line = line("U1", "PAIN", "1", "2.00", "2.00");
        line.ean = "3017620422003";
        line.product = productShowingCode("false");
        String out = renderWithLines(service, line);
        assertFalse(out.contains("3017620422003"), out);
        assertTrue(out.contains("PAIN"), out);
    }

    /**
     * BO-10-02-34: an article whose own flag says YES prints its code under its
     * label even where the shop prints none.
     */
    @Test
    void anArticleFlaggedOnPrintsItsCodeDespiteTheShopParameter() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.showEan()).thenReturn(false);
        TicketLine line = line("U1", "PAIN", "1", "2.00", "2.00");
        line.ean = "3017620422003";
        line.product = productShowingCode("true");
        String out = renderWithLines(service, line);
        assertTrue(out.contains("PAIN\n  3017620422003\n"), out);
    }

    /**
     * BO-10-02-34: an administered layout reads the article code only where the
     * receipt would print it — empty for a line whose code is not shown, the
     * code itself for an article flagged on.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theLayoutReadsTheCodeOnlyWhereItIsShown() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.showEan()).thenReturn(false);
        Ticket ticket = ticket(0, null);
        TicketLine hidden = line("U1", "PAIN", "1", "2.00", "2.00");
        hidden.ean = "3017620422003";
        TicketLine shown = line("U2", "LAIT", "1", "1.00", "1.00");
        shown.ean = "3256220000017";
        shown.product = productShowingCode("true");
        ticket.lines.add(hidden);
        ticket.lines.add(shown);
        List<Map<String, Object>> rows = (List<Map<String, Object>>)
                saleData(service, ticket, false, 0).get("lines");
        assertEquals("", rows.get(0).get("ean"));
        assertEquals("3256220000017", rows.get(1).get("ean"));
    }

    /**
     * BO-10-02-33: the pots frozen at closing print as a "SOLDES DES
     * CAGNOTTES" block, one line per pot — the balance on the right, nothing
     * there for a pot the programme sent without one.
     */
    @Test
    void theFrozenPotBalancesPrintUnderTheirHeading() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.fidelityPotBalances = "Cagnotte\t12.50\nBons\t";
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            String out = service.renderTicket(ticket, false, 0);
            assertTrue(out.contains("           SOLDES DES CAGNOTTES\n"), out);
            assertTrue(out.contains("Cagnotte" + " ".repeat(27) + "12,50 E\n"), out);
            assertTrue(out.contains("\nBons"), out);
            assertTrue(out.indexOf("SOLDES DES CAGNOTTES") < out.indexOf("Bons"), out);
        }
    }

    /**
     * BO-10-02-33: a pot label longer than the paper is cut so its amount still
     * ends the 42-column line.
     */
    @Test
    void aLongPotLabelIsCutToKeepItsAmountOnThePaper() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.fidelityPotBalances = "CAGNOTTE FIDELITE PROGRAMME NATIONAL TRES LONG\t3.20";
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            String out = service.renderTicket(ticket, false, 0);
            assertTrue(out.contains("\nCAGNOTTE FIDELITE PROGRAMME NATIONA 3,20 E\n"), out);
        }
    }

    /**
     * BO-10-02-33: a sale on which no pot was frozen prints no heading.
     */
    @Test
    void aSaleWithoutFrozenPotsPrintsNoPotBlock() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            String out = service.renderTicket(ticket, false, 0);
            assertFalse(out.contains("SOLDES DES CAGNOTTES"), out);
        }
    }

    /**
     * BO-10-02-33: the reprint restates the frozen pots — the duplicata reads
     * them from the ticket, not from a live programme.
     */
    @Test
    void theDuplicataRestatesTheFrozenPots() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(1, null);
        ticket.fidelityPotBalances = "Cagnotte\t12.50";
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.contains("DUPLICATA"), out);
            assertTrue(out.contains("SOLDES DES CAGNOTTES"), out);
            assertTrue(out.contains("12,50 E"), out);
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
            silenceCatalog(mocked);
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
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L)).thenReturn(valuations);
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.contains("AVANTAGE"));
        }
    }

    /**
     * A CORRECTION is printed on its own line, with its own label and a plus
     * sign: the ticket went UP, and printing that under an advantage label
     * would tell the customer the exact contrary of what happened.
     */
    @Test
    void printTicketPrintsTheCorrectionOnItsOwnLine() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "NUTELLA", "1", "10.00", "10.00"));
        List<TicketLineValuation> valuations = new ArrayList<>();
        TicketLineValuation valued = valuation("U1", "10.00", "6.60", "PROMO", "OFFRE");
        valued.correctionLabel = "AJUSTEMENT EGALIM";
        valued.correctionAmount = new BigDecimal("1.60");
        valuations.add(valued);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L)).thenReturn(valuations);
            service.printTicket(1L);
            String out = captureReceipt(service);
            // The advantage keeps its own amount, correction excluded: -5.00
            // granted, 1.60 put back, 6.60 paid.
            assertTrue(out.contains("PROMO"));
            assertTrue(out.contains("-5,00 E"));
            assertTrue(out.contains("AJUSTEMENT EGALIM"));
            assertTrue(out.contains("+1,60 E"));
        }
    }

    /**
     * A correction with no label falls back to a generic one, and a label
     * longer than the printable width is truncated — the two arms the
     * correction line adds.
     */
    @Test
    void printTicketFallsBackAndTruncatesTheCorrectionLabel() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "CAFE", "1", "10.00", "10.00"));
        ticket.lines.add(line("U2", "THE", "1", "10.00", "10.00"));
        List<TicketLineValuation> valuations = new ArrayList<>();
        TicketLineValuation noLabel = valuation("U1", "10.00", "11.00", null, null);
        noLabel.correctionAmount = new BigDecimal("1.00");
        valuations.add(noLabel);
        TicketLineValuation longLabel = valuation("U2", "10.00", "11.00", null, null);
        longLabel.correctionLabel = "AJUSTEMENT EGALIM TRES TRES LONG LIBELLE";
        longLabel.correctionAmount = new BigDecimal("1.00");
        valuations.add(longLabel);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L)).thenReturn(valuations);
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.contains("AJUSTEMENT"));
            assertTrue(out.contains("AJUSTEMENT EGALIM TRES TRE"));
            assertFalse(out.contains("LONG LIBELLE"));
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
            silenceCatalog(mocked);
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
        assertTrue(out.contains("ESPECES"));
        assertFalse(out.contains("CASH"));
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
            silenceCatalog(mocked);
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
     * A refund with no recalculation at all prints no recalculation block
     * (LC-05-07-02): all three legs of the block guard false.
     */
    @Test
    void printRefundPrintsNoRecalculationBlockWhenNoneApplied() {
        TicketPrinterService service = newService();
        Refund refund = refund(2L, Refund.RefundMethod.CASH, refundLine("PAIN", "1", "6.00"));
        refund.recalculationStatus = "NOT_APPLICABLE";
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Refund.findById(5L)).thenReturn(refund);
            mocked.when(() -> Ticket.findById(2L)).thenReturn(null);
            service.printRefund(5L);
            String out = captureReceipt(service);
            assertFalse(out.contains("RECALCUL DES AVANTAGES"));
        }
    }

    /**
     * A recomputed recalculation prints the advantage withdrawn and the
     * advantages the kept basket still earns, one per line (LC-05-07-02).
     */
    @Test
    void printRefundPrintsTheWithdrawnAdvantageAndTheSurvivingOnes() {
        TicketPrinterService service = newService();
        Refund refund = refund(2L, Refund.RefundMethod.CASH, refundLine("PAIN", "1", "6.00"));
        refund.recalculationStatus = "RECOMPUTED";
        refund.withdrawnGenerosity = new BigDecimal("2.50");
        refund.keptAdvantages = "REMISE PANIER | OFFRE-7";
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Refund.findById(5L)).thenReturn(refund);
            mocked.when(() -> Ticket.findById(2L)).thenReturn(null);
            service.printRefund(5L);
            String out = captureReceipt(service);
            assertTrue(out.contains("RECALCUL DES AVANTAGES"));
            assertTrue(out.contains("AVANTAGE REPRIS"));
            assertTrue(out.contains("2,50 E"));
            assertTrue(out.contains("Avantages conserves"));
            assertTrue(out.contains("- REMISE PANIER"));
            assertTrue(out.contains("- OFFRE-7"));
            assertFalse(out.contains("Cagnotte et coupons"));
        }
    }

    /**
     * A recomputed recalculation with no amount stored prints zero rather than
     * nothing (nullity leg of the withdrawn amount), and a kept-advantage column
     * left blank prints no list (both legs of that guard).
     */
    @Test
    void printRefundPrintsZeroAndNoListOnAnEmptyRecalculation() {
        TicketPrinterService service = newService();
        Refund refund = refund(2L, Refund.RefundMethod.CASH, refundLine("PAIN", "1", "6.00"));
        refund.recalculationStatus = "RECOMPUTED";
        refund.keptAdvantages = "   ";
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Refund.findById(5L)).thenReturn(refund);
            mocked.when(() -> Ticket.findById(2L)).thenReturn(null);
            service.printRefund(5L);
            String out = captureReceipt(service);
            assertTrue(out.contains("AVANTAGE REPRIS"));
            assertTrue(out.contains("0,00 E"));
            assertFalse(out.contains("Avantages conserves"));
        }
    }

    /**
     * A recomputed recalculation whose kept-advantage column is null prints no
     * list either (the other leg of that nullity guard).
     */
    @Test
    void printRefundPrintsNoListWithoutKeptAdvantages() {
        TicketPrinterService service = newService();
        Refund refund = refund(2L, Refund.RefundMethod.CASH, refundLine("PAIN", "1", "6.00"));
        refund.recalculationStatus = "RECOMPUTED";
        refund.withdrawnGenerosity = new BigDecimal("1.00");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Refund.findById(5L)).thenReturn(refund);
            mocked.when(() -> Ticket.findById(2L)).thenReturn(null);
            service.printRefund(5L);
            String out = captureReceipt(service);
            assertTrue(out.contains("AVANTAGE REPRIS"));
            assertFalse(out.contains("Avantages conserves"));
        }
    }

    /**
     * A surviving advantage whose label is wider than the paper is truncated to
     * the paper, as every other printed label is (LC-05-07-02).
     */
    @Test
    void printRefundTruncatesAWideSurvivingAdvantage() {
        TicketPrinterService service = newService();
        Refund refund = refund(2L, Refund.RefundMethod.CASH, refundLine("PAIN", "1", "6.00"));
        refund.recalculationStatus = "RECOMPUTED";
        refund.withdrawnGenerosity = new BigDecimal("1.00");
        refund.keptAdvantages = "AVANTAGE AU LIBELLE BEAUCOUP TROP LARGE POUR LE PAPIER";
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Refund.findById(5L)).thenReturn(refund);
            mocked.when(() -> Ticket.findById(2L)).thenReturn(null);
            service.printRefund(5L);
            String out = captureReceipt(service);
            assertTrue(out.contains("- AVANTAGE AU LIBELLE"));
            assertFalse(out.contains("POUR LE PAPIER"));
        }
    }

    /**
     * An engine that could not answer says so on the paper rather than printing
     * silence: a customer who loses an advantage must read why (LC-05-07-02,
     * degraded arm).
     */
    @Test
    void printRefundSaysTheEngineWasUnavailable() {
        TicketPrinterService service = newService();
        Refund refund = refund(2L, Refund.RefundMethod.CASH, refundLine("PAIN", "1", "6.00"));
        refund.recalculationStatus = "UNAVAILABLE";
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Refund.findById(5L)).thenReturn(refund);
            mocked.when(() -> Ticket.findById(2L)).thenReturn(null);
            service.printRefund(5L);
            String out = captureReceipt(service);
            assertTrue(out.contains("RECALCUL DES AVANTAGES"));
            assertTrue(out.contains("Moteur indisponible"));
            assertTrue(out.contains("recalcul non effectue"));
            assertFalse(out.contains("AVANTAGE REPRIS"));
        }
    }

    /**
     * A return on a card-bearing ticket states that the décagnottage was handed
     * to the loyalty programme — never an amount the register did not compute
     * (LC-05-07-02, third leg of the block guard, on its own).
     */
    @Test
    void printRefundStatesTheLoyaltyRecalculationWasSent() {
        TicketPrinterService service = newService();
        Refund refund = refund(2L, Refund.RefundMethod.CASH, refundLine("PAIN", "1", "6.00"));
        refund.loyaltyRecalculationSent = true;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Refund.findById(5L)).thenReturn(refund);
            mocked.when(() -> Ticket.findById(2L)).thenReturn(null);
            service.printRefund(5L);
            String out = captureReceipt(service);
            assertTrue(out.contains("RECALCUL DES AVANTAGES"));
            assertTrue(out.contains("Cagnotte et coupons"));
            assertTrue(out.contains("recalcul transmis au programme"));
            assertFalse(out.contains("AVANTAGE REPRIS"));
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
            silenceCatalog(mocked);
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
            silenceCatalog(mocked);
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
            silenceCatalog(mocked);
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
     * Covers the CREDIT arm of {@code printRefund} (LC-05-06-05): a customer
     * in-account credit refund prints the {@code CREDIT CLIENT} mode label.
     */
    @Test
    void printRefundPrintsCreditClientMethod() {
        TicketPrinterService service = newService();
        Refund refund = refund(2L, Refund.RefundMethod.CREDIT, refundLine("PAIN", "1", "6.00"));
        Ticket original = new Ticket();
        original.ticketNumber = "C04-00000009";
        original.store = null;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Refund.findById(5L)).thenReturn(refund);
            mocked.when(() -> Ticket.findById(2L)).thenReturn(original);
            service.printRefund(5L);
            String out = captureReceipt(service);
            assertTrue(out.contains("CREDIT CLIENT"));
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
            silenceCatalog(mocked);
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
            silenceCatalog(mocked);
            mocked.when(() -> Refund.findById(5L)).thenReturn(refund);
            mocked.when(() -> Ticket.findById(2L)).thenReturn(null);
            service.printRefund(5L);
            String out = captureReceipt(service);
            assertTrue(out.contains("CAGNOTTE"));
        }
    }

    /**
     * {@code printRefund} prints the chosen return reason (LC-05-05-03) when the
     * store administers it and a reason is present — all three arms of the gate
     * true: printReturnReason, reason non-null, reason non-blank.
     */
    @Test
    void printRefundPrintsReturnReasonWhenAdministered() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.printReturnReason()).thenReturn(true);
        Refund refund = refund(2L, Refund.RefundMethod.CASH, refundLine("THE", "1", "6.00"));
        refund.returnReason = "Article périmé";
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Refund.findById(5L)).thenReturn(refund);
            mocked.when(() -> Ticket.findById(2L)).thenReturn(null);
            service.printRefund(5L);
            String out = captureReceipt(service);
            assertTrue(out.contains("Motif  : Article périmé"));
        }
    }

    /**
     * {@code printRefund} omits the reason when the store does not administer its
     * printing (printReturnReason false arm), even though a reason is present.
     */
    @Test
    void printRefundOmitsReturnReasonWhenPrintingDisabled() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.printReturnReason()).thenReturn(false);
        Refund refund = refund(2L, Refund.RefundMethod.CASH, refundLine("THE", "1", "6.00"));
        refund.returnReason = "Article périmé";
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Refund.findById(5L)).thenReturn(refund);
            mocked.when(() -> Ticket.findById(2L)).thenReturn(null);
            service.printRefund(5L);
            String out = captureReceipt(service);
            assertFalse(out.contains("Motif"));
        }
    }

    /**
     * {@code printRefund} omits the reason line when printing is administered but
     * no reason was chosen (reason-null arm of the gate).
     */
    @Test
    void printRefundOmitsReturnReasonWhenNull() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.printReturnReason()).thenReturn(true);
        Refund refund = refund(2L, Refund.RefundMethod.CASH, refundLine("THE", "1", "6.00"));
        refund.returnReason = null;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Refund.findById(5L)).thenReturn(refund);
            mocked.when(() -> Ticket.findById(2L)).thenReturn(null);
            service.printRefund(5L);
            String out = captureReceipt(service);
            assertFalse(out.contains("Motif"));
        }
    }

    /**
     * {@code printRefund} omits the reason line when printing is administered but
     * the chosen reason is blank (reason-blank arm of the gate).
     */
    @Test
    void printRefundOmitsReturnReasonWhenBlank() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.printReturnReason()).thenReturn(true);
        Refund refund = refund(2L, Refund.RefundMethod.CASH, refundLine("THE", "1", "6.00"));
        refund.returnReason = "   ";
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Refund.findById(5L)).thenReturn(refund);
            mocked.when(() -> Ticket.findById(2L)).thenReturn(null);
            service.printRefund(5L);
            String out = captureReceipt(service);
            assertFalse(out.contains("Motif"));
        }
    }

    /**
     * {@code refundDocumentData} carries the return reason in the {@code document}
     * map (LC-05-05-03) so an administered layout can print it too.
     */
    @Test
    void refundDocumentDataCarriesReturnReason() {
        TicketPrinterService service = newService();
        Refund refund = refund(2L, Refund.RefundMethod.CASH, refundLine("THE", "1", "6.00"));
        refund.returnReason = "Erreur de caisse";
        @SuppressWarnings("unchecked")
        Map<String, Object> document =
                (Map<String, Object>) service.refundDocumentData(refund, null).get("document");
        assertEquals("Erreur de caisse", document.get("returnReason"));
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

    /**
     * LC-13-06-13: the training receipt faces the customer, so it comes out in
     * the language the customer chose, and the cashier's labels stay French.
     */
    @Test
    void printTrainingReceiptSpeaksTheCustomersLanguage() {
        TicketPrinterService service = newService();
        PosState state = new PosState();
        service.i18n = TestMessages.french(state);
        state.customerLanguage = "nl";
        service.printTrainingReceipt(state);
        String out = captureReceipt(service);
        assertTrue(out.contains("OPLEIDINGSMODUS"));
        assertTrue(out.contains("*** OPLEIDING - ZONDER WAARDE ***"));
        assertFalse(out.contains("MODE FORMATION"));
        assertEquals("MODE FORMATION", service.i18n.get("paper.modeFormation"));
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
            silenceCatalog(mocked);
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
            silenceCatalog(mocked);
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
            silenceCatalog(mocked);
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
            silenceCatalog(mocked);
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
            silenceCatalog(mocked);
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
            silenceCatalog(mocked);
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
            silenceCatalog(mocked);
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
            silenceCatalog(mocked);
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
     * Arms a tender carrying a slip: the gabarit, how many times it prints,
     * whether a duplicate goes with it and whether it outlives the receipt.
     *
     * @param copies how many originals
     * @param duplicate whether a duplicate goes with them
     * @param forced whether the slip prints when the receipt does not
     * @return the tender
     */
    private com.intermarche.pos.domain.payment.TenderDefinition slipTender(
            int copies, boolean duplicate, boolean forced) {
        com.intermarche.pos.domain.payment.TenderDefinition tender =
                new com.intermarche.pos.domain.payment.TenderDefinition();
        tender.code = "CHEQUE";
        tender.documentTemplateCode = "JUSTIF";
        tender.printCount = copies;
        tender.printDuplicate = duplicate;
        tender.printForced = forced;
        return tender;
    }

    /**
     * A gabarit that renders.
     *
     * @return the template
     */
    private DocumentTemplate slipTemplate() {
        DocumentTemplate template = new DocumentTemplate();
        template.code = "JUSTIF";
        template.documentType = DocumentTemplate.DocumentType.SALE_RECEIPT;
        template.active = true;
        template.width = 42;
        template.source = "JUSTIFICATIF";
        return template;
    }

    /**
     * BO-03-02-32 and -33: the slip of a tender is printed as many times as
     * the tender says, each copy cut, and nothing else of the tender is
     * decided in Java.
     */
    @Test
    void aTenderSlipIsPrintedAsManyTimesAsAdministered() {
        TicketPrinterService service = newService();
        service.documentTemplateService = mock(DocumentTemplateService.class);
        when(service.documentTemplateService.renderTemplate(any(), any()))
                .thenReturn("JUSTIFICATIF");
        Ticket ticket = ticket(0, null);
        ticket.payments.add(new com.intermarche.pos.domain.payment.ChequePayment(new BigDecimal("10.00")));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> PanacheEntityBase.find("code", "CHEQUE"))
                    .thenAnswer(ignored -> answer(slipTender(2, false, false)));
            mocked.when(() -> PanacheEntityBase.find("code", "JUSTIF"))
                    .thenAnswer(ignored -> answer(slipTemplate()));
            assertEquals(2, service.printTenderSlips(ticket, true));
        }
        verify(service.hardwareService, times(2)).printReceipt("JUSTIFICATIF");
    }

    /**
     * BO-03-02-26: a duplicate goes BESIDE the originals and is rendered as a
     * reprint, so it wears the gabarit's reprint name rather than being a
     * third identical copy.
     */
    @Test
    void aTenderSlipMayCarryADuplicate() {
        TicketPrinterService service = newService();
        service.documentTemplateService = mock(DocumentTemplateService.class);
        when(service.documentTemplateService.renderTemplate(any(), any()))
                .thenReturn("JUSTIFICATIF");
        Ticket ticket = ticket(0, null);
        ticket.payments.add(new com.intermarche.pos.domain.payment.ChequePayment(new BigDecimal("10.00")));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> PanacheEntityBase.find("code", "CHEQUE"))
                    .thenAnswer(ignored -> answer(slipTender(1, true, false)));
            mocked.when(() -> PanacheEntityBase.find("code", "JUSTIF"))
                    .thenAnswer(ignored -> answer(slipTemplate()));
            assertEquals(2, service.printTenderSlips(ticket, true));
        }
    }

    /**
     * BO-03-02-31: a receipt that was NOT printed takes the slip with it,
     * unless the shop administered the slip as forced — both arms, because
     * that is the whole question the line asks.
     */
    @Test
    void anUnprintedReceiptTakesTheSlipUnlessItIsForced() {
        TicketPrinterService quiet = newService();
        quiet.documentTemplateService = mock(DocumentTemplateService.class);
        when(quiet.documentTemplateService.renderTemplate(any(), any())).thenReturn("JUSTIFICATIF");
        Ticket ticket = ticket(0, null);
        ticket.payments.add(new com.intermarche.pos.domain.payment.ChequePayment(new BigDecimal("10.00")));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> PanacheEntityBase.find("code", "CHEQUE"))
                    .thenAnswer(ignored -> answer(slipTender(1, false, false)));
            mocked.when(() -> PanacheEntityBase.find("code", "JUSTIF"))
                    .thenAnswer(ignored -> answer(slipTemplate()));
            assertEquals(0, quiet.printTenderSlips(ticket, false));
        }
        TicketPrinterService forced = newService();
        forced.documentTemplateService = mock(DocumentTemplateService.class);
        when(forced.documentTemplateService.renderTemplate(any(), any())).thenReturn("JUSTIFICATIF");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> PanacheEntityBase.find("code", "CHEQUE"))
                    .thenAnswer(ignored -> answer(slipTender(1, false, true)));
            mocked.when(() -> PanacheEntityBase.find("code", "JUSTIF"))
                    .thenAnswer(ignored -> answer(slipTemplate()));
            assertEquals(1, forced.printTenderSlips(ticket, false));
        }
    }

    /**
     * The empty legs: no ticket, no payments, a tender nobody declared, a
     * tender naming no gabarit, and a tender naming one that has not arrived
     * — each printing nothing rather than failing the settlement.
     */
    @Test
    void aTenderWithoutASlipPrintsNothing() {
        TicketPrinterService service = newService();
        assertEquals(0, service.printTenderSlips(null, true));
        Ticket empty = ticket(0, null);
        assertEquals(0, service.printTenderSlips(empty, true));
        Ticket ticket = ticket(0, null);
        ticket.payments.add(new com.intermarche.pos.domain.payment.ChequePayment(new BigDecimal("10.00")));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            assertEquals(0, service.printTenderSlips(ticket, true));
        }
        com.intermarche.pos.domain.payment.TenderDefinition silent = slipTender(1, false, false);
        silent.documentTemplateCode = null;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> PanacheEntityBase.find("code", "CHEQUE"))
                    .thenAnswer(ignored -> answer(silent));
            assertEquals(0, service.printTenderSlips(ticket, true));
        }
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> PanacheEntityBase.find("code", "CHEQUE"))
                    .thenAnswer(ignored -> answer(slipTender(1, false, false)));
            assertEquals(0, service.printTenderSlips(ticket, true));
        }
    }

    /**
     * BO-03-04-13: a reprint wears the ADMINISTERED name when the shop gave it
     * one, and the register still numbers it — renaming a document does not
     * renumber its reprints.
     */
    @Test
    void aReprintWearsTheAdministeredName() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(1, null);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        DocumentTemplate renamed = new DocumentTemplate();
        renamed.code = "REIMP";
        renamed.documentType = DocumentTemplate.DocumentType.SALE_RECEIPT;
        renamed.active = true;
        renamed.source = "";
        renamed.duplicateTitle = "*** DUPLICADO N%d ***";
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            silenceCatalog(mocked);
            mocked.when(() -> DocumentTemplate.list(
                            "active = true and documentType = ?1 order by priority, code",
                            DocumentTemplate.DocumentType.SALE_RECEIPT))
                    .thenReturn(java.util.List.of(renamed));
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.contains("DUPLICADO N1"));
            assertFalse(out.contains("DUPLICATA"));
        }
    }

    /**
     * BO-03-05-01 and -03: an administered BANNER replaces the single centred
     * message at the head and at the foot, line for line and alignment for
     * alignment, while the rest of the built-in receipt is untouched.
     */
    @Test
    void administeredBannersReplaceTheSingleHeaderAndFooterMessages() {
        TicketPrinterService service = newService();
        service.documentTemplateService = mock(DocumentTemplateService.class);
        when(service.posSettingsService.ticketHeaderMessage()).thenReturn("PROMO DU JOUR");
        when(service.posSettingsService.ticketFooterMessage()).thenReturn("SUIVEZ-NOUS EN LIGNE");
        when(service.documentTemplateService.render(
                eq(DocumentTemplate.DocumentType.TICKET_HEADER), any()))
                .thenReturn("  BIENVENUE\nCHEZ NOUS\n");
        when(service.documentTemplateService.render(
                eq(DocumentTemplate.DocumentType.TICKET_FOOTER), any()))
                .thenReturn("A TRES BIENTOT\n       SIRET 123\n");
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            String out = captureReceipt(service);
            // The banner's own lines, kept exactly as the layout laid them out.
            assertTrue(out.contains("  BIENVENUE\nCHEZ NOUS\n"));
            assertTrue(out.contains("A TRES BIENTOT\n       SIRET 123\n"));
            // And the single centred messages they replace are NOT printed.
            assertFalse(out.contains("PROMO DU JOUR"));
            assertFalse(out.contains("SUIVEZ-NOUS EN LIGNE"));
            // The receipt is otherwise the built-in one: the courtesy line still
            // precedes the foot, and the identification barcode still ends it.
            assertTrue(out.indexOf("A BIENTOT") < out.indexOf("A TRES BIENTOT"));
            assertTrue(out.trim().endsWith("[[BARCODE C04-00000001]]"));
        }
    }

    /**
     * The other arm, one side at a time: a shop that administers a head and no
     * foot gets its head and keeps the single centred message at the foot. The
     * two banners are independent, which is what lets a shop restate one
     * without the other.
     */
    @Test
    void anAdministeredHeadLeavesTheFooterMessageStanding() {
        TicketPrinterService service = newService();
        service.documentTemplateService = mock(DocumentTemplateService.class);
        when(service.posSettingsService.ticketHeaderMessage()).thenReturn("PROMO DU JOUR");
        when(service.posSettingsService.ticketFooterMessage()).thenReturn("SUIVEZ-NOUS EN LIGNE");
        when(service.documentTemplateService.render(
                eq(DocumentTemplate.DocumentType.TICKET_HEADER), any()))
                .thenReturn("BIENVENUE\n");
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.contains("BIENVENUE"));
            assertFalse(out.contains("PROMO DU JOUR"));
            assertTrue(out.contains("SUIVEZ-NOUS EN LIGNE"));
        }
    }

    /**
     * A banner whose source ends on blank lines — and a text block almost
     * always does — does NOT feed blank paper: the trailing empties are
     * dropped, the inner ones kept, because a blank line inside a banner is a
     * spacing the shop asked for.
     */
    @Test
    void aBannerDropsItsTrailingBlankLinesOnly() {
        TicketPrinterService service = newService();
        service.documentTemplateService = mock(DocumentTemplateService.class);
        when(service.documentTemplateService.render(
                eq(DocumentTemplate.DocumentType.TICKET_HEADER), any()))
                .thenReturn("A\n\nB\n\n   \n");
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.contains("A\n\nB\n" + "-".repeat(42)));
        }
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
        assertTrue(out.startsWith("[[IMAGE ") || out.contains("INTERMARCHE"),
                "ni logo ni nom d'enseigne en tete");
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
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            TicketPrinterService service = newService();
            Refund refund = new Refund();
            refund.id = 12L;
            refund.creationDate = NOW;
            refund.totalAmount = new BigDecimal("9.99");
            service.printRefundVoucher(refund, "297000000000077", NOW.plusDays(365));
            String out = captureReceipt(service);
            assertTrue(out.contains("AVOIR"));
            assertTrue(out.contains("9,99 E"));
            assertTrue(out.contains("N° 297000000000077"));
            assertTrue(out.contains("(scannable en caisse - solde au registre)"));
            // LC-05-06-07: a Code-128 of the registry number and a validity end
            // date are printed (validUntil non-null arm).
            assertTrue(out.contains("[[BARCODE 297000000000077]]"));
            assertTrue(out.contains("Valable jusqu'au :"));
        }
    }

    /**
     * {@code printRefundVoucher} prints amounts ABOVE the historical 99,99 €
     * encoded cap just the same: the registry number carries no amount, so
     * nothing limits the printable credit note.
     */
    @Test
    void printRefundVoucherHasNoAmountCap() {
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            TicketPrinterService service = newService();
            Refund refund = new Refund();
            refund.id = 12L;
            refund.creationDate = NOW;
            refund.totalAmount = new BigDecimal("150.00");
            service.printRefundVoucher(refund, "297000000000078", null);
            String out = captureReceipt(service);
            assertTrue(out.contains("AVOIR"));
            assertTrue(out.contains("150,00 E"));
            assertTrue(out.contains("N° 297000000000078"));
            // A null validity prints no validity line (validUntil null arm), but the
            // Code-128 is always present.
            assertTrue(out.contains("[[BARCODE 297000000000078]]"));
            assertFalse(out.contains("Valable jusqu'au"));
        }
    }

    // --------------------------------------------------
    // printGiftCardVoucher
    // --------------------------------------------------

    /**
     * {@code printGiftCardVoucher} prints the freshly issued card: CARTE
     * CADEAU title, the loaded amount, the registry number, its barcode
     * (BO-03-06-30 to -38) and the registry notice — the customer's proof
     * after the fiscal moment.
     */
    @Test
    void printGiftCardVoucherPrintsNumberAndAmount() {
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            TicketPrinterService service = newService();
            service.printGiftCardVoucher("296000000000042", new BigDecimal("50.00"));
            String out = captureReceipt(service);
            assertTrue(out.contains("CARTE CADEAU"));
            assertTrue(out.contains("50,00 E"));
            assertTrue(out.contains("N° 296000000000042"));
            assertTrue(out.contains("[[BARCODE 296000000000042]]"));
            assertTrue(out.contains("(scannable en caisse - solde au registre)"));
        }
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
        assertTrue(out.contains("ESPECES"));
        assertFalse(out.contains("CARTE BANCAIRE"));
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

    /**
     * A session report whose cashier DECLARED the drawer states the declared cash
     * and its gap to the theoretical, right under it: the comparison is the whole
     * reason for asking, and the declaration used to be written and never read.
     */
    @Test
    void printSessionReportStatesTheDeclarationAndItsGap() {
        TicketPrinterService service = newService();
        CashSessionService.SessionReport report = new CashSessionService.SessionReport();
        report.session = session(null);
        report.closing = false;
        report.totalsByMethod = new LinkedHashMap<>();
        report.theoreticalCash = new BigDecimal("300.00");
        report.declaredCash = new BigDecimal("310.00");
        report.declaredCashVariance = new BigDecimal("10.00");
        report.totalRefunds = BigDecimal.ZERO;
        service.printSessionReport(report);
        String out = captureReceipt(service);
        assertTrue(out.contains("Especes declarees"));
        assertTrue(out.contains("310,00 E"));
        assertTrue(out.contains("Ecart declaration"));
        assertTrue(out.contains("10,00 E"));
    }

    /**
     * A session with no declaration prints neither line (the false arm): a nil gap
     * would state a count nobody made.
     */
    @Test
    void printSessionReportStaysSilentWithoutADeclaration() {
        TicketPrinterService service = newService();
        CashSessionService.SessionReport report = new CashSessionService.SessionReport();
        report.session = session(null);
        report.closing = false;
        report.totalsByMethod = new LinkedHashMap<>();
        report.theoreticalCash = new BigDecimal("300.00");
        report.totalRefunds = BigDecimal.ZERO;
        service.printSessionReport(report);
        String out = captureReceipt(service);
        assertFalse(out.contains("Especes declarees"));
        assertFalse(out.contains("Ecart declaration"));
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
            stubNoRegime(panache);
            String rendered = service.renderTicket(ticket, false, 0);
            assertEquals("MAGASIN LYON|C04-00000001|C04|Jean Dupont"
                    + "|LAIT 2,00x3,00=6,00|TTC 12,00|HT 10,00|CASH 12,00\n[[BARCODE C04-00000001]]\n", rendered);
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
            assertEquals("TOTAL 12,00\n[[BARCODE C04-00000001]]\n", service.renderTicket(ticket, false, 0));
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
        Map<String, Object> data = saleData(service, ticket, true, 2);

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
                (List<Map<String, Object>>) saleData(service, ticket, false, 0).get("lines");
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
        assertEquals("ESPECES", tenders.get(0).get("label"));
        assertEquals("CASH", tenders.get(0).get("key"));

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
     * The movement justificatif (recette) states the type, the reason, the tender,
     * the amount and a signature line, with the register and the operator in the
     * header: the paper an expense taken from the drawer leaves behind.
     */
    @Test
    void printMovementBuildsTheJustificatif() {
        TicketPrinterService service = newService();
        service.printMovementTicket("Dépense", new BigDecimal("12.50"), "Achat de timbres",
                "CHEQUE", "MARIE", "C04");
        String out = captureReceipt(service);
        assertTrue(out.contains("MOUVEMENT DE CAISSE"));
        assertTrue(out.contains("Caisse : C04"));
        assertTrue(out.contains("Operateur : MARIE"));
        assertTrue(out.contains("Dépense"));
        assertTrue(out.contains("Achat de timbres"));
        assertTrue(out.contains("CHEQUE"));
        assertTrue(out.contains("12,50 E"));
        assertTrue(out.contains("Signature:"));
    }

    /**
     * The same justificatif with NOTHING optional — no register, no operator, no
     * reason, no tender (the false arm of all four guards) — keeps the amount and
     * the signature line and prints no empty row.
     */
    @Test
    void printMovementOmitsTheAbsentFields() {
        TicketPrinterService service = newService();
        service.printMovementTicket("Déclaration (comptage)", new BigDecimal("200.00"),
                null, "   ", "  ", null);
        String out = captureReceipt(service);
        assertTrue(out.contains("Déclaration (comptage)"));
        assertTrue(out.contains("200,00 E"));
        assertFalse(out.contains("Caisse :"));
        assertFalse(out.contains("Operateur :"));
        assertFalse(out.contains("MOTIF"));
        assertFalse(out.contains("MOYEN"));
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
     * The change-order slip (LC-12-05-08) builds the built-in ticket with the
     * register number, the operator (number and name), the table header and one
     * row per denomination; a long label is truncated to the column width and a
     * short row is padded with blanks (the {@code row.length} guard arms).
     */
    @Test
    void printChangeOrderBuildsTheBuiltInSlip() {
        TicketPrinterService service = newService();
        java.util.List<String[]> rows = java.util.List.of(
                new String[] {"Billet 50 €", "100,00", "2"},
                new String[] {"Pièce 0,20 €", "", ""},
                new String[] {"UneDenominationBeaucoupTropLongue", "5,00", "1"},
                new String[] {});
        service.printChangeOrder("C04", "17", "Marie", rows, new BigDecimal("105.00"));
        String out = captureReceipt(service);
        assertTrue(out.contains("COMMANDE DE MONNAIE"));
        assertTrue(out.contains("Caisse : C04"));
        assertTrue(out.contains("Operateur : 17 Marie"));
        assertTrue(out.contains("DENOMINATION"));
        assertTrue(out.contains("Billet 50 €"));
        assertTrue(out.contains("100,00"));
        assertTrue(out.contains("Pièce 0,20 €"));
        assertTrue(out.contains("UneDenominationBea"));
        // What the cashier hands over to be given the rolls, and a line to sign for it.
        assertTrue(out.contains("TOTAL A REMETTRE"));
        assertTrue(out.contains("105,00 E"));
        assertTrue(out.contains("Signature:"));
    }

    /**
     * The change-order slip tolerates a null register, a null operator, a null row
     * list and a null total ({@code terminalId == null} skip arm, blank-operator skip
     * arm, {@code rows == null} skip arm, {@code total == null} skip arm): the header
     * still prints, and an EMPTY slip states no amount — a printed "TOTAL 0,00" on a
     * sheet meant to be filled by hand is a figure the shop would have to cross out.
     */
    @Test
    void printChangeOrderToleratesNullOperatorAndRows() {
        TicketPrinterService service = newService();
        service.printChangeOrder(null, null, null, null, null);
        String out = captureReceipt(service);
        assertTrue(out.contains("COMMANDE DE MONNAIE"));
        assertFalse(out.contains("Caisse :"));
        assertFalse(out.contains("Operateur :"));
        assertFalse(out.contains("TOTAL A REMETTRE"));
        assertFalse(out.contains("Signature:"));
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
     * not a refund, and carries the registry number, its barcode (BO-03-06-30
     * to -38) and the amount.
     */
    @Test
    void printChangeVoucherSaysItIsChange() {
        TicketPrinterService service = newService();
        service.printChangeVoucher("297000000000042", new BigDecimal("30.00"));
        String out = captureReceipt(service);
        assertTrue(out.contains("AVOIR - RENDU DE MONNAIE"));
        assertTrue(out.contains("30,00 E"));
        assertTrue(out.contains("N° 297000000000042"));
        assertTrue(out.contains("[[BARCODE 297000000000042]]"));
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
        assertTrue(out.contains("[[BARCODE C04-00000001]]"));
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

    /** LC-07-10-05: the authorization slip states the card, amount and signature area. */
    @Test
    void loyaltySignatureSlipContainsAuthorizationDetails() {
        TicketPrinterService service = newService();
        service.printFidelitySignatureSlip(new BigDecimal("4.50"), "CARD-42");
        String out = captureRaw(service);
        assertTrue(out.contains("CARD-42"));
        assertTrue(out.contains("4,50"));
        assertTrue(out.contains("Signature du client"));
        assertTrue(out.contains("_".repeat(42)));
    }

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
        assertTrue(out.contains("[[BARCODE 12341234]]"));
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
        assertFalse(out.contains("[[BARCODE"));
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
            silenceCatalog(mocked);
            logo.when(() -> ReceiptLogo.directive(any(), anyInt())).thenReturn("[[LOGO abcd]]");
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
            silenceCatalog(mocked);
            logo.when(() -> ReceiptLogo.directive(any(), anyInt())).thenReturn("");
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
            silenceCatalog(mocked);
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
            silenceCatalog(mocked);
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
            silenceCatalog(mocked);
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
            silenceCatalog(mocked);
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
        assertEquals("TNA-FRAME\n", out);
        assertFalse(out.contains("*** ABANDON DEBIT ***"));
        assertFalse(out.contains("MONTANT"));
    }

    /**
     * {@code printCardTnaReceipt} with a frame that does NOT end on a newline appends
     * no character: LC-07-04-10 requires an unchanged vendor frame.
     */
    @Test
    void printCardTnaReceiptPreservesFrameWithoutNewline() {
        TicketPrinterService service = newService();
        service.printCardTnaReceipt(null, "TNA-FRAME");
        assertEquals("TNA-FRAME", captureReceipt(service));
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

    /**
     * BO-03-03-29 on the layout that actually prints: a holder's receipt SHOWS
     * the administered message, token replaced.
     * <p>
     * The document data already carried it, but the built-in layout is what
     * comes out of the printer until a template is administered — and nothing
     * is administered out of the box. A message computed and never printed is
     * an administered parameter nobody can observe.
     */
    @Test
    void theReceiptPrintsTheHolderMessage() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.fidelityOfflineMessage())
                .thenReturn("CARTE {carte} - FIDELITE INDISPONIBLE");
        Ticket ticket = ticket(0, null);
        freeze(ticket, null);
        ticket.fidelityUnavailable = true;
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            String printed = service.renderTicket(ticket, false, 0);
            assertTrue(printed.contains("CARTE 2990000000019 - FIDELITE"), printed);
        }
    }

    /**
     * BO-03-03-30 on the same layout: a sale WITHOUT a card prints the other
     * message.
     */
    @Test
    void theReceiptPrintsTheNonHolderMessage() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.fidelityOfflineMessageNoCard())
                .thenReturn("PRESENTEZ VOTRE CARTE LA PROCHAINE FOIS");
        Ticket ticket = ticket(0, null);
        ticket.fidelityCard = null;
        ticket.fidelityLines = new ArrayList<>();
        ticket.fidelityUnavailable = true;
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            String printed = service.renderTicket(ticket, false, 0);
            assertTrue(printed.contains("PRESENTEZ VOTRE CARTE"), printed);
        }
    }

    /**
     * A sale the loyalty service DID answer prints no message at all — the
     * false arm of the unavailability guard, which is the ordinary sale.
     */
    @Test
    void anAnsweredSalePrintsNoMessage() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.fidelityOfflineMessage())
                .thenReturn("CARTE {carte} - FIDELITE INDISPONIBLE");
        Ticket ticket = ticket(0, null);
        freeze(ticket, null);
        ticket.fidelityUnavailable = false;
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            assertFalse(service.renderTicket(ticket, false, 0).contains("FIDELITE INDISPONIBLE"));
        }
    }

    /**
     * LC-03-05-03: the balances zone states the NEW balance the loyalty
     * service returns at every attachment — for an earn-only sale that is the
     * projection the ledger will credit, printed with the same word a burn's
     * confirmed balance uses. The OLD balance (the one read at attachment)
     * was already printed; this closes the pair the line asks for.
     */
    @Test
    void theBalancesZonePrintsTheNewBalanceOfAnEarnOnlySale() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.fidelityPrintBalances()).thenReturn(true);
        Ticket ticket = ticket(0, null);
        freeze(ticket, null);
        ticket.fidelityAvailableBalance = new BigDecimal("42.30");
        ticket.fidelityProjectedAfterEarn = new BigDecimal("43.33");
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            String printed = service.renderTicket(ticket, false, 0);
            assertTrue(printed.contains("SOLDE DISPONIBLE"), printed);
            assertTrue(printed.contains("42,30"), printed);
            assertTrue(printed.contains("NOUVEAU SOLDE"), printed);
            assertTrue(printed.contains("43,33"), printed);
        }
    }

    /**
     * LC-03-05-03, the precedence arm: a sale that SETTLED on the card carries
     * the ledger's confirmed balance, which outranks the projection — the
     * zone states one new balance, never both.
     */
    @Test
    void theBalancesZonePrefersTheConfirmedBalanceOfABurn() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.fidelityPrintBalances()).thenReturn(true);
        Ticket ticket = ticket(0, null);
        freeze(ticket, null);
        ticket.fidelityAvailableBalance = new BigDecimal("42.30");
        ticket.fidelityBalanceAfter = new BigDecimal("39.30");
        ticket.fidelityProjectedAfterEarn = new BigDecimal("43.33");
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            String printed = service.renderTicket(ticket, false, 0);
            assertTrue(printed.contains("NOUVEAU SOLDE"), printed);
            assertTrue(printed.contains("39,30"), printed);
            assertFalse(printed.contains("43,33"), printed);
        }
    }

    /**
     * A shop that administers NO message prints nothing, and the receipt is the
     * very one it printed before this lot — the blank arm, which is the
     * historical behaviour and must stay reachable.
     */
    @Test
    void ashopWithoutAMessagePrintsTheHistoricalReceipt() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.fidelityOfflineMessage()).thenReturn("");
        Ticket ticket = ticket(0, null);
        freeze(ticket, null);
        ticket.fidelityUnavailable = true;
        Ticket answered = ticket(0, null);
        freeze(answered, null);
        answered.fidelityUnavailable = false;
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            assertEquals(service.renderTicket(answered, false, 0),
                    service.renderTicket(ticket, false, 0));
        }
    }

    /**
     * A message LONGER than the paper is wrapped on spaces, not truncated: every
     * word survives and no line exceeds the width.
     */
    @Test
    void alongMessageIsWrappedAndNotTruncated() {
        TicketPrinterService service = newService();
        String message = "FIDELITE INDISPONIBLE VOS AVANTAGES SERONT CREDITES "
                + "LORS DE VOTRE PROCHAIN PASSAGE EN MAGASIN MERCI DE VOTRE COMPREHENSION";
        when(service.posSettingsService.fidelityOfflineMessage()).thenReturn(message);
        Ticket ticket = ticket(0, null);
        freeze(ticket, null);
        ticket.fidelityUnavailable = true;
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            String printed = service.renderTicket(ticket, false, 0);
            for (String word : message.split(" ")) {
                assertTrue(printed.contains(word), word + " absent");
            }
            assertEveryTextLineFitsThePaper(printed);
        }
    }

    /**
     * A single WORD longer than the paper is cut rather than dropped: the inner
     * loop of the wrap, which only a message made of one long token reaches.
     */
    @Test
    void awordLongerThanThePaperIsCut() {
        TicketPrinterService service = newService();
        String word = "A".repeat(100);
        when(service.posSettingsService.fidelityOfflineMessage())
                .thenReturn("DEBUT " + word + " FIN");
        Ticket ticket = ticket(0, null);
        freeze(ticket, null);
        ticket.fidelityUnavailable = true;
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            String printed = service.renderTicket(ticket, false, 0);
            assertTrue(printed.contains("A".repeat(42)), printed);
            assertTrue(printed.contains("DEBUT"), printed);
            assertTrue(printed.contains("FIN"), printed);
            assertEveryTextLineFitsThePaper(printed);
        }
    }

    /**
     * Asserts that every TEXT line of a printed document fits the paper.
     *
     * <p>The logo travels as a print DIRECTIVE and not as text: one long token
     * the printer turns into dots. Measuring it against the paper width would
     * be measuring the wrong thing, so it is skipped - and the number skipped
     * is asserted, so the exemption stays ONE banner and does not quietly
     * become a licence to print lines nobody checks.
     *
     * @param printed the document as printed
     */
    private void assertEveryTextLineFitsThePaper(String printed) {
        int directives = 0;
        for (String line : printed.split("\n")) {
            if (line.startsWith("[[IMAGE ")) {
                directives++;
                continue;
            }
            assertTrue(line.length() <= 42, "ligne trop large: '" + line + "'");
        }
        assertTrue(directives <= 1, "plus d'une directive image: " + directives);
    }

    // --------------------------------------------------
    // Coverage campaign — residual branches
    // --------------------------------------------------

    /**
     * Covers the unit-of-measure arms of the sale line in {@code printTicket}: a
     * line whose {@code unitName} is blank prints the plain "qty x unit price"
     * form (the empty-unit arm), while a line whose {@code unitName} is a real
     * unit prints the "qty unit x unit price" form (the non-null, non-blank arm
     * that trims the unit). The null arm is already exercised by the other
     * cases, so the two remaining legs of the ternary and the false leg of the
     * unit-not-empty append are reached here.
     */
    @Test
    void printTicketPrintsBothUnitOfMeasureArms() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        TicketLine blankUnit = line("U1", "PAIN", "1", "2.00", "2.00");
        blankUnit.unitName = "   ";
        ticket.lines.add(blankUnit);
        TicketLine namedUnit = line("U2", "TISSU", "2.360", "4.99", "11.78");
        namedUnit.unitName = "m";
        ticket.lines.add(namedUnit);
        ticket.payments.add(new CashPayment(new BigDecimal("13.78"), new BigDecimal("13.78")));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.contains("m x"));
            assertTrue(out.contains("TISSU"));
            assertTrue(out.contains("PAIN"));
        }
    }

    /**
     * Covers the zero-total arm of {@code refundDocumentData}: a refund line
     * whose price is null and another whose quantity is null both fall to
     * {@code BigDecimal.ZERO}, exercising the two true legs of the compound
     * {@code price == null || quantity == null} guard that the priced lines
     * elsewhere leave uncovered.
     */
    @Test
    void refundDocumentDataUsesZeroWhenPriceOrQuantityMissing() {
        TicketPrinterService service = newService();
        Refund refund = mock(Refund.class);
        refund.lines = new ArrayList<>();
        RefundLine noPrice = refundLine("SANS PRIX", "2", "2.00");
        noPrice.price = null;
        refund.lines.add(noPrice);
        RefundLine noQuantity = refundLine("SANS QTE", "1", "3.00");
        noQuantity.quantity = null;
        refund.lines.add(noQuantity);
        Map<String, Object> data = service.refundDocumentData(refund, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) data.get("lines");
        assertEquals(2, lines.size());
        assertEquals("0,00", lines.get(0).get("total"));
        assertEquals("", lines.get(0).get("unitPrice"));
        assertEquals("0,00", lines.get(1).get("total"));
        assertEquals("", lines.get(1).get("quantity"));
    }

    /**
     * Covers the named-operator arm of {@code sessionDocumentData}: a report
     * whose session carries a closing cashier resolves the operator to that
     * cashier's full name, reaching the false-false leg of the
     * {@code session == null || session.closingCashier == null} guard that the
     * bare and cashier-less reports leave uncovered.
     */
    @Test
    void sessionDocumentDataNamesTheClosingCashier() {
        TicketPrinterService service = newService();
        CashSessionService.SessionReport report = new CashSessionService.SessionReport();
        CashSession sess = session(NOW);
        sess.closingCashier = cashier();
        report.session = sess;
        Map<String, Object> data = service.sessionDocumentData(report);
        assertEquals("Jean Dupont", data.get("operator"));
    }

    /**
     * Covers the register-line arm of {@code printLoyaltyReceipt}: a settlement
     * whose ticket carries a terminal id prints the "Caisse :" line, the true
     * arm of the {@code ticket.terminalId != null} guard that the seed ticket
     * (no terminal id) leaves uncovered on the manual layout path.
     */
    @Test
    void printLoyaltyReceiptPrintsTheRegisterLine() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.terminalId = "C04";
        freeze(ticket, new BigDecimal("1.03"));
        ticket.payments.add(new FidelityPayment(new BigDecimal("3.00")));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            assertEquals(1, service.printLoyaltyReceipt(1L));
        }
        String out = captureReceipt(service);
        assertTrue(out.contains("Caisse : C04"));
    }

    /**
     * Covers the missing-ticket guard of {@code printAbandonTicket}: the finder
     * resolves to null, so the method returns false and prints nothing.
     */
    @Test
    void printAbandonTicketReturnsFalseWhenTicketMissing() {
        TicketPrinterService service = newService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(null);
            assertFalse(service.printAbandonTicket(1L, "Client parti", true, "Jean"));
            verifyNoInteractions(service.hardwareService);
        }
    }

    /**
     * Covers the fully-detailed arm of {@code printAbandonTicket}: a draft with
     * a store, a terminal id, a named operator and a reason, printed with its
     * detail. The basket carries a cancelled line (skipped by the continue), a
     * line whose total is null (the ZERO arm of the amount accumulation) and a
     * line whose label overflows 24 characters (the truncation arm). Every true
     * leg of the header guards, the detail gate and the label truncation is
     * reached, and the abandoned amount sums only the live lines.
     */
    @Test
    void printAbandonTicketPrintsFullDetail() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.terminalId = "C04";
        TicketLine live = line("U1", "PAIN", "1", "2.00", "2.00");
        ticket.lines.add(live);
        TicketLine cancelled = line("U2", "PRODUIT ANNULE", "1", "5.00", "5.00");
        cancelled.cancelled = true;
        ticket.lines.add(cancelled);
        TicketLine longLabel = line("U3", "ARTICLE AU LIBELLE TRES TRES LONG", "1", "3.00", "3.00");
        ticket.lines.add(longLabel);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            assertTrue(service.printAbandonTicket(1L, "Client parti", true, "Jean Caissier"));
        }
        String out = captureReceipt(service);
        assertTrue(out.contains("TICKET ABANDONNE"));
        assertTrue(out.contains("MAGASIN LYON"));
        assertTrue(out.contains("Caisse : C04"));
        assertTrue(out.contains("Operateur : Jean Caissier"));
        assertTrue(out.contains("Motif  : Client parti"));
        assertTrue(out.contains("MONTANT ABANDONNE"));
        assertTrue(out.contains("AUCUN ENCAISSEMENT"));
        assertTrue(out.contains("ARTICLE AU LIBELLE TRES "));
        assertFalse(out.contains("PRODUIT ANNULE"));
    }

    /**
     * Covers the bare arm of {@code printAbandonTicket}: a draft with no store,
     * no terminal id, a null operator and a null reason, printed WITHOUT its
     * detail. The store, register, operator and reason guards all take their
     * false leg, the detail gate is false (no per-line block and no extra
     * separator), the first live line's non-null total feeds the amount and a
     * second live line whose total is null takes the ZERO arm of the
     * accumulation (reachable only without detail, since the detail formatting
     * would throw on a null total — see the reported gap at line 1683).
     */
    @Test
    void printAbandonTicketPrintsBareWithoutDetail() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.store = null;
        ticket.terminalId = null;
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        TicketLine noTotal = line("U2", "LAIT", "1", "1.00", "1.00");
        noTotal.totalPrice = null;
        ticket.lines.add(noTotal);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            assertTrue(service.printAbandonTicket(1L, null, false, null));
        }
        String out = captureReceipt(service);
        assertTrue(out.contains("TICKET ABANDONNE"));
        assertTrue(out.contains("MONTANT ABANDONNE"));
        assertFalse(out.contains("MAGASIN LYON"));
        assertFalse(out.contains("Caisse :"));
        assertFalse(out.contains("Operateur :"));
        assertFalse(out.contains("Motif  :"));
        assertFalse(out.contains("PAIN"));
    }

    /**
     * Covers the blank-operator and blank-reason arms of
     * {@code printAbandonTicket}: a non-null but blank operator and a non-null
     * but blank reason both take the {@code isBlank} true leg, so neither the
     * "Operateur" nor the "Motif" line is printed even though the strings are
     * not null.
     */
    @Test
    void printAbandonTicketOmitsBlankOperatorAndReason() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            assertTrue(service.printAbandonTicket(1L, "   ", false, "  "));
        }
        String out = captureReceipt(service);
        assertFalse(out.contains("Operateur :"));
        assertFalse(out.contains("Motif  :"));
    }

    /**
     * Covers the missing-ticket guard of {@code printCollectionVoucher}: the
     * finder resolves to null, so the method returns zero and prints nothing.
     */
    @Test
    void printCollectionVoucherReturnsZeroWhenTicketMissing() {
        TicketPrinterService service = newService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(null);
            assertEquals(0, service.printCollectionVoucher(1L));
            verifyNoInteractions(service.hardwareService);
        }
    }

    /**
     * Covers the no-marked-line arm of {@code printCollectionVoucher}: a ticket
     * whose only lines are either cancelled-but-to-collect or live-but-not-to
     * collect marks nothing, so the method returns zero and prints nothing. The
     * two false legs of the {@code !cancelled && toCollect} filter and the
     * empty-list guard are reached here.
     */
    @Test
    void printCollectionVoucherReturnsZeroWhenNothingMarked() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        TicketLine cancelledToCollect = line("U1", "PAIN", "1", "2.00", "2.00");
        cancelledToCollect.cancelled = true;
        cancelledToCollect.toCollect = true;
        ticket.lines.add(cancelledToCollect);
        TicketLine liveNotToCollect = line("U2", "LAIT", "1", "1.00", "1.00");
        ticket.lines.add(liveNotToCollect);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            assertEquals(0, service.printCollectionVoucher(1L));
            verifyNoInteractions(service.hardwareService);
        }
    }

    /**
     * Covers the full print arm of {@code printCollectionVoucher}: a ticket with
     * a store, a terminal id and a creation date, whose marked lines are one
     * with an EAN and an overlong label (>30, the truncation arm) and one with a
     * null EAN (the EAN-absent arm). The count returned is the number of marked
     * lines, the voucher names both articles and carries the collection blanks
     * and the barcode.
     */
    @Test
    void printCollectionVoucherPrintsMarkedArticles() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.terminalId = "C04";
        TicketLine withEan = line("U1", "ARTICLE AU LIBELLE VRAIMENT TRES LONG", "1", "9.00", "9.00");
        withEan.toCollect = true;
        withEan.ean = "3560070139682";
        ticket.lines.add(withEan);
        TicketLine noEan = line("U2", "TABOURET", "2", "5.00", "10.00");
        noEan.toCollect = true;
        noEan.ean = null;
        ticket.lines.add(noEan);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            assertEquals(2, service.printCollectionVoucher(1L));
        }
        String out = captureReceipt(service);
        assertTrue(out.contains("ARTICLES A ENLEVER"));
        assertTrue(out.contains("MAGASIN LYON"));
        assertTrue(out.contains("Caisse : C04"));
        assertTrue(out.contains("ARTICLE AU LIBELLE VRAIMENT TR"));
        assertTrue(out.contains("3560070139682"));
        assertTrue(out.contains("TABOURET"));
        assertTrue(out.contains("Retire : ............  Reste : ............"));
        assertTrue(out.contains("Signature: .............................."));
    }

    /**
     * TWO COPIES, NAMED. The handover slip is not a fiscal document but a proof
     * that goods left the shop: the collection desk KEEPS one — the manager
     * collects them in the evening — and the customer KEEPS one, carrying the
     * "retiré / reste" boxes they come back with. Two identical sheets would
     * leave nobody able to tell an honoured slip from an open one, so the first
     * is the store's and the second the customer's, and both carry the articles
     * and the barcode the desk scans.
     */
    @Test
    void printCollectionVoucherPrintsTwoNamedCopies() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.collectVoucherCopies()).thenReturn(2);
        Ticket ticket = ticket(0, null);
        TicketLine marked = line("U1", "CIMENT", "10", "6.00", "60.00");
        marked.toCollect = true;
        ticket.lines.add(marked);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            assertEquals(1, service.printCollectionVoucher(1L));
        }
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(service.hardwareService, times(2)).printReceipt(captor.capture());
        verify(service.hardwareService, times(2)).cutPaper();
        assertTrue(captor.getAllValues().get(0).contains("EXEMPLAIRE MAGASIN"));
        assertFalse(captor.getAllValues().get(0).contains("EXEMPLAIRE CLIENT"));
        assertTrue(captor.getAllValues().get(1).contains("EXEMPLAIRE CLIENT"));
        assertFalse(captor.getAllValues().get(1).contains("EXEMPLAIRE MAGASIN"));
        for (String copy : captor.getAllValues()) {
            assertTrue(copy.contains("CIMENT"));
            assertTrue(copy.contains("ARTICLES A ENLEVER"));
        }
    }

    /**
     * A SINGLE administered copy carries NO exemplaire line: there is nothing to
     * tell apart, and a lone slip announcing itself as one of two would be a
     * lie — the false leg of the copy-count test.
     */
    @Test
    void printCollectionVoucherOnASingleCopyNamesNoExemplaire() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        TicketLine marked = line("U1", "CIMENT", "10", "6.00", "60.00");
        marked.toCollect = true;
        ticket.lines.add(marked);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            assertEquals(1, service.printCollectionVoucher(1L));
        }
        String out = captureReceipt(service);
        assertFalse(out.contains("EXEMPLAIRE"));
        assertTrue(out.contains("CIMENT"));
    }

    /**
     * THE SLIP NAMES THE PLACE when the store administered one: the customer
     * pays at the till and drives to the collection point — the BATI counter in
     * a hardware store, the DRIVE in a food one — so the slip has to say where.
     * Surrounding blanks are trimmed.
     */
    @Test
    void printCollectionVoucherNamesTheAdministeredCollectionPoint() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.collectPointLabel()).thenReturn("  BATI  ");
        Ticket ticket = ticket(0, null);
        TicketLine marked = line("U1", "PARPAING", "20", "1.20", "24.00");
        marked.toCollect = true;
        ticket.lines.add(marked);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            assertEquals(1, service.printCollectionVoucher(1L));
        }
        assertTrue(captureReceipt(service).contains("RETRAIT AU BATI"));
    }

    /**
     * A store that administered NO collection point gets a slip naming none,
     * rather than one inventing a place — the blank and the null legs of the
     * guard, which a mocked setting answers as null by default.
     */
    @Test
    void printCollectionVoucherNamesNoPointWhenNoneIsAdministered() {
        for (String administered : new String[] {null, "   "}) {
            TicketPrinterService service = newService();
            when(service.posSettingsService.collectPointLabel()).thenReturn(administered);
            Ticket ticket = ticket(0, null);
            TicketLine marked = line("U1", "PARPAING", "20", "1.20", "24.00");
            marked.toCollect = true;
            ticket.lines.add(marked);
            try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
                mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
                assertEquals(1, service.printCollectionVoucher(1L));
            }
            assertFalse(captureReceipt(service).contains("RETRAIT AU"));
        }
    }

    /**
     * Covers the optional-line false arms of {@code printCollectionVoucher}: a
     * ticket with no store, no terminal id and no creation date, whose single
     * marked line carries an empty (non-null) EAN. The store, register and date
     * guards all take their false leg, and the empty-EAN line takes the
     * {@code !ean.isEmpty()} false leg so no EAN line is printed. The count
     * returned is one.
     */
    @Test
    void printCollectionVoucherPrintsWithoutOptionalLines() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.store = null;
        ticket.terminalId = null;
        ticket.creationDate = null;
        TicketLine emptyEan = line("U1", "SEAU", "1", "3.00", "3.00");
        emptyEan.toCollect = true;
        emptyEan.ean = "";
        ticket.lines.add(emptyEan);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            assertEquals(1, service.printCollectionVoucher(1L));
        }
        String out = captureReceipt(service);
        assertTrue(out.contains("ARTICLES A ENLEVER"));
        assertTrue(out.contains("SEAU"));
        assertFalse(out.contains("MAGASIN LYON"));
        assertFalse(out.contains("Caisse :"));
        assertFalse(out.contains("Date   :"));
    }

    /**
     * Covers the fidelity-card legs of {@code renderTicket}: a sale carrying a
     * non-blank fidelity card reaches the true legs of the
     * {@code fidelityCard != null && !fidelityCard.isBlank()} guard, and a
     * second sale carrying a blank (non-null) card reaches the {@code isBlank}
     * true leg — the two arms the card-less sales (the null leg) leave
     * uncovered.
     */
    @Test
    void renderTicketReadsFidelityCardPresenceAndBlankness() {
        TicketPrinterService service = newService();
        Ticket named = ticket(0, null);
        named.fidelityCard = "2990000000019";
        named.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        named.payments.add(new CashPayment(new BigDecimal("2.00"), new BigDecimal("2.00")));
        Ticket blank = ticket(0, null);
        blank.fidelityCard = "   ";
        blank.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        blank.payments.add(new CashPayment(new BigDecimal("2.00"), new BigDecimal("2.00")));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            mocked.when(() -> Ticket.findById(1L)).thenReturn(named);
            service.printTicket(1L);
            assertTrue(captureReceipt(service).contains("INTERMARCHE"));
            TicketPrinterService blankService = newService();
            mocked.when(() -> Ticket.findById(1L)).thenReturn(blank);
            blankService.printTicket(1L);
            assertTrue(captureReceipt(blankService).contains("INTERMARCHE"));
        }
    }

    /**
     * Covers the empty-string arms of {@code saleLines}: a live article whose
     * quantity, unit price, line total and VAT rate are all null resolves each
     * of the four figure ternaries to its empty-string leg, the arms the priced
     * articles leave uncovered.
     */
    @Test
    void saleLinesUsesEmptyStringsForNullFigures() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        TicketLine bare = new TicketLine();
        bare.lineUid = "U1";
        bare.productLabel = "ARTICLE";
        bare.quantity = null;
        bare.unitPrice = null;
        bare.totalPrice = null;
        bare.vatRate = null;
        ticket.lines.add(bare);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines =
                (List<Map<String, Object>>) saleData(service, ticket, false, 0).get("lines");
        assertEquals(1, lines.size());
        assertEquals("", lines.get(0).get("quantity"));
        assertEquals("", lines.get(0).get("unitPrice"));
        assertEquals("", lines.get(0).get("total"));
        assertEquals("", lines.get(0).get("vatRate"));
    }

    /**
     * Covers the null-lines arm of {@code refundDocumentData} and the null-VAT
     * arm of its line builder: a refund whose {@code lines} collection is null
     * takes the false leg of the {@code refund.lines != null} guard (no line
     * described), while a refund line whose VAT rate is null resolves the VAT
     * ternary to its empty-string leg.
     */
    @Test
    void refundDocumentDataHandlesNullLinesAndNullVatRate() {
        TicketPrinterService service = newService();
        Refund noLines = mock(Refund.class);
        noLines.lines = null;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> emptyLines =
                (List<Map<String, Object>>) service.refundDocumentData(noLines, null).get("lines");
        assertTrue(emptyLines.isEmpty());
        Refund withNullVat = mock(Refund.class);
        withNullVat.lines = new ArrayList<>();
        RefundLine line = refundLine("BEURRE", "2", "2.00");
        line.vatRate = null;
        withNullVat.lines.add(line);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines =
                (List<Map<String, Object>>) service.refundDocumentData(withNullVat, null).get("lines");
        assertEquals("", lines.get(0).get("vatRate"));
    }

    /**
     * Covers the session-present, figure-absent arms of
     * {@code sessionDocumentData}: a report whose session is non-null but whose
     * opening date, opening float, counted amount, variance and withdrawn amount
     * are all null resolves each figure to its empty-string leg — the
     * session-non-null-yet-field-null legs the fully-populated session leaves
     * uncovered.
     */
    @Test
    void sessionDocumentDataUsesEmptyStringsForNullSessionFigures() {
        TicketPrinterService service = newService();
        CashSessionService.SessionReport report = new CashSessionService.SessionReport();
        CashSession sess = new CashSession();
        sess.sessionNumber = "S-001";
        sess.terminalId = "C04";
        sess.openingDate = null;
        sess.openingFloat = null;
        sess.countedAmount = null;
        sess.variance = null;
        sess.withdrawnAmount = null;
        report.session = sess;
        Map<String, Object> data = service.sessionDocumentData(report);
        @SuppressWarnings("unchecked")
        Map<String, Object> session = (Map<String, Object>) data.get("session");
        assertEquals("", session.get("openedAt"));
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) data.get("totals");
        assertEquals("", totals.get("openingFloat"));
        assertEquals("", totals.get("countedCash"));
        assertEquals("", totals.get("variance"));
        assertEquals("", totals.get("withdrawn"));
    }

    /**
     * Covers the date-less arm of {@code printCardCreditReceipt}: a refund
     * carrying no creation date resolves the {@code creationDate == null} guard
     * (line 1851) to its true leg (a null date string passed to the layout),
     * the arm the dated refund (the seed default) leaves uncovered. The layout
     * is administered so only the guarded data builder runs; the built-in slip
     * is NOT exercised because its own date line (line 1863) formats the date
     * with no null guard and would throw — the reported gap.
     */
    @Test
    void printCardCreditReceiptHandlesAMissingCreationDate() {
        TicketPrinterService service = newService();
        wireRenderer(service);
        com.intermarche.pos.domain.setting.DocumentTemplate slip = layout(
                com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.CARD_RECEIPT,
                "{card.kind} {card.amount} {terminal}");
        Refund refund = refund();
        refund.creationDate = null;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubAdministeredLayout(panache, slip);
            service.printCardCreditReceipt(refund);
            assertEquals("CREDIT 4,00 C04", captureReceipt(service));
        }
    }

    // --------------------------------------------------
    // Campaign 2 — branches the retours/langue lots added
    // --------------------------------------------------

    /**
     * Covers both PLU legs of the decomposition sub-line argument (line 238):
     * a line whose {@code plu} is non-null and non-empty reaches the true leg of
     * {@code plu != null} and the false leg of {@code isEmpty()}, a line whose
     * {@code plu} is the empty string reaches the true leg of {@code isEmpty()},
     * and a line whose {@code plu} is null reaches the false leg of
     * {@code plu != null} — the three outcomes that drive the price-embedded flag
     * handed to {@code detailLineOf}.
     */
    @Test
    void printTicketEvaluatesEveryPluLegOfTheDecompositionLine() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        TicketLine withPlu = line("U1", "POMMES", "1.250", "2.00", "2.50");
        withPlu.plu = "0471";
        withPlu.unitName = "kg";
        TicketLine emptyPlu = line("U2", "PAIN", "1", "1.00", "1.00");
        emptyPlu.plu = "";
        TicketLine nullPlu = line("U3", "LAIT", "1", "1.00", "1.00");
        nullPlu.plu = null;
        ticket.lines.add(withPlu);
        ticket.lines.add(emptyPlu);
        ticket.lines.add(nullPlu);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.contains("POMMES"));
            assertTrue(out.contains("PAIN"));
            assertTrue(out.contains("LAIT"));
        }
    }

    /**
     * Covers the true arm of the SOUS-TOTAL marker guard (line 282): a line that
     * carries a {@code subtotalBoundary} prints the running sub-total since the
     * previous marker, the EXP-43 boundary the unmarked tickets never reach.
     */
    @Test
    void printTicketPrintsTheSubtotalMarker() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        TicketLine first = line("U1", "PAIN", "1", "2.00", "2.00");
        first.subtotalBoundary = true;
        ticket.lines.add(first);
        ticket.lines.add(line("U2", "LAIT", "1", "1.00", "1.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.contains("SOUS-TOTAL"));
            assertTrue(out.contains("2,00 E"));
        }
    }

    /**
     * Covers the two true legs of the footer-message guard (line 419): an engine
     * message whose text is null and one whose text is blank are both skipped by
     * the {@code text == null || text.isBlank()} continue, so neither reaches the
     * wrapping below — the arms the retained message (text present) leaves
     * uncovered. The courtesy line still prints, proving the loop ran through.
     */
    @Test
    void printTicketSkipsNullAndBlankFooterMessages() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.messages = new ArrayList<>(List.of(
                new com.intermarche.pos.domain.sale.TicketMessage("A", null, "FOOTER"),
                new com.intermarche.pos.domain.sale.TicketMessage("B", "   ", "FOOTER")));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.contains("MERCI DE VOTRE VISITE"));
        }
    }

    /**
     * Covers the null arm of the fidelity-lines guard in
     * {@code appendFidelityAdvantages} (line 738): a sale whose projection prints
     * but whose {@code fidelityLines} collection is null resolves the
     * {@code fidelityLines == null ? List.of() : fidelityLines} ternary to the
     * empty-list leg, so the advantage block renders its header and nothing more.
     */
    @Test
    void printTicketRendersTheProjectionWithNullFidelityLines() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.fidelityCard = "2990000000019";
        ticket.fidelityEarnTotal = new BigDecimal("1.00");
        ticket.fidelityLines = null;
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.contains("CAGNOTTE DU JOUR"));
            assertTrue(out.contains("+1,00 E"));
        }
    }

    /**
     * Covers the typed-advantage grouping of {@code appendFidelityAdvantages}
     * (lines 758, 769, 789 and the untyped append): a sale carrying a typed group
     * with a label and an order (header non-null, order non-null), a typed group
     * with neither (header null falls back to the key, order null falls to
     * {@code Integer.MAX_VALUE}) and an untyped line (the {@code advantageType ==
     * null} true leg, appended flat after the groups). The labelled group,
     * ordered first, and the keyed group, ordered last, prove both legs of each
     * ternary.
     */
    @Test
    void printTicketGroupsTypedFidelityAdvantagesAndAppendsUntypedOnes() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.fidelityCard = "2990000000019";
        ticket.fidelityEarnTotal = new BigDecimal("1.00");
        ticket.fidelityLines = new ArrayList<>();
        TicketFidelityLine socle = new TicketFidelityLine("R1", "Cagnotte socle", new BigDecimal("0.20"));
        socle.advantageType = "SOCLE";
        socle.advantageTypeLabel = "AVANTAGES SOCLE";
        socle.advantageTypeOrder = 1;
        TicketFidelityLine bonus = new TicketFidelityLine("R2", "Bonus fruits", new BigDecimal("0.30"));
        bonus.advantageType = "BONUS";
        bonus.advantageTypeLabel = null;
        bonus.advantageTypeOrder = null;
        TicketFidelityLine plain = new TicketFidelityLine("R3", "Remise directe", new BigDecimal("0.10"));
        plain.advantageType = null;
        ticket.fidelityLines.add(socle);
        ticket.fidelityLines.add(bonus);
        ticket.fidelityLines.add(plain);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.contains("AVANTAGES SOCLE"));
            assertTrue(out.contains("BONUS"));
            assertTrue(out.contains("Cagnotte socle"));
            assertTrue(out.contains("Bonus fruits"));
            assertTrue(out.contains("Remise directe"));
            assertTrue(out.indexOf("AVANTAGES SOCLE") < out.indexOf("BONUS"));
        }
    }

    /**
     * Covers the non-null arm of the administered balance-after figure in
     * {@code fidelityData} (line 826): a sale whose {@code fidelityBalanceAfter}
     * is set resolves the {@code balanceAfter == null ? "" : format} ternary to
     * the formatted leg, the arm the balance-less sales leave uncovered. The data
     * builder is read directly, the way the store-layout render reaches it.
     */
    @Test
    void saleDocumentDataFormatsThePresentBalanceAfter() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.fidelityCard = "2990000000019";
        ticket.fidelityBalanceAfter = new BigDecimal("12.34");
        @SuppressWarnings("unchecked")
        Map<String, Object> fidelity =
                (Map<String, Object>) service.saleDocumentData(ticket, false, 0).get("fidelity");
        assertEquals("12,34", fidelity.get("balanceAfter"));
    }

    /**
     * Covers the empty-logo arm of {@code brandBanner} (line 1299): a till whose
     * {@link ReceiptLogo#directive()} yields no printable logo falls back to the
     * centred INTERMARCHE text banner, the arm a till carrying a logo leaves
     * uncovered. Driven through {@code printLoyaltyCredit}, which opens with the
     * banner. Reachable in operation on a deployment with no logo file, so the
     * static is stubbed rather than reported.
     */
    @Test
    void brandBannerFallsBackToTextWhenNoLogo() {
        TicketPrinterService service = newService();
        try (MockedStatic<ReceiptLogo> logo = mockStatic(ReceiptLogo.class)) {
            logo.when(() -> ReceiptLogo.directive(any())).thenReturn("");
            service.printLoyaltyCredit(new BigDecimal("5.00"));
            String out = captureReceipt(service);
            assertTrue(out.contains("INTERMARCHE"));
            assertTrue(out.contains("REMBOURSEMENT EN CAGNOTTE"));
        }
    }

    /**
     * Covers the true arm of the balance-after guard of {@code printLoyaltyReceipt}
     * on the built-in path (line 1723): a settlement whose ticket carries a
     * {@code fidelityBalanceAfter} prints the SOLDE APRES line, the arm the seed
     * ticket (no post-burn balance) leaves uncovered.
     */
    @Test
    void printLoyaltyReceiptPrintsTheBalanceAfter() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.terminalId = "C04";
        freeze(ticket, new BigDecimal("1.03"));
        ticket.fidelityAvailableBalance = new BigDecimal("20.00");
        ticket.fidelityBalanceAfter = new BigDecimal("17.00");
        ticket.payments.add(new FidelityPayment(new BigDecimal("3.00")));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            assertEquals(1, service.printLoyaltyReceipt(1L));
        }
        String out = captureReceipt(service);
        assertTrue(out.contains("SOLDE APRES"));
        assertTrue(out.contains("17,00 E"));
    }

    /**
     * Covers the remaining legs of the movement justificatif guards (lines 1898,
     * 1902, 1903, 1906): a movement with a null type label (the empty-string leg),
     * a null operator (the {@code operator != null} false leg), a blank reason
     * (the {@code !reason.isBlank()} false leg) and a null tender (the
     * {@code tenderLabel != null} false leg) prints the header and the amount and
     * none of the optional rows — the legs the fully-populated justificatif and
     * the all-absent one together leave uncovered.
     */
    @Test
    void printMovementTicketCoversTheRemainingFieldLegs() {
        TicketPrinterService service = newService();
        service.printMovementTicket(null, new BigDecimal("15.00"), "   ", null, null, null);
        String out = captureReceipt(service);
        assertTrue(out.contains("MOUVEMENT DE CAISSE"));
        assertTrue(out.contains("TYPE"));
        assertTrue(out.contains("15,00 E"));
        assertFalse(out.contains("Caisse :"));
        assertFalse(out.contains("Operateur :"));
        assertFalse(out.contains("MOTIF"));
        assertFalse(out.contains("MOYEN"));
    }

    /**
     * Covers the fully-populated arm of {@code printFloatCountTicket}: a count
     * with a register, a named operator and a non-empty denomination list reaches
     * the true leg of {@code terminalId != null}, both true legs of the operator
     * guard, the true leg of {@code lines != null}, the loop body and the true leg
     * of {@code !lines.isEmpty()}.
     */
    @Test
    void printFloatCountTicketPrintsTheFullCount() {
        TicketPrinterService service = newService();
        service.printFloatCountTicket(new BigDecimal("250.00"),
                List.<String[]>of(new String[] {"Billets 50", "200,00"}), "MARIE", "C04");
        String out = captureReceipt(service);
        assertTrue(out.contains("COMPTAGE DE CONTROLE"));
        assertTrue(out.contains("Caisse : C04"));
        assertTrue(out.contains("Operateur : MARIE"));
        assertTrue(out.contains("Billets 50"));
        assertTrue(out.contains("TOTAL COMPTE"));
        assertTrue(out.contains("250,00 E"));
    }

    /**
     * Covers the all-absent arm of {@code printFloatCountTicket}: a null register
     * (the {@code terminalId != null} false leg), a null operator (the
     * {@code operator != null} false leg) and a null denomination list (the
     * {@code lines != null} false leg) keep the title and the total and print no
     * register or operator row.
     */
    @Test
    void printFloatCountTicketOmitsTheAbsentFields() {
        TicketPrinterService service = newService();
        service.printFloatCountTicket(new BigDecimal("0.00"), null, null, null);
        String out = captureReceipt(service);
        assertTrue(out.contains("COMPTAGE DE CONTROLE"));
        assertTrue(out.contains("TOTAL COMPTE"));
        assertTrue(out.contains("0,00 E"));
        assertFalse(out.contains("Caisse :"));
        assertFalse(out.contains("Operateur :"));
    }

    /**
     * Covers the blank-operator and empty-list arms of
     * {@code printFloatCountTicket}: a blank operator reaches the
     * {@code !operator.isBlank()} false leg and an empty (non-null) denomination
     * list reaches the loop's zero-iteration leg and the {@code !lines.isEmpty()}
     * false leg — no operator row and no extra separator after the (absent) lines.
     */
    @Test
    void printFloatCountTicketTakesTheBlankOperatorAndEmptyListLegs() {
        TicketPrinterService service = newService();
        service.printFloatCountTicket(new BigDecimal("10.00"),
                new ArrayList<String[]>(), "   ", "C04");
        String out = captureReceipt(service);
        assertTrue(out.contains("Caisse : C04"));
        assertTrue(out.contains("TOTAL COMPTE"));
        assertTrue(out.contains("10,00 E"));
        assertFalse(out.contains("Operateur :"));
    }

    /**
     * Covers the fully-populated arm of {@code printDepositTicket}: an apport with
     * a register, a named operator and a non-empty denomination list reaches the
     * true legs of every guard, the loop body and the non-empty separator.
     */
    @Test
    void printDepositTicketPrintsTheFullDeposit() {
        TicketPrinterService service = newService();
        service.printDepositTicket(new BigDecimal("500.00"),
                List.<String[]>of(new String[] {"Pieces 2", "100,00"}), "MARIE", "11111111", "C04");
        String out = captureReceipt(service);
        assertTrue(out.contains("APPORT DE MONNAIE"));
        assertTrue(out.contains("Caisse : C04"));
        assertTrue(out.contains("Operateur : MARIE"));
        assertTrue(out.contains("Pieces 2"));
        assertTrue(out.contains("TOTAL APPORTE"));
        assertTrue(out.contains("500,00 E"));
        assertTrue(out.contains("Signature:"));
    }

    /**
     * Covers the all-absent arm of {@code printDepositTicket}: a null register, a
     * null operator and a null denomination list take the false legs of the three
     * guards while keeping the title, the total and the signature line.
     */
    @Test
    void printDepositTicketOmitsTheAbsentFields() {
        TicketPrinterService service = newService();
        service.printDepositTicket(new BigDecimal("0.00"), null, null, null, null);
        String out = captureReceipt(service);
        assertTrue(out.contains("APPORT DE MONNAIE"));
        assertTrue(out.contains("TOTAL APPORTE"));
        assertTrue(out.contains("0,00 E"));
        assertFalse(out.contains("Caisse :"));
        assertFalse(out.contains("Operateur :"));
    }

    /**
     * Covers the blank-operator and empty-list arms of {@code printDepositTicket}:
     * a blank operator reaches the {@code !operator.isBlank()} false leg and an
     * empty (non-null) list reaches the loop's zero-iteration leg and the
     * {@code !lines.isEmpty()} false leg.
     */
    @Test
    void printDepositTicketTakesTheBlankOperatorAndEmptyListLegs() {
        TicketPrinterService service = newService();
        service.printDepositTicket(new BigDecimal("5.00"),
                new ArrayList<String[]>(), "   ", "   ", "C04");
        String out = captureReceipt(service);
        assertTrue(out.contains("Caisse : C04"));
        assertTrue(out.contains("TOTAL APPORTE"));
        assertTrue(out.contains("5,00 E"));
        assertFalse(out.contains("Operateur :"));
    }

    /**
     * Covers the fully-populated arm of {@code printSettlementVoucher}: a report
     * with a session (both true legs of {@code report != null && session != null}),
     * a named operator (both true legs of the operator guard), and a non-empty
     * method list (the {@code report != null} true leg and the loop body) prints
     * the session, the register, the operator, the one settlement row and the
     * tax-included total.
     */
    @Test
    void printSettlementVoucherPrintsTheFullVoucher() {
        TicketPrinterService service = newService();
        CashSessionService.SessionReport report = new CashSessionService.SessionReport();
        report.session = session(NOW);
        report.totalsByMethod.put("CB", new BigDecimal("120.00"));
        report.totalIncludingTax = new BigDecimal("120.00");
        service.printSettlementVoucher(report, "MARIE");
        String out = captureReceipt(service);
        assertTrue(out.contains("BORDEREAU DE REGLEMENT"));
        assertTrue(out.contains("Session : S-001"));
        assertTrue(out.contains("Caisse  : C04"));
        assertTrue(out.contains("Operateur : MARIE"));
        assertTrue(out.contains("CB"));
        assertTrue(out.contains("TOTAL TTC"));
        assertTrue(out.contains("120,00 E"));
        assertTrue(out.contains("Signature:"));
    }

    /**
     * Covers the null-report arm of {@code printSettlementVoucher}: a null report
     * takes the false leg of {@code report != null} in both guards (the session
     * block and the rows block are skipped), and a null operator takes the false
     * leg of {@code operator != null} — only the title and the signature line
     * remain.
     */
    @Test
    void printSettlementVoucherToleratesANullReportAndOperator() {
        TicketPrinterService service = newService();
        service.printSettlementVoucher(null, null);
        String out = captureReceipt(service);
        assertTrue(out.contains("BORDEREAU DE REGLEMENT"));
        assertTrue(out.contains("Signature:"));
        assertFalse(out.contains("Session :"));
        assertFalse(out.contains("Operateur :"));
        assertFalse(out.contains("TOTAL TTC"));
    }

    /**
     * Covers the session-less and empty-rows arms of
     * {@code printSettlementVoucher}: a non-null report whose session is null
     * takes the {@code report != null} true / {@code session != null} false legs
     * (no session block), a blank operator takes the {@code !operator.isBlank()}
     * false leg, and an empty method map takes the loop's zero-iteration leg while
     * still printing the tax-included total under the {@code report != null} true
     * leg.
     */
    @Test
    void printSettlementVoucherHandlesANullSessionAndEmptyRows() {
        TicketPrinterService service = newService();
        CashSessionService.SessionReport report = new CashSessionService.SessionReport();
        report.session = null;
        report.totalIncludingTax = new BigDecimal("0.00");
        service.printSettlementVoucher(report, "   ");
        String out = captureReceipt(service);
        assertTrue(out.contains("BORDEREAU DE REGLEMENT"));
        assertTrue(out.contains("TOTAL TTC"));
        assertTrue(out.contains("0,00 E"));
        assertFalse(out.contains("Session :"));
        assertFalse(out.contains("Operateur :"));
    }

    // --------------------------------------------------
    // Line discounts and immediate-discount total (BO-03-03-22, BO-03-03-24)
    // --------------------------------------------------

    /**
     * Builds a line carrying a MODIFICATION: the label the register showed on
     * screen and the price it was charged from.
     *
     * @param uid the line's uid
     * @param label the product label
     * @param quantity the quantity sold
     * @param originalUnitPrice the price before the modification, null for none kept
     * @param unitPrice the price charged
     * @param totalPrice the line total
     * @param modifierLabel the modification's label, possibly null or blank
     * @return the line
     */
    private TicketLine modifiedLine(String uid, String label, String quantity,
            String originalUnitPrice, String unitPrice, String totalPrice,
            String modifierLabel) {
        TicketLine line = line(uid, label, quantity, unitPrice, totalPrice);
        line.originalUnitPrice = originalUnitPrice == null
                ? null : new BigDecimal(originalUnitPrice);
        line.modifierLabel = modifierLabel;
        return line;
    }

    /**
     * Renders a ticket carrying one line, with the Panache statics neutralised.
     *
     * @param service the printer under test
     * @param ticket the sale to print
     * @return the rendered receipt text
     */
    private String printed(TicketPrinterService service, Ticket ticket) {
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            return captureReceipt(service);
        }
    }

    /**
     * Neutralises the catalog lookups a rendering makes without the test's
     * knowledge, inside a {@code MockedStatic<PanacheEntityBase>} scope and
     * BEFORE the test's own stubs — the most recently registered stub wins, so
     * the exact stubs must come after these catch-alls. Two funnels are
     * covered: {@code find}, reached by the VAT legal mentions and the VAT
     * code ({@code VatRate.findByRate}), and {@code list}, reached by the
     * administered document names ({@code DocumentTemplate.titleFor}). A
     * plain unit test has no persistence context to answer either with.
     *
     * @param panache the static mock the test holds open
     */
    private void silenceCatalog(MockedStatic<PanacheEntityBase> panache) {
        @SuppressWarnings("unchecked")
        PanacheQuery<Object> nothing = mock(PanacheQuery.class);
        when(nothing.firstResult()).thenReturn(null);
        panache.when(() -> PanacheEntityBase.find(anyString(), any(Object[].class)))
                .thenReturn(nothing);
        panache.when(() -> PanacheEntityBase.list(anyString(), any(Object[].class)))
                .thenReturn(java.util.List.of());
    }

    /**
     * Opens a {@code PanacheEntityBase} static mock that answers every
     * catalog lookup with nothing, for the tests that drive a printing funnel
     * without a static mock of their own. The caller closes the mock through
     * its own try-with-resources.
     *
     * @return the opened static mock, answering no row anywhere
     */
    private MockedStatic<PanacheEntityBase> quietCatalog() {
        MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
        silenceCatalog(panache);
        return panache;
    }

    /**
     * A Panache query answering ONE VAT regime, for the tests that administer
     * what {@code VatRate.findByRate} resolves to. The stub goes on the
     * underlying {@code find} because {@code findByRate} is a plain static
     * the Panache mock never intercepts — stubbing it directly leaves the
     * matcher dangling and poisons the next Mockito call.
     *
     * @param regime the regime every rate lookup resolves to
     * @return a query whose first result is the regime
     */
    private PanacheQuery<com.intermarche.pos.domain.catalog.VatRate> vatQuery(
            com.intermarche.pos.domain.catalog.VatRate regime) {
        @SuppressWarnings("unchecked")
        PanacheQuery<com.intermarche.pos.domain.catalog.VatRate> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(regime);
        return query;
    }

    /**
     * A Panache query whose first result is the given row, for the tests that
     * administer what an entity finder resolves to: the stub goes on the
     * underlying {@code find}, because finders such as {@code findByCode} are
     * plain statics the Panache mock never intercepts.
     *
     * @param row the row every matching lookup resolves to
     * @return a query whose first result is the row
     */
    private <T> PanacheQuery<T> answer(T row) {
        @SuppressWarnings("unchecked")
        PanacheQuery<T> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        return query;
    }

    /**
     * BO-03-03-22, the guard's true arm: the modification's label prints under
     * its article, with the amount it took off that line beside it.
     */
    @Test
    void aLineDiscountPrintsUnderItsArticle() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.ticketLineDiscountPrint()).thenReturn(true);
        Ticket ticket = ticket(0, null);
        ticket.lines.add(modifiedLine("U1", "PAIN", "2", "2.00", "1.50", "3.00",
                "Remise -0,50E"));
        String out = printed(service, ticket);
        assertTrue(out.contains("Remise -0,50E"));
        assertTrue(out.contains("-1,00 E"));
    }

    /**
     * BO-03-03-22, the three legs of the guard held false one at a time: the
     * shop not asking, a line carrying no modification label, and one carrying
     * a blank label.
     */
    @Test
    void aLineDiscountStaysOutOnEachLegOfItsGuard() {
        TicketPrinterService silent = newService();
        when(silent.posSettingsService.ticketLineDiscountPrint()).thenReturn(false);
        Ticket asked = ticket(0, null);
        asked.lines.add(modifiedLine("U1", "PAIN", "2", "2.00", "1.50", "3.00",
                "Remise -0,50E"));
        assertFalse(printed(silent, asked).contains("Remise -0,50E"));

        TicketPrinterService noLabel = newService();
        when(noLabel.posSettingsService.ticketLineDiscountPrint()).thenReturn(true);
        Ticket unlabelled = ticket(0, null);
        unlabelled.lines.add(modifiedLine("U1", "PAIN", "2", "2.00", "1.50", "3.00", null));
        assertFalse(printed(noLabel, unlabelled).contains("-1,00 E"));

        TicketPrinterService blankLabel = newService();
        when(blankLabel.posSettingsService.ticketLineDiscountPrint()).thenReturn(true);
        Ticket blank = ticket(0, null);
        blank.lines.add(modifiedLine("U1", "PAIN", "2", "2.00", "1.50", "3.00", "   "));
        assertFalse(printed(blankLabel, blank).contains("-1,00 E"));
    }

    /**
     * BO-03-03-22: a modification whose original price the back office masks
     * keeps NO original on the line, so the label prints and no amount does —
     * the register states only what it vouched for on screen. The same holds
     * for a modification that did not lower the price (the {@code signum() <= 0}
     * arm).
     */
    @Test
    void aLineDiscountStatesNoAmountItCannotCompute() {
        TicketPrinterService masked = newService();
        when(masked.posSettingsService.ticketLineDiscountPrint()).thenReturn(true);
        Ticket noOriginal = ticket(0, null);
        noOriginal.lines.add(modifiedLine("U1", "PAIN", "2", null, "1.50", "3.00",
                "Prix force"));
        String out = printed(masked, noOriginal);
        assertTrue(out.contains("Prix force"));
        assertFalse(out.contains("-1,00 E"));

        TicketPrinterService raised = newService();
        when(raised.posSettingsService.ticketLineDiscountPrint()).thenReturn(true);
        Ticket higher = ticket(0, null);
        higher.lines.add(modifiedLine("U1", "PAIN", "2", "1.00", "1.50", "3.00",
                "Prix force"));
        assertTrue(printed(raised, higher).contains("Prix force"));
    }

    /**
     * BO-03-03-24, the guard's true arm: the cumulated immediate discounts —
     * the line modifications plus the ticket discount — print on one line under
     * the totals.
     */
    @Test
    void theImmediateDiscountTotalCumulatesTheLinesAndTheTicket() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.ticketImmediateDiscountTotal()).thenReturn(true);
        Ticket ticket = ticket(0, null);
        ticket.globalDiscountApplied = new BigDecimal("2.00");
        ticket.lines.add(modifiedLine("U1", "PAIN", "2", "2.00", "1.50", "3.00",
                "Remise -0,50E"));
        String out = printed(service, ticket);
        assertTrue(out.contains("TOTAL REMISES IMMEDIATES"));
        assertTrue(out.contains("-3,00 E"));
    }

    /**
     * BO-03-03-24, the two false arms: the shop not asking, and a basket that
     * saved nothing — a line stating "0,00" of savings says nothing and costs
     * paper.
     */
    @Test
    void theImmediateDiscountTotalStaysOutWhenThereIsNothingToSay() {
        TicketPrinterService silent = newService();
        when(silent.posSettingsService.ticketImmediateDiscountTotal()).thenReturn(false);
        Ticket asked = ticket(0, null);
        asked.globalDiscountApplied = new BigDecimal("2.00");
        assertFalse(printed(silent, asked).contains("TOTAL REMISES IMMEDIATES"));

        TicketPrinterService nothing = newService();
        when(nothing.posSettingsService.ticketImmediateDiscountTotal()).thenReturn(true);
        Ticket plain = ticket(0, null);
        plain.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        assertFalse(printed(nothing, plain).contains("TOTAL REMISES IMMEDIATES"));
    }

    /**
     * BO-03-03-24: a CANCELLED line is outside the sale and its modification is
     * outside the cumul, exactly as it is outside the totals.
     */
    @Test
    void theImmediateDiscountTotalIgnoresACancelledLine() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.ticketImmediateDiscountTotal()).thenReturn(true);
        Ticket ticket = ticket(0, null);
        TicketLine cancelled = modifiedLine("U1", "PAIN", "2", "2.00", "1.50", "3.00",
                "Remise -0,50E");
        cancelled.cancelled = true;
        ticket.lines.add(cancelled);
        ticket.lines.add(line("U2", "LAIT", "1", "1.00", "1.00"));
        assertFalse(printed(service, ticket).contains("TOTAL REMISES IMMEDIATES"));
    }

    /**
     * BO-03-03-03 / BO-03-03-22: an administered layout reads BOTH unit prices
     * of a line and the modification it carries — the tax-excluded price being
     * removed by the very rule the VAT table uses.
     */
    @Test
    @SuppressWarnings("unchecked")
    void saleDocumentDataCarriesBothUnitPricesAndTheLineDiscount() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.lines.add(modifiedLine("U1", "PAIN", "2", "2.00", "1.20", "2.40",
                "Remise -0,80E"));
        List<Map<String, Object>> lines =
                (List<Map<String, Object>>) saleData(service, ticket, false, 0).get("lines");
        assertEquals("1,20", lines.get(0).get("unitPrice"));
        assertEquals("1,00", lines.get(0).get("unitExcludingTax"));
        assertEquals("Remise -0,80E", lines.get(0).get("discountLabel"));
        assertEquals("1,60", lines.get(0).get("discount"));
    }

    /**
     * The two guards of the tax-excluded unit price: a line carrying no price
     * states none rather than a zero, and a line carrying no VAT rate is read
     * at zero percent, so its tax-excluded price is the price itself.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theTaxExcludedUnitPriceToleratesAMissingPriceAndAMissingRate() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        TicketLine noPrice = line("U1", "PAIN", "1", "2.00", "2.00");
        noPrice.unitPrice = null;
        TicketLine noRate = line("U2", "LAIT", "1", "1.50", "1.50");
        noRate.vatRate = null;
        ticket.lines.add(noPrice);
        ticket.lines.add(noRate);
        List<Map<String, Object>> lines =
                (List<Map<String, Object>>) saleData(service, ticket, false, 0).get("lines");
        assertEquals("", lines.get(0).get("unitExcludingTax"));
        assertEquals("1,50", lines.get(1).get("unitExcludingTax"));
    }

    /**
     * BO-03-03-24: the cumul reaches an administered layout too, and empty
     * rather than "0,00" when the sale saved nothing.
     */
    @Test
    @SuppressWarnings("unchecked")
    void saleDocumentDataCarriesTheImmediateDiscountTotal() {
        TicketPrinterService service = newService();
        Ticket saving = ticket(0, null);
        saving.globalDiscountApplied = new BigDecimal("2.00");
        Map<String, Object> totals =
                (Map<String, Object>) service.saleDocumentData(saving, false, 0).get("totals");
        assertEquals("2,00", totals.get("immediateDiscount"));

        TicketPrinterService plain = newService();
        Map<String, Object> none = (Map<String, Object>)
                plain.saleDocumentData(ticket(0, null), false, 0).get("totals");
        assertEquals("", none.get("immediateDiscount"));
    }

    /**
     * BO-03-03-08: the instrument voucher hands its layout the end of validity
     * and the barcode DIRECTIVE — the two values the credit note was missing.
     */
    @Test
    @SuppressWarnings("unchecked")
    void instrumentDocumentDataCarriesTheExpiryAndTheBarcode() {
        TicketPrinterService service = newService();
        Map<String, Object> instrument = (Map<String, Object>) service.instrumentDocumentData(
                "297000000000042", new BigDecimal("30.00"),
                LocalDateTime.of(2027, 3, 15, 0, 0)).get("instrument");
        assertEquals("15/03/2027", instrument.get("validUntil"));
        assertEquals("[[BARCODE 297000000000042]]", instrument.get("barcode"));
    }

    /**
     * The two guards of that same block: an instrument that never expires
     * states no date, and one carrying no number carries no barcode — a
     * directive over an empty value would print the directive itself on the
     * paper.
     */
    @Test
    @SuppressWarnings("unchecked")
    void instrumentDocumentDataStatesNoExpiryAndNoBarcodeItDoesNotHold() {
        TicketPrinterService service = newService();
        Map<String, Object> noDate = (Map<String, Object>) service.instrumentDocumentData(
                "297000000000042", new BigDecimal("30.00"), null).get("instrument");
        assertEquals("", noDate.get("validUntil"));

        Map<String, Object> noNumber = (Map<String, Object>) service.instrumentDocumentData(
                null, new BigDecimal("30.00"), null).get("instrument");
        assertEquals("", noNumber.get("barcode"));

        Map<String, Object> blankNumber = (Map<String, Object>) service.instrumentDocumentData(
                "  ", new BigDecimal("30.00"), null).get("instrument");
        assertEquals("", blankNumber.get("barcode"));
    }

    /**
     * BO-03-03-08, the lookup's true arm: with the shop asking for the date, the
     * registry is read and the note states its end of validity.
     */
    @Test
    void printChangeVoucherStatesTheExpiryWhenTheShopAsksForIt() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.creditNotePrintExpiry()).thenReturn(true);
        com.intermarche.pos.domain.payment.StoredValue note =
                new com.intermarche.pos.domain.payment.StoredValue();
        note.validUntil = LocalDateTime.of(2027, 3, 15, 0, 0);
        try (MockedStatic<com.intermarche.pos.domain.payment.StoredValue> registry =
                     mockStatic(com.intermarche.pos.domain.payment.StoredValue.class)) {
            registry.when(() -> com.intermarche.pos.domain.payment.StoredValue
                    .findByNumber("297000000000042")).thenReturn(note);
            service.printChangeVoucher("297000000000042", new BigDecimal("30.00"));
        }
        assertTrue(captureReceipt(service).contains("VALABLE JUSQU'AU 15/03/2027"));
    }

    /**
     * BO-03-03-08, the three legs of the lookup held false one at a time: the
     * shop not asking — in which case the registry is not even read —, a blank
     * number, and a number the registry does not know.
     */
    @Test
    void printChangeVoucherStatesNoExpiryOnEachLegOfItsGuard() {
        TicketPrinterService silent = newService();
        when(silent.posSettingsService.creditNotePrintExpiry()).thenReturn(false);
        silent.printChangeVoucher("297000000000042", new BigDecimal("30.00"));
        assertFalse(captureReceipt(silent).contains("VALABLE"));

        TicketPrinterService blank = newService();
        when(blank.posSettingsService.creditNotePrintExpiry()).thenReturn(true);
        blank.printChangeVoucher("   ", new BigDecimal("30.00"));
        assertFalse(captureReceipt(blank).contains("VALABLE"));

        TicketPrinterService unknown = newService();
        when(unknown.posSettingsService.creditNotePrintExpiry()).thenReturn(true);
        try (MockedStatic<com.intermarche.pos.domain.payment.StoredValue> registry =
                     mockStatic(com.intermarche.pos.domain.payment.StoredValue.class)) {
            registry.when(() -> com.intermarche.pos.domain.payment.StoredValue
                    .findByNumber("297000000000042")).thenReturn(null);
            unknown.printChangeVoucher("297000000000042", new BigDecimal("30.00"));
        }
        assertFalse(captureReceipt(unknown).contains("VALABLE"));
    }

    /**
     * BO-03-03-08: a note the registry knows but which never expires states no
     * date — the null arm of the instrument's own field.
     */
    @Test
    void printChangeVoucherStatesNoExpiryOnANoteThatNeverExpires() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.creditNotePrintExpiry()).thenReturn(true);
        com.intermarche.pos.domain.payment.StoredValue note =
                new com.intermarche.pos.domain.payment.StoredValue();
        note.validUntil = null;
        try (MockedStatic<com.intermarche.pos.domain.payment.StoredValue> registry =
                     mockStatic(com.intermarche.pos.domain.payment.StoredValue.class)) {
            registry.when(() -> com.intermarche.pos.domain.payment.StoredValue
                    .findByNumber("297000000000042")).thenReturn(note);
            service.printChangeVoucher("297000000000042", new BigDecimal("30.00"));
        }
        assertFalse(captureReceipt(service).contains("VALABLE"));
    }

    /**
     * LC-07-03-06: an administered layout reads the settlement under the word
     * the CUSTOMER reads, the very one the built-in layout prints — not the
     * Java class name, which used to make the same sale read two ways
     * depending on whether the shop had written a template.
     */
    @Test
    @SuppressWarnings("unchecked")
    void saleDocumentDataNamesTheSettlementInFrench() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.payments.add(new com.intermarche.pos.domain.payment.CashPayment(
                new BigDecimal("12.00"), new BigDecimal("12.00")));
        List<Map<String, Object>> payments = (List<Map<String, Object>>)
                service.saleDocumentData(ticket, false, 0).get("payments");
        assertEquals("ESPECES", payments.get(0).get("label"));
        assertEquals("CASH", payments.get(0).get("key"));
    }

    // --------------------------------------------------
    // BO-03-03-37: the VAT regime code on the article line
    // --------------------------------------------------

    /**
     * The VAT regime's NUMBER prints beside the article when the store asks for
     * it, read back from the rate the line snapshotted (BO-03-03-37).
     */
    @Test
    void theVatCodePrintsOnTheArticleLineWhenAdministered() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.ticketVatCode()).thenReturn(true);
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        com.intermarche.pos.domain.catalog.VatRate regime =
                new com.intermarche.pos.domain.catalog.VatRate();
        regime.number = 1;
        regime.rate = new BigDecimal("0.2000");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            mocked.when(() -> PanacheEntityBase.find(eq("rate"), any(Object[].class)))
                    .thenAnswer(ignored -> vatQuery(regime));
            service.printTicket(1L);
            assertTrue(captureReceipt(service).contains("CODE TVA : B"));
            assertTrue(captureReceipt(service).contains("TVA B"));
        }
    }

    /**
     * Not administered, nothing prints and the regime is never even looked up
     * (the false arm of the setting).
     */
    @Test
    void theVatCodeStaysOffTheLineByDefault() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            assertFalse(captureReceipt(service).contains("CODE TVA"));
        }
    }

    /**
     * A line carrying no rate, and a rate no regime carries, print NOTHING
     * rather than a number nobody can check — the two null arms of the lookup.
     */
    @Test
    void anUnresolvableVatCodePrintsNothing() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.ticketVatCode()).thenReturn(true);
        Ticket ticket = ticket(0, null);
        TicketLine rateless = line("U1", "PAIN", "1", "2.00", "2.00");
        rateless.vatRate = null;
        ticket.lines.add(rateless);
        ticket.lines.add(line("U2", "LAIT", "1", "1.00", "1.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            assertFalse(captureReceipt(service).contains("CODE TVA"));
        }
    }

    /**
     * A regime found but carrying no number prints nothing either (the third
     * leg of the same compound guard).
     */
    @Test
    void aNumberlessRegimePrintsNothing() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.ticketVatCode()).thenReturn(true);
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        com.intermarche.pos.domain.catalog.VatRate regime =
                new com.intermarche.pos.domain.catalog.VatRate();
        regime.number = null;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            mocked.when(() -> PanacheEntityBase.find(eq("rate"), any(Object[].class)))
                    .thenAnswer(ignored -> vatQuery(regime));
            service.printTicket(1L);
            assertFalse(captureReceipt(service).contains("CODE TVA"));
        }
    }

    // --------------------------------------------------
    // BO-03-03-36: the exit ticket
    // --------------------------------------------------

    /**
     * A store that does not administer the exit ticket prints none, and says so
     * by answering zero — which is what makes the receipt print as before.
     */
    @Test
    void theExitTicketPrintsNothingWhenNotAdministered() {
        TicketPrinterService service = newService();
        assertEquals(0, service.printExitTicket(1L));
        verify(service.hardwareService, never()).printReceipt(any());
    }

    /**
     * An unknown ticket prints no exit ticket either (the second guard).
     */
    @Test
    void theExitTicketPrintsNothingForAnUnknownSale() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.exitTicketEnabled()).thenReturn(true);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(null);
            assertEquals(0, service.printExitTicket(1L));
        }
        verify(service.hardwareService, never()).printReceipt(any());
    }

    /**
     * The built-in exit ticket states its title, the total, the count of
     * articles, the dearest ones and the sale's number with its barcode
     * (BO-03-03-36).
     */
    @Test
    void theExitTicketStatesTheTotalTheCountAndTheDearestArticles() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.exitTicketEnabled()).thenReturn(true);
        when(service.posSettingsService.exitTicketArticles()).thenReturn(2);
        Ticket ticket = ticket(0, null);
        ticket.itemCount = 3;
        ticket.lines.add(line("U1", "LAIT", "6", "1.15", "6.90"));
        ticket.lines.add(line("U2", "COTE DE BOEUF", "1", "18.90", "18.90"));
        ticket.lines.add(line("U3", "PAIN", "1", "1.00", "1.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            assertEquals(1, service.printExitTicket(1L));
        }
        String out = captureReceipt(service);
        assertTrue(out.contains("TICKET DE SORTIE"));
        assertTrue(out.contains("TOTAL TTC"));
        assertTrue(out.contains("NOMBRE D'ARTICLES"));
        assertTrue(out.contains("3"));
        assertTrue(out.contains("COTE DE BOEUF"));
        assertTrue(out.contains("LAIT"));
        // The store asked for two: the cheapest line is NOT named.
        assertFalse(out.contains("PAIN"));
        // The dearest first, which is what a gate compares a trolley against.
        assertTrue(out.indexOf("COTE DE BOEUF") < out.indexOf("LAIT"));
        assertTrue(out.contains("[[BARCODE C04-00000001]]"));
    }

    /**
     * A cancelled line is not sold, so it is never named; a sale with fewer
     * lines than the store asked for names all of them (the other arm of the
     * cap).
     */
    @Test
    void theExitTicketIgnoresCancelledLinesAndNamesWhatIsLeft() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.exitTicketEnabled()).thenReturn(true);
        when(service.posSettingsService.exitTicketArticles()).thenReturn(5);
        Ticket ticket = ticket(0, null);
        ticket.itemCount = 1;
        TicketLine cancelled = line("U1", "ANNULE", "1", "99.00", "99.00");
        cancelled.cancelled = true;
        ticket.lines.add(cancelled);
        ticket.lines.add(line("U2", "PAIN", "1", "1.00", "1.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            assertEquals(1, service.printExitTicket(1L));
        }
        String out = captureReceipt(service);
        assertFalse(out.contains("ANNULE"));
        assertTrue(out.contains("PAIN"));
    }

    /**
     * A sale with no line at all, and totals the register never set, still
     * prints a document rather than failing — the null arms of the figures.
     */
    @Test
    void theExitTicketSurvivesASaleWithoutLinesOrTotal() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.exitTicketEnabled()).thenReturn(true);
        when(service.posSettingsService.exitTicketArticles()).thenReturn(3);
        Ticket ticket = ticket(0, null);
        ticket.totalIncludingTax = null;
        ticket.lines = null;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            assertEquals(1, service.printExitTicket(1L));
        }
        assertTrue(captureReceipt(service).contains("0,00"));
    }

    /**
     * An ADMINISTERED layout replaces the exit ticket entirely, and the
     * register cuts the paper all the same.
     */
    @Test
    void anAdministeredLayoutReplacesTheExitTicket() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.exitTicketEnabled()).thenReturn(true);
        when(service.posSettingsService.exitTicketArticles()).thenReturn(3);
        service.documentTemplateService = mock(DocumentTemplateService.class);
        when(service.documentTemplateService.render(
                eq(DocumentTemplate.DocumentType.EXIT_TICKET), any()))
                .thenReturn("MISE EN PAGE MAGASIN");
        Ticket ticket = ticket(0, null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            assertEquals(1, service.printExitTicket(1L));
        }
        assertEquals("MISE EN PAGE MAGASIN", captureReceipt(service));
    }

    /**
     * The exit ticket a layout reads carries the sale's number and barcode, the
     * total, the count and the dearest lines — the contract the back office's
     * preview pane advertises.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theExitTicketALayoutReadsNamesTheDearestLines() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.exitTicketArticles()).thenReturn(1);
        Ticket ticket = ticket(0, null);
        ticket.itemCount = 2;
        ticket.lines.add(line("U1", "LAIT", "6", "1.15", "6.90"));
        ticket.lines.add(line("U2", "COTE DE BOEUF", "1", "18.90", "18.90"));
        Map<String, Object> data = service.exitDocumentData(ticket, ticket.lines);
        assertEquals("C04-00000001",
                ((Map<String, Object>) data.get("document")).get("number"));
        assertEquals("[[BARCODE C04-00000001]]",
                ((Map<String, Object>) data.get("document")).get("barcode"));
        assertEquals("12,00", ((Map<String, Object>) data.get("totals")).get("includingTax"));
        assertEquals("2", data.get("articleCount"));
        List<Map<String, Object>> top = (List<Map<String, Object>>) data.get("topLines");
        assertEquals(2, top.size());
        assertEquals("LAIT", top.get(0).get("label"));
        assertEquals("6,90", top.get(0).get("total"));
        assertEquals("1,15", top.get(0).get("unitPrice"));
    }

    /**
     * A line carrying neither unit price nor total renders both as the empty
     * field rather than a figure the sale never held (the two null arms).
     */
    @Test
    @SuppressWarnings("unchecked")
    void theExitTicketLayoutRendersAFigurelessLineAsEmpty() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        TicketLine bare = line("U1", null, "1", "1.00", "1.00");
        bare.productLabel = null;
        bare.unitPrice = null;
        bare.totalPrice = null;
        Map<String, Object> data = service.exitDocumentData(ticket, List.of(bare));
        List<Map<String, Object>> top = (List<Map<String, Object>>) data.get("topLines");
        assertEquals("", top.get(0).get("label"));
        assertEquals("", top.get(0).get("unitPrice"));
        assertEquals("", top.get(0).get("total"));
    }

    // --------------------------------------------------
    // BO-03-03-09 and -11: the deposit slip and the gift-card payment slip
    // --------------------------------------------------

    /**
     * Nothing is printed without a unique number or without a positive amount:
     * a proof nobody can look up is not a proof — every leg of the guard.
     */
    @Test
    void theDepositSlipPrintsNothingWithoutANumberOrAnAmount() {
        TicketPrinterService service = newService();
        assertEquals(0, service.printDepositReceipt(null, "CMD-1", "MARTIN",
                new BigDecimal("150.00"), null));
        assertEquals(0, service.printDepositReceipt("   ", "CMD-1", "MARTIN",
                new BigDecimal("150.00"), null));
        assertEquals(0, service.printDepositReceipt("AC-1", "CMD-1", "MARTIN", null, null));
        assertEquals(0, service.printDepositReceipt("AC-1", "CMD-1", "MARTIN",
                BigDecimal.ZERO, null));
        verify(service.hardwareService, never()).printReceipt(any());
    }

    /**
     * The deposit slip names the order, the customer and the amount, and
     * carries its own number as a BARCODE so the balance is settled by
     * scanning it (BO-03-03-09). A VAT recap handed in is printed; absent, the
     * block simply does not print.
     */
    @Test
    void theDepositSlipCarriesItsNumberAsABarcode() {
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            TicketPrinterService service = newService();
            assertEquals(1, service.printDepositReceipt("  AC-000045  ", "CMD-2026-0187",
                    "MARTIN Claire", new BigDecimal("150.00"),
                    java.util.List.of(java.util.Map.of("rate", "5,50", "amount", "5,21"))));
            String out = captureReceipt(service);
            assertTrue(out.contains("ACOMPTE"));
            assertTrue(out.contains("MARTIN Claire"));
            assertTrue(out.contains("CMD-2026-0187"));
            assertTrue(out.contains("150,00"));
            assertTrue(out.contains("5,21"));
            assertTrue(out.contains("[[BARCODE AC-000045]]"));
            verify(service.hardwareService).cutPaper();

            TicketPrinterService bare = newService();
            assertEquals(1, bare.printDepositReceipt("AC-2", null, null,
                    new BigDecimal("10.00"), null));
            String plain = captureReceipt(bare);
            assertFalse(plain.contains("null"));
        }
    }

    /**
     * A null row inside the VAT recap is skipped rather than printed as
     * "null": one malformed row must not spoil a proof (the null-row arm).
     */
    @Test
    void theDepositSlipSkipsANullVatRow() {
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            TicketPrinterService service = newService();
            java.util.List<java.util.Map<String, String>> rows = new java.util.ArrayList<>();
            rows.add(null);
            rows.add(java.util.Map.of("rate", "20,00", "amount", "8,33"));
            assertEquals(1, service.printDepositReceipt("AC-3", "CMD-1", "MARTIN",
                    new BigDecimal("50.00"), rows));
            String out = captureReceipt(service);
            assertTrue(out.contains("8,33"));
            assertFalse(out.contains("null"));
        }
    }

    /**
     * LC-06-03-06: nothing is printed without an order code or without a
     * positive deposit — a slip that names no order reopens nothing and a
     * slip that states no money proves nothing — every leg of the guard.
     */
    @Test
    void theOrderDepositTicketPrintsNothingWithoutACodeOrADeposit() {
        TicketPrinterService service = newService();
        assertEquals(0, service.printOrderDepositTicket(null, List.of(),
                new BigDecimal("10.00"), "0.2:1"));
        assertEquals(0, service.printOrderDepositTicket("   ", List.of(),
                new BigDecimal("10.00"), "0.2:1"));
        assertEquals(0, service.printOrderDepositTicket("C04-CMD000001", List.of(),
                null, null));
        assertEquals(0, service.printOrderDepositTicket("C04-CMD000001", List.of(),
                BigDecimal.ZERO, null));
        verify(service.hardwareService, never()).printReceipt(any());
    }

    /**
     * LC-06-03-06: the ticket of an order created at the till carries the
     * order's articles (their quantities included), the deposit perceived, the
     * VAT recap computed on that deposit and the BARCODE of the order code —
     * the one thing that reopens the order at any register of the store.
     */
    @Test
    void theOrderDepositTicketCarriesArticlesVatAndBarcode() {
        TicketPrinterService service = newService();
        assertEquals(1, service.printOrderDepositTicket("C04-CMD000001",
                List.of(new com.intermarche.pos.ui.recall.RecalledDocument.Line(
                                "301", "LAIT", new BigDecimal("2"), new BigDecimal("3.00"),
                                new BigDecimal("0.20")),
                        new com.intermarche.pos.ui.recall.RecalledDocument.Line(
                                "302", "PAIN", BigDecimal.ONE, new BigDecimal("40.00"),
                                new BigDecimal("0.055"))),
                new BigDecimal("10.00"), "0.2:1;0.055:0.21"));
        String out = captureReceipt(service);
        assertTrue(out.contains("ACOMPTE"));
        assertTrue(out.contains("C04-CMD000001"));
        assertTrue(out.contains("ARTICLES DE LA COMMANDE"));
        assertTrue(out.contains("LAIT x2"));
        assertTrue(out.contains("6,00"));
        assertTrue(out.contains("PAIN"));
        assertTrue(out.contains("40,00"));
        assertTrue(out.contains("TVA ACOMPTE"));
        assertTrue(out.contains("20,00%"));
        assertTrue(out.contains("1,00"));
        assertTrue(out.contains("5,50%"));
        assertTrue(out.contains("0,21"));
        assertTrue(out.contains("10,00"));
        assertTrue(out.contains("[[BARCODE C04-CMD000001]]"));
        verify(service.hardwareService).cutPaper();
    }

    /**
     * LC-06-03-06: a null line is skipped rather than printed as "null", a
     * line without quantity reads as one piece, and a deposit that ventilated
     * to nothing prints no VAT recap at all (the null-line, null-quantity and
     * empty-detail arms).
     */
    @Test
    void theOrderDepositTicketSkipsNullLinesAndEmptyVat() {
        TicketPrinterService service = newService();
        java.util.List<com.intermarche.pos.ui.recall.RecalledDocument.Line> lines =
                new java.util.ArrayList<>();
        lines.add(null);
        lines.add(new com.intermarche.pos.ui.recall.RecalledDocument.Line(
                "301", "LAIT", null, new BigDecimal("3.00"), new BigDecimal("0.20")));
        assertEquals(1, service.printOrderDepositTicket("C04-CMD000001", lines,
                new BigDecimal("10.00"), null));
        String out = captureReceipt(service);
        assertTrue(out.contains("LAIT"));
        assertTrue(out.contains("3,00"));
        assertFalse(out.contains("TVA ACOMPTE"));
        assertFalse(out.contains("null"));
        assertFalse(out.contains("xnull"));
    }

    /**
     * LC-06-03-08: the final ticket of a sale that settles an order states,
     * next to its own total VAT, the VAT already collected on the deposit —
     * ventilation by ventilation, from the detail the recovery carries — and
     * the settlement names itself "REPRISE ACOMPTE" in the payments section.
     */
    @Test
    void theFinalTicketStatesTheDepositVatAndNamesTheRecovery() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.payments.add(new com.intermarche.pos.domain.payment.DepositPayment(
                new BigDecimal("10.00")));
        com.intermarche.pos.domain.payment.DepositPayment acompte =
                (com.intermarche.pos.domain.payment.DepositPayment) ticket.payments.get(0);
        acompte.vatDetail = "0.2:1;0.055:0.21";
        acompte.orderCode = "C04-CMD000001";
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            String rendered = service.renderTicket(ticket, false, 0);
            assertTrue(rendered.contains("TVA DÉJÀ PERÇUE SUR L'ACOMPTE"));
            assertTrue(rendered.contains("20,00%"));
            assertTrue(rendered.contains("1,00"));
            assertTrue(rendered.contains("5,50%"));
            assertTrue(rendered.contains("0,21"));
            assertTrue(rendered.contains("REPRISE ACOMPTE"));
        }
    }

    /**
     * LC-06-03-08: a recovery without a VAT detail names itself in the
     * payments but states no ventilation (null and blank detail arms), and a
     * sale that recovers no deposit carries none of it (no DepositPayment
     * arm).
     */
    @Test
    void theFinalTicketWithoutDepositVatPrintsNoVentilation() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        com.intermarche.pos.domain.payment.DepositPayment bare =
                new com.intermarche.pos.domain.payment.DepositPayment(new BigDecimal("10.00"));
        bare.vatDetail = null;
        ticket.payments.add(bare);
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            String rendered = service.renderTicket(ticket, false, 0);
            assertTrue(rendered.contains("REPRISE ACOMPTE"));
            assertFalse(rendered.contains("TVA DÉJÀ PERÇUE SUR L'ACOMPTE"));
        }
        Ticket withBlank = ticket(0, null);
        com.intermarche.pos.domain.payment.DepositPayment blank =
                new com.intermarche.pos.domain.payment.DepositPayment(new BigDecimal("10.00"));
        blank.vatDetail = "   ";
        withBlank.payments.add(blank);
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            assertFalse(service.renderTicket(withBlank, false, 0)
                    .contains("TVA DÉJÀ PERÇUE SUR L'ACOMPTE"));
        }
        Ticket plain = ticket(0, null);
        plain.payments.add(new CashPayment(new BigDecimal("12.00"), new BigDecimal("12.00")));
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            String rendered = service.renderTicket(plain, false, 0);
            assertFalse(rendered.contains("TVA DÉJÀ PERÇUE SUR L'ACOMPTE"));
            assertFalse(rendered.contains("REPRISE ACOMPTE"));
        }
    }

    /**
     * LC-06-03-08: only the FIRST recovery of the ticket states its
     * ventilation — the break of the deposit loop — so a second recovery with
     * another detail does not print a second block.
     */
    @Test
    void theFinalTicketStatesOnlyTheFirstRecoverysVentilation() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        com.intermarche.pos.domain.payment.DepositPayment first =
                new com.intermarche.pos.domain.payment.DepositPayment(new BigDecimal("10.00"));
        first.vatDetail = "0.2:1";
        com.intermarche.pos.domain.payment.DepositPayment second =
                new com.intermarche.pos.domain.payment.DepositPayment(new BigDecimal("5.00"));
        second.vatDetail = "0.055:0.26";
        ticket.payments.add(first);
        ticket.payments.add(second);
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            String rendered = service.renderTicket(ticket, false, 0);
            assertTrue(rendered.contains("TVA DÉJÀ PERÇUE SUR L'ACOMPTE"));
            assertTrue(rendered.contains("20,00%"));
            assertFalse(rendered.contains("0,26"));
        }
    }

    /**
     * An ADMINISTERED layout replaces the deposit slip entirely, and the slip
     * a layout reads carries the order, the customer, the amount and the
     * barcode.
     */
    @Test
    @SuppressWarnings("unchecked")
    void anAdministeredLayoutReplacesTheDepositSlip() {
        TicketPrinterService service = newService();
        service.documentTemplateService = mock(DocumentTemplateService.class);
        when(service.documentTemplateService.render(
                eq(DocumentTemplate.DocumentType.DEPOSIT_RECEIPT), any()))
                .thenReturn("MISE EN PAGE MAGASIN");
        assertEquals(1, service.printDepositReceipt("AC-000045", "CMD-1", "MARTIN",
                new BigDecimal("150.00"), null));
        assertEquals("MISE EN PAGE MAGASIN", captureReceipt(service));

        Map<String, Object> data = service.depositDocumentData(" AC-000045 ", "CMD-1",
                "MARTIN", new BigDecimal("150.00"), null);
        Map<String, Object> document = (Map<String, Object>) data.get("document");
        assertEquals("AC-000045", document.get("number"));
        assertEquals("CMD-1", document.get("orderNumber"));
        assertEquals("MARTIN", document.get("customerName"));
        assertEquals("150,00", document.get("amount"));
        assertEquals("[[BARCODE AC-000045]]", document.get("barcode"));
        assertTrue(((java.util.List<?>) data.get("vatLines")).isEmpty());
    }

    /**
     * A gift-card number is printed MASKED, last four characters only: a gift
     * card is bearer money, and a full number on a receipt left in a trolley is
     * a card somebody else can spend. A number too short to mask is printed
     * whole, and an absent one prints nothing — every leg.
     */
    @Test
    void theGiftCardNumberIsMaskedDownToItsLastFour() {
        assertEquals("", TicketPrinterService.maskedCardNumber(null));
        assertEquals("", TicketPrinterService.maskedCardNumber("   "));
        assertEquals("4321", TicketPrinterService.maskedCardNumber(" 4321 "));
        assertEquals("****4321", TicketPrinterService.maskedCardNumber("9999000011114321"));
    }

    /**
     * The gift-card payment slip names what was spent and what the card still
     * carries — the one question asked at the counter (BO-03-03-11). A balance
     * the issuer did not state leaves the line out rather than printing a zero
     * nobody verified.
     */
    @Test
    void theGiftCardPaymentSlipStatesTheRemainingBalance() {
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            TicketPrinterService service = newService();
            assertEquals(1, service.printGiftCardPaymentReceipt("C04-000124",
                    "9999000011114321", new BigDecimal("30.00"), new BigDecimal("20.00")));
            String out = captureReceipt(service);
            assertTrue(out.contains("PAIEMENT CARTE CADEAU"));
            assertTrue(out.contains("****4321"));
            assertFalse(out.contains("9999000011114321"));
            assertTrue(out.contains("30,00"));
            assertTrue(out.contains("20,00"));
            verify(service.hardwareService).cutPaper();

            TicketPrinterService noBalance = newService();
            assertEquals(1, noBalance.printGiftCardPaymentReceipt("C04-000124", "4321",
                    new BigDecimal("30.00"), null));
            assertFalse(captureReceipt(noBalance).contains("SOLDE RESTANT"));
        }
    }

    /**
     * Nothing is printed without a positive amount, and an ADMINISTERED layout
     * replaces the slip entirely. The slip a layout reads carries the masked
     * number and the balance, empty when the issuer stated none.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theGiftCardPaymentSlipRefusesNothingToSayAndObeysItsLayout() {
        TicketPrinterService service = newService();
        assertEquals(0, service.printGiftCardPaymentReceipt("C04-000124", "4321", null, null));
        assertEquals(0, service.printGiftCardPaymentReceipt("C04-000124", "4321",
                BigDecimal.ZERO, null));
        verify(service.hardwareService, never()).printReceipt(any());

        TicketPrinterService administered = newService();
        administered.documentTemplateService = mock(DocumentTemplateService.class);
        when(administered.documentTemplateService.render(
                eq(DocumentTemplate.DocumentType.GIFT_CARD_PAYMENT), any()))
                .thenReturn("MISE EN PAGE MAGASIN");
        assertEquals(1, administered.printGiftCardPaymentReceipt("C04-000124",
                "9999000011114321", new BigDecimal("30.00"), new BigDecimal("20.00")));
        assertEquals("MISE EN PAGE MAGASIN", captureReceipt(administered));

        Map<String, Object> data = administered.giftCardPaymentDocumentData("C04-000124",
                "9999000011114321", new BigDecimal("30.00"), null, null, null, null, null);
        Map<String, Object> document = (Map<String, Object>) data.get("document");
        assertEquals("****4321", document.get("cardNumber"));
        assertEquals("30,00", document.get("amount"));
        assertEquals("", document.get("remainingBalance"));
        assertEquals("", document.get("balanceBefore"));
        assertEquals("", document.get("provider"));
        Map<String, Object> hub = (Map<String, Object>) administered.giftCardPaymentDocumentData("C04-000124",
                "9999000011114321", new BigDecimal("30.00"), new BigDecimal("50.00"),
                new BigDecimal("20.00"), "ILLICADO", "P7", "HUB00000007").get("document");
        assertEquals("50,00", hub.get("balanceBefore"));
        assertEquals("ILLICADO", hub.get("provider"));
        assertEquals("P7", hub.get("approval"));
        assertEquals("HUB00000007", hub.get("transaction"));
    }

    // --------------------------------------------------
    // BO-03-02-35: the slip of a cancelled settlement
    // --------------------------------------------------

    /**
     * Nothing is printed when the tender is not named or the amount is not
     * positive: a slip saying a settlement of nothing was cancelled would only
     * confuse the till's journal — every leg of the guard.
     */
    @Test
    void theCancellationSlipPrintsNothingWithoutATenderOrAnAmount() {
        TicketPrinterService service = newService();
        assertEquals(0, service.printPaymentCancellation("C04-000124", null,
                new BigDecimal("42.50")));
        assertEquals(0, service.printPaymentCancellation("C04-000124", "   ",
                new BigDecimal("42.50")));
        assertEquals(0, service.printPaymentCancellation("C04-000124", "CHEQUE", null));
        assertEquals(0, service.printPaymentCancellation("C04-000124", "CHEQUE",
                BigDecimal.ZERO));
        assertEquals(0, service.printPaymentCancellation("C04-000124", "CHEQUE",
                new BigDecimal("-1.00")));
        verify(service.hardwareService, never()).printReceipt(any());
    }

    /**
     * The slip states which tender was given back, for how much and on which
     * ticket — the three things a dispute turns on (BO-03-02-35). A ticket
     * number the register does not have prints the line empty rather than
     * "null".
     */
    @Test
    void theCancellationSlipStatesTheTenderAndTheAmount() {
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            TicketPrinterService service = newService();
            assertEquals(1, service.printPaymentCancellation("C04-000124", "  CHEQUE  ",
                    new BigDecimal("42.50")));
            String out = captureReceipt(service);
            assertTrue(out.contains("ANNULATION DE REGLEMENT"));
            assertTrue(out.contains("CHEQUE"));
            assertTrue(out.contains("42,50"));
            assertTrue(out.contains("C04-000124"));
            verify(service.hardwareService).cutPaper();

            TicketPrinterService noNumber = newService();
            assertEquals(1, noNumber.printPaymentCancellation(null, "CARTE",
                    new BigDecimal("10.00")));
            assertFalse(captureReceipt(noNumber).contains("null"));
        }
    }

    /**
     * An ADMINISTERED layout replaces the slip entirely, and the register cuts
     * the paper all the same.
     */
    @Test
    void anAdministeredLayoutReplacesTheCancellationSlip() {
        TicketPrinterService service = newService();
        service.documentTemplateService = mock(DocumentTemplateService.class);
        when(service.documentTemplateService.render(
                eq(DocumentTemplate.DocumentType.PAYMENT_CANCELLATION), any()))
                .thenReturn("MISE EN PAGE MAGASIN");
        assertEquals(1, service.printPaymentCancellation("C04-000124", "CHEQUE",
                new BigDecimal("42.50")));
        assertEquals("MISE EN PAGE MAGASIN", captureReceipt(service));
        verify(service.hardwareService).cutPaper();
    }

    /**
     * The slip a layout reads carries the ticket, the tender and the amount,
     * trimmed and formatted as the paper shows them (BO-03-02-35).
     */
    @Test
    @SuppressWarnings("unchecked")
    void theCancellationSlipALayoutReadsCarriesTheSettlement() {
        TicketPrinterService service = newService();
        Map<String, Object> data = service.paymentCancellationDocumentData(
                "C04-000124", "  CHEQUE  ", new BigDecimal("42.50"));
        Map<String, Object> document = (Map<String, Object>) data.get("document");
        assertEquals("C04-000124", document.get("number"));
        assertEquals("CHEQUE", document.get("tenderLabel"));
        assertEquals("42,50", document.get("amount"));
        assertNotNull(data.get("date"));
        assertNotNull(data.get("time"));
        assertEquals("", data.get("terminal"));
        // A ticket the register cannot name renders empty, never "null".
        assertEquals("", ((Map<String, Object>) service.paymentCancellationDocumentData(
                null, "CARTE", new BigDecimal("1.00")).get("document")).get("number"));
    }

    // --------------------------------------------------
    // BO-03-10-05: the unknown-article slip
    // --------------------------------------------------

    /**
     * An island that asks for no slip prints none, and a blank or null code
     * prints none either — all three legs of the guard.
     */
    @Test
    void theUnknownArticleSlipPrintsNothingWhenNotAsked() {
        TicketPrinterService service = newService();
        assertEquals(0, service.printUnknownArticleTicket("2912345678901"));
        when(service.posSettingsService.islandUnknownArticleTicket()).thenReturn(true);
        assertEquals(0, service.printUnknownArticleTicket(null));
        assertEquals(0, service.printUnknownArticleTicket("   "));
        verify(service.hardwareService, never()).printReceipt(any());
    }

    /**
     * Where the island asks for it, the slip states the code as it was READ and
     * the moment — the paper a department manager walks back to the referential
     * with (BO-03-10-05).
     */
    @Test
    void theUnknownArticleSlipStatesTheCodeItRead() {
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            TicketPrinterService service = newService();
            when(service.posSettingsService.islandUnknownArticleTicket()).thenReturn(true);
            assertEquals(1, service.printUnknownArticleTicket("  2912345678901  "));
            String out = captureReceipt(service);
            assertTrue(out.contains("ARTICLE INCONNU"));
            assertTrue(out.contains("CODE LU"));
            // Trimmed: the spaces a scanner adds are not part of the code.
            assertTrue(out.contains("2912345678901"));
        }
    }

    /**
     * An ADMINISTERED layout replaces the slip entirely, and the register cuts
     * the paper all the same.
     */
    @Test
    void anAdministeredLayoutReplacesTheUnknownArticleSlip() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.islandUnknownArticleTicket()).thenReturn(true);
        service.documentTemplateService = mock(DocumentTemplateService.class);
        when(service.documentTemplateService.render(
                eq(DocumentTemplate.DocumentType.UNKNOWN_ARTICLE_TICKET), any()))
                .thenReturn("MISE EN PAGE MAGASIN");
        assertEquals(1, service.printUnknownArticleTicket("2912345678901"));
        assertEquals("MISE EN PAGE MAGASIN", captureReceipt(service));
    }

    /**
     * The slip a layout reads carries the code and the moment, and names no
     * article: a code nobody recognized is a string, not an article.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theUnknownArticleSlipALayoutReadsCarriesTheCode() {
        TicketPrinterService service = newService();
        Map<String, Object> data = service.unknownArticleDocumentData(" 2912345678901 ");
        assertEquals("2912345678901",
                ((Map<String, Object>) data.get("document")).get("code"));
        assertNotNull(data.get("date"));
        assertNotNull(data.get("time"));
        assertEquals("", data.get("terminal"));
    }

    // --------------------------------------------------
    // BO-03-03-07: the resume ticket
    // --------------------------------------------------

    /**
     * A store that does not administer the resume ticket prints none, and a
     * null draft prints none either (both legs of the guard).
     */
    @Test
    void theResumeTicketPrintsNothingWhenNotAdministered() {
        TicketPrinterService service = newService();
        assertEquals(0, service.printResumeTicket(ticket(0, null), "C03-000087"));
        when(service.posSettingsService.parkingPrintResume()).thenReturn(true);
        assertEquals(0, service.printResumeTicket(null, "C03-000087"));
        verify(service.hardwareService, never()).printReceipt(any());
    }

    /**
     * The built-in resume ticket states its title, the printing date and BOTH
     * numbers — the ticket the sale goes on with and the one the parked paper
     * carried (BO-03-03-07).
     */
    @Test
    void theResumeTicketStatesBothNumbers() {
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            TicketPrinterService service = newService();
            when(service.posSettingsService.parkingPrintResume()).thenReturn(true);
            assertEquals(1, service.printResumeTicket(ticket(0, null), "C03-000087"));
            String out = captureReceipt(service);
            assertTrue(out.contains("TICKET DE REPRISE"));
            assertTrue(out.contains("C04-00000001"));
            assertTrue(out.contains("C03-000087"));
        }
    }

    /**
     * An ADMINISTERED layout replaces the resume ticket entirely.
     */
    @Test
    void anAdministeredLayoutReplacesTheResumeTicket() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.parkingPrintResume()).thenReturn(true);
        service.documentTemplateService = mock(DocumentTemplateService.class);
        when(service.documentTemplateService.render(
                eq(DocumentTemplate.DocumentType.PARKED_TICKET_RESUME), any()))
                .thenReturn("MISE EN PAGE MAGASIN");
        assertEquals(1, service.printResumeTicket(ticket(0, null), "C03-000087"));
        assertEquals("MISE EN PAGE MAGASIN", captureReceipt(service));
    }

    /**
     * The resume ticket a layout reads names both numbers, and a resume whose
     * original number is unknown renders it as the empty field rather than the
     * word "null" (the null arm of {@code safe}).
     */
    @Test
    @SuppressWarnings("unchecked")
    void theResumeTicketALayoutReadsNamesBothNumbers() {
        TicketPrinterService service = newService();
        Map<String, Object> data = service.resumeDocumentData(ticket(0, null), "C03-000087");
        Map<String, Object> document = (Map<String, Object>) data.get("document");
        assertEquals("C04-00000001", document.get("number"));
        assertEquals("C03-000087", document.get("originalNumber"));
        assertEquals("Jean Dupont", data.get("operator"));
        Map<String, Object> bare = (Map<String, Object>)
                service.resumeDocumentData(ticket(0, null), null).get("document");
        assertEquals("", bare.get("originalNumber"));
    }

    /**
     * BO-10-03-05 / BO-10-03-12: a shop that named a gabarit for the
     * holder-online case gets THAT zone on the paper, and the built-in one
     * stands down entirely — the card number the built-in zone prints is absent
     * from the receipt, which is how one tells the two apart.
     */
    @Test
    void printTicketPrintsTheAdministeredLoyaltyZoneInsteadOfTheBuiltInOne() {
        TicketPrinterService service = newService();
        service.documentTemplateService = mock(DocumentTemplateService.class);
        when(service.posSettingsService.fidelityPrintCardNumber()).thenReturn(true);
        when(service.posSettingsService.fidelityAdvantageGrouping()).thenReturn("TYPE");
        when(service.posSettingsService.fidelityTemplateCode(true, false, false))
                .thenReturn("FID-ON");
        DocumentTemplate gabarit = new DocumentTemplate();
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        freeze(ticket, new BigDecimal("1.03"), "SOCLE");
        // BO-10-02-33: the frozen pots stand down with the built-in zone.
        ticket.fidelityPotBalances = "Cagnotte\t12.50";
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedStatic<DocumentTemplate> gabarits = mockStatic(DocumentTemplate.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<>());
            gabarits.when(() -> DocumentTemplate.findByCode("FID-ON")).thenReturn(gabarit);
            when(service.documentTemplateService.renderTemplate(eq(gabarit), anyMap()))
                    .thenReturn("ZONE FIDELITE ADMINISTREE");
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.contains("ZONE FIDELITE ADMINISTREE"));
            assertFalse(out.contains("2990000000019"));
            assertFalse(out.contains("SOLDES DES CAGNOTTES"));
        }
    }

    /**
     * BO-10-03-21: the grouped case is asked for — the advantages are grouped
     * by category — and the shop named no gabarit at all. Nothing changes: the
     * built-in zone prints, card number included.
     */
    @Test
    void printTicketKeepsTheBuiltInLoyaltyZoneWhenNoGabaritIsAdministered() {
        TicketPrinterService service = newService();
        service.documentTemplateService = mock(DocumentTemplateService.class);
        when(service.posSettingsService.fidelityPrintCardNumber()).thenReturn(true);
        when(service.posSettingsService.fidelityAdvantageGrouping()).thenReturn("CATEGORY");
        when(service.posSettingsService.fidelityTemplateCode(true, false, true))
                .thenReturn(null);
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        freeze(ticket, new BigDecimal("1.03"), "SOCLE");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedStatic<DocumentTemplate> gabarits = mockStatic(DocumentTemplate.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<>());
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.contains("2990000000019"));
            verify(service.documentTemplateService, never())
                    .renderTemplate(any(DocumentTemplate.class), anyMap());
        }
    }

    /**
     * BO-10-03-05: a gabarit named but NOT ARRIVED on this register — or one
     * that will not compile — renders nothing, and the built-in zone takes
     * over. A layout nobody can render must not cost the customer a ticket.
     */
    @Test
    void printTicketFallsBackWhenTheNamedLoyaltyGabaritCannotRender() {
        TicketPrinterService service = newService();
        service.documentTemplateService = mock(DocumentTemplateService.class);
        when(service.posSettingsService.fidelityPrintCardNumber()).thenReturn(true);
        when(service.posSettingsService.fidelityAdvantageGrouping()).thenReturn("TYPE");
        when(service.posSettingsService.fidelityTemplateCode(true, false, false))
                .thenReturn("FID-ABSENT");
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        freeze(ticket, new BigDecimal("1.03"), "SOCLE");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedStatic<DocumentTemplate> gabarits = mockStatic(DocumentTemplate.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<>());
            gabarits.when(() -> DocumentTemplate.findByCode("FID-ABSENT")).thenReturn(null);
            when(service.documentTemplateService.renderTemplate(isNull(), anyMap()))
                    .thenReturn(null);
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.contains("2990000000019"));
        }
    }

    /**
     * BO-10-07-06: the symbol goes BEFORE the amount when the shop administers
     * the left, and the amount keeps one space between the two.
     */
    @Test
    void printTicketPutsTheCurrencySymbolOnTheAdministeredSide() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.currencySymbol()).thenReturn("EUR");
        when(service.posSettingsService.currencySymbolPosition()).thenReturn("left");
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<>());
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.contains("EUR 2,00"));
            assertFalse(out.contains("2,00 EUR"));
        }
    }

    /**
     * BO-10-07-06: an unknown position is the right, which is the default the
     * register has always printed.
     */
    @Test
    void printTicketFallsBackToTheRightForAnUnknownSymbolPosition() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.currencySymbolPosition()).thenReturn("MILIEU");
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<>());
            service.printTicket(1L);
            assertTrue(captureReceipt(service).contains("2,00 E"));
        }
    }

    /**
     * BO-10-07-04: a shop whose paper is already libellé prints bare amounts —
     * and bare means bare, with no trailing space where the symbol used to be.
     */
    @Test
    void printTicketPrintsBareAmountsWhenTheSymbolIsSwitchedOff() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.currencySymbolPrinted()).thenReturn(false);
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<>());
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.contains("2,00"));
            assertFalse(out.contains("2,00 E"));
        }
    }

    /**
     * BO-10-07-05: a BLANK symbol is the absence of a symbol, not a symbol made
     * of spaces — the amount is printed nude and the separating space with it.
     */
    @Test
    void printTicketPrintsBareAmountsForABlankSymbol() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.currencySymbol()).thenReturn("   ");
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<>());
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.contains("2,00"));
            assertFalse(out.contains("2,00 E"));
        }
    }

    /**
     * BO-10-02-20: the second code rattached to the EAN prints under its
     * article when the shop asks for it, and not otherwise.
     */
    @Test
    void printTicketPrintsTheSecondCodeUnderTheLineWhenAsked() {
        TicketPrinterService printing = newService();
        when(printing.posSettingsService.secondCodePrinted()).thenReturn(true);
        Ticket ticket = ticket(0, null);
        TicketLine carried = line("U1", "CARTE CADEAU", "1", "20.00", "20.00");
        carried.secondCode = "DEMAT-4242";
        ticket.lines.add(carried);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<>());
            printing.printTicket(1L);
            assertTrue(captureReceipt(printing).contains("DEMAT-4242"));
        }

        TicketPrinterService silent = newService();
        Ticket other = ticket(0, null);
        TicketLine same = line("U1", "CARTE CADEAU", "1", "20.00", "20.00");
        same.secondCode = "DEMAT-4242";
        other.lines.add(same);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(other);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<>());
            silent.printTicket(1L);
            assertFalse(captureReceipt(silent).contains("DEMAT-4242"));
        }
    }

    /**
     * BO-10-02-20: an article that carries no second code prints no indented
     * empty line, even where the shop asked for the code.
     */
    @Test
    void printTicketPrintsNothingForAnArticleWithoutASecondCode() {
        TicketPrinterService printing = newService();
        when(printing.posSettingsService.secondCodePrinted()).thenReturn(true);
        Ticket ticket = ticket(0, null);
        TicketLine bare = line("U1", "PAIN", "1", "2.00", "2.00");
        bare.secondCode = "  ";
        ticket.lines.add(bare);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<>());
            printing.printTicket(1L);
            assertFalse(captureReceipt(printing).contains("\n    \n"));
        }
    }

    /**
     * BO-10-07-10: the article count sits beside the amount to pay, on a line
     * of its own, or nowhere — and an unknown placement is nowhere, which is
     * what every ticket printed before the key existed.
     */
    @Test
    void printTicketStatesTheArticleCountWhereTheShopAsks() {
        TicketPrinterService beside = newService();
        when(beside.posSettingsService.ticketItemCountPrint()).thenReturn("total_line");
        Ticket counted = ticket(0, null);
        counted.itemCount = 3;
        counted.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(counted);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<>());
            beside.printTicket(1L);
            assertTrue(captureReceipt(beside).contains("TOTAL TTC (3)"));
        }

        TicketPrinterService own = newService();
        when(own.posSettingsService.ticketItemCountPrint()).thenReturn("OWN_LINE");
        Ticket alone = ticket(0, null);
        alone.itemCount = 3;
        alone.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(alone);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<>());
            own.printTicket(1L);
            String out = captureReceipt(own);
            assertTrue(out.contains("NOMBRE D'ARTICLES"));
            assertFalse(out.contains("TOTAL TTC (3)"));
        }

        TicketPrinterService nowhere = newService();
        when(nowhere.posSettingsService.ticketItemCountPrint()).thenReturn("AILLEURS");
        Ticket silent = ticket(0, null);
        silent.itemCount = 3;
        silent.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(silent);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<>());
            nowhere.printTicket(1L);
            String out = captureReceipt(nowhere);
            assertFalse(out.contains("NOMBRE D'ARTICLES"));
            assertFalse(out.contains("TOTAL TTC (3)"));
        }
    }

    /**
     * BO-11-01-20: with the vignettes managed, the ticket states the vignettes
     * the sale earns under the administered formula; a sale that earns none,
     * or a store that does not manage them, prints no vignette line.
     */
    @Test
    void printTicketStatesTheVignettesTheSaleEarns() {
        for (String[] scenario : new String[][] {{"true", "25.00", "4"}, {"true", "5.00", null},
                {"false", "25.00", null}}) {
            TicketPrinterService printing = newService();
            when(printing.posSettingsService.vignetteEnabled()).thenReturn(Boolean.parseBoolean(scenario[0]));
            when(printing.posSettingsService.vignetteAmountStep()).thenReturn(new BigDecimal("10.00"));
            when(printing.posSettingsService.vignettePerStep()).thenReturn(2);
            Ticket sale = ticket(0, null);
            sale.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
            sale.totalIncludingTax = new BigDecimal(scenario[1]);
            try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
                silenceCatalog(mocked);
                mocked.when(() -> Ticket.findById(1L)).thenReturn(sale);
                mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                        .thenReturn(new ArrayList<>());
                printing.printTicket(1L);
                String out = captureReceipt(printing);
                if (scenario[2] == null) {
                    assertFalse(out.contains("VIGNETTES"));
                } else {
                    assertTrue(out.lines().anyMatch(row -> row.startsWith("VIGNETTES") && row.trim().endsWith("4")));
                }
            }
        }
    }

    /**
     * BO-10-07-11: the unknown-article slip carries the read code as a barcode
     * directive when the shop asks for it, and only in clear otherwise.
     */
    @Test
    void printUnknownArticleTicketCarriesTheBarcodeWhenAsked() {
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            TicketPrinterService encoded = newService();
            when(encoded.posSettingsService.islandUnknownArticleTicket()).thenReturn(true);
            when(encoded.posSettingsService.unknownArticleBarcodePrinted()).thenReturn(true);
            assertEquals(1, encoded.printUnknownArticleTicket("3017620422003"));
            assertTrue(captureReceipt(encoded).contains("[[BARCODE 3017620422003]]"));

            TicketPrinterService plain = newService();
            when(plain.posSettingsService.islandUnknownArticleTicket()).thenReturn(true);
            assertEquals(1, plain.printUnknownArticleTicket("3017620422003"));
            String out = captureReceipt(plain);
            assertTrue(out.contains("3017620422003"));
            assertFalse(out.contains("[[BARCODE"));
        }
    }

    /**
     * BO-10-05-04: the tare the scale deducted prints under the weighed line
     * where the shop asks for it, never for a line that carries none, and never
     * when the shop did not ask.
     */
    @Test
    void printTicketStatesTheAppliedTareWhenAsked() {
        TicketPrinterService stating = newService();
        when(stating.posSettingsService.tarePrinted()).thenReturn(true);
        Ticket weighed = ticket(0, null);
        TicketLine weighedLine = line("U1", "TOMATES", "1.480", "3.95", "5.85");
        weighedLine.appliedTareKg = new java.math.BigDecimal("0.035");
        weighed.lines.add(weighedLine);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(weighed);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<>());
            stating.printTicket(1L);
            assertTrue(captureReceipt(stating).contains("TARE 0,035 kg"));
        }

        TicketPrinterService zero = newService();
        when(zero.posSettingsService.tarePrinted()).thenReturn(true);
        Ticket none = ticket(0, null);
        TicketLine noTare = line("U1", "PAIN", "1", "2.00", "2.00");
        noTare.appliedTareKg = java.math.BigDecimal.ZERO;
        none.lines.add(noTare);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(none);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<>());
            zero.printTicket(1L);
            assertFalse(captureReceipt(zero).contains("TARE"));
        }

        TicketPrinterService silent = newService();
        Ticket other = ticket(0, null);
        TicketLine carried = line("U1", "TOMATES", "1.480", "3.95", "5.85");
        carried.appliedTareKg = new java.math.BigDecimal("0.035");
        other.lines.add(carried);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(other);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<>());
            silent.printTicket(1L);
            assertFalse(captureReceipt(silent).contains("TARE"));
        }
    }

    /**
     * BO-10-06-03: the certification number goes on the SALE document and on
     * the RETURN document alike, and a blank one prints nothing at all — the
     * receipts of every country that requires none are untouched.
     */
    @Test
    void theFiscalCertificateGoesOnSaleAndReturnDocuments() {
        TicketPrinterService certified = newService();
        when(certified.posSettingsService.fiscalCertificateNumber()).thenReturn("  0000/AT  ");
        Ticket sale = ticket(0, null);
        sale.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(sale);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<>());
            certified.printTicket(1L);
            assertTrue(captureReceipt(certified).contains("CERTIFICAT N° 0000/AT"));
        }

        TicketPrinterService returned = newService();
        when(returned.posSettingsService.fiscalCertificateNumber()).thenReturn("0000/AT");
        Refund refund = refund(2L, null, refundLine("YAOURT", "1", "6.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Refund.findById(5L)).thenReturn(refund);
            mocked.when(() -> Ticket.findById(2L)).thenReturn(null);
            returned.printRefund(5L);
            assertTrue(captureReceipt(returned).contains("CERTIFICAT N° 0000/AT"));
        }

        TicketPrinterService plain = newService();
        when(plain.posSettingsService.fiscalCertificateNumber()).thenReturn("   ");
        Ticket other = ticket(0, null);
        other.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(other);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<>());
            plain.printTicket(1L);
            assertFalse(captureReceipt(plain).contains("CERTIFICAT"));
        }
    }

    /**
     * BO-10-07-14: the amount due carries the enlargement directive where the
     * shop asks for it, and the directive wraps the ALIGNED line — the markers
     * are not characters the alignment counted.
     */
    @Test
    void printTicketEnlargesTheAmountDueWhenAsked() {
        TicketPrinterService enlarged = newService();
        when(enlarged.posSettingsService.dueAmountLarge()).thenReturn(true);
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<>());
            enlarged.printTicket(1L);
            String out = captureReceipt(enlarged);
            assertTrue(out.contains("[[DOUBLE]]TOTAL TTC"));
            assertTrue(out.contains("[[/DOUBLE]]"));
        }

        TicketPrinterService plain = newService();
        Ticket other = ticket(0, null);
        other.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(other);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<>());
            plain.printTicket(1L);
            assertFalse(captureReceipt(plain).contains("[[DOUBLE]]"));
        }
    }

    /**
     * BO-10-06-06: the eco-participation prints under each article that carries
     * one, or once for the whole sale, or nowhere — and an article without one
     * never prints a zero nobody owes.
     */
    @Test
    void printTicketStatesTheEcoParticipationWhereTheShopAsks() {
        Product bearing = new Product();
        bearing.attributes.put("ECO_TAX", "0.20");

        TicketPrinterService perLine = newService();
        when(perLine.posSettingsService.ecoTaxPrint()).thenReturn("line");
        Ticket detailed = ticket(0, null);
        TicketLine taxed = line("U1", "TELEVISEUR", "2", "299.00", "598.00");
        taxed.product = bearing;
        taxed.quantity = new java.math.BigDecimal("2");
        detailed.lines.add(taxed);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(detailed);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<>());
            perLine.printTicket(1L);
            assertTrue(captureReceipt(perLine).contains("DONT ECO-PARTICIPATION"));
        }

        TicketPrinterService summed = newService();
        when(summed.posSettingsService.ecoTaxPrint()).thenReturn("TOTAL");
        Ticket whole = ticket(0, null);
        TicketLine alsoTaxed = line("U1", "TELEVISEUR", "1", "299.00", "299.00");
        alsoTaxed.product = bearing;
        alsoTaxed.quantity = java.math.BigDecimal.ONE;
        whole.lines.add(alsoTaxed);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(whole);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<>());
            summed.printTicket(1L);
            String out = captureReceipt(summed);
            assertTrue(out.contains("DONT ECO-PARTICIPATION"));
            assertTrue(out.indexOf("DONT ECO-PARTICIPATION") < out.indexOf("REGLEMENT"));
        }

        TicketPrinterService untaxed = newService();
        when(untaxed.posSettingsService.ecoTaxPrint()).thenReturn("LINE");
        Ticket plain = ticket(0, null);
        plain.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(plain);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<>());
            untaxed.printTicket(1L);
            assertFalse(captureReceipt(untaxed).contains("ECO-PARTICIPATION"));
        }

        TicketPrinterService silent = newService();
        Ticket other = ticket(0, null);
        TicketLine carried = line("U1", "TELEVISEUR", "1", "299.00", "299.00");
        carried.product = bearing;
        carried.quantity = java.math.BigDecimal.ONE;
        other.lines.add(carried);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(other);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<>());
            silent.printTicket(1L);
            assertFalse(captureReceipt(silent).contains("ECO-PARTICIPATION"));
        }
    }
    /** LC-08: null line components never invent a monetary discount or an eco-tax quantity. */
    @Test
    void coverageNullableLineAmounts() throws Exception {
        TicketPrinterService service = newService();
        Class<?>[] types = {TicketLine.class};
        assertEquals(BigDecimal.ZERO, invokePrinterHelper(service, "lineDiscount", types, (Object) null));
        TicketLine line = new TicketLine();
        line.originalUnitPrice = BigDecimal.TEN;
        line.unitPrice = null;
        assertEquals(BigDecimal.ZERO, invokePrinterHelper(service, "lineDiscount", types, line));
        line.unitPrice = BigDecimal.ONE;
        line.quantity = null;
        assertEquals(BigDecimal.ZERO, invokePrinterHelper(service, "lineDiscount", types, line));
        assertEquals(BigDecimal.ZERO, invokePrinterHelper(service, "ecoTaxOf",
                new Class<?>[]{TicketLine.class, com.intermarche.pos.domain.catalog.attribute.EcoTaxDefinition.class},
                line, com.intermarche.pos.domain.catalog.attribute.EcoTaxDefinition.parse(null).get(0)));
    }

    /**
     * LC-13-06-11: a bilingual mention too wide for the paper is centred on two
     * lines, split at its " / "; a mention that fits, one with no separator, one
     * starting with it and a missing one are centred as before.
     */
    @Test
    void aBilingualMentionTooWideIsCentredOnTwoLines() throws Exception {
        TicketPrinterService service = newService();
        Class<?>[] types = {String.class, int.class};
        com.intermarche.pos.service.DocumentLayout layout = new com.intermarche.pos.service.DocumentLayout(42);
        assertEquals(layout.centre("DANK VOOR UW BEZOEK /") + "\n" + layout.centre("MERCI DE VOTRE VISITE"),
                invokePrinterHelper(service, "center", types, "DANK VOOR UW BEZOEK / MERCI DE VOTRE VISITE", 42));
        assertEquals(layout.centre("TOT BINNENKORT / A BIENTOT"),
                invokePrinterHelper(service, "center", types, "TOT BINNENKORT / A BIENTOT", 42));
        String noSeparator = "X".repeat(43);
        assertEquals(noSeparator, invokePrinterHelper(service, "center", types, noSeparator, 42));
        String leading = " / " + "Y".repeat(41);
        assertEquals(leading, invokePrinterHelper(service, "center", types, leading, 42));
        assertEquals(layout.centre(null),
                invokePrinterHelper(service, "center", types, null, 42));
    }

    /** BO-03-04: administered titles override the default and an all-empty banner has no paper lines. */
    @Test
    void coverageAdministeredTitleAndEmptyBanner() throws Exception {
        TicketPrinterService service = newService();
        var type = com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.SALE_RECEIPT;
        try (var templates = mockStatic(com.intermarche.pos.domain.setting.DocumentTemplate.class)) {
            templates.when(() -> com.intermarche.pos.domain.setting.DocumentTemplate.titleFor(type)).thenReturn("ADMIN TITLE");
            assertEquals("ADMIN TITLE", invokePrinterHelper(service, "documentTitle",
                    new Class<?>[]{type.getClass(), String.class}, type, "DEFAULT"));
        }
        service.documentTemplateService = mock(com.intermarche.pos.service.DocumentTemplateService.class);
        when(service.documentTemplateService.render(eq(type), any())).thenReturn("\n \n");
        assertEquals(java.util.List.of(), invokePrinterHelper(service, "administeredBanner",
                new Class<?>[]{type.getClass(), java.util.Map.class}, type, java.util.Map.of()));
    }

    /** BO-03-02: nullable tender sets and null/duplicate keys never cause repeated slip printing. */
    @Test
    void coverageTenderSlipNullAndDuplicateKeys() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.payments = null;
        assertEquals(0, service.printTenderSlips(ticket, true));
        ticket.payments = new ArrayList<>();
        var payment = mock(com.intermarche.pos.domain.payment.TicketPayment.class);
        ticket.payments.add(payment);
        assertEquals(0, service.printTenderSlips(ticket, true));
        when(payment.getMethodKey()).thenReturn("CASH");
        ticket.payments.add(payment);
        try (var tenders = mockStatic(com.intermarche.pos.domain.payment.TenderDefinition.class)) {
            assertEquals(0, service.printTenderSlips(ticket, true));
            tenders.verify(() -> com.intermarche.pos.domain.payment.TenderDefinition.findByCode("CASH"), times(1));
        }
    }

    /** BO-03-02: every missing template/rendering boundary refuses a slip without losing the settlement. */
    @Test
    void coverageTenderTemplateAndRenderGuards() throws Exception {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        var tender = new com.intermarche.pos.domain.payment.TenderDefinition();
        tender.printForced = true;
        Class<?>[] types = {Ticket.class, String.class, boolean.class};
        try (var tenders = mockStatic(com.intermarche.pos.domain.payment.TenderDefinition.class);
             var templates = mockStatic(com.intermarche.pos.domain.setting.DocumentTemplate.class)) {
            tenders.when(() -> com.intermarche.pos.domain.payment.TenderDefinition.findByCode("CASH")).thenReturn(tender);
            tender.documentTemplateCode = " ";
            assertEquals(0, invokePrinterHelper(service, "printOneTenderSlip", types, ticket, "CASH", true));
            tender.documentTemplateCode = "DOC";
            var template = mock(com.intermarche.pos.domain.setting.DocumentTemplate.class);
            templates.when(() -> com.intermarche.pos.domain.setting.DocumentTemplate.findByCode("DOC")).thenReturn(template);
            assertEquals(0, invokePrinterHelper(service, "printOneTenderSlip", types, ticket, "CASH", true));
            when(template.isRenderable()).thenReturn(true);
            assertEquals(0, invokePrinterHelper(service, "printOneTenderSlip", types, ticket, "CASH", true));
            service.documentTemplateService = mock(com.intermarche.pos.service.DocumentTemplateService.class);
            assertEquals(0, invokePrinterHelper(service, "printOneTenderSlip", types, ticket, "CASH", true));
            tender.printDuplicate = true;
            when(service.documentTemplateService.renderTemplate(eq(template), any())).thenReturn("BODY", (String) null);
            assertEquals(1, invokePrinterHelper(service, "printOneTenderSlip", types, ticket, "CASH", true));
            verify(service.hardwareService).printReceipt("BODY");
        }
    }

    /** LC-08: a rendered sale or return may deliberately keep the paper attached without a cut. */
    @Test
    void coveragePrintedDocumentsWithoutCut() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        Refund refund = refund();
        try (var base = mockStatic(PanacheEntityBase.class);
             var rates = mockStatic(com.intermarche.pos.domain.catalog.VatRate.class)) {
            base.when(() -> PanacheEntityBase.findById(1L)).thenReturn(ticket);
            service.printTicket(1L, false);
            base.when(() -> PanacheEntityBase.findById(2L)).thenReturn(refund);
            service.printRefund(2L, false);
        }
        verify(service.hardwareService, never()).cutPaper();
    }

    /** BO-03: a missing cashier/date and blank card number yield empty document fields, not literal nulls. */
    @Test
    void coverageDocumentDataMissingIdentities() throws Exception {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.cashier = null;
        ticket.creationDate = null;
        ticket.fidelityCard = " ";
        var exit = service.exitDocumentData(ticket, java.util.List.of());
        assertEquals("", exit.get("operator"));
        assertEquals("", exit.get("date"));
        assertEquals("", exit.get("time"));
        assertEquals("", service.resumeDocumentData(ticket, "OLD").get("operator"));
        assertTrue(invokePrinterHelper(service, "fidelityData", new Class<?>[]{Ticket.class}, ticket) instanceof java.util.Map);
    }

    /** BO-03: an invalid advantage visual and absent barcode settings preserve printable text without extra directives. */
    @Test
    void coverageInvalidAdvantagePictureAndBlankPrefix() throws Exception {
        TicketPrinterService service = newService();
        service.storeImageService = mock(com.intermarche.pos.service.StoreImageService.class);
        when(service.storeImageService.advantageVisual(any(), any())).thenReturn("INVALID IMAGE");
        service.posSettingsService = null;
        var line = new com.intermarche.pos.domain.sale.TicketFidelityLine();
        assertEquals("", invokePrinterHelper(service, "advantageVisual", new Class<?>[]{line.getClass()}, line));
        service = newService();
        when(service.posSettingsService.ticketBarcodePrefix()).thenReturn(" ");
        assertEquals("123", invokePrinterHelper(service, "ticketBarcode", new Class<?>[]{String.class}, "123"));
        when(service.posSettingsService.creditNotePrintExpiry()).thenReturn(true);
        assertNull(invokePrinterHelper(service, "creditNoteExpiry", new Class<?>[]{String.class}, " "));
    }

    /** Invokes a defensive rendering branch without introducing a CDI or database container.
     * @param service printer under test
     * @param name helper name
     * @param types declared parameter types
     * @param arguments helper arguments
     * @return helper result
     * @throws Exception when reflection fails
     */
    private Object invokePrinterHelper(TicketPrinterService service, String name, Class<?>[] types, Object... arguments) throws Exception {
        var method = TicketPrinterService.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(service, arguments);
    }

    /** BO-03: technical slips retain the terminal identifier whenever numbering is injected. */
    @Test
    void technicalSlipsIncludeTheTerminalWhenAvailable() {
        for (int kind = 0; kind < 4; kind++) {
            TicketPrinterService service = newService();
            service.ticketNumberService = mock(com.intermarche.pos.service.TicketNumberService.class);
            when(service.ticketNumberService.getTerminalId()).thenReturn("C04");
            try (var catalog = mockStatic(PanacheEntityBase.class)) {
                silenceCatalog(catalog);
            if (kind == 0) {
                when(service.posSettingsService.islandUnknownArticleTicket()).thenReturn(true);
                assertEquals(1, service.printUnknownArticleTicket("12345678"));
            } else if (kind == 1) {
                assertEquals(1, service.printPaymentCancellation("T1", "CASH", BigDecimal.ONE));
            } else if (kind == 2) {
                assertEquals(1, service.printDepositReceipt("D1", "O1", "CUSTOMER", BigDecimal.ONE, null));
            } else {
                assertEquals(1, service.printGiftCardPaymentReceipt("T1", "12345678", BigDecimal.ONE, null));
            }
            assertTrue(captureReceipt(service).contains("C04"));
            }
        }
    }

    /** LC-06-03-06: an order deposit tolerates absent lines, numbering and unit prices. */
    @Test
    void orderDepositRendersMissingOptionalLineData() {
        TicketPrinterService service = newService();
        assertEquals(1, service.printOrderDepositTicket("O1", null, BigDecimal.ONE, null));
        assertTrue(captureReceipt(service).contains("O1"));
        service = newService();
        service.ticketNumberService = mock(com.intermarche.pos.service.TicketNumberService.class);
        when(service.ticketNumberService.getTerminalId()).thenReturn("C04");
        var item = new com.intermarche.pos.ui.recall.RecalledDocument.Line("EAN", "ARTICLE", BigDecimal.ONE, null, null);
        assertEquals(1, service.printOrderDepositTicket("O2", java.util.List.of(item), BigDecimal.ONE, null));
        String paper = captureReceipt(service);
        assertTrue(paper.contains("C04"));
        assertTrue(paper.contains("ARTICLE"));
        assertTrue(paper.contains("0,00"));
    }

    /** BO-03/BO-10: null settings, note identifiers and quantities keep their documented fallbacks. */
    @Test
    void renderingFallbacksDoNotRequireOptionalData() throws Exception {
        TicketPrinterService service = newService();
        service.posSettingsService = null;
        assertEquals("", invokePrinterHelper(service, "ticketBarcode", new Class<?>[]{String.class}, (Object) null));
        service = newService();
        when(service.posSettingsService.creditNotePrintExpiry()).thenReturn(true);
        assertNull(invokePrinterHelper(service, "creditNoteExpiry", new Class<?>[]{String.class}, (Object) null));
        service.documentTemplateService = mock(com.intermarche.pos.service.DocumentTemplateService.class);
        Ticket ticket = ticket(0, null);
        ticket.fidelityCard = " ";
        assertNull(invokePrinterHelper(service, "administeredLoyaltyZone", new Class<?>[]{Ticket.class, java.util.Map.class}, ticket, java.util.Map.of()));
        Product product = new Product();
        product.attributes.put("ECO_TAX", "0.20");
        TicketLine item = new TicketLine();
        item.product = product;
        item.quantity = null;
        assertEquals(new BigDecimal("0.20"), invokePrinterHelper(service, "ecoTaxOf",
                new Class<?>[]{TicketLine.class, com.intermarche.pos.domain.catalog.attribute.EcoTaxDefinition.class},
                item, com.intermarche.pos.domain.catalog.attribute.EcoTaxDefinition.parse(null).get(0)));
    }

    /** BO-03-03: an administered refund prints its body without cutting when the caller defers the cut. */
    @Test
    void administeredRefundHonoursDeferredCut() {
        TicketPrinterService service = newService();
        service.documentTemplateService = mock(com.intermarche.pos.service.DocumentTemplateService.class);
        when(service.documentTemplateService.render(eq(com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.REFUND_RECEIPT), any())).thenReturn("REFUND BODY");
        try (var base = mockStatic(PanacheEntityBase.class)) {
            base.when(() -> PanacheEntityBase.findById(2L)).thenReturn(refund());
            base.when(() -> PanacheEntityBase.findById(1L)).thenReturn(ticket(0, null));
            service.printRefund(2L, false);
        }
        verify(service.hardwareService).printReceipt("REFUND BODY");
        verify(service.hardwareService, never()).cutPaper();
    }

    /** BO-03-03-36: missing totals sort as zero and do not displace the most expensive article. */
    @Test
    void dearestLinesTreatMissingTotalsAsZero() throws Exception {
        TicketPrinterService service = newService();
        when(service.posSettingsService.exitTicketArticles()).thenReturn(10);
        Ticket ticket = ticket(0, null);
        TicketLine zero = line("A", "ZERO", "1", "1", "1");
        zero.totalPrice = null;
        TicketLine expensive = line("B", "TOP", "1", "10", "10");
        TicketLine anotherZero = line("C", "ZERO2", "1", "1", "1");
        anotherZero.totalPrice = null;
        ticket.lines.addAll(java.util.List.of(zero, expensive, anotherZero));
        assertEquals(java.util.List.of(expensive, zero, anotherZero), invokePrinterHelper(service, "dearestLines", new Class<?>[]{Ticket.class}, ticket));
    }

    /** LC-08/BO-10: zero tare, blank secondary code and malformed deposit VAT never print invented details. */
    @Test
    void saleReceiptSkipsEmptyOptionalDetailsAndZeroEcoTax() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.tarePrinted()).thenReturn(true);
        when(service.posSettingsService.secondCodePrinted()).thenReturn(true);
        when(service.posSettingsService.ticketLineDiscountPrint()).thenReturn(true);
        when(service.posSettingsService.ecoTaxPrint()).thenReturn("TOTAL");
        when(service.posSettingsService.fidelityPrintBalances()).thenReturn(true);
        Ticket ticket = ticket(0, null);
        ticket.fidelityCard = " ";
        TicketLine item = line("A", "ARTICLE", "1", "2", "2");
        item.appliedTareKg = BigDecimal.ZERO;
        item.secondCode = " ";
        item.modifierLabel = "A VERY LONG ADMINISTERED DISCOUNT WORDING";
        ticket.lines.add(item);
        TicketLine cancelled = line("B", "CANCELLED", "1", "2", "2");
        cancelled.cancelled = true;
        ticket.lines.add(cancelled);
        var deposit = new com.intermarche.pos.domain.payment.DepositPayment(BigDecimal.ONE);
        deposit.vatDetail = "malformed";
        ticket.payments.add(deposit);
        try (var catalog = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(catalog);
            catalog.when(() -> PanacheEntityBase.findById(1L)).thenReturn(ticket);
            service.printTicket(1L);
        }
        String paper = captureReceipt(service);
        assertTrue(paper.contains("A VERY LONG ADMINISTERED D"));
        assertFalse(paper.contains("A VERY LONG ADMINISTERED DISCOUNT WORDING"));
        assertFalse(paper.contains("CANCELLED"));
        assertFalse(paper.contains("DONT ECO-PARTICIPATION"));
        assertFalse(paper.contains("TVA ACOMPTE DEJA PERCUE"));
    }

    /** BO-10-03-05: an administered loyalty zone without a trailing newline still ends before payment. */
    @Test
    void administeredLoyaltyZoneGetsItsOwnLine() {
        TicketPrinterService service = newService();
        service.documentTemplateService = mock(com.intermarche.pos.service.DocumentTemplateService.class);
        when(service.posSettingsService.fidelityTemplateCode(false, false, false)).thenReturn("LOYALTY");
        var template = mock(com.intermarche.pos.domain.setting.DocumentTemplate.class);
        when(service.documentTemplateService.renderTemplate(eq(template), any())).thenReturn("LOYALTY BODY");
        Ticket ticket = ticket(0, null);
        try (var catalog = mockStatic(PanacheEntityBase.class);
             var templates = mockStatic(com.intermarche.pos.domain.setting.DocumentTemplate.class)) {
            silenceCatalog(catalog);
            catalog.when(() -> PanacheEntityBase.findById(1L)).thenReturn(ticket);
            templates.when(() -> com.intermarche.pos.domain.setting.DocumentTemplate.findByCode("LOYALTY")).thenReturn(template);
            service.printTicket(1L);
        }
        assertTrue(captureReceipt(service).contains("LOYALTY BODY\n"));
    }

    /** BO-03-03-36: exit tickets omit missing labels and truncate labels beyond twenty characters. */
    @Test
    void exitTicketHandlesNullAndLongArticleLabels() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.exitTicketEnabled()).thenReturn(true);
        when(service.posSettingsService.exitTicketArticles()).thenReturn(10);
        Ticket ticket = ticket(0, null);
        TicketLine unnamed = line("A", "TEMP", "1", "2", "2");
        unnamed.productLabel = null;
        ticket.lines.add(unnamed);
        ticket.lines.add(line("B", "ABCDEFGHIJKLMNOPQRSTUVWX", "1", "3", "3"));
        try (var catalog = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(catalog);
            catalog.when(() -> PanacheEntityBase.findById(1L)).thenReturn(ticket);
            assertEquals(1, service.printExitTicket(1L));
        }
        String paper = captureReceipt(service);
        assertTrue(paper.contains("ABCDEFGHIJKLMNOPQRST x1"));
        assertFalse(paper.contains("ABCDEFGHIJKLMNOPQRSTUVWX"));
        assertFalse(paper.contains("null"));
    }

    /** BO-03-03-09: an empty VAT recap is omitted while the deposit proof remains printable. */
    @Test
    void depositReceiptSkipsEmptyVatRecap() {
        TicketPrinterService service = newService();
        try (var catalog = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(catalog);
            assertEquals(1, service.printDepositReceipt("D1", "O1", "CUSTOMER", BigDecimal.ONE, java.util.List.of()));
        }
        assertTrue(captureReceipt(service).contains("D1"));
    }

    /** LC-07-05-05: a deferred cheque keeps its distinct customer-facing tender label. */
    @Test
    void deferredChequeHasItsOwnReceiptLabel() throws Exception {
        TicketPrinterService service = newService();
        var payment = new com.intermarche.pos.domain.payment.DeferredChequePayment(BigDecimal.ONE);
        assertEquals(service.i18n.both("pay.deferredCheque"), invokePrinterHelper(service, "tenderLabel", new Class<?>[]{com.intermarche.pos.domain.payment.TicketPayment.class}, payment));
    }

    /** BO-10: enabled optional zones tolerate null article/card metadata without printing placeholder values. */
    @Test
    void enabledOptionalZonesSkipNullMetadata() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.tarePrinted()).thenReturn(true);
        when(service.posSettingsService.secondCodePrinted()).thenReturn(true);
        when(service.posSettingsService.fidelityPrintBalances()).thenReturn(true);
        Ticket ticket = ticket(0, null);
        ticket.fidelityCard = null;
        TicketLine item = line("A", "ARTICLE", "1", "2", "2");
        item.appliedTareKg = null;
        item.secondCode = null;
        ticket.lines.add(item);
        try (var catalog = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(catalog);
            catalog.when(() -> PanacheEntityBase.findById(1L)).thenReturn(ticket);
            service.printTicket(1L);
        }
        String paper = captureReceipt(service);
        assertTrue(paper.contains("ARTICLE"));
        assertFalse(paper.contains("null"));
    }

    /** BO-10-03-05: a loyalty zone already terminated by a newline is not given a blank line. */
    @Test
    void terminatedLoyaltyZoneIsNotSeparatedByAnExtraBlankLine() {
        TicketPrinterService service = newService();
        service.documentTemplateService = mock(com.intermarche.pos.service.DocumentTemplateService.class);
        when(service.posSettingsService.fidelityTemplateCode(false, false, false)).thenReturn("LOYALTY");
        var template = mock(com.intermarche.pos.domain.setting.DocumentTemplate.class);
        when(service.documentTemplateService.renderTemplate(eq(template), any())).thenReturn("LOYALTY BODY\n");
        Ticket ticket = ticket(0, null);
        try (var catalog = mockStatic(PanacheEntityBase.class);
             var templates = mockStatic(com.intermarche.pos.domain.setting.DocumentTemplate.class)) {
            silenceCatalog(catalog);
            catalog.when(() -> PanacheEntityBase.findById(1L)).thenReturn(ticket);
            templates.when(() -> com.intermarche.pos.domain.setting.DocumentTemplate.findByCode("LOYALTY")).thenReturn(template);
            service.printTicket(1L);
        }
        String paper = captureReceipt(service);
        assertTrue(paper.contains("LOYALTY BODY\n"));
        assertFalse(paper.contains("LOYALTY BODY\n\n"));
    }


    /** LC-04-01-02: a summary waiting receipt retains its exact scan code and total, without articles. */
    @Test
    void parkedReceiptWithoutDetailsStillHasItsResumeBarcode() {
        var service = newService();
        when(service.posSettingsService.parkingReceiptDetailed()).thenReturn(false);
        Ticket draft = ticket(0, null);
        draft.lines.add(line("A", "MILK", "1", "1.50", "1.50"));
        service.printParkedTicket(draft);
        String out = captureRaw(service);
        assertTrue(out.contains("[[BARCODE C04-00000001]]"));
        assertTrue(out.contains("TOTAL EN ATTENTE"));
        assertFalse(out.contains("MILK"));
    }


    /** LC-12-07-01: the print contains the approved operator snapshot and excludes drawer-wide figures. */
    @Test
    void operatorReportPrintsOnlyApprovedRevenueSnapshot() {
        var printer = new TicketPrinterService();
        printer.i18n = TestMessages.french();
        printer.hardwareService = mock(HardwareService.class);
        var report = new CashSessionService.SessionReport();
        report.session = new CashSession();
        report.session.sessionNumber = "S-OP7";
        report.session.terminalId = "POS01";
        report.session.openingDate = LocalDateTime.of(2026, 10, 21, 8, 0);
        report.session.openingFloat = new BigDecimal("999.99");
        report.ticketCount = 2;
        report.totalIncludingTax = new BigDecimal("25.00");
        report.totalsByMethod.put("CASH", new BigDecimal("25.00"));
        report.operationRows = List.of(new com.intermarche.pos.service.RevenueReportService.Row("report.section.tenders", "CASH", 2, "25,00"));
        printer.printOperatorReport(report, "Operator Seven");
        var text = ArgumentCaptor.forClass(String.class);
        verify(printer.hardwareService).printReceipt(text.capture());
        assertTrue(text.getValue().contains("Operator Seven"));
        assertTrue(text.getValue().contains("S-OP7"));
        assertTrue(text.getValue().contains("25,00"));
        assertTrue(text.getValue().contains("ESPECES"));
        assertFalse(text.getValue().contains("999"));
        verify(printer.hardwareService).cutPaper();
        report.totalsByMethod.clear();
        report.operationRows = List.of();
        printer.printOperatorReport(report, "Operator Seven");
        verify(printer.hardwareService, times(2)).cutPaper();
    }

    /** LC-12-07-02: configured operation rows appear even with an administered X layout; Z remains unchanged. */
    @Test
    void configuredRubricsExtendTheAdministeredReadingOnly() {
        var report = new CashSessionService.SessionReport();
        report.session = session(null);
        report.operationRows = List.of(new com.intermarche.pos.service.RevenueReportService.Row("report.section.expenses", "", 2, "7,50"));
        var service = newService();
        service.documentTemplateService = mock(DocumentTemplateService.class);
        when(service.documentTemplateService.render(eq(DocumentTemplate.DocumentType.X_REPORT), anyMap())).thenReturn("CUSTOM X\n");
        service.printSessionReport(report);
        String text = captureReceipt(service);
        assertTrue(text.startsWith("CUSTOM X"));
        assertTrue(text.contains("Dépenses"));
        assertTrue(text.contains("2 /"));
        assertTrue(text.contains("7,50"));
        var closingService = newService();
        closingService.documentTemplateService = mock(DocumentTemplateService.class);
        when(closingService.documentTemplateService.render(eq(DocumentTemplate.DocumentType.Z_REPORT), anyMap())).thenReturn("CUSTOM Z");
        report.closing = true;
        closingService.printSessionReport(report);
        assertEquals("CUSTOM Z", captureReceipt(closingService));
    }

    /** LC-12-04-04: disabling supply printing emits neither paper nor cut while leaving movement recording to its existing service. */
    @Test
    void cashSupplySlipRespectsBackOfficePrintFlag() {
        var service = newService();
        when(service.posSettingsService.cashDepositPrint()).thenReturn(false);
        service.printDepositTicket(new BigDecimal("10.00"), List.of(), "MARIE", "11111111", "POS01");
        verifyNoInteractions(service.hardwareService);
    }

    /** LC-12-04-04: the slip carries the operator's recorded number, name, register and total together. */
    @Test
    void cashSupplySlipCarriesBothOperatorIdentifiers() {
        var service = newService();
        service.printDepositTicket(new BigDecimal("10.00"), List.of(), "MARIE", "11111111", "POS01");
        String paper = captureReceipt(service);
        assertTrue(paper.contains("11111111"));
        assertTrue(paper.contains("MARIE"));
        assertTrue(paper.contains("POS01"));
        assertTrue(paper.contains("10,00"));
    }

    /** LC-02-10-03: alphabetic codes stay unique across zero-based regime identities and multi-letter boundaries. */
    @Test
    void vatRegimeLettersCoverZeroAndMultipleLettersWithoutInventingMissingRegimes() throws Exception {
        var service = newService();
        var rate = new BigDecimal("0.20");
        var regime = new com.intermarche.pos.domain.catalog.VatRate();
        try (var regimes = mockStatic(com.intermarche.pos.domain.catalog.VatRate.class)) {
            regimes.when(() -> com.intermarche.pos.domain.catalog.VatRate.findByRate(rate)).thenReturn(regime);
            for (var example : java.util.Map.of(0, "A", 1, "B", 25, "Z", 26, "AA", 27, "AB").entrySet()) {
                regime.number = example.getKey();
                assertEquals(example.getValue(), invokePrinterHelper(service, "vatLetterForRate", new Class<?>[]{BigDecimal.class}, rate));
            }
            regime.number = null;
            assertNull(invokePrinterHelper(service, "vatLetterForRate", new Class<?>[]{BigDecimal.class}, rate));
            regime.number = -1;
            assertNull(invokePrinterHelper(service, "vatLetterForRate", new Class<?>[]{BigDecimal.class}, rate));
            assertNull(invokePrinterHelper(service, "vatLetterForRate", new Class<?>[]{BigDecimal.class}, new BigDecimal("0.10")));
            assertNull(invokePrinterHelper(service, "vatLetterForRate", new Class<?>[]{BigDecimal.class}, (Object) null));
        }
    }
    /** LC-08-06-02/-03: explicit sale identity reaches both paper and administered layouts. */
    @Test
    void saleFiscalIdentityAndAnonymousWording() {
        String[][] cases = {
            {null, null, null, ""}, {"", "999999990", "CONSUMIDOR FINAL", ""},
            {"123456789", null, null, "123456789"},
            {"123456789", " ", null, "123456789"},
            {"123456789", "999999990", "CONSUMIDOR FINAL", "123456789"},
            {"999999990", "999999990", null, "999999990"},
            {"999999990", "999999990", " ", "999999990"},
            {"999999990", "999999990", " CONSUMIDOR FINAL ", "CONSUMIDOR FINAL"}
        };
        for (String[] values : cases) {
            TicketPrinterService service = newService();
            when(service.posSettingsService.defaultTaxId()).thenReturn(values[1]);
            when(service.posSettingsService.defaultTaxIdLabel()).thenReturn(values[2]);
            Ticket sale = ticket(0, null);
            freeze(sale, null);
            sale.customerTaxId = values[0];
            try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
                String paper = service.renderTicket(sale, false, 0);
                java.util.Map<?, ?> document = (java.util.Map<?, ?>) service.saleDocumentData(sale, false, 0).get("document");
                assertEquals(values[0] == null ? "" : values[0], document.get("taxId"));
                assertEquals(values[3], document.get("taxIdLabel"));
                // LC-08-06-11: the layouts also read the country and the bare number.
                assertEquals("PT", document.get("taxIdCountry"));
                assertEquals(values[0] == null ? "" : values[0], document.get("taxIdNumber"));
                if (values[3].isEmpty()) assertFalse(paper.contains("NIF"), paper);
                else assertTrue(paper.contains(values[3]), paper);
            }
        }
    }
    /** LC-08-01-03: the exact same prefixed identity is last in 1D/QR, or suppressed entirely. */
    @Test
    void administeredIdentificationCodeKindsAndSuppression() {
        for (boolean enabled : new boolean[]{false, true}) {
            for (boolean qr : new boolean[]{false, true}) {
                TicketPrinterService service = newService();
                when(service.posSettingsService.ticketBarcodeEnabled()).thenReturn(enabled);
                when(service.posSettingsService.ticketBarcodeQr()).thenReturn(qr);
                when(service.posSettingsService.ticketBarcodePrefix()).thenReturn("P-");
                Ticket sale = ticket(0, null);freeze(sale, null);
                try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
                    String paper = service.renderTicket(sale, false, 0);
                    String directive = "[[" + (qr ? "QR " : "BARCODE ") + "P-" + sale.ticketNumber + "]]";
                    if (enabled) assertTrue(paper.stripTrailing().endsWith(directive), paper);
                    else {
                        assertFalse(paper.contains("[[BARCODE P-"), paper);
                        assertFalse(paper.contains("[[QR P-"), paper);
                    }
                }
            }
        }
    }
    /** LC-08-02-06: customer voucher rendering is frozen exactly, while unclassified vendor output is excluded. */
    @Test
    void archivesOnlyExplicitCustomerCopy() {
        var service = newService();
        service.customerDocumentService = mock(com.intermarche.pos.ui.customer.CustomerDocumentService.class);
        try (var panache = mockStatic(PanacheEntityBase.class)) {
            service.printGiftCardVoucher("GIFT-42", new BigDecimal("42.00"));
        }
        var text = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(service.hardwareService).printReceipt(text.capture());
        verify(service.customerDocumentService).archive(
                com.intermarche.pos.domain.sale.CustomerDocument.Kind.VOUCHER, "GIFT-42", text.getValue());
        service.printTerminalFrame("MERCHANT OR UNCLASSIFIED FRAME");
        verifyNoMoreInteractions(service.customerDocumentService);
    }

    /** LC-08-03-07: independent forced families control paper while all customer copies remain archived. */
    @Test
    void forcedReturnCreditAndDepositPaperKeepsElectronicCopies() {
        boolean[] conditional = {false, true, true, true, true};
        java.util.List<java.util.List<String>> forced = java.util.List.of(java.util.List.of(), java.util.List.of(),
                java.util.List.of("RETOUR"), java.util.List.of("AVOIR"), java.util.List.of("ACOMPTE"));
        int[][] expected = {{1, 1, 1}, {0, 0, 0}, {1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        for (int scenario = 0; scenario < conditional.length; scenario++) {
            for (boolean administered : new boolean[]{false, true}) {
                var service = newService();
                service.customerDocumentService = mock(com.intermarche.pos.ui.customer.CustomerDocumentService.class);
                service.printPolicy = new PrintPolicy();
                service.printPolicy.posSettingsService = service.posSettingsService;
                when(service.posSettingsService.printConditionalEnabled()).thenReturn(conditional[scenario]);
                when(service.posSettingsService.printForcedDocuments()).thenReturn(forced.get(scenario));
                service.documentTemplateService = mock(DocumentTemplateService.class);
                when(service.documentTemplateService.render(any(), anyMap()))
                        .thenReturn(administered ? "ADMINISTERED CUSTOMER COPY" : null);
                Refund returned = refund(2L, null, refundLine("MILK", "1", "6.00"));
                returned.id = 9L;
                try (var panache = mockStatic(PanacheEntityBase.class)) {
                    silenceCatalog(panache);
                    panache.when(() -> Refund.findById(9L)).thenReturn(returned);
                    service.printRefund(9L, false);
                    service.printRefundVoucher(returned, "AV-9", null);
                    assertEquals(expected[scenario][2], service.printDepositReceipt("DEP-9", "ORDER-9", "CLIENT",
                            new BigDecimal("1.00"), null));
                    assertEquals(expected[scenario][2], service.printOrderDepositTicket("ORDER-10", java.util.List.of(),
                            new BigDecimal("1.00"), null));
                }
                verify(service.hardwareService, times(expected[scenario][0] + expected[scenario][1]
                        + 2 * expected[scenario][2])).printReceipt(anyString());
                verify(service.hardwareService, times(expected[scenario][1] + 2 * expected[scenario][2])).cutPaper();
                // LC-08-02-02: a copy kept from the paper is recorded all the same.
                verify(service.hardwareService, times((1 - expected[scenario][0]) + (1 - expected[scenario][1])
                        + 2 * (1 - expected[scenario][2]))).recordUnprinted(anyString());
                verify(service.customerDocumentService).archive(
                        eq(com.intermarche.pos.domain.sale.CustomerDocument.Kind.RETURN), eq("9"), anyString());
                verify(service.customerDocumentService).archive(
                        eq(com.intermarche.pos.domain.sale.CustomerDocument.Kind.CREDIT), eq("AV-9"), anyString());
                verify(service.customerDocumentService).archive(
                        eq(com.intermarche.pos.domain.sale.CustomerDocument.Kind.DEPOSIT), eq("DEP-9"), anyString());
                verify(service.customerDocumentService).archive(
                        eq(com.intermarche.pos.domain.sale.CustomerDocument.Kind.DEPOSIT), eq("ORDER-10"), anyString());
                verifyNoMoreInteractions(service.customerDocumentService);
            }
        }
    }


    // --------------------------------------------------
    // Portuguese fiscal documents (LC-08-01-05, LC-08-01-06, LC-08-04-18 to -23, LC-05-07-03)
    // --------------------------------------------------

    /** The query of the active documents of sale 1. */
    private static final String PT_ACTIVE =
            "ticketId = ?1 and cancelled = false and documentType in ?2 order by part";

    /**
     * Builds a Portuguese document of sale 1.
     *
     * @param type its type
     * @param rank its rank
     * @return the document
     */
    private FiscalDocument ptDocument(FiscalDocument.Type type, int rank) {
        FiscalDocument document = new FiscalDocument();
        document.documentType = type;
        document.number = type + " C04/2026/0000000" + rank;
        document.atcud = "CODE" + type + "-" + rank;
        document.ticketId = 1L;
        document.issuedAt = NOW;
        document.hash = "A123456789B123456789C123456789D123456789";
        document.qrPayload = "A:500100200*B:999999990";
        document.grossTotal = new BigDecimal("12.00");
        document.taxTotal = new BigDecimal("2.00");
        return document;
    }

    /**
     * Stubs the active documents of sale 1, after the catch-all catalog stubs.
     *
     * @param panache the static mock
     * @param documents the documents answered
     */
    private void ptActive(MockedStatic<PanacheEntityBase> panache, FiscalDocument... documents) {
        panache.when(() -> PanacheEntityBase.list(PT_ACTIVE, 1L,
                List.of(FiscalDocument.Type.FS, FiscalDocument.Type.FT))).thenReturn(List.of(documents));
    }

    /**
     * Prints sale 1 with the given active documents and the fiscal service wired.
     *
     * @param service the printer
     * @param ticket the sale
     * @param cut whether the caller asks for the cut
     * @param documents the active documents
     */
    private void printPt(TicketPrinterService service, Ticket ticket, boolean cut, FiscalDocument... documents) {
        service.portugueseFiscalService = mock(com.intermarche.pos.ui.invoice.PortugueseFiscalService.class);
        when(service.posSettingsService.fiscalCertificateNumber()).thenReturn("2468/AT");
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            ptActive(panache, documents);
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(ticket);
            service.printTicket(1L, cut);
        }
        verify(service.portugueseFiscalService).issueSaleDocuments(1L);
    }

    /**
     * LC-08-01-05 / LC-08-04-23: a sale stated on a simplified invoice prints the
     * document under the ticket's identity — for the final consumer when the sale
     * carries no NIF — and its legal mentions instead of the bare certificate line.
     */
    @Test
    void aSimplifiedInvoiceIsStatedOnTheReceipt() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        printPt(service, ticket, true, ptDocument(FiscalDocument.Type.FS, 1));
        String receipt = captureReceipt(service);
        assertTrue(receipt.contains("Vendeur: Jean Dupont\n" + center("FATURA SIMPLIFICADA") + "\n"
                + "N°                    FS C04/2026/00000001\n" + "NIF CONSUMIDOR FINAL\n" + "-".repeat(42)), receipt);
        assertTrue(receipt.contains(center("ABCD-Processado por programa certificado") + "\n"
                + center("n.º 2468/AT") + "\n" + center("ATCUD: CODEFS-1") + "\n[[QR A:500100200*B:999999990]]\n"),
                receipt);
        assertFalse(receipt.contains("CERTIFICAT"), receipt);
    }

    /**
     * Centres a text on the 42-column paper as the printer does.
     *
     * @param text the text
     * @return the centred text, without its line feed
     */
    private String center(String text) {
        return new com.intermarche.pos.service.DocumentLayout(42).centre(text);
    }

    /**
     * LC-08-04-18 / -21, LC-08-06-11: an invoice states its customer — country
     * code and NIF, name, address — and the document it replaces, in its own
     * block: the NIF the sale itself carried is not printed beside it.
     */
    @Test
    void anInvoiceStatesItsCustomerAndItsSource() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.customerTaxId = "500100200";
        FiscalDocument invoice = ptDocument(FiscalDocument.Type.FT, 2);
        invoice.customerTaxId = "123456789";
        invoice.customerName = "ACME LDA";
        invoice.customerAddress = "RUA A 1";
        invoice.sourceNumber = "FS C04/2026/00000001";
        printPt(service, ticket, true, invoice);
        String receipt = captureReceipt(service);
        assertTrue(receipt.contains(center("FATURA") + "\n"
                + "N°                    FT C04/2026/00000002\n" + "NIF PT 123456789\nACME LDA\nRUA A 1\n"
                + "REF. : FS C04/2026/00000001\n" + "-".repeat(42)), receipt);
        assertFalse(receipt.contains("NIF 500100200"), receipt);
    }

    /**
     * LC-08-01-05, LC-08-06-11: a simplified invoice prints the sale's NIF once,
     * with its country code, in its own block; an invoice without an address
     * prints none.
     */
    @Test
    void aSimplifiedInvoiceDoesNotRepeatTheSaleNif() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.customerTaxId = "123456789";
        FiscalDocument simplified = ptDocument(FiscalDocument.Type.FS, 1);
        simplified.customerTaxId = "123456789";
        printPt(service, ticket, true, simplified);
        String receipt = captureReceipt(service);
        assertTrue(receipt.contains(center("FATURA SIMPLIFICADA") + "\n"
                + "N°                    FS C04/2026/00000001\n" + "NIF PT 123456789\n" + "-".repeat(42)), receipt);
        assertEquals(1, receipt.split("123456789", -1).length - 1, receipt);
    }

    /**
     * LC-08-01-06: a sale stated on two documents prints two papers, each cut,
     * each with the lines, the VAT and the payment shares of its part.
     */
    @Test
    void aSplitSalePrintsItsTwoDocuments() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.store.vatNumber = "PT500100200";
        ticket.lines.add(line("A", "LAIT DEMI ECREME BIO 1L", "2", "4.00", "8.00"));
        ticket.lines.add(line("B", "PAIN", "1", "4.00", "4.00"));
        ticket.payments.add(new CashPayment(new BigDecimal("7.00"), new BigDecimal("7.00")));
        ticket.payments.add(new com.intermarche.pos.domain.payment.SocialCardPayment(new BigDecimal("5.00")));
        FiscalDocument first = ptDocument(FiscalDocument.Type.FS, 1);
        first.part = 1;
        first.lineUids = "A";
        first.paymentShares = "0:3.00;1:5.00";
        first.grossTotal = new BigDecimal("8.00");
        first.taxTotal = new BigDecimal("1.33");
        FiscalDocument second = ptDocument(FiscalDocument.Type.FS, 2);
        second.part = 2;
        second.lineUids = "B";
        second.paymentShares = "0:4.00";
        second.grossTotal = new BigDecimal("4.00");
        second.taxTotal = new BigDecimal("0.67");
        printPt(service, ticket, true, first, second);
        ArgumentCaptor<String> papers = ArgumentCaptor.forClass(String.class);
        verify(service.hardwareService, times(2)).printReceipt(papers.capture());
        verify(service.hardwareService, times(2)).cutPaper();
        assertEquals(center("INTERMARCHE") + "\n" + center("MAGASIN LYON") + "\n" + center("LYON") + "\n"
                + center("NIF PT500100200") + "\n" + "-".repeat(42) + "\n"
                + center("FATURA SIMPLIFICADA") + "\n"
                + "N°                    FS C04/2026/00000001\n" + "NIF CONSUMIDOR FINAL\n"
                + "Date   : 02/08/2026 10:30\n" + "Ticket: C04-00000001\n" + "-".repeat(42) + "\n"
                + "LAIT DEMI ECREME BIO\n" + "  2 x 4,00 E\n" + String.format("%42s%n", "8,00")
                + "-".repeat(42) + "\n"
                + "TOTAL TTC                           8,00 E\n"
                + "Dont TVA                            1,33 E\n"
                + "  TVA 20,00%             HT 6,67  TVA 1,33\n"
                + "\n" + center("REGLEMENT") + "\n"
                + "ESPECES                             3,00 E\n"
                + "CARTE ACHAT SOCIALE                 5,00 E\n"
                + "\n" + center("ABCD-Processado por programa certificado") + "\n" + center("n.º 2468/AT") + "\n"
                + center("ATCUD: CODEFS-1") + "\n[[QR A:500100200*B:999999990]]\n", papers.getAllValues().get(0));
        assertTrue(papers.getAllValues().get(1).contains("PAIN\n" + String.format("%42s%n", "4,00")),
                papers.getAllValues().get(1));
        assertFalse(papers.getAllValues().get(1).contains("CARTE ACHAT SOCIALE"));
    }

    /**
     * LC-08-01-06: a split sale whose caller defers the cut cuts between its two
     * papers only; a reprint of it marks both as duplicates.
     */
    @Test
    void aSplitSaleDefersItsLastCutAndReprintsAsDuplicates() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(1, null);
        ticket.store.address = null;
        ticket.store.vatNumber = " ";
        FiscalDocument first = ptDocument(FiscalDocument.Type.FS, 1);
        first.part = 1;
        first.lineUids = "A";
        FiscalDocument second = ptDocument(FiscalDocument.Type.FS, 2);
        second.part = 2;
        second.lineUids = "B";
        printPt(service, ticket, false, first, second);
        ArgumentCaptor<String> papers = ArgumentCaptor.forClass(String.class);
        verify(service.hardwareService, times(2)).printReceipt(papers.capture());
        verify(service.hardwareService, times(1)).cutPaper();
        assertTrue(papers.getAllValues().get(0).startsWith(center("INTERMARCHE") + "\n" + center("MAGASIN LYON")
                + "\n" + "-".repeat(42) + "\n" + center("*** DUPLICATA N°1 ***") + "\n"), papers.getAllValues().get(0));
    }

    /**
     * LC-08-01-06: rendering a split sale — the frozen copy, a store-node
     * duplicata — gives its two documents one after the other.
     */
    @Test
    void renderingASplitSaleGivesItsTwoDocuments() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.fiscalCertificateNumber()).thenReturn("");
        Ticket ticket = ticket(0, null);
        ticket.store = null;
        FiscalDocument first = ptDocument(FiscalDocument.Type.FS, 1);
        first.lineUids = "A";
        FiscalDocument second = ptDocument(FiscalDocument.Type.FS, 2);
        second.lineUids = "B";
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            ptActive(panache, first, second);
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(ticket);
            String rendered = service.renderTicket(ticket, false, 0);
            assertEquals(2, rendered.split("FATURA SIMPLIFICADA", -1).length - 1);
            assertTrue(rendered.contains("n.º 0/AT"), rendered);
        }
    }

    /**
     * LC-08-04-23: an administered sale layout still states its document, before
     * the identification code.
     */
    @Test
    void anAdministeredLayoutStillStatesItsDocument() {
        TicketPrinterService service = newService();
        service.documentTemplateService = mock(DocumentTemplateService.class);
        when(service.documentTemplateService.render(eq(DocumentTemplate.DocumentType.SALE_RECEIPT), any()))
                .thenReturn("SALE BODY\n");
        when(service.posSettingsService.fiscalCertificateNumber()).thenReturn("2468");
        Ticket ticket = ticket(0, null);
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            ptActive(panache, ptDocument(FiscalDocument.Type.FS, 1));
            String rendered = service.renderTicket(ticket, false, 0);
            assertTrue(rendered.startsWith("SALE BODY\n" + center("FATURA SIMPLIFICADA") + "\n"), rendered);
            assertTrue(rendered.endsWith("[[QR A:500100200*B:999999990]]\n\n[[BARCODE C04-00000001]]\n"), rendered);
        }
    }

    /**
     * LC-05-07-03: a return stated on a credit note prints it under the return's
     * title, with its legal mentions; an administered return layout gets them
     * appended.
     */
    @Test
    void aReturnPrintsItsCreditNote() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.fiscalCertificateNumber()).thenReturn("2468/AT");
        FiscalDocument note = ptDocument(FiscalDocument.Type.NC, 1);
        note.refundId = 7L;
        note.sourceNumber = "FS C04/2026/00000001";
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            panache.when(() -> PanacheEntityBase.findById(7L)).thenReturn(refund(1L, Refund.RefundMethod.CASH,
                    refundLine("BEURRE", "2", "2.00")));
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(ticket(0, null));
            panache.when(() -> PanacheEntityBase.list("refundId", 7L)).thenReturn(List.of(note));
            service.printRefund(7L);
        }
        String paper = captureReceipt(service);
        assertTrue(paper.contains(center("TICKET DE RETOUR") + "\n" + center("NOTA DE CRÉDITO") + "\n"
                + "N°                    NC C04/2026/00000001\n" + "NIF CONSUMIDOR FINAL\n"
                + "REF. : FS C04/2026/00000001\n" + "-".repeat(42)), paper);
        assertFalse(paper.contains("MOTIF"), paper);
        assertTrue(paper.contains(center("ATCUD: CODENC-1")), paper);
        TicketPrinterService administered = newService();
        administered.documentTemplateService = mock(DocumentTemplateService.class);
        when(administered.documentTemplateService.render(eq(DocumentTemplate.DocumentType.REFUND_RECEIPT), any()))
                .thenReturn("REFUND BODY\n");
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            panache.when(() -> PanacheEntityBase.findById(7L)).thenReturn(refund());
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(ticket(0, null));
            panache.when(() -> PanacheEntityBase.list("refundId", 7L)).thenReturn(List.of(note));
            administered.printRefund(7L);
        }
        String body = captureReceipt(administered);
        assertTrue(body.startsWith("REFUND BODY\n" + center("NOTA DE CRÉDITO") + "\n"), body);
        assertTrue(body.endsWith("[[QR A:500100200*B:999999990]]\n"), body);
    }

    /**
     * LC-08-04-21: the credit note cancelling a simplified invoice states why,
     * the lines it cancels and no payment.
     */
    @Test
    void aConversionCreditNoteStatesWhyAndNoPayment() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("A", "LAIT", "1", "8.00", "8.00"));
        TicketLine weighed = line("B", "TOMATES", "0.500", "4.00", "2.00");
        weighed.plu = "123";
        TicketLine noPlu = line("C", "SEL", "1", "1.00", "1.00");
        noPlu.plu = "";
        ticket.lines.add(weighed);
        ticket.lines.add(noPlu);
        ticket.payments.add(new CashPayment(new BigDecimal("8.00"), new BigDecimal("8.00")));
        FiscalDocument note = ptDocument(FiscalDocument.Type.NC, 1);
        note.sourceNumber = "FS C04/2026/00000001";
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(ticket);
            service.printFiscalDocument(note);
        }
        String paper = captureReceipt(service);
        assertTrue(paper.contains("REF. : FS C04/2026/00000001\nMOTIF : ANNULATION, CONVERSION EN FATURA\n"), paper);
        assertTrue(paper.contains("LAIT\n" + String.format("%42s%n", "8,00")), paper);
        assertFalse(paper.contains("REGLEMENT"), paper);
    }

    /**
     * LC-08-04-22: a receipt states the amount received and the payments of the
     * invoice, no line and no VAT.
     */
    @Test
    void aReceiptStatesTheAmountReceived() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("A", "LAIT", "1", "8.00", "8.00"));
        ticket.payments.add(new CashPayment(new BigDecimal("12.00"), new BigDecimal("12.00")));
        FiscalDocument receipt = ptDocument(FiscalDocument.Type.RC, 1);
        receipt.customerTaxId = "123456789";
        receipt.customerName = "ACME";
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(ticket);
            service.printFiscalDocument(receipt);
        }
        String paper = captureReceipt(service);
        assertTrue(paper.contains(center("RECIBO") + "\n"), paper);
        assertTrue(paper.contains("MONTANT RECU                       12,00 E\n\n" + center("REGLEMENT") + "\n"
                + "ESPECES                            12,00 E\n"), paper);
        assertFalse(paper.contains("LAIT"), paper);
        assertFalse(paper.contains("Dont TVA"), paper);
    }

    /**
     * A document whose sale cannot be read prints its identity, its total and
     * its legal mentions only.
     */
    @Test
    void aDocumentWithoutItsSalePrintsItsIdentityOnly() {
        TicketPrinterService service = newService();
        FiscalDocument orphan = ptDocument(FiscalDocument.Type.FT, 1);
        orphan.ticketId = null;
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            service.printFiscalDocument(orphan);
        }
        String paper = captureReceipt(service);
        assertEquals(center("INTERMARCHE") + "\n" + "-".repeat(42) + "\n" + center("FATURA") + "\n"
                + "N°                    FT C04/2026/00000001\n" + "NIF CONSUMIDOR FINAL\n"
                + "Date   : 02/08/2026 10:30\n" + "-".repeat(42) + "\n"
                + "TOTAL TTC                          12,00 E\n"
                + "Dont TVA                            2,00 E\n" + "\n"
                + center("ABCD-Processado por programa certificado") + "\n" + center("n.º 0/AT") + "\n"
                + center("ATCUD: CODEFT-1") + "\n[[QR A:500100200*B:999999990]]\n", paper);
    }
    /**
     * LC-06-02-02 / -12: a line recalled from a document stating both
     * quantities prints the ordered one beside the delivered one it sells,
     * and a plain line prints no such mention.
     */
    @Test
    void aRecalledLinePrintsItsOrderedAndDeliveredQuantities() {
        TicketPrinterService service = newService();
        wireRenderer(service);
        Ticket ticket = ticket(0, null);
        TicketLine recalled = line("u1", "LAIT", "4", "2.00", "8.00");
        recalled.orderedQuantity = new BigDecimal("5.000");
        ticket.lines.add(recalled);
        ticket.lines.add(line("u2", "PAIN", "1", "1.00", "1.00"));
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            panache.when(() -> com.intermarche.pos.domain.setting.DocumentTemplate.list(
                            "active = true and documentType = ?1 order by priority, code",
                            com.intermarche.pos.domain.setting.DocumentTemplate
                                    .DocumentType.SALE_RECEIPT))
                    .thenReturn(List.of());
            String rendered = service.renderTicket(ticket, false, 0);
            assertTrue(rendered.contains("  QTE COMMANDEE 5 / LIVREE 4\n"));
            assertEquals(1, rendered.split("QTE COMMANDEE", -1).length - 1);
        }
    }

    /**
     * The order lines a deposit invoice is printed with: a priced one and one
     * without a unit price.
     *
     * @return the lines
     */
    private List<com.intermarche.pos.ui.recall.RecalledDocument.Line> invoiceLines() {
        return List.of(new com.intermarche.pos.ui.recall.RecalledDocument.Line(
                        "301", "LAIT", new BigDecimal("5"), new BigDecimal("15.00"),
                        new BigDecimal("0.2"), new BigDecimal("4")),
                new com.intermarche.pos.ui.recall.RecalledDocument.Line(
                        "302", "FORFAIT", BigDecimal.ONE, null, new BigDecimal("0.055")));
    }

    /**
     * LC-08-04-15: the deposit invoice of a store fully identified states its
     * legal name, address, SIRET and VAT number, its own number, the order, the
     * lines, the total, the deposit with its VAT per rate, what was already paid
     * and what remains, and the VAT mention; it is archived as a deposit
     * document.
     */
    @Test
    void theDepositInvoiceStatesTheIdentifiedStoreAndTheAmounts() {
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            Store store = store();
            store.legalName = "SAS LYON DISTRIBUTION";
            store.address.streetLine1 = "1 RUE DU PORT";
            store.address.postalCode = "69001";
            store.siret = "12345678900012";
            store.vatNumber = "FR12345678900";
            panache.when(() -> Store.listAll()).thenReturn(List.of(store));
            TicketPrinterService service = newService();
            service.customerDocumentService = mock(com.intermarche.pos.ui.customer.CustomerDocumentService.class);
            assertEquals(1, service.printDepositInvoice(com.intermarche.pos.ui.recall.RecalledDocument.Kind.DEVIS, "C04-FA2026-000001", "DV-7", invoiceLines(),
                    new BigDecimal("60.00"), new BigDecimal("12.00"), new BigDecimal("6.00")));
            String out = captureReceipt(service);
            assertTrue(out.contains("SAS LYON DISTRIBUTION"));
            assertFalse(out.contains("MAGASIN LYON"));
            assertTrue(out.contains("1 RUE DU PORT"));
            assertTrue(out.contains("69001 LYON"));
            assertTrue(out.contains("SIRET 12345678900012"));
            assertTrue(out.contains("TVA INTRACOM. FR12345678900"));
            assertTrue(out.contains("FACTURE D'ACOMPTE"));
            assertTrue(out.contains("C04-FA2026-000001"));
            assertTrue(out.contains("DV-7"));
            assertTrue(out.contains("DEVIS" + " ".repeat(42 - 5 - 4) + "DV-7"));
            assertTrue(out.contains("  4 x 15,00"));
            assertTrue(out.contains("60,00"));
            assertTrue(out.contains("  1 x 0,00"));
            assertTrue(out.contains("TOTAL COMMANDE TTC"));
            assertTrue(out.contains("ACOMPTE TTC"));
            assertTrue(out.contains("ACOMPTE HT"));
            assertTrue(out.contains("10,00"));
            assertTrue(out.contains("TVA SUR ACOMPTE"));
            assertTrue(out.contains("2,00"));
            assertTrue(out.contains("DEJA VERSE"));
            assertTrue(out.contains("6,00"));
            assertTrue(out.contains("RESTE A PAYER"));
            assertTrue(out.contains("42,00"));
            assertTrue(out.contains("CGI art. 269-2-a"));
            verify(service.customerDocumentService).archive(
                    eq(com.intermarche.pos.domain.sale.CustomerDocument.Kind.DEPOSIT),
                    eq("C04-FA2026-000001"), any());
        }
    }

    /**
     * LC-08-04-15: a store without legal name (blank), address, SIRET or VAT
     * number (blank) is named by its trading name only, and an invoice on an
     * order paid nothing before states no previous payment.
     */
    @Test
    void theDepositInvoiceOfABarelyIdentifiedStore() {
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            Store store = new Store();
            store.name = "MAGASIN NU";
            store.legalName = " ";
            store.siret = " ";
            store.vatNumber = " ";
            panache.when(() -> Store.listAll()).thenReturn(List.of(store));
            TicketPrinterService service = newService();
            assertEquals(1, service.printDepositInvoice(com.intermarche.pos.ui.recall.RecalledDocument.Kind.COMMANDE, "C04-FA2026-000002", "DV-8", invoiceLines(),
                    new BigDecimal("60.00"), new BigDecimal("12.00"), BigDecimal.ZERO));
            String out = captureReceipt(service);
            assertTrue(out.contains("MAGASIN NU"));
            assertFalse(out.contains("SIRET"));
            assertFalse(out.contains("TVA INTRACOM."));
            assertFalse(out.contains("DEJA VERSE"));
            assertTrue(out.contains("48,00"));
        }
    }

    /**
     * LC-08-04-15: a store whose legal name, SIRET and VAT number are absent is
     * named by its trading name, and a register knowing no store prints the
     * invoice without a store block.
     */
    @Test
    void theDepositInvoiceWithoutStoreIdentity() {
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            Store store = new Store();
            store.name = "MAGASIN NUL";
            panache.when(() -> Store.listAll()).thenReturn(List.of(store));
            TicketPrinterService service = newService();
            service.printDepositInvoice(null, "C04-FA2026-000003", "DV-9", invoiceLines(),
                    new BigDecimal("60.00"), new BigDecimal("12.00"), BigDecimal.ZERO);
            String out = captureReceipt(service);
            assertTrue(out.contains("MAGASIN NUL"));
            assertFalse(out.contains("SIRET"));
        }
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            panache.when(() -> Store.listAll()).thenReturn(List.of());
            TicketPrinterService service = newService();
            service.printDepositInvoice(com.intermarche.pos.ui.recall.RecalledDocument.Kind.BON_VENTE, "C04-FA2026-000004", "DV-9", invoiceLines(),
                    new BigDecimal("60.00"), new BigDecimal("12.00"), BigDecimal.ZERO);
            String out = captureReceipt(service);
            assertTrue(out.contains("FACTURE D'ACOMPTE"));
            assertFalse(out.contains("MAGASIN"));
        }
    }

    /**
     * LC-06-02-04: the deposit slip names the document by its kind — a quote is
     * a quote, a sale voucher and a click-and-collect basket keep their names,
     * and an order (or a document of unknown kind) stays an order.
     */
    @Test
    void theDepositSlipNamesTheDocumentKind() {
        java.util.Map<com.intermarche.pos.ui.recall.RecalledDocument.Kind, String> expected = new java.util.LinkedHashMap<>();
        expected.put(com.intermarche.pos.ui.recall.RecalledDocument.Kind.DEVIS, "DEVIS");
        expected.put(com.intermarche.pos.ui.recall.RecalledDocument.Kind.BON_VENTE, "BON DE VENTE");
        expected.put(com.intermarche.pos.ui.recall.RecalledDocument.Kind.CLICK_COLLECT, "CLICK AND COLLECT");
        expected.put(com.intermarche.pos.ui.recall.RecalledDocument.Kind.COMMANDE, "COMMANDE");
        expected.put(com.intermarche.pos.ui.recall.RecalledDocument.Kind.SELF_SCAN, "SELFSCANNING");
        expected.put(com.intermarche.pos.ui.recall.RecalledDocument.Kind.QBUSTING, "QBUSTING");
        expected.put(com.intermarche.pos.ui.recall.RecalledDocument.Kind.ECOMMERCE, "E-COMMERCE");
        expected.put(com.intermarche.pos.ui.recall.RecalledDocument.Kind.MOBILITY, "MOBILITÉ");
        expected.put(null, "COMMANDE");
        for (java.util.Map.Entry<com.intermarche.pos.ui.recall.RecalledDocument.Kind, String> entry : expected.entrySet()) {
            TicketPrinterService service = newService();
            assertEquals(1, service.printOrderDepositTicket(entry.getKey(), "DV-0001", List.of(),
                    new BigDecimal("10.00"), null));
            String out = captureReceipt(service);
            String label = entry.getValue();
            assertTrue(out.contains(label + " ".repeat(42 - label.length() - 7) + "DV-0001"), label);
        }
    }

    /**
     * LC-08-06-11: a document stated for the final consumer — its stored NIF
     * 999999990 — prints CONSUMIDOR FINAL, and a foreign customer's NIF prints
     * with its own country code.
     */
    @Test
    void thePortugueseNifStatesItsCountryOrTheFinalConsumer() {
        TicketPrinterService anonymous = newService();
        FiscalDocument simplified = ptDocument(FiscalDocument.Type.FS, 1);
        simplified.customerTaxId = "999999990";
        printPt(anonymous, ticket(0, null), true, simplified);
        String receipt = captureReceipt(anonymous);
        assertTrue(receipt.contains("NIF CONSUMIDOR FINAL\n"), receipt);
        assertFalse(receipt.contains("NIF 999999990"), receipt);
        TicketPrinterService foreign = newService();
        FiscalDocument invoice = ptDocument(FiscalDocument.Type.FT, 2);
        invoice.customerTaxId = "ESB12345678";
        invoice.customerName = "SOCIEDAD SL";
        printPt(foreign, ticket(0, null), true, invoice);
        assertTrue(captureReceipt(foreign).contains("NIF ES B12345678\nSOCIEDAD SL\n"));
    }


    // --------------------------------------------------
    // LC-02-09-03, LC-07-11-03, LC-05-07-02: hub slips and return details
    // --------------------------------------------------

    /**
     * LC-07-11-03: the gift-card slip states the balance before, the balance
     * after, the provider, its code and the hub's transaction.
     */
    @Test
    void theGiftCardSlipStatesWhatTheHubAnswered() {
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            TicketPrinterService service = newService();
            assertEquals(1, service.printGiftCardPaymentReceipt("POS01-00000009", "6299000000000017",
                    new BigDecimal("12.40"), new BigDecimal("30.00"), new BigDecimal("17.60"),
                    "ILLICADO", "P7", "HUB00000007"));
            String out = captureReceipt(service);
            assertTrue(out.contains("****0017"));
            assertTrue(out.contains("ANCIEN SOLDE"));
            assertTrue(out.contains("30,00"));
            assertTrue(out.contains("SOLDE RESTANT"));
            assertTrue(out.contains("17,60"));
            assertTrue(out.contains("FOURNISSEUR"));
            assertTrue(out.contains("ILLICADO"));
            assertTrue(out.contains("CODE FOURNISSEUR"));
            assertTrue(out.contains("P7"));
            assertTrue(out.contains("TRANSACTION"));
            assertTrue(out.contains("HUB00000007"));
        }
    }

    /**
     * LC-02-09-03: the activation ticket states the article, its code in full,
     * the amount, the balances, the hub's transaction and the provider's code;
     * nothing is printed without an amount.
     */
    @Test
    void theActivationTicket() {
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            TicketPrinterService service = newService();
            assertEquals(0, service.printActivationTicket("T", "CARTE", "9001", null, null, null, null, null, null));
            assertEquals(0, service.printActivationTicket("T", "CARTE", "9001", BigDecimal.ZERO, null, null, null,
                    null, null));
            service.ticketNumberService = mock(com.intermarche.pos.service.TicketNumberService.class);
            when(service.ticketNumberService.getTerminalId()).thenReturn("POS01");
            assertEquals(1, service.printActivationTicket("POS01-00000003", "CARTE CADEAU 25", "9001",
                    new BigDecimal("25.00"), BigDecimal.ZERO, new BigDecimal("25.00"), "HUB1", "TRANSACTIS", "S1"));
            String out = captureReceipt(service);
            assertTrue(out.contains("TICKET D'ACTIVATION"));
            assertTrue(out.contains("POS01"));
            assertTrue(out.contains("POS01-00000003"));
            assertTrue(out.contains("CARTE CADEAU 25"));
            assertTrue(out.contains("CODE ACTIVATION"));
            assertTrue(out.contains("9001"));
            assertTrue(out.contains("ANCIEN SOLDE"));
            assertTrue(out.contains("NOUVEAU SOLDE"));
            assertTrue(out.contains("25,00"));
            assertTrue(out.contains("HUB1"));
            assertTrue(out.contains("TRANSACTIS"));
            assertTrue(out.contains("S1"));
            TicketPrinterService bare = newService();
            assertEquals(1, bare.printActivationTicket(null, null, null, BigDecimal.ONE, null, null, null, null, null));
            String plain = captureReceipt(bare);
            assertFalse(plain.contains("ANCIEN SOLDE"));
            assertFalse(plain.contains("NOUVEAU SOLDE"));
            assertFalse(plain.contains("FOURNISSEUR"));
        }
    }

    /**
     * LC-05-07-02: the refund ticket details the loyalty advantages taken back,
     * line by line — the type before the rule when there is one, a long label
     * cut — and their total; an administered layout reads them too.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theRefundDetailsTheLoyaltyAdvantagesTakenBack() {
        TicketPrinterService service = newService();
        Refund refund = refund(2L, Refund.RefundMethod.CASH, refundLine("PAIN", "1", "6.00"));
        refund.loyaltyRecalculationSent = true;
        refund.fidelityLines = new ArrayList<>(List.of(
                new com.intermarche.pos.domain.sale.RefundFidelityLine("R1", "5 % CAGNOTTE", "Cagnotte", new BigDecimal("0.50")),
                new com.intermarche.pos.domain.sale.RefundFidelityLine("R2", "COUPON CAFE", " ", new BigDecimal("1.00")),
                new com.intermarche.pos.domain.sale.RefundFidelityLine(null, "BON D'ACHAT FIDELITE TRES LONG", null,
                        new BigDecimal("5.00"))));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Refund.findById(5L)).thenReturn(refund);
            mocked.when(() -> Ticket.findById(2L)).thenReturn(null);
            service.printRefund(5L);
            String out = captureReceipt(service);
            assertTrue(out.contains("DECAGNOTTAGE, BONS ET COUPONS RETIRES"));
            assertTrue(out.contains("Cagnotte 5 % CAGNOTTE"));
            assertTrue(out.contains("-0,50"));
            assertTrue(out.contains("  COUPON CAFE"));
            assertTrue(out.contains("BON D'ACHAT FIDELITE TRES"));
            assertFalse(out.contains("TRES LONG"));
            assertTrue(out.contains("TOTAL RETIRE"));
            assertTrue(out.contains("-6,50"));
        }
        Map<String, Object> data = service.refundDocumentData(refund, null);
        List<Map<String, Object>> loyalty = (List<Map<String, Object>>) data.get("loyaltyLines");
        assertEquals(3, loyalty.size());
        assertEquals("Cagnotte", loyalty.get(0).get("type"));
        assertEquals("0,50", loyalty.get(0).get("amount"));
        assertEquals("", loyalty.get(2).get("type"));
        refund.fidelityLines = new ArrayList<>();
        assertEquals(List.of(), service.refundDocumentData(refund, null).get("loyaltyLines"));
        TicketPrinterService nothingTaken = newService();
        refund.fidelityLines = null;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Refund.findById(5L)).thenReturn(refund);
            mocked.when(() -> Ticket.findById(2L)).thenReturn(null);
            nothingTaken.printRefund(5L);
            String out = captureReceipt(nothingTaken);
            assertTrue(out.contains("recalcul transmis au programme"));
            assertFalse(out.contains("DECAGNOTTAGE"));
        }
    }

    /**
     * A ticket mixing sales and returns, the line cancelled set apart.
     *
     * @return the ticket
     */
    private Ticket mixedTicket() {
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("u1", "LAIT", "2", "1.15", "2.30"));
        TicketLine back = line("u2", "BOUILLOIRE ELECTRIQUE INOX 1,7 LITRE GRAND MODELE", "-1", "24.90", "-24.90");
        back.returnLine = true;
        ticket.lines.add(back);
        TicketLine cancelledBack = line("u3", "ANNULE", "-1", "3.00", "-3.00");
        cancelledBack.returnLine = true;
        cancelledBack.cancelled = true;
        ticket.lines.add(cancelledBack);
        TicketLine battery = line("u4", "PILE", "-1", "2.00", "-2.00");
        battery.returnLine = true;
        ticket.lines.add(battery);
        return ticket;
    }

    /**
     * LC-05-07-02: where the store asks for it, a ticket mixing sales and returns
     * carries the value of its returns only, its return lines going to the
     * separate detail; a ticket without returns, or a store that does not ask,
     * prints its lines as before.
     */
    @Test
    void aMixedTicketCarriesTheValueOfItsReturns() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.returnSeparateDetail()).thenReturn(true);
        String out = printed(service, mixedTicket());
        assertTrue(out.contains("VALEUR DES RETOURS"));
        assertTrue(out.contains("-26,90"));
        assertFalse(out.contains("BOUILLOIRE"));
        assertTrue(out.contains("LAIT"));
        TicketPrinterService plainStore = newService();
        String detailed = printed(plainStore, mixedTicket());
        assertTrue(detailed.contains("BOUILLOIRE"));
        assertFalse(detailed.contains("VALEUR DES RETOURS"));
        Ticket onlySales = ticket(0, null);
        onlySales.lines.add(line("u1", "LAIT", "2", "1.15", "2.30"));
        TicketPrinterService asking = newService();
        when(asking.posSettingsService.returnSeparateDetail()).thenReturn(true);
        assertFalse(printed(asking, onlySales).contains("VALEUR DES RETOURS"));
    }

    /**
     * A ticket is mixed only with a live sold line and a live returned line.
     */
    @Test
    void whatMakesATicketMixed() {
        assertTrue(TicketPrinterService.mixed(mixedTicket()));
        Ticket returnsOnly = ticket(0, null);
        TicketLine back = line("u2", "RETOUR", "-1", "1", "-1");
        back.returnLine = true;
        returnsOnly.lines.add(back);
        assertFalse(TicketPrinterService.mixed(returnsOnly));
        Ticket cancelledSale = ticket(0, null);
        cancelledSale.lines.add(back);
        TicketLine gone = line("u1", "LAIT", "1", "1", "1");
        gone.cancelled = true;
        cancelledSale.lines.add(gone);
        assertFalse(TicketPrinterService.mixed(cancelledSale));
    }

    /**
     * LC-05-07-02: the detail of the returns lists each article handed back,
     * its quantity, price and total, their total and what the returns change to
     * the advantages — the loyalty programme's part named when a card was there;
     * nothing is printed without a ticket, without the store asking or on a
     * ticket that is not mixed.
     */
    @Test
    void theDetailOfTheReturns() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.returnSeparateDetail()).thenReturn(true);
        Ticket ticket = mixedTicket();
        try (MockedStatic<PanacheEntityBase> mocked = quietCatalog()) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            assertEquals(0, service.printReturnDetail(null));
            assertEquals(0, service.printReturnDetail(2L));
            assertEquals(1, service.printReturnDetail(1L));
            String out = captureReceipt(service);
            assertTrue(out.contains("DETAIL DES RETOURS"));
            assertTrue(out.contains("C04-00000001"));
            assertTrue(out.contains("BOUILLOIRE ELECTRIQUE INOX 1,7 LITRE GRAN"));
            assertFalse(out.contains("GRAND MODELE"));
            assertTrue(out.contains("-1 x 24,90"));
            assertTrue(out.contains("PILE"));
            assertTrue(out.contains("TOTAL RETOURS"));
            assertTrue(out.contains("-26,90"));
            assertTrue(out.contains("articles retournes exclus des avantages"));
            assertFalse(out.contains("Cagnotte et coupons"));
            assertFalse(out.contains("ANNULE"));
            assertFalse(out.contains("LAIT"));
            ticket.fidelityCard = "2990000000019";
            TicketPrinterService carded = newService();
            when(carded.posSettingsService.returnSeparateDetail()).thenReturn(true);
            assertEquals(1, carded.printReturnDetail(1L));
            assertTrue(captureReceipt(carded).contains("recalcul transmis au programme"));
            TicketPrinterService notAsking = newService();
            assertEquals(0, notAsking.printReturnDetail(1L));
            Ticket sales = ticket(0, null);
            sales.lines.add(line("u1", "LAIT", "2", "1.15", "2.30"));
            mocked.when(() -> Ticket.findById(3L)).thenReturn(sales);
            assertEquals(0, carded.printReturnDetail(3L));
        }
    }

    // --- lot 18, group H ---

    /**
     * Prints a sale whose lines carry the given valuation traces.
     *
     * @param service the printer under test
     * @param ticket the sale to print
     * @param valuations the valuation traces of the sale
     * @return the rendered receipt text
     */
    private String printedWithValuations(TicketPrinterService service, Ticket ticket,
            List<TicketLineValuation> valuations) {
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L)).thenReturn(new ArrayList<>(valuations));
            service.printTicket(1L);
            return captureReceipt(service);
        }
    }

    /**
     * Counts the occurrences of a text in a receipt.
     *
     * @param text the receipt
     * @param part the text counted
     * @return the number of occurrences
     */
    private int occurrences(String text, String part) {
        return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }

    /**
     * A small black square as an administered image, which the thermal head
     * can rasterise.
     *
     * @return the image as a data URI
     * @throws Exception when the image cannot be encoded
     */
    private String blackSquare() throws Exception {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(16, 16,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", bytes);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    /**
     * BO-10-06-05/06 in LINE mode: every printed eco-tax of the article under its
     * line, with the administered label, the register's wording for the built-in
     * one and the code otherwise; a NOPRINT eco-tax and a zero amount print nothing.
     */
    @Test
    void declaredEcoTaxesPrintUnderTheirLine() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.ecoTaxPrint()).thenReturn("LINE");
        when(service.posSettingsService.ecoTaxes()).thenReturn(
                com.intermarche.pos.domain.catalog.attribute.EcoTaxDefinition.parse(
                        "DEEE=Eco DEEE;PILES>NOPRINT;EMB;PNEUS"));
        Product product = new Product();
        product.attributes.put("ECO_TAX", "0.20");
        product.attributes.put("DEEE", "1.00");
        product.attributes.put("PILES", "0.50");
        product.attributes.put("EMB", "0.10");
        Ticket ticket = ticket(0, null);
        TicketLine taxed = line("U1", "TELEVISEUR", "2", "299.00", "598.00");
        taxed.product = product;
        ticket.lines.add(taxed);
        String out = printed(service, ticket);
        assertTrue(out.contains(layoutLine("  DONT ECO-PARTICIPATION", "0,40 E")));
        assertTrue(out.contains(layoutLine("  Eco DEEE", "2,00 E")));
        assertTrue(out.contains(layoutLine("  EMB", "0,20 E")));
        assertFalse(out.contains("PILES"));
        assertFalse(out.contains("PNEUS"));
    }

    /**
     * BO-10-06-05/06 in TOTAL mode: one line per printed eco-tax under the VAT
     * table, the NOPRINT one left to the invoice.
     */
    @Test
    void declaredEcoTaxesPrintTotalledPerEcoTax() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.ecoTaxPrint()).thenReturn("TOTAL");
        when(service.posSettingsService.ecoTaxes()).thenReturn(
                com.intermarche.pos.domain.catalog.attribute.EcoTaxDefinition.parse("DEEE=Eco DEEE;PILES>NOPRINT"));
        Product product = new Product();
        product.attributes.put("DEEE", "1.00");
        product.attributes.put("PILES", "0.50");
        Ticket ticket = ticket(0, null);
        TicketLine taxed = line("U1", "LAMPE", "1", "10.00", "10.00");
        taxed.product = product;
        ticket.lines.add(taxed);
        TicketLine cancelled = line("U2", "LAMPE", "1", "10.00", "10.00");
        cancelled.product = product;
        cancelled.cancelled = true;
        ticket.lines.add(cancelled);
        String out = printed(service, ticket);
        assertTrue(out.contains(layoutLine("Eco DEEE", "1,00 E")));
        assertFalse(out.contains("ECO-PARTICIPATION"));
        assertFalse(out.contains("PILES"));
    }

    /**
     * Lays out a label and a value as the receipt does, newline included.
     *
     * @param left the label
     * @param right the value
     * @return the receipt line
     */
    private static String layoutLine(String left, String right) {
        return new com.intermarche.pos.service.DocumentLayout(42).line(left, right) + "\n";
    }

    /**
     * BO-10-07-15: printed by family, each family closes on its subtotal, the
     * last one too, and the amounts are bold; the first family opens without
     * a subtotal before it.
     */
    @Test
    void familySubtotalsAndBoldAmountsArePrinted() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.ticketLineOrder()).thenReturn("FAMILY");
        when(service.posSettingsService.ticketFamilySubtotals()).thenReturn(true);
        when(service.posSettingsService.ticketBoldAmounts()).thenReturn(true);
        Ticket ticket = ticket(0, null);
        TicketLine bread = line("U1", "BAGUETTE", "1", "1.00", "1.00");
        bread.familyLabel = "BOULANGERIE";
        TicketLine cake = line("U2", "GATEAU", "1", "4.00", "4.00");
        cake.familyLabel = "BOULANGERIE";
        TicketLine milk = line("U3", "LAIT", "1", "7.00", "7.00");
        milk.familyLabel = "CREMERIE";
        ticket.lines.addAll(List.of(bread, cake, milk));
        String out = printed(service, ticket);
        String sub = new com.intermarche.pos.service.DocumentLayout(42).line("SOUS-TOTAL", "5,00 E");
        assertTrue(out.contains(sub.substring(0, sub.length() - 6) + "[[BOLD]]5,00 E[[/BOLD]]\n"));
        assertTrue(out.contains("[[BOLD]]7,00 E[[/BOLD]]\n"));
        assertTrue(out.contains("[[BOLD]]1,00[[/BOLD]]\n"));
        assertTrue(out.contains("[[BOLD]]12,00 E[[/BOLD]]\n"));
        assertEquals(2, occurrences(out, "SOUS-TOTAL"));
        assertTrue(out.indexOf("SOUS-TOTAL") < out.indexOf("CREMERIE"));
    }

    /**
     * BO-10-07-15: the subtotals need the family order — another order prints
     * none — and a family order over a sale printing no line closes on nothing.
     */
    @Test
    void familySubtotalsNeedPrintedFamilies() {
        TicketPrinterService entry = newService();
        when(entry.posSettingsService.ticketFamilySubtotals()).thenReturn(true);
        Ticket sale = ticket(0, null);
        TicketLine bread = line("U1", "BAGUETTE", "1", "1.00", "1.00");
        bread.familyLabel = "BOULANGERIE";
        sale.lines.add(bread);
        assertFalse(printed(entry, sale).contains("SOUS-TOTAL"));
        TicketPrinterService empty = newService();
        when(empty.posSettingsService.ticketLineOrder()).thenReturn("FAMILY");
        when(empty.posSettingsService.ticketFamilySubtotals()).thenReturn(true);
        assertFalse(printed(empty, ticket(0, null)).contains("SOUS-TOTAL"));
    }

    /**
     * BO-10-08-30: where the shop asks for it, a cancelled line prints marked
     * and outside the totals, under its family heading in the family order.
     */
    @Test
    void cancelledLinesPrintWhereTheShopAsks() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.cancelledLinesPrinted()).thenReturn(true);
        when(service.posSettingsService.ticketLineOrder()).thenReturn("FAMILY");
        when(service.posSettingsService.ticketFamilySubtotals()).thenReturn(true);
        Ticket ticket = ticket(0, null);
        TicketLine kept = line("U1", "LAIT", "1", "12.00", "12.00");
        kept.familyLabel = "CREMERIE";
        TicketLine cancelled = line("U2", "UN LIBELLE ARTICLE TRES LONG A COUPER", "1", "5.00", "5.00");
        cancelled.familyLabel = "EPICERIE";
        cancelled.cancelled = true;
        TicketLine shortCancelled = line("U3", "SEL", "1", "1.00", "1.00");
        shortCancelled.familyLabel = "EPICERIE";
        shortCancelled.cancelled = true;
        ticket.lines.addAll(List.of(kept, cancelled, shortCancelled));
        String out = printed(service, ticket);
        assertTrue(out.contains("UN LIBELLE ARTICLE T\n" + layoutLine("  ANNULE", "5,00 E")));
        assertTrue(out.contains("SEL\n" + layoutLine("  ANNULE", "1,00 E")));
        assertTrue(out.contains("EPICERIE"));
        assertTrue(out.contains(layoutLine("SOUS-TOTAL", "0,00 E")));
        assertTrue(out.contains(layoutLine("SOUS-TOTAL", "12,00 E")));
    }

    /**
     * BO-10-08-30 on the parked receipt: a cancelled line prints marked where
     * the shop asks for it.
     */
    @Test
    void parkedReceiptPrintsCancelledLinesWhereTheShopAsks() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.cancelledLinesPrinted()).thenReturn(true);
        Ticket draft = ticket(0, null);
        TicketLine cancelled = line("B", "PRODUIT ANNULE", "1", "5.00", "5.00");
        cancelled.cancelled = true;
        draft.lines.add(cancelled);
        service.printParkedTicket(draft);
        assertTrue(captureRaw(service).contains("PRODUIT ANNULE\n" + layoutLine("  ANNULE", "5,00 E")));
    }

    /**
     * BO-10-08-31: identical pieces print one per line where the shop asks for
     * it, the subtotal marker on the last one; every other line — weighed,
     * measured, modified, recalled, engine-valued, fractional, priced apart,
     * single or cancelled — keeps its single line.
     */
    @Test
    void identicalPiecesAreDetailedWhereTheShopAsks() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.identicalLinesDetailed()).thenReturn(true);
        Ticket ticket = ticket(0, null);
        TicketLine pieces = line("P", "YAOURT", "3", "0.50", "1.50");
        pieces.subtotalBoundary = true;
        pieces.familyCode = "CREM";
        TicketLine emptyPlu = line("E", "BISCUIT", "2", "1.00", "2.00");
        emptyPlu.plu = "";
        TicketLine weighed = line("W", "POMMES", "2", "1.00", "2.00");
        weighed.plu = "1234";
        TicketLine measured = line("M", "TISSU", "2", "1.00", "2.00");
        measured.unitName = "m";
        TicketLine blankUnit = line("B", "CRAYON", "2", "0.25", "0.50");
        blankUnit.unitName = " ";
        TicketLine modified = line("D", "JUS", "2", "1.00", "1.80");
        modified.modifierLabel = "REMISE";
        TicketLine recalled = line("R", "CAGEOT", "2", "1.00", "2.00");
        recalled.orderedQuantity = new BigDecimal("3");
        TicketLine valued = line("V", "CAFE", "2", "3.00", "6.00");
        TicketLine fractional = line("F", "RUBAN", "2.5", "1.00", "2.50");
        TicketLine priced = line("X", "THE", "2", "1.00", "1.90");
        TicketLine single = line("S", "SUCRE", "1", "1.00", "1.00");
        TicketLine cancelled = line("C", "FARINE", "2", "1.00", "2.00");
        cancelled.cancelled = true;
        ticket.lines.addAll(List.of(pieces, emptyPlu, weighed, measured, blankUnit, modified, recalled,
                valued, fractional, priced, single, cancelled));
        String out = printedWithValuations(service, ticket, List.of(valuation("V", "6.00", "6.00", null, null)));
        assertEquals(3, occurrences(out, "YAOURT\n"));
        assertEquals(1, occurrences(out, "SOUS-TOTAL"));
        assertTrue(out.indexOf("SOUS-TOTAL") > out.lastIndexOf("YAOURT"));
        assertEquals(2, occurrences(out, "BISCUIT\n"));
        assertEquals(2, occurrences(out, "CRAYON\n"));
        for (String kept : List.of("POMMES\n", "TISSU\n", "JUS\n", "CAGEOT\n", "CAFE\n", "RUBAN\n", "THE\n", "SUCRE\n")) {
            assertEquals(1, occurrences(out, kept), kept);
        }
        assertFalse(out.contains("FARINE"));
    }

    /**
     * BO-10-08-28/29: the receipt writes a weight, its price per weight and the
     * tare with the administered decimals and symbol.
     */
    @Test
    void weighedLinesFollowTheAdministeredWriting() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.unitFormat()).thenReturn(
                new com.intermarche.pos.domain.catalog.UnitFormat(2, 2, "KG"));
        when(service.posSettingsService.tarePrinted()).thenReturn(true);
        Ticket ticket = ticket(0, null);
        TicketLine weighed = line("W", "POMMES", "0.188", "17.99", "3.38");
        weighed.plu = "1234";
        weighed.appliedTareKg = new BigDecimal("0.050");
        ticket.lines.add(weighed);
        String out = printed(service, ticket);
        assertTrue(out.contains("  0,19 KG x 17,99 E/KG\n"));
        assertTrue(out.contains("  TARE 0,05 KG\n"));
        assertFalse(out.contains(" kg"));
    }

    /**
     * BO-10-07-13: a terminal frame prints under the administered monetique
     * logo, the contactless logo added on a contactless payment; an empty or
     * unreadable image adds nothing.
     *
     * @throws Exception when the test image cannot be encoded
     */
    @Test
    void terminalFramesCarryTheEftLogos() throws Exception {
        String square = blackSquare();
        String directive = ReceiptLogo.directive(square, 0);
        assertFalse(directive.isEmpty());
        TicketPrinterService service = newService();
        service.storeImageService = mock(com.intermarche.pos.service.StoreImageService.class);
        when(service.posSettingsService.eftLogosPrinted()).thenReturn(true);
        when(service.storeImageService.eftLogo()).thenReturn(square);
        when(service.storeImageService.contactlessLogo()).thenReturn(square);
        service.printTerminalFrame("FRAME", "SANS_CONTACT");
        assertEquals(directive + "\n" + directive + "\nFRAME", captureReceipt(service));
        TicketPrinterService chip = newService();
        chip.storeImageService = mock(com.intermarche.pos.service.StoreImageService.class);
        when(chip.posSettingsService.eftLogosPrinted()).thenReturn(true);
        when(chip.storeImageService.eftLogo()).thenReturn(square);
        chip.printTerminalFrame("FRAME", "CODE");
        assertEquals(directive + "\nFRAME", captureReceipt(chip));
        TicketPrinterService none = newService();
        none.storeImageService = mock(com.intermarche.pos.service.StoreImageService.class);
        when(none.posSettingsService.eftLogosPrinted()).thenReturn(true);
        when(none.storeImageService.eftLogo()).thenReturn("");
        when(none.storeImageService.contactlessLogo()).thenReturn("data:image/png;base64,bm90IGFuIGltYWdl");
        none.printTerminalFrame("FRAME", "SANS_CONTACT");
        assertEquals("FRAME", captureReceipt(none));
    }

    /**
     * BO-10-07-13: without the image service or with the logos disabled, the
     * frame prints alone, as the single-argument form always did.
     */
    @Test
    void terminalFramesPrintAloneWithoutLogos() {
        TicketPrinterService unwired = newService();
        unwired.storeImageService = null;
        unwired.printTerminalFrame("FRAME");
        assertEquals("FRAME", captureReceipt(unwired));
        TicketPrinterService disabled = newService();
        disabled.storeImageService = mock(com.intermarche.pos.service.StoreImageService.class);
        disabled.printTerminalFrame("FRAME", "SANS_CONTACT");
        assertEquals("FRAME", captureReceipt(disabled));
        verifyNoInteractions(disabled.storeImageService);
    }

    /**
     * BO-10-02-18: the VAT tables round with the administered mode, half-up for
     * a printer built without its parameters.
     *
     * @throws Exception when the helper cannot be invoked
     */
    @Test
    void vatTablesUseTheAdministeredRounding() throws Exception {
        TicketPrinterService service = newService();
        when(service.posSettingsService.calcRoundingMode()).thenReturn(java.math.RoundingMode.HALF_EVEN);
        assertEquals(java.math.RoundingMode.HALF_EVEN, invokePrinterHelper(service, "roundingMode", new Class<?>[0]));
        service.posSettingsService = null;
        assertNull(invokePrinterHelper(service, "roundingMode", new Class<?>[0]));
    }

    // --------------------------------------------------
    // Lot 19 M — BO-03-03 / BO-03-04 / BO-03-08
    // --------------------------------------------------

    /**
     * Gives the printer a forced-printing policy that keeps every associated
     * document from the paper, as conditional printing does for a document the
     * shop did not force (BO-03-03-01).
     *
     * @param service the printer
     * @param documentKey the key the policy is asked about
     */
    private void suppress(TicketPrinterService service, String documentKey) {
        service.printPolicy = mock(PrintPolicy.class);
        when(service.printPolicy.isAssociatedDocumentPrinted(documentKey)).thenReturn(false);
    }

    /**
     * BO-03-08-07: the gift-card slip prints the shop's sentence for the
     * provider's answer, its figures filled in and the card masked.
     */
    @Test
    void theGiftCardSlipPrintsTheShopsSentence() {
        TicketPrinterService service = newService();
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            assertEquals(1, service.printGiftCardPaymentReceipt("T1", "6299000012345678", new BigDecimal("30.00"),
                    new BigDecimal("50.00"), new BigDecimal("20.00"), "ILLICADO", "P1", "HUB1",
                    "Vous avez réglé {montant}, solde {solde} sur la carte {carte}"));
        }
        String paper = captureReceipt(service);
        String tail = paper.substring(paper.indexOf("Vous avez"));
        assertTrue(tail.contains("réglé 30,00 E"), tail);
        assertTrue(tail.contains("solde 20,00 E"), tail);
        assertTrue(tail.contains("****5678"), tail);
    }

    /**
     * BO-03-08-07: the shop's sentence is wrapped to the paper, and absent —
     * or blank — prints nothing; a token without its figure is emptied.
     */
    @Test
    void theProviderSentenceIsWrappedAndOptional() {
        TicketPrinterService service = newService();
        assertEquals("", service.providerTextLines(null, BigDecimal.ONE, BigDecimal.ONE, "1"));
        assertEquals("", service.providerTextLines("  ", BigDecimal.ONE, BigDecimal.ONE, "1"));
        String emptied = service.providerTextLines("MONTANT {montant} SOLDE {solde} CARTE{carte}", null, null, null);
        assertTrue(emptied.startsWith("MONTANT"), emptied);
        assertFalse(emptied.contains("{"), emptied);
        String wrapped = service.providerTextLines("x".repeat(30) + " " + "y".repeat(30), null, null, null);
        assertEquals(2, wrapped.split("\n").length);
    }

    /**
     * BO-03-03-01: under conditional printing the gift-card slip — built in or
     * administered — and the activation ticket come out only when forced; the
     * customer copy is kept either way.
     */
    @Test
    void theGiftCardSlipAndTheActivationTicketAreAssociatedDocuments() {
        TicketPrinterService service = newService();
        suppress(service, PrintPolicy.DOCUMENT_GIFT_CARD_PAYMENT);
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            assertEquals(0, service.printGiftCardPaymentReceipt("T1", "6299", new BigDecimal("5.00"),
                    null, null, null, null, null, null));
        }
        verify(service.hardwareService).recordUnprinted(org.mockito.ArgumentMatchers.contains("6299"));
        service.documentTemplateService = mock(DocumentTemplateService.class);
        when(service.documentTemplateService.render(eq(DocumentTemplate.DocumentType.GIFT_CARD_PAYMENT), any()))
                .thenReturn("ADMINISTERED SLIP");
        assertEquals(0, service.printGiftCardPaymentReceipt("T1", "6299", new BigDecimal("5.00"),
                null, null, null, null, null, "MERCI {montant}"));
        verify(service.hardwareService).recordUnprinted("ADMINISTERED SLIP");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> data = ArgumentCaptor.forClass(java.util.Map.class);
        verify(service.documentTemplateService).render(eq(DocumentTemplate.DocumentType.GIFT_CARD_PAYMENT),
                data.capture());
        assertEquals("MERCI 5,00 E", ((java.util.Map<?, ?>) data.getValue().get("document")).get("providerMessage"));
        TicketPrinterService activation = newService();
        suppress(activation, PrintPolicy.DOCUMENT_DEMATERIALISED);
        assertEquals(0, activation.printActivationTicket("T1", "CARTE", "9001", new BigDecimal("25.00"),
                null, new BigDecimal("25.00"), null, null, null, "ACTIVEE {solde}"));
        verify(activation.hardwareService).recordUnprinted(org.mockito.ArgumentMatchers.contains("ACTIVEE 25,00 E"));
        verify(activation.hardwareService, never()).printReceipt(any());
    }

    /**
     * BO-03-03-06: the register's parked slip states the printing moment; the
     * shop's PARKED_TICKET layout, when there is one, is printed instead and
     * reads the five values and the articles put aside.
     */
    @Test
    void theParkedTicketIsLaidOutByItsTemplate() {
        TicketPrinterService service = newService();
        service.documentTemplateService = mock(DocumentTemplateService.class);
        when(service.documentTemplateService.render(eq(DocumentTemplate.DocumentType.PARKED_TICKET), any()))
                .thenReturn("PARKED LAYOUT");
        Ticket draft = ticket(0, null);
        draft.lines.add(line("A", "LAIT", "2", "1.15", "2.30"));
        TicketLine cancelled = line("B", "ANNULE", "1", "1.00", "1.00");
        cancelled.cancelled = true;
        draft.lines.add(cancelled);
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            service.printParkedTicket(draft);
        }
        verify(service.hardwareService).printReceipt("PARKED LAYOUT");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> data = ArgumentCaptor.forClass(java.util.Map.class);
        verify(service.documentTemplateService).render(eq(DocumentTemplate.DocumentType.PARKED_TICKET),
                data.capture());
        java.util.Map<?, ?> document = (java.util.Map<?, ?>) data.getValue().get("document");
        assertEquals("TICKET EN ATTENTE", document.get("title"));
        assertEquals("[[BARCODE C04-00000001]]", document.get("barcode"));
        assertEquals("C04-00000001", document.get("barcodeNumber"));
        assertEquals(16, ((String) document.get("printDate")).length());
        assertEquals(1, ((List<?>) data.getValue().get("lines")).size());
        assertEquals("Jean Dupont", data.getValue().get("operator"));
    }

    /**
     * BO-03-03-06: the parked layout's data tolerate a slip without number,
     * articles, cashier or total, and carry no article where the shop does not
     * detail the slip; an article without quantity or total states none.
     */
    @Test
    void theParkedDataTolerateAnIncompleteDraft() {
        TicketPrinterService service = newService();
        Ticket draft = ticket(0, null);
        draft.ticketNumber = null;
        draft.cashier = null;
        draft.totalIncludingTax = null;
        TicketLine odd = line("A", "LAIT", "1", "1.00", "1.00");
        odd.quantity = null;
        odd.totalPrice = null;
        draft.lines.add(odd);
        java.util.Map<String, Object> data = service.parkedDocumentData(draft, "15/09/2026 11:24");
        java.util.Map<?, ?> document = (java.util.Map<?, ?>) data.get("document");
        assertEquals("", document.get("barcode"));
        assertEquals("", data.get("operator"));
        assertEquals("15/09/2026", data.get("date"));
        assertEquals("11:24", data.get("time"));
        assertEquals("", ((java.util.Map<?, ?>) data.get("totals")).get("includingTax"));
        java.util.Map<?, ?> row = (java.util.Map<?, ?>) ((List<?>) data.get("lines")).get(0);
        assertEquals("", row.get("quantity"));
        assertEquals("", row.get("total"));
        draft.lines = null;
        assertTrue(((List<?>) service.parkedDocumentData(draft, "15/09/2026 11:24").get("lines")).isEmpty());
        when(service.posSettingsService.parkingReceiptDetailed()).thenReturn(false);
        draft.lines = new ArrayList<>(List.of(odd));
        assertTrue(((List<?>) service.parkedDocumentData(draft, "15/09/2026 11:24").get("lines")).isEmpty());
    }

    /**
     * BO-03-03-09: the deposit ticket names its customer and states the VAT
     * recap of the deposit with the regime code, the base, the tax and the
     * total; a zero rate states no base; a blank customer prints no line.
     */
    @Test
    void theDepositTicketNamesItsCustomerAndItsVatRecap() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.ticketVatCode()).thenReturn(true);
        com.intermarche.pos.domain.catalog.VatRate normal = new com.intermarche.pos.domain.catalog.VatRate();
        normal.number = 3;
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            PanacheQuery<com.intermarche.pos.domain.catalog.VatRate> query = vatQuery(normal);
            panache.when(() -> PanacheEntityBase.find(eq("rate"), any(Object[].class))).thenReturn(query);
            assertEquals(1, service.printOrderDepositTicket(com.intermarche.pos.ui.recall.RecalledDocument.Kind.COMMANDE,
                    "CMD-1", List.of(), new BigDecimal("12.00"), "0.2:2;0:0", "MARTIN Claire"));
        }
        String paper = captureReceipt(service);
        assertTrue(paper.contains("MARTIN Claire"), paper);
        assertTrue(paper.contains("D 20,00%"), paper);
        assertTrue(paper.contains("10,00"), paper);
        assertTrue(paper.contains("12,00"), paper);
        java.util.List<java.util.Map<String, String>> plain = newService().depositVatRecap("0.2:2;0:0");
        assertEquals("", plain.get(0).get("code"));
        assertEquals("10,00", plain.get(0).get("base"));
        assertEquals("12,00", plain.get(0).get("includingTax"));
        assertEquals("", plain.get(1).get("base"));
        TicketPrinterService blank = newService();
        blank.printOrderDepositTicket(null, "CMD-2", null, BigDecimal.ONE, null, "  ");
        assertFalse(captureReceipt(blank).contains(TestMessages.french().get("paper.acompteClient")));
    }

    /**
     * BO-03-03-09: the shop's DEPOSIT_RECEIPT layout lays out the deposit
     * ticket, reading the order, the customer, the amount and the recap.
     */
    @Test
    void theDepositTicketIsLaidOutByItsTemplate() {
        TicketPrinterService service = newService();
        service.documentTemplateService = mock(DocumentTemplateService.class);
        when(service.documentTemplateService.render(eq(DocumentTemplate.DocumentType.DEPOSIT_RECEIPT), any()))
                .thenReturn("DEPOSIT LAYOUT");
        assertEquals(1, service.printOrderDepositTicket("CMD-1", List.of(), new BigDecimal("12.00"), "0.2:2"));
        verify(service.hardwareService).printReceipt("DEPOSIT LAYOUT");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> data = ArgumentCaptor.forClass(java.util.Map.class);
        verify(service.documentTemplateService).render(eq(DocumentTemplate.DocumentType.DEPOSIT_RECEIPT),
                data.capture());
        assertEquals("CMD-1", ((java.util.Map<?, ?>) data.getValue().get("document")).get("orderNumber"));
        assertEquals(1, ((List<?>) data.getValue().get("vatLines")).size());
    }

    /**
     * BO-03-03-23: the shop's placement prevails — LINE under the article,
     * FOOTER at the foot —, and otherwise the advantage's own placement.
     */
    @Test
    void thePromotionPlacementPrevailsOverTheAdvantage() {
        assertTrue(TicketPrinterService.advantageUnderLine("LINE", null));
        assertFalse(TicketPrinterService.advantageUnderLine("FOOTER", "LINE"));
        assertTrue(TicketPrinterService.advantageUnderLine("ENGINE", "LINE"));
        assertFalse(TicketPrinterService.advantageUnderLine(null, "FOOTER"));
    }

    /**
     * BO-03-03-23: on the receipt, a LINE placement prints a footer advantage
     * under its article.
     */
    @Test
    void aLinePlacementPrintsThePromotionUnderTheArticle() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.ticketPromotionPlacement()).thenReturn("LINE");
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "LAIT", "1", "2.00", "2.00"));
        TicketLineValuation valuation = valuation("U1", "2.00", "1.50", "PROMO LAIT", null);
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            panache.when(() -> PanacheEntityBase.list(eq("ticket.id"), any(Object[].class)))
                    .thenReturn(List.of(valuation));
            String paper = service.renderTicket(ticket, false, 0);
            assertTrue(paper.indexOf("PROMO LAIT") < paper.indexOf(TestMessages.french().get("paper.totalTtc").trim()),
                    paper);
        }
    }

    /**
     * BO-03-03-26 / -27: the short message prints before the advantages, each
     * line cut to the paper; the commercial messages print at the end of the
     * loyalty section, set apart, as many as the shop lets through.
     */
    @Test
    void theLoyaltyMessagesPrintWhereTheShopPlacesThem() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.fidelityShortMessagePrinted()).thenReturn(true);
        when(service.posSettingsService.fidelityCommercialMessagesMax()).thenReturn(2);
        Ticket ticket = ticket(0, null);
        ticket.fidelityCard = "6045";
        ticket.fidelityEarnTotal = new BigDecimal("1.00");
        ticket.fidelityMessages = "S\tCOURT " + "x".repeat(50) + "\nS\tPETIT\nC0\tPROMO A\nC1\tPROMO B " + "y".repeat(50)
                + "\nC2\tPROMO C";
        String paper;
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            paper = service.renderTicket(ticket, false, 0);
        }
        assertTrue(paper.contains("COURT " + "x".repeat(36) + "\nPETIT\n"), paper);
        assertTrue(paper.indexOf("COURT") < paper.indexOf(TestMessages.french().get("paper.cagnotteJour").trim()));
        assertTrue(paper.contains("PROMO A\n\nPROMO B"), paper);
        assertFalse(paper.contains("PROMO C"), paper);
        assertTrue(paper.indexOf("PROMO A") > paper.indexOf(TestMessages.french().get("paper.cagnotteJour").trim()));
    }

    /**
     * BO-03-03-26 / -27 / -35 / -04: the layout's data carry the messages as
     * the shop lets them through, every pot of the card, and the VAT code of
     * each rate where the shop prints codes.
     */
    @Test
    void theSaleDataCarryTheMessagesThePotsAndTheVatCodes() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.fidelityCommercialMessagesMax()).thenReturn(1);
        when(service.posSettingsService.ticketVatCode()).thenReturn(true);
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "LAIT", "1", "2.00", "2.00"));
        ticket.fidelityMessages = "S\tCOURT\nC0\tPROMO A\nC1\tPROMO B";
        ticket.fidelityPotBalances = "CAGNOTTE\t12.40\nBONUS\t";
        com.intermarche.pos.domain.catalog.VatRate normal = new com.intermarche.pos.domain.catalog.VatRate();
        normal.number = 0;
        java.util.Map<String, Object> data;
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            PanacheQuery<com.intermarche.pos.domain.catalog.VatRate> query = vatQuery(normal);
            panache.when(() -> PanacheEntityBase.find(eq("rate"), any(Object[].class))).thenReturn(query);
            data = service.saleDocumentData(ticket, false, 0);
        }
        java.util.Map<?, ?> fidelity = (java.util.Map<?, ?>) data.get("fidelity");
        assertEquals(List.of(), fidelity.get("shortMessage"));
        assertEquals(1, ((List<?>) fidelity.get("commercialMessages")).size());
        List<?> pots = (List<?>) fidelity.get("pots");
        assertEquals("12,40", ((java.util.Map<?, ?>) pots.get(0)).get("balance"));
        assertEquals("", ((java.util.Map<?, ?>) pots.get(1)).get("balance"));
        assertEquals("A", ((java.util.Map<?, ?>) ((List<?>) data.get("vatRows")).get(0)).get("code"));
        when(service.posSettingsService.fidelityShortMessagePrinted()).thenReturn(true);
        when(service.posSettingsService.ticketVatCode()).thenReturn(false);
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            data = service.saleDocumentData(ticket, false, 0);
        }
        assertEquals(List.of("COURT"), ((java.util.Map<?, ?>) data.get("fidelity")).get("shortMessage"));
        assertEquals("", ((java.util.Map<?, ?>) ((List<?>) data.get("vatRows")).get(0)).get("code"));
    }

    /**
     * BO-03-02-01: a settlement taken as a tender the store added reads that
     * tender's label and prints THAT tender's slip; a tender code nobody
     * administers, or one with a blank label, reads the nature's word.
     */
    @Test
    @SuppressWarnings("unchecked")
    void anAddedTenderNamesItsSettlementAndPrintsItsSlip() {
        TicketPrinterService service = newService();
        service.documentTemplateService = mock(DocumentTemplateService.class);
        when(service.documentTemplateService.renderTemplate(any(), any())).thenReturn("JUSTIFICATIF");
        Ticket ticket = ticket(0, null);
        com.intermarche.pos.domain.payment.CardPayment amex =
                new com.intermarche.pos.domain.payment.CardPayment(new BigDecimal("10.00"));
        amex.tenderCode = "AMEX";
        com.intermarche.pos.domain.payment.CardPayment gone =
                new com.intermarche.pos.domain.payment.CardPayment(new BigDecimal("5.00"));
        gone.tenderCode = "GONE";
        com.intermarche.pos.domain.payment.CardPayment blank =
                new com.intermarche.pos.domain.payment.CardPayment(new BigDecimal("2.00"));
        blank.tenderCode = "BLANK";
        ticket.payments.add(amex);
        ticket.payments.add(gone);
        ticket.payments.add(blank);
        com.intermarche.pos.domain.payment.TenderDefinition tender = slipTender(1, false, false);
        tender.code = "AMEX";
        tender.label = "American Express";
        com.intermarche.pos.domain.payment.TenderDefinition unlabelled = slipTender(1, false, false);
        unlabelled.code = "BLANK";
        unlabelled.label = " ";
        unlabelled.documentTemplateCode = null;
        com.intermarche.pos.domain.payment.TenderDefinition nameless = slipTender(1, false, false);
        nameless.code = "NONAME";
        nameless.label = null;
        nameless.documentTemplateCode = null;
        com.intermarche.pos.domain.payment.CardPayment anonymous =
                new com.intermarche.pos.domain.payment.CardPayment(new BigDecimal("1.00"));
        anonymous.tenderCode = "NONAME";
        ticket.payments.add(anonymous);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> PanacheEntityBase.find("code", "AMEX")).thenAnswer(ignored -> answer(tender));
            mocked.when(() -> PanacheEntityBase.find("code", "BLANK")).thenAnswer(ignored -> answer(unlabelled));
            mocked.when(() -> PanacheEntityBase.find("code", "GONE")).thenAnswer(ignored -> answer(null));
            mocked.when(() -> PanacheEntityBase.find("code", "NONAME")).thenAnswer(ignored -> answer(nameless));
            mocked.when(() -> PanacheEntityBase.find("code", "JUSTIF")).thenAnswer(ignored -> answer(slipTemplate()));
            List<Map<String, Object>> payments = (List<Map<String, Object>>)
                    service.saleDocumentData(ticket, false, 0).get("payments");
            assertEquals("American Express", payments.get(0).get("label"));
            assertEquals("CARTE BANCAIRE", payments.get(1).get("label"));
            assertEquals("CARTE BANCAIRE", payments.get(2).get("label"));
            assertEquals("CARTE BANCAIRE", payments.get(3).get("label"));
            assertEquals(1, service.printTenderSlips(ticket, true));
        }
    }

    // ---- lot 19: K ----

    /**
     * Prints a one-line sale under the given VAT table model and returns every
     * receipt pushed to the printer.
     *
     * @param service the service
     * @param mode the VAT table model
     * @param receipts how many receipts the model prints
     * @return the receipts, in order
     */
    private List<String> printUnderVatModel(TicketPrinterService service, String mode, int receipts) {
        when(service.posSettingsService.vatTableMode()).thenReturn(mode);
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
        }
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(service.hardwareService, times(receipts)).printReceipt(captor.capture());
        return captor.getAllValues();
    }

    /**
     * BO-03-09-03: NONE prints neither the VAT amount nor the table;
     * AMOUNT_ONLY the amount alone; COMBINED the amount and the table.
     */
    @Test
    void theVatTableModelDecidesWhatTheTicketCarries() {
        String none = printUnderVatModel(newService(), "NONE", 1).get(0);
        assertFalse(none.contains("Dont TVA"));
        assertFalse(none.contains("TVA 20"));
        String amount = printUnderVatModel(newService(), "AMOUNT_ONLY", 1).get(0);
        assertTrue(amount.contains("Dont TVA"));
        assertFalse(amount.contains("TVA 20"));
        String combined = printUnderVatModel(newService(), "COMBINED", 1).get(0);
        assertTrue(combined.contains("Dont TVA"));
        assertTrue(combined.contains("TVA 20"));
    }

    /**
     * BO-03-09-03: SEPARATE prints the amount on the ticket and the table on
     * a coupon of its own, cut apart.
     */
    @Test
    void theSeparateModelPrintsTheTableOnItsOwnCoupon() {
        TicketPrinterService service = newService();
        List<String> receipts = printUnderVatModel(service, "SEPARATE", 2);
        assertTrue(receipts.get(0).contains("Dont TVA"));
        assertFalse(receipts.get(0).contains("TVA 20"));
        assertTrue(receipts.get(1).contains("DÉTAIL TVA"));
        assertTrue(receipts.get(1).contains("TVA 20"));
        assertTrue(receipts.get(1).contains("C04-00000001"));
        verify(service.hardwareService, times(2)).cutPaper();
    }

    /**
     * BO-03-09-03: the VAT coupon leaves the cancelled lines out and carries
     * the VAT letter when the register prints it.
     */
    @Test
    void theVatCouponLeavesCancelledLinesOutAndCarriesTheLetter() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        TicketLine cancelled = line("U2", "VIN", "1", "9.00", "9.00");
        cancelled.vatRate = new BigDecimal("0.0550");
        cancelled.cancelled = true;
        ticket.lines.add(cancelled);
        String plain = service.renderVatCoupon(ticket);
        assertTrue(plain.contains("TVA 20"));
        assertFalse(plain.contains("5,5"));
        when(service.posSettingsService.ticketVatCode()).thenReturn(true);
        com.intermarche.pos.domain.catalog.VatRate regime = new com.intermarche.pos.domain.catalog.VatRate();
        regime.number = 0;
        @SuppressWarnings("unchecked")
        PanacheQuery<Object> found = mock(PanacheQuery.class);
        when(found.firstResult()).thenReturn(regime);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> PanacheEntityBase.find(eq("rate"), any(Object[].class))).thenReturn(found);
            assertTrue(service.renderVatCoupon(ticket).contains("TVA A 20"));
        }
    }

    /**
     * BO-03-09-02: the head or foot model chosen for this register is the one
     * printed; an unknown code, a model of another kind, no choice at all or a
     * service without its collaborators fall back to the first of its type.
     */
    @Test
    void theChosenBannerIsTheOneOfThisRegister() {
        TicketPrinterService service = newService();
        assertNull(service.chosenBanner(DocumentTemplate.DocumentType.TICKET_HEADER));
        service.documentTemplateService = mock(DocumentTemplateService.class);
        DocumentTemplate head = new DocumentTemplate();
        head.documentType = DocumentTemplate.DocumentType.TICKET_HEADER;
        @SuppressWarnings("unchecked")
        PanacheQuery<Object> found = mock(PanacheQuery.class);
        when(found.firstResult()).thenReturn(head);
        when(service.posSettingsService.ticketHeaderTemplate()).thenReturn("ENTETE_B");
        when(service.posSettingsService.ticketFooterTemplate()).thenReturn("ENTETE_B");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> PanacheEntityBase.find("code", "ENTETE_B")).thenReturn(found);
            assertSame(head, service.chosenBanner(DocumentTemplate.DocumentType.TICKET_HEADER));
            assertNull(service.chosenBanner(DocumentTemplate.DocumentType.TICKET_FOOTER));
            when(service.posSettingsService.ticketHeaderTemplate()).thenReturn("INCONNU");
            assertNull(service.chosenBanner(DocumentTemplate.DocumentType.TICKET_HEADER));
        }
        service.posSettingsService = null;
        assertNull(service.chosenBanner(DocumentTemplate.DocumentType.TICKET_HEADER));
    }

    /**
     * BO-03-09-02: the chosen head is rendered on the ticket in place of the
     * first head of its type.
     */
    @Test
    void theChosenHeadIsPrinted() {
        TicketPrinterService service = newService();
        service.documentTemplateService = mock(DocumentTemplateService.class);
        DocumentTemplate head = new DocumentTemplate();
        head.documentType = DocumentTemplate.DocumentType.TICKET_HEADER;
        @SuppressWarnings("unchecked")
        PanacheQuery<Object> found = mock(PanacheQuery.class);
        when(found.firstResult()).thenReturn(head);
        when(service.posSettingsService.ticketHeaderTemplate()).thenReturn("ENTETE_B");
        when(service.documentTemplateService.renderTemplate(eq(head), any())).thenReturn("ENTETE CHOISIE\n");
        when(service.documentTemplateService.render(eq(DocumentTemplate.DocumentType.TICKET_HEADER), any()))
                .thenReturn("PREMIERE ENTETE\n");
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> PanacheEntityBase.find("code", "ENTETE_B")).thenReturn(found);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.contains("ENTETE CHOISIE"));
            assertFalse(out.contains("PREMIERE ENTETE"));
        }
    }

    /**
     * BO-03-10-19/20: the whole store record is offered to the documents,
     * every field empty when the store or its address is absent.
     */
    @Test
    void theStoreFieldsCarryTheWholeRecord() {
        TicketPrinterService service = newService();
        assertEquals("", service.storeFields(null).get("name"));
        assertEquals("", service.storeFields(null).get("city"));
        assertEquals(11, service.storeFields(null).size());
        Store bare = new Store();
        bare.name = "MAGASIN";
        assertEquals("MAGASIN", service.storeFields(bare).get("name"));
        assertEquals("", service.storeFields(bare).get("street"));
        Store full = store();
        full.legalName = "SAS LYON";
        full.siret = "12345678900011";
        full.vatNumber = "FR00123456789";
        full.phone = "0400000000";
        full.bankAccountNumber = "FR76";
        full.address.streetLine1 = "1 RUE";
        full.address.streetLine2 = "BAT A";
        full.address.postalCode = "69000";
        full.address.country = "FR";
        java.util.Map<String, Object> fields = service.storeFields(full);
        assertEquals("SAS LYON", fields.get("legalName"));
        assertEquals("1 RUE", fields.get("street"));
        assertEquals("BAT A", fields.get("street2"));
        assertEquals("69000", fields.get("postalCode"));
        assertEquals("LYON", fields.get("city"));
        assertEquals("FR", fields.get("country"));
        assertEquals("12345678900011", fields.get("siret"));
        assertEquals("FR00123456789", fields.get("vatNumber"));
        assertEquals("0400000000", fields.get("phone"));
        assertEquals("FR76", fields.get("bankAccount"));
    }

    /**
     * BO-03-10-20: the placeholders of an administered message are filled
     * from the store record, case, accents and spaces not mattering; an
     * unknown one is printed as written.
     */
    @Test
    void theStorePlaceholdersAreFilled() {
        java.util.Map<String, Object> store = java.util.Map.of("name", "MAGASIN LYON", "phone", "0400$1");
        assertEquals("MAGASIN LYON - 0400$1 - %Inconnu% - sans",
                TicketPrinterService.fillStorePlaceholders("%Nom Magasin% - %Téléphone% - %Inconnu% - sans", store));
        assertEquals("rien", TicketPrinterService.fillStorePlaceholders("rien", store));
    }

    /**
     * BO-03-10-20: a line of an administered head or foot template fills its
     * store placeholders; a centred line is centred again on the filled text,
     * a line that is not centred is filled in place, and a line without
     * placeholder is returned untouched.
     */
    @Test
    void theBannerLinesFillTheirStorePlaceholders() {
        java.util.Map<String, Object> store = java.util.Map.of("name", "MAGASIN LYON", "city", "LYON");
        assertEquals(" ".repeat(15) + "MAGASIN LYON",
                TicketPrinterService.fillBannerLine(" ".repeat(14) + "%Nom Magasin%", store));
        assertEquals("VILLE : LYON", TicketPrinterService.fillBannerLine("VILLE : %Ville%", store));
        assertEquals("  RIEN", TicketPrinterService.fillBannerLine("  RIEN", store));
    }

    /**
     * BO-03-10-20: a header message writing a placeholder prints the store
     * data on the ticket.
     */
    @Test
    void aHeaderMessagePrintsTheStoreData() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.ticketHeaderMessage()).thenReturn("BIENVENUE A %VILLE%");
        when(service.posSettingsService.ticketFooterMessage()).thenReturn("%MAGASIN%");
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.contains("BIENVENUE A LYON"));
            assertTrue(out.contains("MAGASIN LYON"));
            assertFalse(out.contains("%VILLE%"));
            assertFalse(out.contains("%MAGASIN%"));
        }
    }

    /**
     * Describes a sale for an administered layout with no VAT regime held
     * (BO-02-02-02: the mentions are looked up, and none answers).
     *
     * @param service the printer
     * @param ticket the sale
     * @param duplicata whether it is a reprint
     * @param number the reprint's rank
     * @return the document values
     */
    private Map<String, Object> saleData(TicketPrinterService service, Ticket ticket, boolean duplicata,
                                         int number) {
        try (MockedStatic<com.intermarche.pos.domain.catalog.VatRate> rates =
                     mockStatic(com.intermarche.pos.domain.catalog.VatRate.class)) {
            return service.saleDocumentData(ticket, duplicata, number);
        }
    }

    /**
     * Answers "no row" to the VAT regime lookups a test does not stub.
     *
     * @param panache the active static mock
     */
    @SuppressWarnings("unchecked")
    private void stubNoRegime(MockedStatic<PanacheEntityBase> panache) {
        io.quarkus.hibernate.orm.panache.PanacheQuery<Object> none =
                org.mockito.Mockito.mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        panache.when(() -> PanacheEntityBase.find(org.mockito.ArgumentMatchers.eq("rate"),
                org.mockito.ArgumentMatchers.<Object>any())).thenReturn(none);
    }
    /**
     * Builds a Portuguese exemption regime with its legal ground.
     *
     * @param number the regime number
     * @param mention its legal mention
     * @return the regime
     */
    private com.intermarche.pos.domain.catalog.VatRate exemption(int number, String mention) {
        com.intermarche.pos.domain.catalog.VatRate regime =
                new com.intermarche.pos.domain.catalog.VatRate(number, new BigDecimal("0.0000"), "Isento");
        regime.exempt = true;
        regime.legalMention = mention;
        return regime;
    }

    /**
     * BO-02-02-02: a receipt states no line but the legal mention of the regimes
     * of the invoice it settles, read by the regime number its lines kept
     * (BO-02-02-01).
     */
    @Test
    void aReceiptStatesTheMentionsOfItsInvoice() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        TicketLine book = line("A", "LIVRO", "1", "8.00", "8.00");
        book.vatRate = new BigDecimal("0.0000");
        book.vatNumber = 10;
        ticket.lines.add(book);
        ticket.payments.add(new CashPayment(new BigDecimal("8.00"), new BigDecimal("8.00")));
        FiscalDocument receipt = ptDocument(FiscalDocument.Type.RC, 1);
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(ticket);
            PanacheQuery<?> regime10 = vatQuery(exemption(10, "Isento artigo 9.º do CIVA"));
            panache.when(() -> PanacheEntityBase.find("number", 10)).thenReturn((PanacheQuery) regime10);
            service.printFiscalDocument(receipt);
        }
        String paper = captureReceipt(service);
        assertTrue(paper.contains("Isento artigo 9.º do CIVA\n"), paper);
        assertFalse(paper.contains("LIVRO"), paper);
    }

    /**
     * BO-02-02-01/02: two exemptions of one percentage on one invoice are two
     * ventilation lines and two mentions, each named by its own regime.
     */
    @Test
    void twoExemptionsOfOnePercentageKeepTheirOwnMentions() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.ticketVatCode()).thenReturn(true);
        Ticket ticket = ticket(0, null);
        TicketLine book = line("A", "LIVRO", "1", "8.00", "8.00");
        book.vatRate = new BigDecimal("0.0000");
        book.vatNumber = 10;
        TicketLine export = line("B", "EXPORT", "1", "2.00", "2.00");
        export.vatRate = new BigDecimal("0.0000");
        export.vatNumber = 11;
        TicketLine unknown = line("C", "OUTRO", "1", "1.13", "1.13");
        unknown.vatRate = new BigDecimal("0.1300");
        ticket.lines.add(book);
        ticket.lines.add(export);
        ticket.lines.add(unknown);
        FiscalDocument invoice = ptDocument(FiscalDocument.Type.FT, 1);
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(ticket);
            PanacheQuery<?> regime10 = vatQuery(exemption(10, "Isento artigo 9.º"));
            panache.when(() -> PanacheEntityBase.find("number", 10)).thenReturn((PanacheQuery) regime10);
            PanacheQuery<?> regime11 = vatQuery(exemption(11, "Isento artigo 14.º"));
            panache.when(() -> PanacheEntityBase.find("number", 11)).thenReturn((PanacheQuery) regime11);
            service.printFiscalDocument(invoice);
        }
        String paper = captureReceipt(service);
        assertTrue(paper.contains("Isento artigo 9.º\nIsento artigo 14.º\n"), paper);
        assertTrue(paper.contains("LIVRO"), paper);
        assertTrue(paper.contains("K 0,00%"), paper);
        assertTrue(paper.contains("L 0,00%"), paper);
        assertTrue(paper.contains("13,00%"), paper);
    }

    /**
     * BO-02-03-26/-42: a Portuguese invoice prints the same ventilation
     * headings as the sale ticket: an exempt line under its regime's code and
     * EXO, a line sold for a third party under its own third-party heading.
     */
    @Test
    void anInvoicePrintsTheExemptAndThirdPartyHeadingsOfTheTicket() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.ticketVatCode()).thenReturn(true);
        Ticket ticket = ticket(0, null);
        TicketLine book = line("A", "LIVRO", "1", "8.00", "8.00");
        book.vatRate = new BigDecimal("0.0000");
        book.vatNumber = 10;
        book.vatExempt = true;
        TicketLine stamp = line("B", "SELO", "1", "1.20", "1.20");
        stamp.vatRate = new BigDecimal("0.2000");
        stamp.thirdPartySale = true;
        ticket.lines.add(book);
        ticket.lines.add(stamp);
        FiscalDocument invoice = ptDocument(FiscalDocument.Type.FT, 1);
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(ticket);
            PanacheQuery<?> regime10 = vatQuery(exemption(10, "Isento artigo 9.º"));
            panache.when(() -> PanacheEntityBase.find("number", 10)).thenReturn((PanacheQuery) regime10);
            service.printFiscalDocument(invoice);
        }
        String paper = captureReceipt(service);
        assertTrue(paper.contains("K EXO"), paper);
        assertFalse(paper.contains("K 0,00%"), paper);
        assertTrue(paper.contains("20,00% (cpte tiers)"), paper);
        assertTrue(paper.contains("Isento artigo 9.º\n"), paper);
    }

    /**
     * BO-02-02-01: the return document names the regime of the sold line it
     * gives back; a refund line whose original line is unknown, or that names
     * none, falls back on its rate.
     */
    @Test
    void aReturnNamesTheRegimeOfTheOriginalLine() {
        TicketPrinterService service = newService();
        RefundLine back = refundLine("LIVRO", "1", "8.00");
        back.vatRate = new BigDecimal("0.0000");
        back.originalLineId = 70L;
        Refund refund = refund(1L, Refund.RefundMethod.CASH, back);
        RefundLine lost = refundLine("SEL", "1", "1.00");
        lost.originalLineId = 71L;
        refund.lines.add(lost);
        refund.lines.add(refundLine("PAIN", "1", "1.00"));
        TicketLine original = line("A", "LIVRO", "1", "8.00", "8.00");
        original.vatNumber = 10;
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            panache.when(() -> PanacheEntityBase.findById(7L)).thenReturn(refund);
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(ticket(0, null));
            panache.when(() -> PanacheEntityBase.findById(70L)).thenReturn(original);
            PanacheQuery<?> regime10 = vatQuery(exemption(10, "Isento artigo 9.º"));
            panache.when(() -> PanacheEntityBase.find("number", 10)).thenReturn((PanacheQuery) regime10);
            service.printRefund(7L);
        }
        assertTrue(captureReceipt(service).contains("Isento artigo 9.º\n"));
    }

    /**
     * BO-02-02-02: an administered layout receives the legal mentions of the
     * sale, its cancelled lines left out, and a sale without lines owes none;
     * each VAT row carries its own mention.
     */
    @Test
    @SuppressWarnings("unchecked")
    void anAdministeredLayoutReceivesTheMentions() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        TicketLine book = line("A", "LIVRO", "1", "8.00", "8.00");
        book.vatRate = new BigDecimal("0.0000");
        book.vatNumber = 10;
        TicketLine cancelled = line("B", "VINHO", "1", "5.00", "5.00");
        cancelled.cancelled = true;
        cancelled.vatNumber = 12;
        ticket.lines.add(book);
        ticket.lines.add(cancelled);
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            PanacheQuery<?> regime10 = vatQuery(exemption(10, "Isento artigo 9.º"));
            panache.when(() -> PanacheEntityBase.find("number", 10)).thenReturn((PanacheQuery) regime10);
            PanacheQuery<?> regime12 = vatQuery(exemption(12, "Nunca"));
            panache.when(() -> PanacheEntityBase.find("number", 12)).thenReturn((PanacheQuery) regime12);
            Map<String, Object> data = service.saleDocumentData(ticket, false, 0);
            assertEquals(List.of("Isento artigo 9.º"), data.get("vatMentions"));
            List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("vatRows");
            assertEquals("Isento artigo 9.º", rows.get(0).get("mention"));
            Ticket empty = ticket(0, null);
            empty.lines = null;
            assertEquals(List.of(), service.saleVatMentions(empty));
        }
    }

    /**
     * BO-02-02-01: the VAT coupon names each regime by the number its lines
     * kept — regime 10 is K.
     */
    @Test
    void theVatCouponNamesTheRegimeByItsNumber() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.ticketVatCode()).thenReturn(true);
        Ticket ticket = ticket(0, null);
        TicketLine book = line("A", "LIVRO", "1", "8.00", "8.00");
        book.vatRate = new BigDecimal("0.0000");
        book.vatNumber = 10;
        ticket.lines.add(book);
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            PanacheQuery<?> regime10 = vatQuery(exemption(10, null));
            panache.when(() -> PanacheEntityBase.find("number", 10)).thenReturn((PanacheQuery) regime10);
            assertTrue(service.renderVatCoupon(ticket).contains("K 0,00%"));
        }
    }

    /**
     * BO-02-03-26/42: with the VAT code administered, the exempt heading prints
     * without a code letter and the heading of the lines sold for a third party
     * carries its mention.
     */
    @Test
    void exemptAndThirdPartyHeadingsPrintUnderTheirOwnLabels() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.ticketVatCode()).thenReturn(true);
        Ticket ticket = ticket(0, null);
        TicketLine exempt = line("U1", "LIVRE", "1", "10.00", "10.00");
        exempt.vatRate = BigDecimal.ZERO;
        exempt.vatExempt = true;
        TicketLine third = line("U2", "TIMBRE", "1", "1.50", "1.50");
        third.thirdPartySale = true;
        ticket.lines.add(exempt);
        ticket.lines.add(third);
        com.intermarche.pos.domain.catalog.VatRate regime = new com.intermarche.pos.domain.catalog.VatRate();
        regime.number = 1;
        regime.rate = new BigDecimal("0.2000");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            mocked.when(() -> PanacheEntityBase.find(eq("rate"), any(Object[].class)))
                    .thenAnswer(ignored -> vatQuery(regime));
            service.printTicket(1L);
            String receipt = captureReceipt(service);
            assertTrue(receipt.contains("EXO"));
            assertFalse(java.util.regex.Pattern.compile("TVA [A-Z] EXO").matcher(receipt).find());
            assertTrue(receipt.contains("TVA EXO"));
            assertTrue(receipt.contains("20,00% (cpte tiers)"));
        }
    }

    /**
     * BO-02-02-01/02 with BO-02-03-26: on the sale ticket two exempt regimes of
     * one percentage are two EXO headings, each with its own code and its own
     * mention; the separate VAT coupon states the same headings, the third
     * party's included (BO-02-03-42).
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void twoExemptRegimesPrintTwoExoHeadingsWithTheirCodesAndMentions() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.ticketVatCode()).thenReturn(true);
        Ticket ticket = ticket(0, null);
        TicketLine book = line("U1", "LIVRO", "1", "8.00", "8.00");
        book.vatRate = BigDecimal.ZERO;
        book.vatNumber = 10;
        book.vatExempt = true;
        TicketLine export = line("U2", "EXPORT", "1", "2.00", "2.00");
        export.vatRate = BigDecimal.ZERO;
        export.vatNumber = 11;
        export.vatExempt = true;
        TicketLine stamp = line("U3", "TIMBRE", "1", "1.20", "1.20");
        stamp.vatRate = new BigDecimal("0.2000");
        stamp.vatNumber = 1;
        stamp.thirdPartySale = true;
        ticket.lines.add(book);
        ticket.lines.add(export);
        ticket.lines.add(stamp);
        com.intermarche.pos.domain.catalog.VatRate normal =
                new com.intermarche.pos.domain.catalog.VatRate(1, new BigDecimal("0.2000"), "Normal");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            PanacheQuery<?> regime1 = vatQuery(normal);
            mocked.when(() -> PanacheEntityBase.find("number", 1)).thenReturn((PanacheQuery) regime1);
            PanacheQuery<?> regime10 = vatQuery(exemption(10, "Isento artigo 9.º"));
            mocked.when(() -> PanacheEntityBase.find("number", 10)).thenReturn((PanacheQuery) regime10);
            PanacheQuery<?> regime11 = vatQuery(exemption(11, "Isento artigo 14.º"));
            mocked.when(() -> PanacheEntityBase.find("number", 11)).thenReturn((PanacheQuery) regime11);
            service.printTicket(1L);
            String receipt = captureReceipt(service);
            assertTrue(receipt.contains("TVA K EXO"), receipt);
            assertTrue(receipt.contains("TVA L EXO"), receipt);
            assertTrue(receipt.contains("B 20,00% (cpte tiers)"), receipt);
            assertTrue(receipt.contains("Isento artigo 9.º\nIsento artigo 14.º\n"), receipt);
            String coupon = service.renderVatCoupon(ticket);
            assertTrue(coupon.contains("TVA K EXO"), coupon);
            assertTrue(coupon.contains("TVA L EXO"), coupon);
            assertTrue(coupon.contains("B 20,00% (cpte tiers)"), coupon);
        }
    }

    /**
     * A self-scanning REDUCTION voucher prints its own label on the receipt, as
     * the payment screen and the customer display name it, and not the generic
     * « BON D'ACHAT »; a voucher whose label is blank keeps the generic word.
     */
    @Test
    void aVoucherPrintsItsOwnLabelOnTheReceipt() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        ticket.payments.add(new com.intermarche.pos.domain.payment.VoucherPayment(
                new BigDecimal("1.00"), " BON DE REDUCTION 1 EUR ", "BR-9"));
        ticket.payments.add(new com.intermarche.pos.domain.payment.VoucherPayment(
                new BigDecimal("1.00"), "   ", "B2"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            silenceCatalog(mocked);
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(new ArrayList<TicketLineValuation>());
            service.printTicket(1L);
            String out = captureReceipt(service);
            assertTrue(out.contains("BON DE REDUCTION 1 EUR"), out);
            assertEquals(1, out.split("BON D'ACHAT", -1).length - 1, out);
        }
    }

    /**
     * The exchange voucher writes each quantity as the receipt writes quantities,
     * ahead of the label and never in the money format of a price column: a count
     * without decimals, a weight with its decimals and symbol, a unit-of-measure
     * quantity with its unit.
     */
    @Test
    void printExchangeVoucherWritesQuantitiesAsQuantities() {
        TicketPrinterService service = newService();
        Ticket ticket = ticket(0, null);
        TicketLine count = line("U1", "LAIT UHT 1L", "1", "1.00", "1.00");
        count.id = 1L;
        TicketLine weighed = line("U2", "BANANES", "1.5", "2.00", "3.00");
        weighed.id = 2L;
        weighed.plu = "4011";
        TicketLine measured = line("U3", "CABLE", "2.5", "1.00", "2.50");
        measured.id = 3L;
        measured.unitName = "m";
        TicketLine emptyPlu = line("U4", "YAOURT", "2", "0.50", "1.00");
        emptyPlu.id = 4L;
        emptyPlu.plu = "";
        emptyPlu.unitName = " ";
        ticket.lines.add(count);
        ticket.lines.add(weighed);
        ticket.lines.add(measured);
        ticket.lines.add(emptyPlu);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            service.printExchangeVoucher(1L, null);
        }
        String out = captureReceipt(service);
        assertTrue(out.contains("1        LAIT UHT 1L\n"), out);
        assertTrue(out.contains("1,500 kg BANANES\n"), out);
        assertTrue(out.contains("2,50 m   CABLE\n"), out);
        assertTrue(out.contains("2        YAOURT\n"), out);
        assertFalse(out.contains("1,00"), out);
    }
}
