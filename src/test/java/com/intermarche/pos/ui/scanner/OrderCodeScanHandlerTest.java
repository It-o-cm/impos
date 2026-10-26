package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.recall.BackOfficeDocumentClient;
import com.intermarche.pos.ui.recall.RecalledDocument;
import com.intermarche.pos.ui.ticket.TicketService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OrderCodeScanHandler}.
 * <p>
 * The handler is a plain recognizer: it filters on the already-handled flag,
 * the lock state and the {@code <terminal>-CMD<digits>} shape, then asks the
 * store node for the order behind the code and delegates the load to a mocked
 * {@link TicketService}. The register state is a real {@link PosState}. No
 * database and no Quarkus context is booted.
 * <p>
 * Branch enumeration (every arm exercised — 100%): the already-handled guard
 * (true / false), the lock guard (true / false), the null-code arm, the
 * shape non-match arm (no -CMD marker), the node-knows-nothing arm (the code
 * falls through UNHANDLED to the unknown-code link) and the recognized arm
 * (the order loads and the scan is consumed).
 */
class OrderCodeScanHandlerTest {

    /**
     * Builds a handler over the given mocks.
     *
     * @param documentClient the store-node order seam
     * @param ticketService the recalled-document loader
     * @return the wired handler
     */
    private OrderCodeScanHandler newHandler(BackOfficeDocumentClient documentClient,
                                            TicketService ticketService) {
        OrderCodeScanHandler handler = new OrderCodeScanHandler();
        handler.documentClient = documentClient;
        handler.ticketService = ticketService;
        handler.posSettingsService = mock(PosSettingsService.class);
        when(handler.posSettingsService.orderExternalCodePattern()).thenReturn("");
        handler.selfScanService = mock(com.intermarche.pos.ui.recall.SelfScanService.class);
        return handler;
    }

    /**
     * An unlocked, signed-in register state.
     *
     * @return the state a scan walks
     */
    private PosState unlocked() {
        PosState state = new PosState();
        state.auth.isLocked = false;
        return state;
    }

    /**
     * An already-handled scan is left alone (guard true arm): the node is not
     * even asked.
     */
    @Test
    void alreadyHandledScanIsLeftAlone() {
        BackOfficeDocumentClient documentClient = mock(BackOfficeDocumentClient.class);
        TicketService ticketService = mock(TicketService.class);
        OrderCodeScanHandler handler = newHandler(documentClient, ticketService);
        PosState state = unlocked();
        ScanContext ctx = new ScanContext("C04-CMD000001", state);
        ctx.handled = true;
        handler.handle(ctx);
        verify(documentClient, never()).fetch(any());
    }

    /**
     * A scan on a locked register is ignored (lock guard true arm).
     */
    @Test
    void lockedRegisterIsIgnored() {
        BackOfficeDocumentClient documentClient = mock(BackOfficeDocumentClient.class);
        TicketService ticketService = mock(TicketService.class);
        OrderCodeScanHandler handler = newHandler(documentClient, ticketService);
        PosState state = new PosState();
        state.auth.isLocked = true;
        ScanContext ctx = new ScanContext("C04-CMD000001", state);
        handler.handle(ctx);
        verify(documentClient, never()).fetch(any());
        assertFalse(ctx.handled);
    }

    /**
     * A null code is ignored (null arm).
     */
    @Test
    void nullCodeIsIgnored() {
        BackOfficeDocumentClient documentClient = mock(BackOfficeDocumentClient.class);
        TicketService ticketService = mock(TicketService.class);
        OrderCodeScanHandler handler = newHandler(documentClient, ticketService);
        ScanContext ctx = new ScanContext(null, unlocked());
        handler.handle(ctx);
        verify(documentClient, never()).fetch(any());
        assertFalse(ctx.handled);
    }

    /**
     * A code without the {@code -CMD} marker is not this handler's family:
     * nothing is fetched and the scan stays unhandled (shape non-match arm).
     */
    @Test
    void codeWithoutCmdMarkerIsNotClaimed() {
        BackOfficeDocumentClient documentClient = mock(BackOfficeDocumentClient.class);
        TicketService ticketService = mock(TicketService.class);
        OrderCodeScanHandler handler = newHandler(documentClient, ticketService);
        ScanContext ctx = new ScanContext("C04-00000042", unlocked());
        handler.handle(ctx);
        verify(documentClient, never()).fetch(any());
        assertFalse(ctx.handled);
    }

    /**
     * A well-shaped code the node does not know falls through UNHANDLED, so
     * the unknown-code link answers exactly as before (node-knows-nothing
     * arm).
     */
    @Test
    void unknownOrderFallsThroughUnhandled() {
        BackOfficeDocumentClient documentClient = mock(BackOfficeDocumentClient.class);
        TicketService ticketService = mock(TicketService.class);
        OrderCodeScanHandler handler = newHandler(documentClient, ticketService);
        when(documentClient.fetch("C04-CMD000009")).thenReturn(null);
        ScanContext ctx = new ScanContext("C04-CMD000009", unlocked());
        handler.handle(ctx);
        verify(ticketService, never()).loadRecalledDocument(any(), any());
        assertFalse(ctx.handled);
    }

    /**
     * A well-shaped code the node answers with an order loads it into the
     * sale, consumes the scan and bumps the poll (recognized arm,
     * LC-06-03-07).
     */
    @Test
    void knownOrderLoadsIntoTheSale() {
        BackOfficeDocumentClient documentClient = mock(BackOfficeDocumentClient.class);
        TicketService ticketService = mock(TicketService.class);
        OrderCodeScanHandler handler = newHandler(documentClient, ticketService);
        RecalledDocument order = new RecalledDocument(RecalledDocument.Kind.COMMANDE,
                "C04-CMD000001", null, null);
        when(documentClient.fetch("C04-CMD000001")).thenReturn(order);
        PosState state = unlocked();
        ScanContext ctx = new ScanContext("C04-CMD000001", state);
        handler.handle(ctx);
        verify(ticketService).loadRecalledDocument(eq(state), eq(order));
        assertTrue(ctx.handled);
        assertTrue(state.version > 0);
    }
    /**
     * A null administered pattern claims no external code (null pattern arm).
     */
    @Test
    void nullPatternClaimsNoExternalCode() {
        BackOfficeDocumentClient documentClient = mock(BackOfficeDocumentClient.class);
        OrderCodeScanHandler handler = newHandler(documentClient, mock(TicketService.class));
        when(handler.posSettingsService.orderExternalCodePattern()).thenReturn(null);
        ScanContext ctx = new ScanContext("DV-2026-0042", unlocked());
        handler.handle(ctx);
        verify(documentClient, never()).fetch(any());
        assertFalse(ctx.handled);
    }

    /**
     * An invalid administered pattern claims nothing rather than failing the
     * scan chain (PatternSyntaxException arm).
     */
    @Test
    void invalidPatternClaimsNothing() {
        BackOfficeDocumentClient documentClient = mock(BackOfficeDocumentClient.class);
        OrderCodeScanHandler handler = newHandler(documentClient, mock(TicketService.class));
        when(handler.posSettingsService.orderExternalCodePattern()).thenReturn("DV-(");
        ScanContext ctx = new ScanContext("DV-2026-0042", unlocked());
        handler.handle(ctx);
        verify(documentClient, never()).fetch(any());
        assertFalse(ctx.handled);
    }

    /**
     * A code that does not match the administered pattern is not claimed
     * (pattern non-match arm).
     */
    @Test
    void codeOutsideThePatternIsNotClaimed() {
        BackOfficeDocumentClient documentClient = mock(BackOfficeDocumentClient.class);
        OrderCodeScanHandler handler = newHandler(documentClient, mock(TicketService.class));
        when(handler.posSettingsService.orderExternalCodePattern()).thenReturn(" DV-[0-9]{4}-[0-9]{4} ");
        ScanContext ctx = new ScanContext("BV-2026-0042", unlocked());
        handler.handle(ctx);
        verify(documentClient, never()).fetch(any());
        assertFalse(ctx.handled);
    }

    /**
     * An external voucher matching the administered pattern and admitting no
     * deposit loads straight into the sale (LC-06-02-09, pattern match arm).
     */
    @Test
    void externalVoucherLoadsIntoTheSale() {
        BackOfficeDocumentClient documentClient = mock(BackOfficeDocumentClient.class);
        TicketService ticketService = mock(TicketService.class);
        OrderCodeScanHandler handler = newHandler(documentClient, ticketService);
        when(handler.posSettingsService.orderExternalCodePattern()).thenReturn(" DV-[0-9]{4}-[0-9]{4} ");
        RecalledDocument voucher = new RecalledDocument(RecalledDocument.Kind.BON_VENTE,
                "DV-2026-0042", null, null);
        when(documentClient.fetch("DV-2026-0042")).thenReturn(voucher);
        PosState state = unlocked();
        ScanContext ctx = new ScanContext("DV-2026-0042", state);
        handler.handle(ctx);
        verify(ticketService).loadRecalledDocument(eq(state), eq(voucher));
        assertTrue(ctx.handled);
        assertFalse(state.documentChoiceRequested);
    }

    /**
     * A document admitting a deposit waits for the cashier's choice instead of
     * loading (LC-06-02-04, -08, deposit arm).
     */
    @Test
    void depositDocumentWaitsForTheChoice() {
        BackOfficeDocumentClient documentClient = mock(BackOfficeDocumentClient.class);
        TicketService ticketService = mock(TicketService.class);
        OrderCodeScanHandler handler = newHandler(documentClient, ticketService);
        RecalledDocument quote = new RecalledDocument(RecalledDocument.Kind.DEVIS,
                "C04-CMD000003", null, null);
        quote.depositRule = RecalledDocument.DEPOSIT_OPTIONAL;
        when(documentClient.fetch("C04-CMD000003")).thenReturn(quote);
        PosState state = unlocked();
        ScanContext ctx = new ScanContext("C04-CMD000003", state);
        handler.handle(ctx);
        verify(ticketService, never()).loadRecalledDocument(any(), any());
        assertSame(quote, state.pendingDocument);
        assertTrue(state.documentChoiceRequested);
        assertTrue(ctx.handled);
        assertTrue(state.version > 0);
    }

    /**
     * A selfscanning basket matching its administered pattern opens its control
     * instead of loading, and asks the sale screen for the selfscanning screen
     * (LC-06-05-03).
     */
    @Test
    void selfScanBasketOpensItsControl() {
        BackOfficeDocumentClient documentClient = mock(BackOfficeDocumentClient.class);
        TicketService ticketService = mock(TicketService.class);
        OrderCodeScanHandler handler = newHandler(documentClient, ticketService);
        when(handler.posSettingsService.selfScanCodePattern()).thenReturn("SS[0-9]{8}");
        RecalledDocument basket = new RecalledDocument(RecalledDocument.Kind.SELF_SCAN,
                "SS00000001", null, null);
        when(documentClient.fetch("SS00000001")).thenReturn(basket);
        PosState state = unlocked();
        ScanContext ctx = new ScanContext("SS00000001", state);
        handler.handle(ctx);
        verify(handler.selfScanService).start(state, basket);
        verify(ticketService, never()).loadRecalledDocument(any(), any());
        assertTrue(state.selfScanRequested);
        assertFalse(state.documentChoiceRequested);
        assertTrue(ctx.handled);
    }

    /**
     * A queue-busting basket matching its administered range is loaded into the
     * sale, paid in full like any basket built ahead of the till (BO-03-06-57).
     */
    @Test
    void aQbustingBasketIsLoaded() {
        BackOfficeDocumentClient documentClient = mock(BackOfficeDocumentClient.class);
        TicketService ticketService = mock(TicketService.class);
        OrderCodeScanHandler handler = newHandler(documentClient, ticketService);
        when(handler.posSettingsService.qbustingCodePattern()).thenReturn("QB[0-9]{8}");
        RecalledDocument basket = new RecalledDocument(RecalledDocument.Kind.QBUSTING,
                "QB00000001", null, null);
        when(documentClient.fetch("QB00000001")).thenReturn(basket);
        PosState state = unlocked();
        ScanContext ctx = new ScanContext("QB00000001", state);
        handler.handle(ctx);
        verify(ticketService).loadRecalledDocument(state, basket);
        verify(handler.selfScanService, never()).start(any(), any());
        assertTrue(ctx.handled);
    }
}
