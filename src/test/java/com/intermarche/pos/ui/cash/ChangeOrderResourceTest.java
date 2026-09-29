package com.intermarche.pos.ui.cash;

import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TicketNumberService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.auth.AuthState;
import com.intermarche.pos.ui.hardware.TicketPrinterService;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ChangeOrderResource}.
 * <p>
 * The resource is a thin JAX-RS facade over a real {@link ChangeOrderState}
 * carried by a mocked {@link PosState}, a real {@link CashCountService}
 * (a dependency-free denomination catalog), a {@link PosSettingsService}, a
 * {@link TicketPrinterService}, a {@link TicketNumberService} and one Qute
 * {@link Template}. No database and no Quarkus context is booted.
 * <p>
 * Branch enumeration (both arms, 100%): the page's three {@code messageOf}
 * codes plus the unknown-code null arm and the saved flag; {@code entry}'s
 * bad-denomination arm (null id and unknown id), the AMOUNT and ROLLS type arms
 * (with and without an administered roll value) and the bad-type arm;
 * {@code clear}; {@code printEmpty}; {@code print}'s disabled and enabled arms;
 * and the {@code parseAmount}/{@code parseRolls} negative-to-zero arms.
 */
class ChangeOrderResourceTest {

    /**
     * Builds a resource with fresh mocks, a real {@link ChangeOrderState} and a
     * real {@link CashCountService}; the operator is logged (badge {@code 17},
     * name {@code Marie}).
     *
     * @return the wired resource
     */
    private ChangeOrderResource newResource() {
        ChangeOrderResource resource = new ChangeOrderResource();
        resource.state = mock(PosState.class);
        AuthState auth = new AuthState();
        auth.operatorBadgeId = "17";
        auth.operatorName = "Marie";
        resource.state.auth = auth;
        resource.state.changeOrder = new ChangeOrderState();
        when(resource.state.getOperatorName()).thenReturn("Marie");
        resource.cashCountService = new CashCountService();
        resource.posSettingsService = mock(PosSettingsService.class);
        resource.ticketPrinterService = mock(TicketPrinterService.class);
        resource.ticketNumberService = mock(TicketNumberService.class);
        resource.changeOrder = mock(Template.class);
        when(resource.posSettingsService.changeOrderRollValues())
                .thenReturn("0.20:8.00;0.01:0.50");
        when(resource.ticketNumberService.getTerminalId()).thenReturn("C04");
        return resource;
    }

    /**
     * Stubs the page template chain, returning the link that receives the
     * {@code error} data and the rendered view.
     *
     * @param resource the resource whose template is stubbed
     * @return the two links: [errorLink, result]
     */
    private TemplateInstance[] stubPageChain(ChangeOrderResource resource) {
        TemplateInstance ti1 = mock(TemplateInstance.class);
        TemplateInstance ti2 = mock(TemplateInstance.class);
        TemplateInstance ti3 = mock(TemplateInstance.class);
        TemplateInstance ti4 = mock(TemplateInstance.class);
        when(resource.changeOrder.data("state", resource.state)).thenReturn(ti1);
        when(ti1.data(eq("denominations"), any())).thenReturn(ti2);
        when(ti2.data(eq("saved"), any())).thenReturn(ti3);
        when(ti3.data(eq("error"), any())).thenReturn(ti4);
        return new TemplateInstance[]{ti3, ti4};
    }

    /**
     * Asserts the given response is a 303 redirect to the expected location.
     *
     * @param response the response under test
     * @param location the expected {@code Location} header value
     */
    private void assertRedirect(Response response, String location) {
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals(location, response.getLocation().toString());
    }

    /**
     * The page maps every error code to its French message (three true arms), a
     * null for an unknown code (else arm), and raises the saved flag on {@code ok}.
     */
    @Test
    void pageMapsEveryMessageAndSavedFlag() {
        assertPageError("bad-denom", "DÉNOMINATION INCONNUE");
        assertPageError("bad-type", "TYPE DE SAISIE INVALIDE");
        assertPageError("print-disabled", "IMPRESSION DU BORDEREAU DÉSACTIVÉE");
        assertPageError("bogus", null);
        ChangeOrderResource resource = newResource();
        TemplateInstance[] chain = stubPageChain(resource);
        assertSame(chain[1], resource.changeOrderPage(null, "1"));
        verify(chain[0]).data("error", (String) null);
    }

    /**
     * Renders the page with the given error code and asserts the mapped message.
     *
     * @param error the error code
     * @param expected the message expected on the page
     */
    private void assertPageError(String error, String expected) {
        ChangeOrderResource resource = newResource();
        TemplateInstance[] chain = stubPageChain(resource);
        assertSame(chain[1], resource.changeOrderPage(error, null));
        verify(chain[0]).data("error", expected);
    }

    /**
     * An entry on an unknown denomination — a null id and an off-catalog id —
     * redirects with {@code bad-denom} ({@code denominationById} null and
     * no-match arms) and stores nothing.
     */
    @Test
    void entryUnknownDenominationRedirects() {
        ChangeOrderResource resource = newResource();
        assertRedirect(resource.entry(null, "AMOUNT", "8"), "/change-order?error=bad-denom");
        assertRedirect(resource.entry("zzz", "AMOUNT", "8"), "/change-order?error=bad-denom");
        assertTrue(resource.state.changeOrder.entries.isEmpty());
    }

    /**
     * An amount entry on a 0,20 € coin computes the roll count from the
     * administered correspondence (AMOUNT arm, roll-value present): 8 € is one
     * roll of 8 €.
     */
    @Test
    void entryAmountComputesRolls() {
        ChangeOrderResource resource = newResource();
        assertRedirect(resource.entry("c020", "AMOUNT", "8,00"), "/change-order");
        ChangeOrderState.Entry entry = resource.state.changeOrder.get("c020");
        assertEquals(ChangeOrderState.TYPE_AMOUNT, entry.type);
        assertEquals(0, new BigDecimal("8.00").compareTo(entry.amount));
        assertEquals(1, entry.rolls);
    }

    /**
     * A roll entry on a 0,01 € coin computes the amount from the administered
     * correspondence (ROLLS arm, roll-value present): 3 rolls of 0,50 € is
     * 1,50 € (LC-12-05-07).
     */
    @Test
    void entryRollsComputesAmount() {
        ChangeOrderResource resource = newResource();
        assertRedirect(resource.entry("c001", "ROLLS", "3"), "/change-order");
        ChangeOrderState.Entry entry = resource.state.changeOrder.get("c001");
        assertEquals(ChangeOrderState.TYPE_ROLLS, entry.type);
        assertEquals(3, entry.rolls);
        assertEquals(0, new BigDecimal("1.50").compareTo(entry.amount));
    }

    /**
     * An amount entry on a denomination the correspondence does not cover leaves
     * the roll count null (AMOUNT arm, roll-value absent): the 50 € note has no
     * roll value.
     */
    @Test
    void entryAmountWithoutRollValueLeavesRollsNull() {
        ChangeOrderResource resource = newResource();
        assertRedirect(resource.entry("b50", "AMOUNT", "100"), "/change-order");
        ChangeOrderState.Entry entry = resource.state.changeOrder.get("b50");
        assertEquals(0, new BigDecimal("100").compareTo(entry.amount));
        assertNull(entry.rolls);
    }

    /**
     * A negative amount lands as zero ({@code parseAmount} negative arm) and a
     * negative roll count lands as zero ({@code parseRolls} negative arm).
     */
    @Test
    void entryClampsNegativeFiguresToZero() {
        ChangeOrderResource resource = newResource();
        resource.entry("c020", "AMOUNT", "-5");
        assertEquals(0, BigDecimal.ZERO.compareTo(resource.state.changeOrder.get("c020").amount));
        resource.entry("c001", "ROLLS", "-2");
        assertEquals(0, resource.state.changeOrder.get("c001").rolls);
    }

    /**
     * An entry with an unknown type redirects with {@code bad-type} (else arm)
     * and stores nothing.
     */
    @Test
    void entryBadTypeRedirects() {
        ChangeOrderResource resource = newResource();
        assertRedirect(resource.entry("c020", "SNULL", "8"), "/change-order?error=bad-type");
        assertTrue(resource.state.changeOrder.entries.isEmpty());
    }

    /**
     * Clearing empties every dialed entry and redirects.
     */
    @Test
    void clearEmptiesEntries() {
        ChangeOrderResource resource = newResource();
        resource.entry("c020", "AMOUNT", "8");
        assertRedirect(resource.clear(), "/change-order");
        assertTrue(resource.state.changeOrder.entries.isEmpty());
    }

    /**
     * The empty-slip print sends every denomination with blank amount and roll
     * cells (LC-12-05-02) and redirects.
     */
    @Test
    void printEmptySendsBlankRows() {
        ChangeOrderResource resource = newResource();
        assertRedirect(resource.printEmpty(), "/change-order?ok=empty");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String[]>> rows = ArgumentCaptor.forClass(List.class);
        verify(resource.ticketPrinterService).printChangeOrder(eq("C04"), eq("17"), eq("Marie"),
                rows.capture());
        assertEquals(12, rows.getValue().size());
        for (String[] row : rows.getValue()) {
            assertEquals("", row[1]);
            assertEquals("", row[2]);
        }
    }

    /**
     * The filled-slip print is refused when the setting disables it
     * ({@code changeOrderPrint} false arm): nothing is printed.
     */
    @Test
    void printDisabledRedirectsAndPrintsNothing() {
        ChangeOrderResource resource = newResource();
        when(resource.posSettingsService.changeOrderPrint()).thenReturn(false);
        assertRedirect(resource.print(), "/change-order?error=print-disabled");
        verify(resource.ticketPrinterService, never()).printChangeOrder(any(), any(), any(), any());
    }

    /**
     * The filled-slip print sends the dialed figures when the setting enables it
     * ({@code changeOrderPrint} true arm, LC-12-05-08): the 0,20 € row carries
     * the amount and roll count that were dialed.
     */
    @Test
    void printFilledSendsDialedFigures() {
        ChangeOrderResource resource = newResource();
        when(resource.posSettingsService.changeOrderPrint()).thenReturn(true);
        resource.entry("c020", "AMOUNT", "8,00");
        assertRedirect(resource.print(), "/change-order?ok=1");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String[]>> rows = ArgumentCaptor.forClass(List.class);
        verify(resource.ticketPrinterService).printChangeOrder(eq("C04"), eq("17"), eq("Marie"),
                rows.capture());
        String[] coinRow = rows.getValue().stream()
                .filter(r -> r[0].equals("Pièce 0,20 €")).findFirst().orElseThrow();
        assertEquals("8,00", coinRow[1]);
        assertEquals("1", coinRow[2]);
    }
}
