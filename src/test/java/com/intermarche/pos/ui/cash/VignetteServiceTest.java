package com.intermarche.pos.ui.cash;

import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TechnicalEventService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link VignetteService} (BO-11-01-20), targeting 100% branch
 * coverage: the formula and its three refusals, the summary with and without
 * an open shift, the declared counts read back — readable, unreadable,
 * absent — and the declaration refused without a shift or for a count that
 * is not positive.
 */
class VignetteServiceTest {

    /**
     * Builds the service with the given open shift.
     *
     * @param session the open shift, or null
     * @return the service
     */
    private VignetteService service(CashSession session) {
        VignetteService service = new VignetteService();
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.vignetteEnabled()).thenReturn(true);
        when(service.posSettingsService.vignetteAmountStep()).thenReturn(new BigDecimal("10.00"));
        when(service.posSettingsService.vignettePerStep()).thenReturn(1);
        service.cashSessionService = mock(CashSessionService.class);
        when(service.cashSessionService.getOpenSession()).thenReturn(session);
        service.technicalEventService = mock(TechnicalEventService.class);
        return service;
    }

    /**
     * Builds a shift.
     *
     * @return the shift
     */
    private CashSession shift() {
        CashSession session = new CashSession();
        session.sessionNumber = "C04-S00001";
        return session;
    }

    /**
     * Builds a closed sale.
     *
     * @param total its tax-included total, possibly null
     * @return the ticket
     */
    private Ticket sale(String total) {
        Ticket ticket = new Ticket();
        ticket.totalIncludingTax = total == null ? null : new BigDecimal(total);
        return ticket;
    }

    /**
     * Builds a declaration.
     *
     * @param detail its detail, possibly null
     * @return the event
     */
    private TechnicalEvent declaration(String detail) {
        TechnicalEvent event = new TechnicalEvent();
        event.detail = detail;
        return event;
    }

    /** The formula counts whole steps, and nothing without a total, a positive total or a step. */
    @Test
    void theFormulaCountsWholeSteps() {
        Assertions.assertEquals(6L, VignetteService.vignettesFor(new BigDecimal("39.99"), BigDecimal.TEN, 2));
        Assertions.assertEquals(0L, VignetteService.vignettesFor(null, BigDecimal.TEN, 2));
        Assertions.assertEquals(0L, VignetteService.vignettesFor(BigDecimal.ZERO, BigDecimal.TEN, 2));
        Assertions.assertEquals(0L, VignetteService.vignettesFor(BigDecimal.TEN, BigDecimal.ZERO, 2));
        Assertions.assertTrue(service(null).enabled());
    }

    /** Without an open shift there is no summary. */
    @Test
    void thereIsNoSummaryWithoutAShift() {
        Assertions.assertNull(service(null).summary());
    }

    /**
     * The summary counts the shift's closed sales, the theoretical vignettes
     * of the formula and the declared ones, an unreadable or absent count
     * counting none.
     */
    @Test
    void theSummaryStatesTheShift() {
        CashSession session = shift();
        VignetteService service = service(session);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.list("session = ?1 and status = ?2", session, Ticket.TicketStatus.CLOSED))
                    .thenReturn(List.of(sale("25.00"), sale(null), sale("10.00")));
            panache.when(() -> TechnicalEvent.list("eventType = ?1 and detail like ?2",
                    TechnicalEvent.EventType.VIGNETTES_DECLARED, "session=C04-S00001;%"))
                    .thenReturn(List.of(declaration("session=C04-S00001;count=2"),
                            declaration("session=C04-S00001;count=x"), declaration("session=C04-S00001;"),
                            declaration(null)));
            Assertions.assertEquals(new VignetteService.Summary("C04-S00001", 3, 3, 2), service.summary());
        }
    }

    /** A declaration is journalled for the open shift; none without a shift or for a count that is not positive. */
    @Test
    void aDeclarationIsJournalledForTheShift() {
        VignetteService service = service(shift());
        Assertions.assertTrue(service.declare(5, "12341234"));
        verify(service.technicalEventService).log(TechnicalEvent.EventType.VIGNETTES_DECLARED,
                "session=C04-S00001;count=5", "12341234");
        Assertions.assertFalse(service.declare(0, "12341234"));
        VignetteService closed = service(null);
        Assertions.assertFalse(closed.declare(5, "12341234"));
        verify(closed.technicalEventService, never()).log(any(), anyString(), any());
    }
}
