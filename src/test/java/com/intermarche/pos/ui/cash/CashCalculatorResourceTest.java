package com.intermarche.pos.ui.cash;

import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CashCalculatorResource} (EXP-04).
 * <p>
 * The resource is a thin facade: it serves the float-calculator page with the
 * calculator's banknotes and the count page's coins and rolls, and records
 * nothing. The {@link CashCountService} is a real instance (its coin and roll
 * lists are the arithmetic reused here) and the Qute {@link Template} is a mock
 * whose fluent {@code data(...)} chain records the pairs into a sink so the fed
 * data can be asserted. The printer, the register naming and the screen state are
 * mocks: printing is the only thing this screen sends anywhere, and it still writes
 * no movement. No database and no Quarkus context is booted.
 */
class CashCalculatorResourceTest {

    /**
     * Builds a resource with a recording template and a real count service.
     *
     * @param sink the map the recorded key-and-value pairs are written into
     * @return the resource under test
     */
    private CashCalculatorResource newResource(Map<String, Object> sink) {
        CashCalculatorResource resource = new CashCalculatorResource();
        resource.cashCountService = new CashCountService();
        TemplateInstance instance = mock(TemplateInstance.class);
        when(instance.data(anyString(), any())).thenAnswer(invocation -> {
            sink.put(invocation.getArgument(0), invocation.getArgument(1));
            return instance;
        });
        Template template = mock(Template.class);
        when(template.data(anyString(), any())).thenAnswer(invocation -> {
            sink.put(invocation.getArgument(0), invocation.getArgument(1));
            return instance;
        });
        resource.cashCalculator = template;
        resource.ticketPrinterService =
                mock(com.intermarche.pos.ui.hardware.TicketPrinterService.class);
        resource.ticketNumberService =
                mock(com.intermarche.pos.service.TicketNumberService.class);
        resource.state = mock(com.intermarche.pos.ui.PosState.class);
        return resource;
    }

    /**
     * {@code calculatorPage} feeds the calculator banknotes plus the count
     * page's coins and rolls, and returns the rendered view.
     */
    @Test
    void calculatorPageFeedsTheDenominations() {
        Map<String, Object> sink = new LinkedHashMap<>();
        CashCalculatorResource resource = newResource(sink);
        resource.calculatorPage(null);
        assertEquals(resource.cashCountService.getCoins().size(), ((List<?>) sink.get("coins")).size());
        assertEquals(resource.cashCountService.getRolls().size(), ((List<?>) sink.get("rolls")).size());
        assertEquals(6, ((List<?>) sink.get("bills")).size());
    }

    /**
     * The page says whether the previous count was printed, on both arms of the
     * flag: the banner is the only answer the cashier gets, the slip being on paper.
     */
    @Test
    void calculatorPageSaysWhetherTheCountWasPrinted() {
        Map<String, Object> sink = new LinkedHashMap<>();
        CashCalculatorResource resource = newResource(sink);
        resource.calculatorPage(null);
        assertEquals(Boolean.FALSE, sink.get("printed"));
        resource.calculatorPage("1");
        assertEquals(Boolean.TRUE, sink.get("printed"));
    }

    /**
     * Printing a count hands the printer the total and the counted denominations —
     * the calculator's own banknotes included, which the Z count does not carry —
     * and records NOTHING: no movement, no event. The cashier lands back on the
     * calculator, which still holds her figures.
     */
    @Test
    void printCountPrintsTheSlipAndRecordsNothing() {
        Map<String, Object> sink = new LinkedHashMap<>();
        CashCalculatorResource resource = newResource(sink);
        when(resource.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(resource.state.getOperatorName()).thenReturn("MARIE");
        Response response = resource.printCount("260,50", "{\"b200\":1,\"b50\":1,\"c050\":21}");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/cash-calculator?printed=1", response.getLocation().toString());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String[]>> lines = ArgumentCaptor.forClass(List.class);
        verify(resource.ticketPrinterService).printFloatCountTicket(
                eq(new BigDecimal("260.50")), lines.capture(), eq("MARIE"), eq("C04"));
        assertEquals(3, lines.getValue().size());
        assertEquals("Billet 200 € x1", lines.getValue().get(0)[0]);
        assertEquals("200,00 E", lines.getValue().get(0)[1]);
    }

    /**
     * A count that adds up to NOTHING prints nothing (non-positive arm) and comes
     * back plainly, without the printed banner: an empty total is a count not yet
     * made, not a mistake.
     */
    @Test
    void printCountOfAnEmptyDrawerPrintsNothing() {
        Map<String, Object> sink = new LinkedHashMap<>();
        CashCalculatorResource resource = newResource(sink);
        Response response = resource.printCount("0", "{}");
        assertEquals("/cash-calculator", response.getLocation().toString());
        verifyNoInteractions(resource.ticketPrinterService);
    }

    /**
     * A null, a blank and an unreadable total all read as zero (the three arms of
     * the amount reading) and print nothing.
     */
    @Test
    void printCountOfAnUnreadableTotalPrintsNothing() {
        Map<String, Object> sink = new LinkedHashMap<>();
        CashCalculatorResource resource = newResource(sink);
        assertEquals("/cash-calculator", resource.printCount(null, "{}").getLocation().toString());
        assertEquals("/cash-calculator", resource.printCount("   ", "{}").getLocation().toString());
        assertEquals("/cash-calculator", resource.printCount("abc", "{}").getLocation().toString());
        verifyNoInteractions(resource.ticketPrinterService);
    }

    /**
     * The calculator banknotes run from 200 € down to 5 €, the notes a cashier
     * may hold when making up the float (the Z count stops at 50 €).
     */
    @Test
    void calculatorBillsRunFromTwoHundredDownToFive() {
        CashCalculatorResource resource = new CashCalculatorResource();
        List<CashItem> bills = resource.calculatorBills();
        assertEquals(6, bills.size());
        assertEquals("b200", bills.get(0).id);
        assertEquals(200.0, bills.get(0).value);
        assertEquals("b5", bills.get(bills.size() - 1).id);
        assertEquals(5.0, bills.get(bills.size() - 1).value);
    }
}
