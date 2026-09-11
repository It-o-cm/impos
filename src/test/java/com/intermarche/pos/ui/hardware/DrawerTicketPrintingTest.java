package com.intermarche.pos.ui.hardware;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the two drawer papers ({@code LC-12-03-07} withdrawal and
 * {@code LC-12-10-05} settlement transfer).
 *
 * <p>Neither ticket reads a database, so both are exercised on a hand-written printer
 * stand-in rather than on Mockito: what is asserted is the TEXT, since that is the
 * whole of what these two methods produce and the only thing a manager reading the
 * drawer at closing time ever sees.
 *
 * <p>Every arm the rendering has is covered: lines and no lines, a named operator and
 * an anonymous one, a named register and none.
 */
class DrawerTicketPrintingTest {

    /** A hardware service that keeps what it was asked to print. */
    private static class FakeHardware extends HardwareService {

        /** The last content pushed to the printer. */
        private String printed = "";

        /** How many times the paper was cut. */
        private int cuts = 0;

        /** {@inheritDoc} */
        @Override
        public void printReceipt(String content) {
            printed = content;
        }

        /** {@inheritDoc} */
        @Override
        public void cutPaper() {
            cuts++;
        }
    }

    /** The service under test. */
    private TicketPrinterService service;

    /** The stand-in printer. */
    private FakeHardware hardware;

    /**
     * Wires a fresh service to a fresh printer before each test.
     */
    @BeforeEach
    void setUp() {
        service = new TicketPrinterService();
        hardware = new FakeHardware();
        service.hardwareService = hardware;
    }

    /**
     * The withdrawal ticket names the gesture, the tender, the register, the operator,
     * every counted line and the total, then cuts the paper.
     */
    @Test
    void theWithdrawalTicketStatesTheTenderTheLinesAndTheTotal() {
        service.printWithdrawalTicket("Espèces", new BigDecimal("70.00"),
                List.of(new String[] {"Billet 50 € x1", "50,00 E"},
                        new String[] {"Pièce 1 € x20", "20,00 E"}),
                "Alice", "C04");
        String text = hardware.printed;
        assertTrue(text.contains("PRELEVEMENT"));
        assertTrue(text.contains("Moyen  : Espèces"));
        assertTrue(text.contains("Caisse : C04"));
        assertTrue(text.contains("Operateur : Alice"));
        assertTrue(text.contains("Billet 50 € x1"));
        assertTrue(text.contains("50,00 E"));
        assertTrue(text.contains("Pièce 1 € x20"));
        assertTrue(text.contains("TOTAL PRELEVE"));
        assertTrue(text.contains("Signature:"));
        assertEquals(1, hardware.cuts);
    }

    /**
     * A withdrawal with nothing to detail still prints its total: the paper exists to
     * be counter-signed, and a tender with no line is not a reason to print nothing.
     */
    @Test
    void theWithdrawalTicketWithoutLinesStillStatesItsTotal() {
        service.printWithdrawalTicket("Chèques", new BigDecimal("80.00"),
                List.of(), "Alice", "C04");
        assertTrue(hardware.printed.contains("TOTAL PRELEVE"));
        assertEquals(1, hardware.cuts);
    }

    /**
     * A null line list is the same as an empty one: an absent detail is not a failure
     * to print.
     */
    @Test
    void theWithdrawalTicketToleratesANullLineList() {
        service.printWithdrawalTicket("Chèques", new BigDecimal("80.00"), null, "Alice", "C04");
        assertTrue(hardware.printed.contains("TOTAL PRELEVE"));
    }

    /**
     * An unknown operator prints no operator line at all rather than an empty one, on
     * both the null and the blank arms.
     */
    @Test
    void theWithdrawalTicketOmitsAnUnknownOperator() {
        service.printWithdrawalTicket("Espèces", BigDecimal.ONE, List.of(), null, "C04");
        assertFalse(hardware.printed.contains("Operateur"));
        service.printWithdrawalTicket("Espèces", BigDecimal.ONE, List.of(), "  ", "C04");
        assertFalse(hardware.printed.contains("Operateur"));
    }

    /**
     * An unnamed register prints no register line rather than a blank one.
     */
    @Test
    void theWithdrawalTicketOmitsAnUnnamedRegister() {
        service.printWithdrawalTicket("Espèces", BigDecimal.ONE, List.of(), "Alice", null);
        assertFalse(hardware.printed.contains("Caisse"));
    }

    /**
     * The transfer ticket states the three facts it exists for — where the amount left,
     * where it landed, and how much — and cuts the paper.
     */
    @Test
    void theTransferTicketStatesBothEndsAndTheAmount() {
        service.printTransferTicket("Chèques", "Espèces", new BigDecimal("20.00"), "Alice", "C04");
        String text = hardware.printed;
        assertTrue(text.contains("TRANSFERT REGLEMENT"));
        assertTrue(text.contains("Caisse : C04"));
        assertTrue(text.contains("Operateur : Alice"));
        assertTrue(text.contains("DE"));
        assertTrue(text.contains("Chèques"));
        assertTrue(text.contains("VERS"));
        assertTrue(text.contains("Espèces"));
        assertTrue(text.contains("MONTANT"));
        assertTrue(text.contains("Signature:"));
        assertEquals(1, hardware.cuts);
    }

    /**
     * The transfer ticket omits an unknown operator and an unnamed register too, on
     * every arm of both.
     */
    @Test
    void theTransferTicketOmitsWhatItDoesNotKnow() {
        service.printTransferTicket("Chèques", "Espèces", BigDecimal.ONE, null, null);
        assertFalse(hardware.printed.contains("Operateur"));
        assertFalse(hardware.printed.contains("Caisse"));
        service.printTransferTicket("Chèques", "Espèces", BigDecimal.ONE, "  ", "C04");
        assertFalse(hardware.printed.contains("Operateur"));
        assertTrue(hardware.printed.contains("Caisse : C04"));
    }
}
