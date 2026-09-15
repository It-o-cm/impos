package com.intermarche.pos.service;

import com.intermarche.pos.domain.session.CashMovement;
import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.sync.SyncOutbox;
import com.intermarche.pos.service.sync.SyncOutboxService;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CashMovementService}.
 * <p>
 * The service is a Panache active-record producer: it constructs a
 * {@link CashMovement}, stamps it, persists it and enqueues an outbox row. The
 * single {@code new CashMovement()} is neutralized with
 * {@link org.mockito.Mockito#mockConstruction} (so {@code persist()} is inert
 * and the stamped fields are readable on the mock), the injected
 * {@link TicketNumberService} and {@link SyncOutboxService} are plain mocks,
 * and no database or Quarkus context is booted.
 * <p>
 * Branch enumeration — every decision point, both arms: {@code
 * requiresEndorsement} ({@code amount != null} both arms, and the {@code
 * compareTo &gt; 0} both arms); {@code record} guard ({@code
 * requiresEndorsement && endorsedBy == null} — the reject arm, the
 * endorsement-supplied arm falsifying the right leg, and the below-threshold
 * arm short-circuiting the left leg). 100% branches.
 */
class CashMovementServiceTest {

    /**
     * Builds a service whose terminal id, outbox and settings are mocked; the
     * administered endorsement threshold resolves to 100.00 €.
     *
     * @param outbox the outbox mock to inject
     * @return the wired service
     */
    private CashMovementService serviceWith(SyncOutboxService outbox) {
        CashMovementService service = new CashMovementService();
        TicketNumberService numbers = mock(TicketNumberService.class);
        when(numbers.getTerminalId()).thenReturn("C04");
        service.ticketNumberService = numbers;
        service.syncOutboxService = outbox;
        PosSettingsService settings = mock(PosSettingsService.class);
        when(settings.cashMovementEndorsementThreshold()).thenReturn(new BigDecimal("100.00"));
        service.posSettingsService = settings;
        return service;
    }

    // --------------------------------------------------
    // requiresEndorsement
    // --------------------------------------------------

    /**
     * A null amount never requires an endorsement (left leg false, short
     * circuit).
     */
    @Test
    void requiresEndorsementFalseWhenAmountNull() {
        assertFalse(serviceWith(mock(SyncOutboxService.class)).requiresEndorsement(null));
    }

    /**
     * An amount at or below the threshold requires no endorsement (left leg
     * true, {@code compareTo > 0} false).
     */
    @Test
    void requiresEndorsementFalseAtThreshold() {
        assertFalse(serviceWith(mock(SyncOutboxService.class))
                .requiresEndorsement(new BigDecimal("100.00")));
    }

    /**
     * An amount strictly above the threshold requires an endorsement (left leg
     * true, {@code compareTo > 0} true).
     */
    @Test
    void requiresEndorsementTrueAboveThreshold() {
        assertTrue(serviceWith(mock(SyncOutboxService.class))
                .requiresEndorsement(new BigDecimal("100.01")));
    }

    // --------------------------------------------------
    // record
    // --------------------------------------------------

    /**
     * A movement below the threshold records without any endorsement (guard
     * left leg false, short circuit): the movement is constructed, fully
     * stamped, persisted, and enqueued as a {@code MOVEMENT} for the store
     * push — its uid is minted and its terminal comes from the register.
     */
    @Test
    void recordBelowThresholdPersistsAndEnqueues() {
        SyncOutboxService outbox = mock(SyncOutboxService.class);
        CashMovementService service = serviceWith(outbox);
        CashSession session = mock(CashSession.class);
        Employee cashier = mock(Employee.class);
        try (MockedConstruction<CashMovement> created = mockConstruction(CashMovement.class)) {
            CashMovement result = service.record(session, cashier,
                    CashMovement.MovementType.WITHDRAWAL, new BigDecimal("30.00"), "coffre", null);
            assertEquals(1, created.constructed().size());
            CashMovement movement = created.constructed().get(0);
            assertSame(movement, result);
            assertNotNull(movement.movementUid);
            assertEquals("C04", movement.terminalId);
            assertSame(session, movement.session);
            assertSame(cashier, movement.cashier);
            assertEquals(CashMovement.MovementType.WITHDRAWAL, movement.type);
            assertEquals(new BigDecimal("30.00"), movement.amount);
            assertEquals("coffre", movement.reason);
            assertNotNull(movement.movementDate);
            assertNull(movement.endorsedBy);
            verify(movement, times(1)).persist();
            verify(outbox, times(1)).enqueue(SyncOutbox.EntityType.MOVEMENT, movement.id);
        }
    }

    /**
     * A movement above the threshold WITH an endorsement records (guard left
     * leg true, right leg {@code endorsedBy == null} false): the endorsing
     * manager's badge is carried on the movement.
     */
    @Test
    void recordAboveThresholdWithEndorsementPersists() {
        SyncOutboxService outbox = mock(SyncOutboxService.class);
        CashMovementService service = serviceWith(outbox);
        try (MockedConstruction<CashMovement> created = mockConstruction(CashMovement.class)) {
            CashMovement result = service.record(mock(CashSession.class), mock(Employee.class),
                    CashMovement.MovementType.DECLARATION, new BigDecimal("150.00"), "comptage", "11111111");
            CashMovement movement = created.constructed().get(0);
            assertSame(movement, result);
            assertEquals(new BigDecimal("150.00"), movement.amount);
            assertEquals("11111111", movement.endorsedBy);
            verify(movement, times(1)).persist();
            verify(outbox, times(1)).enqueue(SyncOutbox.EntityType.MOVEMENT, movement.id);
        }
    }

    /**
     * A movement above the threshold WITHOUT an endorsement is refused (guard
     * both legs true): nothing is constructed, nothing is persisted, nothing is
     * enqueued, and the caller gets null.
     */
    @Test
    void recordAboveThresholdWithoutEndorsementIsRefused() {
        SyncOutboxService outbox = mock(SyncOutboxService.class);
        CashMovementService service = serviceWith(outbox);
        try (MockedConstruction<CashMovement> created = mockConstruction(CashMovement.class)) {
            CashMovement result = service.record(mock(CashSession.class), mock(Employee.class),
                    CashMovement.MovementType.WITHDRAWAL, new BigDecimal("150.00"), "coffre", null);
            assertNull(result);
            assertTrue(created.constructed().isEmpty());
        }
        verifyNoInteractions(outbox);
    }

    /**
     * The short call names no tender, and the movement it writes says so: a
     * movement recorded by a caller that knows nothing of tenders must not claim
     * one, since a null tender is what makes the historical rows read as cash.
     */
    @Test
    void theShortCallNamesNoTender() {
        SyncOutboxService outbox = mock(SyncOutboxService.class);
        CashMovementService service = serviceWith(outbox);
        try (MockedConstruction<CashMovement> created = mockConstruction(CashMovement.class)) {
            service.record(mock(CashSession.class), mock(Employee.class),
                    CashMovement.MovementType.WITHDRAWAL, new BigDecimal("30.00"), "coffre", null);
            CashMovement movement = created.constructed().get(0);
            assertNull(movement.paymentMethod);
            assertNull(movement.transferTo);
            assertNull(movement.denominationDetail);
        }
    }

    /**
     * A withdrawal of a named tender carries that tender and its denomination detail,
     * and nothing to transfer to ({@code LC-12-03-02/04}).
     */
    @Test
    void aWithdrawalCarriesItsTenderAndItsDetail() {
        SyncOutboxService outbox = mock(SyncOutboxService.class);
        CashMovementService service = serviceWith(outbox);
        try (MockedConstruction<CashMovement> created = mockConstruction(CashMovement.class)) {
            CashMovement result = service.record(mock(CashSession.class), mock(Employee.class),
                    CashMovement.MovementType.WITHDRAWAL, new BigDecimal("30.00"),
                    "Prélèvement Espèces", null, "CASH", null, "{\"b20\":1}");
            CashMovement movement = created.constructed().get(0);
            assertSame(movement, result);
            assertEquals("CASH", movement.paymentMethod);
            assertNull(movement.transferTo);
            assertEquals("{\"b20\":1}", movement.denominationDetail);
            verify(movement, times(1)).persist();
            verify(outbox, times(1)).enqueue(SyncOutbox.EntityType.MOVEMENT, movement.id);
        }
    }

    /**
     * A transfer carries both of its ends ({@code LC-12-10-01}): where the amount left
     * and where it landed are what the two theoreticals are moved by.
     */
    @Test
    void aTransferCarriesBothOfItsEnds() {
        SyncOutboxService outbox = mock(SyncOutboxService.class);
        CashMovementService service = serviceWith(outbox);
        try (MockedConstruction<CashMovement> created = mockConstruction(CashMovement.class)) {
            service.record(mock(CashSession.class), mock(Employee.class),
                    CashMovement.MovementType.TRANSFER, new BigDecimal("20.00"),
                    "Transfert Chèques vers Espèces", null, "CHEQUE", "CASH", null);
            CashMovement movement = created.constructed().get(0);
            assertEquals(CashMovement.MovementType.TRANSFER, movement.type);
            assertEquals("CHEQUE", movement.paymentMethod);
            assertEquals("CASH", movement.transferTo);
            assertNull(movement.denominationDetail);
        }
    }

    /**
     * The endorsement guard rules the long call too: an above-threshold withdrawal of a
     * named tender without a manager is refused, and nothing is written.
     */
    @Test
    void aNamedTenderDoesNotEscapeTheEndorsementGuard() {
        SyncOutboxService outbox = mock(SyncOutboxService.class);
        CashMovementService service = serviceWith(outbox);
        try (MockedConstruction<CashMovement> created = mockConstruction(CashMovement.class)) {
            CashMovement result = service.record(mock(CashSession.class), mock(Employee.class),
                    CashMovement.MovementType.WITHDRAWAL, new BigDecimal("150.00"),
                    "Prélèvement Chèques", null, "CHEQUE", null, null);
            assertNull(result);
            assertTrue(created.constructed().isEmpty());
        }
        verifyNoInteractions(outbox);
    }
}
