package com.intermarche.pos.ui.payment;

import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.sale.TrainingTicket;
import com.intermarche.pos.domain.sync.SyncOutbox;
import com.intermarche.pos.service.TicketNumberService;
import com.intermarche.pos.service.sync.register.SyncOutboxService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.TicketPrinterService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.TransactionRunnerOptions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Unit tests for {@link TrainingTicketService}, targeting 100% branch coverage.
 * <p>
 * The collaborators are mocks, the transaction runs its callable in place and
 * the persistence of the new row is neutralised. Branch enumeration: no training
 * mode, a training sale without articles, a sale kept with and without a known
 * operator, and a keeping that fails.
 */
class TrainingTicketServiceTest {

    /**
     * A service over fresh mocks.
     *
     * @return the service
     */
    private TrainingTicketService service() {
        TrainingTicketService service = new TrainingTicketService();
        service.ticketPrinterService = mock(TicketPrinterService.class);
        service.ticketNumberService = mock(TicketNumberService.class);
        service.syncOutboxService = mock(SyncOutboxService.class);
        when(service.ticketNumberService.getTerminalId()).thenReturn("C04");
        return service;
    }

    /**
     * A training sale of one article at 2.50.
     *
     * @return the state
     */
    private PosState trainingSale() {
        PosState state = new PosState();
        state.trainingMode = true;
        state.ticket.addItem("301", null, "LAIT", new BigDecimal("2.50"), BigDecimal.ONE, new BigDecimal("0.055"));
        return state;
    }

    /**
     * Runs the transactional callable in place.
     *
     * @param tx the static mock of the transaction API
     */
    private void runInPlace(MockedStatic<QuarkusTransaction> tx) {
        TransactionRunnerOptions runner = mock(TransactionRunnerOptions.class);
        tx.when(QuarkusTransaction::requiringNew).thenReturn(runner);
        when(runner.call(any())).thenAnswer(inv -> ((Callable<?>) inv.getArgument(0)).call());
    }

    /**
     * Outside training mode, and for a training sale without articles, nothing
     * is kept.
     */
    @Test
    void nothingIsKeptOutsideATrainingSale() {
        TrainingTicketService service = service();
        PosState real = new PosState();
        real.ticket.addItem("301", null, "LAIT", BigDecimal.ONE, BigDecimal.ONE, null);
        assertNull(service.record(real));
        PosState empty = new PosState();
        empty.trainingMode = true;
        assertNull(service.record(empty));
        verify(service.syncOutboxService, never()).enqueue(any(), anyLong());
    }

    /**
     * A training sale is kept with its receipt, its figures and its operator,
     * and queued for the store node (LC-13-05-06).
     */
    @Test
    void aTrainingSaleIsKeptAndQueued() {
        TrainingTicketService service = service();
        PosState state = trainingSale();
        state.auth.operatorId = 5L;
        Employee operator = new Employee();
        operator.loginName = "jdupont";
        when(service.ticketPrinterService.renderTrainingReceipt(state)).thenReturn("RECEIPT");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class);
             MockedConstruction<TrainingTicket> rows = mockConstruction(TrainingTicket.class,
                     withSettings().defaultAnswer(CALLS_REAL_METHODS),
                     (row, context) -> doNothing().when(row).persist())) {
            panache.when(() -> Employee.findById(5L)).thenReturn(operator);
            runInPlace(tx);
            TrainingTicket kept = service.record(state);
            assertSame(rows.constructed().get(0), kept);
            assertEquals("C04", kept.terminalId);
            assertEquals("jdupont", kept.cashierLogin);
            assertEquals(1, kept.itemCount);
            assertEquals(0, new BigDecimal("2.50").compareTo(kept.totalIncludingTax));
            assertEquals("RECEIPT", kept.content);
            verify(service.syncOutboxService).enqueue(eq(SyncOutbox.EntityType.TRAINING), any());
        }
    }

    /**
     * A training sale without a signed-in operator is kept without login, and an
     * operator row gone is no login either.
     */
    @Test
    void aTrainingSaleWithoutOperatorIsKeptWithoutLogin() {
        TrainingTicketService service = service();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class);
             MockedConstruction<TrainingTicket> rows = mockConstruction(TrainingTicket.class,
                     withSettings().defaultAnswer(CALLS_REAL_METHODS),
                     (row, context) -> doNothing().when(row).persist())) {
            runInPlace(tx);
            assertNull(service.record(trainingSale()).cashierLogin);
            PosState gone = trainingSale();
            gone.auth.operatorId = 9L;
            panache.when(() -> Employee.findById(9L)).thenReturn(null);
            assertNull(service.record(gone).cashierLogin);
        }
    }

    /**
     * A training sale that cannot be kept is logged and the lane goes on.
     */
    @Test
    void aFailedKeepingIsTolerated() {
        TrainingTicketService service = service();
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class)) {
            TransactionRunnerOptions runner = mock(TransactionRunnerOptions.class);
            tx.when(QuarkusTransaction::requiringNew).thenReturn(runner);
            when(runner.call(any())).thenThrow(new IllegalStateException("no database"));
            assertNull(service.record(trainingSale()));
        }
    }
}
