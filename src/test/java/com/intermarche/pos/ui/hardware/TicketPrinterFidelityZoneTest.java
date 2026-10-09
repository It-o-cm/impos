package com.intermarche.pos.ui.hardware;

import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.sale.TicketFidelityLine;
import com.intermarche.pos.domain.sale.TicketLine;
import com.intermarche.pos.domain.store.Address;
import com.intermarche.pos.domain.store.Store;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.ui.TestMessages;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the fidelity zone of {@link TicketPrinterService} added by the
 * imfid-contracts integration: the advantage-type grouping (imfid response A),
 * the projected balance printed as PREVISIONNEL (response B) and the anonymous
 * projection for a card-less ticket (response C).
 * <p>
 * The printer is built with mocked hardware and parameters, exactly as the
 * golden-master test does, and its {@code renderTicket} output is asserted
 * directly. No {@code @QuarkusTest}, no database.
 */
class TicketPrinterFidelityZoneTest {

    /** A fixed instant so no date moves the rendered text. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 17, 10, 42, 5);

    /**
     * Builds a printer whose hardware and parameters are mocked, with the
     * fidelity advantages enabled and a fixed projected-balance label.
     *
     * @return the wired printer
     */
    private TicketPrinterService newService() {
        TicketPrinterService service = new TicketPrinterService();
        // The operator messages come from the French bundle, so every
        // assertion below keeps the literal sentence the register shows
        // (LC-13-06).
        service.i18n = TestMessages.french();
        service.hardwareService = mock(HardwareService.class);
        service.technicalEventService = mock(TechnicalEventService.class);
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.fidelityAdvantagesEnabled()).thenReturn(true);
        when(service.posSettingsService.vatBreakdownEnabled()).thenReturn(true);
        when(service.posSettingsService.fidelityProjectedLabel()).thenReturn("CAGNOTTE PREVISIONNELLE");
        return service;
    }

    /**
     * Renders a ticket with the Panache statics neutralised (plain mvn test
     * does not enhance entities), mirroring the golden-master test: the
     * per-line valuation lookup then returns an empty list.
     *
     * @param service the printer under test
     * @param ticket the sale to render
     * @return the rendered receipt text
     */
    private String render(TicketPrinterService service, Ticket ticket) {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            return service.renderTicket(ticket, false, 0);
        }
    }

    /**
     * Builds a minimal but complete sale carrying an empty fidelity zone, ready
     * for a test to fill with advantage lines and projection fields.
     *
     * @return the sale
     */
    private Ticket sale() {
        Ticket ticket = new Ticket();
        ticket.id = 1L;
        ticket.ticketNumber = "C04-00000001";
        ticket.creationDate = NOW;
        Store store = new Store();
        store.name = "INTERMARCHE LYON";
        store.address = new Address();
        ticket.store = store;
        Employee cashier = new Employee();
        cashier.loginName = "jdupont";
        cashier.firstName = "Jean";
        cashier.lastName = "Dupont";
        ticket.cashier = cashier;
        ticket.terminalId = "C04";
        ticket.lines = new ArrayList<>();
        TicketLine line = new TicketLine();
        line.lineUid = "u1";
        line.productLabel = "PAIN COMPLET";
        line.quantity = new BigDecimal("1");
        line.unitPrice = new BigDecimal("2.00");
        line.totalPrice = new BigDecimal("2.00");
        line.vatRate = new BigDecimal("0.2000");
        ticket.lines.add(line);
        ticket.payments = new ArrayList<>();
        ticket.payments.add(new com.intermarche.pos.domain.payment.CashPayment(
                new BigDecimal("2.00"), new BigDecimal("2.00")));
        ticket.fidelityLines = new ArrayList<>();
        ticket.totalIncludingTax = new BigDecimal("2.00");
        ticket.totalExcludingTax = new BigDecimal("1.67");
        ticket.totalVat = new BigDecimal("0.33");
        ticket.fidelityCard = "2990000000019";
        ticket.fidelityEarnTotal = new BigDecimal("3.90");
        return ticket;
    }

    /**
     * Builds one frozen advantage line with its grouping.
     *
     * @param label the printable label
     * @param amount the earn amount
     * @param type the advantage type code
     * @param typeLabel the advantage type label
     * @param order the group print order
     * @return the frozen line
     */
    private TicketFidelityLine advLine(String label, String amount, String type,
            String typeLabel, Integer order) {
        return new TicketFidelityLine("R-" + label, label, new BigDecimal(amount),
                type, typeLabel, order, null, null);
    }

    /**
     * The advantage lines are GROUPED by type and the groups ORDERED by
     * advantageTypeOrder ascending, whatever the arrival order, and the received
     * labels are printed verbatim (imfid response A).
     */
    @Test
    void groupsAdvantagesAndOrdersThemByType() {
        TicketPrinterService service = newService();
        Ticket ticket = sale();
        // Added ticket-order (20) first, product-order (10) second: the output
        // must still print PRODUCT before TICKET.
        ticket.fidelityLines.add(advLine("Palier 50 euros", "2.40", "TICKET", "Vos avantages ticket", 20));
        ticket.fidelityLines.add(advLine("Boost marque", "1.50", "PRODUCT", "Vos avantages produits", 10));
        String receipt = render(service, ticket);
        assertTrue(receipt.contains("Vos avantages produits"));
        assertTrue(receipt.contains("Vos avantages ticket"));
        assertTrue(receipt.contains("Boost marque"));
        assertTrue(receipt.contains("Palier 50 euros"));
        assertTrue(receipt.indexOf("Vos avantages produits") < receipt.indexOf("Vos avantages ticket"),
                "PRODUCT (order 10) must print before TICKET (order 20)");
    }

    /**
     * When NO line carries an advantage type (legacy rules), the zone falls back
     * to the flat list with no group header (imfid response A last bullet).
     */
    @Test
    void fallsBackToTheFlatListWhenNoAdvantageType() {
        TicketPrinterService service = newService();
        Ticket ticket = sale();
        ticket.fidelityLines.add(new TicketFidelityLine("SOCLE", "Cagnotte socle", new BigDecimal("0.28")));
        String receipt = render(service, ticket);
        assertTrue(receipt.contains("CAGNOTTE DU JOUR"));
        assertTrue(receipt.contains("Cagnotte socle"));
        assertFalse(receipt.contains("Vos avantages"));
    }

    /**
     * The projected balance prints under the administered label, marked
     * PREVISIONNEL (imfid response B); nothing is credited before ingestion.
     */
    @Test
    void printsTheProjectedBalanceAsPrevisionnel() {
        TicketPrinterService service = newService();
        Ticket ticket = sale();
        ticket.fidelityLines.add(advLine("Boost marque", "1.50", "PRODUCT", "Vos avantages produits", 10));
        ticket.fidelityProjectedAfterEarn = new BigDecimal("46.20");
        String receipt = render(service, ticket);
        assertTrue(receipt.contains("CAGNOTTE PREVISIONNELLE"));
        assertTrue(receipt.contains("(PREVISIONNEL)"));
        assertTrue(receipt.contains("46,20"));
    }

    /**
     * A card-less ticket flagged as an anonymous projection prints « ce que vous
     * auriez gagné avec la carte » and no cagnotte-du-jour header (imfid
     * response C).
     */
    @Test
    void printsTheAnonymousProjectionPhrase() {
        TicketPrinterService service = newService();
        Ticket ticket = sale();
        ticket.fidelityCard = null;
        ticket.fidelityAnonymousProjection = true;
        String receipt = render(service, ticket);
        assertTrue(receipt.toLowerCase().contains("auriez gagne"));
        assertFalse(receipt.contains("CAGNOTTE DU JOUR"));
    }
}
