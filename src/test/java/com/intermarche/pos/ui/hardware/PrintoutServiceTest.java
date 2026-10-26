package com.intermarche.pos.ui.hardware;

import com.intermarche.pos.domain.sale.PrintedTicket;
import com.intermarche.pos.domain.sync.SyncOutbox;
import com.intermarche.pos.service.TicketNumberService;
import com.intermarche.pos.service.sync.register.SyncOutboxService;
import com.intermarche.pos.ui.PosState;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.TransactionRunnerOptions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Unit tests for {@link PrintoutService}, targeting 100% branch coverage.
 * <p>
 * The collaborators are mocks, the transaction runs its runnable in place and
 * the persistence of the new row is neutralised. Branch enumeration: a null or
 * blank paper is not kept; a paper is kept with its source read on the stack
 * and its audience — training, store or customer; a keeping that fails is
 * tolerated; every leg of the frame filter.
 */
class PrintoutServiceTest {

    /**
     * A service over fresh mocks and a real state.
     *
     * @return the service
     */
    private PrintoutService service() {
        PrintoutService service = new PrintoutService();
        service.state = new PosState();
        service.ticketNumberService = mock(TicketNumberService.class);
        service.syncOutboxService = mock(SyncOutboxService.class);
        when(service.ticketNumberService.getTerminalId()).thenReturn("C04");
        return service;
    }

    /**
     * Runs the transactional runnable in place.
     *
     * @param tx the static mock of the transaction API
     */
    private void runInPlace(MockedStatic<QuarkusTransaction> tx) {
        TransactionRunnerOptions runner = mock(TransactionRunnerOptions.class);
        tx.when(QuarkusTransaction::requiringNew).thenReturn(runner);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(runner).run(any(Runnable.class));
    }

    /**
     * A null or blank paper is not kept.
     */
    @Test
    void anEmptyPaperIsNotKept() {
        PrintoutService service = service();
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class)) {
            service.record(null);
            service.record("  ");
            tx.verifyNoInteractions();
        }
        verify(service.syncOutboxService, never()).enqueue(any(), anyLong());
    }

    /**
     * A paper is kept with its register, its source — the function that
     * produced it, here this test — its audience, its text and its time, and
     * queued for the store node (LC-08-02-02).
     */
    @Test
    void aPaperIsKeptAndQueued() {
        PrintoutService service = service();
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class);
             MockedConstruction<PrintedTicket> rows = mockConstruction(PrintedTicket.class,
                     withSettings().defaultAnswer(CALLS_REAL_METHODS),
                     (row, context) -> doNothing().when(row).persist())) {
            runInPlace(tx);
            service.record("TICKET");
            PrintedTicket kept = rows.constructed().get(0);
            assertEquals("C04", kept.terminalId);
            assertEquals("PrintoutServiceTest.aPaperIsKeptAndQueued", kept.source);
            assertEquals(PrintedTicket.Audience.CUSTOMER, kept.audience);
            assertEquals("TICKET", kept.content);
            assertNotNull(kept.issuedAt);
            verify(service.syncOutboxService).enqueue(eq(SyncOutbox.EntityType.PRINTOUT), any());
        }
    }

    /**
     * A paper printed through the printer names the function that asked the
     * printer, the printer itself being a relay; a paper recorded from a lambda
     * names the method around it.
     */
    @Test
    void theSourceSkipsThePrinterAndTheLambdas() {
        PrintoutService service = service();
        HardwareService printer = new HardwareService();
        printer.printoutService = service;
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class);
             MockedConstruction<PrintedTicket> rows = mockConstruction(PrintedTicket.class,
                     withSettings().defaultAnswer(CALLS_REAL_METHODS),
                     (row, context) -> doNothing().when(row).persist())) {
            runInPlace(tx);
            printer.recordUnprinted("KEPT");
            Runnable fromLambda = () -> service.record("LAMBDA");
            fromLambda.run();
            assertEquals("PrintoutServiceTest.theSourceSkipsThePrinterAndTheLambdas", rows.constructed().get(0).source);
            assertEquals("PrintoutServiceTest.theSourceSkipsThePrinterAndTheLambdas", rows.constructed().get(1).source);
        }
    }

    /**
     * The source of a direct call is its caller, through a method reference too.
     */
    @Test
    void theSourceOfADirectCallIsItsCaller() {
        PrintoutService service = service();
        Supplier<String> walk = service::source;
        assertTrue(walk.get().startsWith("PrintoutServiceTest."));
    }

    /**
     * A paper that cannot be kept is logged and the printing goes on.
     */
    @Test
    void aFailedKeepingIsTolerated() {
        PrintoutService service = service();
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class)) {
            TransactionRunnerOptions runner = mock(TransactionRunnerOptions.class);
            tx.when(QuarkusTransaction::requiringNew).thenReturn(runner);
            doThrow(new IllegalStateException("no database")).when(runner).run(any(Runnable.class));
            service.record("TICKET");
        }
        verify(service.syncOutboxService, never()).enqueue(any(), anyLong());
    }

    /**
     * Every leg of the frame filter: this class, the printer, the CDI proxies,
     * the lambdas and the relays are not the source; any other frame is.
     */
    @Test
    void theFrameFilter() {
        assertFalse(PrintoutService.isSource("PrintoutService.record"));
        assertFalse(PrintoutService.isSource("HardwareService.printReceipt"));
        assertFalse(PrintoutService.isSource("TicketPrinterService_Subclass.printTicket"));
        assertFalse(PrintoutService.isSource("TicketPrinterService_ClientProxy.printTicket"));
        assertFalse(PrintoutService.isSource("PaymentService.lambda$finalize$1"));
        assertFalse(PrintoutService.isSource("TicketPrinterService.printRenderedTicket"));
        assertFalse(PrintoutService.isSource("TicketPrinterService.printAssociatedCustomerCopy"));
        assertTrue(PrintoutService.isSource("TicketPrinterService.printTicket"));
        assertEquals("Inner$Class", PrintoutService.simpleName("a.b.Inner$Class"));
    }

    /**
     * The audience: a training ticket in training mode whatever its source, the
     * store for its technical tickets (LC-08-03-08), the customer otherwise.
     */
    @Test
    void theAudienceOfAPaper() {
        PrintoutService service = service();
        assertEquals(PrintedTicket.Audience.STORE, service.audienceOf("TicketPrinterService.printSessionReport"));
        assertEquals(PrintedTicket.Audience.STORE, service.audienceOf("SessionCloseService.printReceipt"));
        assertEquals(PrintedTicket.Audience.STORE, service.audienceOf("TicketPrinterService.printUnknownArticleTicket"));
        assertEquals(PrintedTicket.Audience.CUSTOMER, service.audienceOf("TicketPrinterService.printTicket"));
        service.state.trainingMode = true;
        assertEquals(PrintedTicket.Audience.TRAINING, service.audienceOf("TicketPrinterService.printSessionReport"));
        assertEquals(13, PrintoutService.STORE_SOURCES.size());
    }
}
