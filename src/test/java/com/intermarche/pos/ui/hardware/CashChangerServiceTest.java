package com.intermarche.pos.ui.hardware;

import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.TestMessages;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CashChangerService} (LC-07-15-01 to -04, LC-13-01-01).
 * <p>
 * The deposit is followed on a private daemon executor and reported through
 * callbacks, so the following loop is exercised end to end through
 * {@link CashChangerService#deposit}: the poll is pinned to one millisecond,
 * the deadline is finite, and outcomes are awaited on latches fed by the
 * callbacks, never on a sleep.
 */
class CashChangerServiceTest {

    /**
     * Builds a service bound to the given bridge, with the cash changer enabled
     * or not.
     *
     * @param client the mock hardware client
     * @param enabled whether the till runs a cash changer
     * @return the service under test
     */
    private CashChangerService serviceWith(HardwareClient client, boolean enabled) {
        CashChangerService service = new CashChangerService();
        service.i18n = TestMessages.french();
        service.hardware = client;
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.hardwareEnabled("cashchanger")).thenReturn(enabled);
        service.pollMillis = 1L;
        service.deadlineMillis = 2000L;
        return service;
    }

    /**
     * Waits for one callback to fire.
     *
     * @param latch the latch a callback counts down
     */
    private void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while awaiting the outcome", e);
        }
    }

    /**
     * A bridge refusing the start reports the machine unreachable, in cents on
     * the wire, and follows nothing.
     */
    @Test
    @SuppressWarnings("unchecked")
    void depositReportsUnreachableWhenTheStartFails() {
        HardwareClient client = mock(HardwareClient.class);
        doThrow(new RuntimeException("down")).when(client).startCashChangerDeposit("2372");
        Consumer<BigDecimal> onProgress = mock(Consumer.class);
        Consumer<CashChangerService.Deposit> onDone = mock(Consumer.class);
        Consumer<String> onError = mock(Consumer.class);
        serviceWith(client, true).deposit(new BigDecimal("23.72"), onProgress, onDone, onError);
        verify(onError).accept("MONNAYEUR INJOIGNABLE");
        verifyNoInteractions(onProgress, onDone);
    }

    /**
     * Each new amount inserted is reported once, an unchanged one is not, and
     * the deposit the machine closed is reported with what it gave back; a
     * blank failure and a line without separator are ignored.
     */
    @Test
    @SuppressWarnings("unchecked")
    void depositReportsTheProgressThenTheOutcome() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getCashChangerDeposit()).thenReturn(
                "state=RUNNING\ninserted=0\nfailure=",
                "state=RUNNING\ninserted=200",
                "state=RUNNING\ninserted=200\nnoise",
                "state=DONE\ninserted=2500\nchange=128\nfailure= ");
        List<BigDecimal> progress = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<CashChangerService.Deposit> outcome = new AtomicReference<>();
        Consumer<String> onError = mock(Consumer.class);
        serviceWith(client, true).deposit(new BigDecimal("23.72"), progress::add, deposit -> {
            outcome.set(deposit);
            done.countDown();
        }, onError);
        await(done);
        verify(client).startCashChangerDeposit("2372");
        assertEquals(List.of(new BigDecimal("2.00")), progress);
        assertEquals(new BigDecimal("25.00"), outcome.get().inserted());
        assertEquals(new BigDecimal("1.28"), outcome.get().change());
        assertEquals(new BigDecimal("23.72"), outcome.get().settled());
        verifyNoInteractions(onError);
    }

    /**
     * A failure the machine reports ends the deposit, in upper case.
     */
    @Test
    @SuppressWarnings("unchecked")
    void depositReportsTheMachineFailure() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getCashChangerDeposit()).thenReturn("state=DONE\ninserted=100\nfailure=jam");
        CountDownLatch failed = new CountDownLatch(1);
        AtomicReference<String> message = new AtomicReference<>();
        Consumer<CashChangerService.Deposit> onDone = mock(Consumer.class);
        serviceWith(client, true).deposit(BigDecimal.TEN, v -> { }, onDone, m -> {
            message.set(m);
            failed.countDown();
        });
        await(failed);
        assertEquals("JAM", message.get());
        verifyNoInteractions(onDone);
    }

    /**
     * A status that cannot be read reports the machine unreachable.
     */
    @Test
    @SuppressWarnings("unchecked")
    void depositReportsUnreachableWhenTheStatusFails() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getCashChangerDeposit()).thenThrow(new RuntimeException("down"));
        CountDownLatch failed = new CountDownLatch(1);
        AtomicReference<String> message = new AtomicReference<>();
        Consumer<CashChangerService.Deposit> onDone = mock(Consumer.class);
        serviceWith(client, true).deposit(BigDecimal.TEN, v -> { }, onDone, m -> {
            message.set(m);
            failed.countDown();
        });
        await(failed);
        assertEquals("MONNAYEUR INJOIGNABLE", message.get());
        verifyNoInteractions(onDone);
    }

    /**
     * A deposit not concluded before the deadline is cancelled — the machine
     * gives the money back — and reported; an empty status reads as nothing
     * inserted, and a malformed amount as zero.
     */
    @Test
    @SuppressWarnings("unchecked")
    void depositCancelsAfterTheDeadline() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getCashChangerDeposit()).thenReturn(null, "state=RUNNING\ninserted=abc");
        CashChangerService service = serviceWith(client, true);
        service.deadlineMillis = 20L;
        CountDownLatch failed = new CountDownLatch(1);
        AtomicReference<String> message = new AtomicReference<>();
        Consumer<BigDecimal> onProgress = mock(Consumer.class);
        service.deposit(BigDecimal.TEN, onProgress, mock(Consumer.class), m -> {
            message.set(m);
            failed.countDown();
        });
        await(failed);
        assertEquals("MONNAYEUR : DÉPÔT NON CONCLU, MONTANT RENDU", message.get());
        verify(client).cancelCashChangerDeposit();
        verifyNoInteractions(onProgress);
    }

    /**
     * A deadline already passed never polls: the deposit is cancelled at once.
     */
    @Test
    @SuppressWarnings("unchecked")
    void depositWithAPassedDeadlineCancelsAtOnce() {
        HardwareClient client = mock(HardwareClient.class);
        CashChangerService service = serviceWith(client, true);
        service.deadlineMillis = 0L;
        CountDownLatch failed = new CountDownLatch(1);
        service.deposit(BigDecimal.TEN, mock(Consumer.class), mock(Consumer.class), m -> failed.countDown());
        await(failed);
        verify(client, times(0)).getCashChangerDeposit();
        verify(client).cancelCashChangerDeposit();
    }

    /**
     * An interruption during the wait stops the following silently.
     */
    @Test
    @SuppressWarnings("unchecked")
    void depositStopsSilentlyWhenTheFollowThreadIsInterrupted() {
        HardwareClient client = mock(HardwareClient.class);
        CountDownLatch probed = new CountDownLatch(1);
        when(client.getCashChangerDeposit()).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            probed.countDown();
            return "state=RUNNING\ninserted=0";
        });
        Consumer<CashChangerService.Deposit> onDone = mock(Consumer.class);
        Consumer<String> onError = mock(Consumer.class);
        serviceWith(client, true).deposit(BigDecimal.TEN, v -> { }, onDone, onError);
        await(probed);
        verify(client, timeout(1000).times(1)).getCashChangerDeposit();
        verifyNoInteractions(onDone, onError);
    }

    /**
     * Fixing and cancelling reach the bridge, and a bridge failure on either is
     * swallowed: the following thread reports the end.
     */
    @Test
    void fixAndCancelReachTheBridgeAndSwallowItsFailures() {
        HardwareClient client = mock(HardwareClient.class);
        CashChangerService service = serviceWith(client, true);
        service.fix();
        service.cancel();
        verify(client).fixCashChangerDeposit();
        verify(client).cancelCashChangerDeposit();
        HardwareClient failing = mock(HardwareClient.class);
        doThrow(new RuntimeException("down")).when(failing).fixCashChangerDeposit();
        doThrow(new RuntimeException("down")).when(failing).cancelCashChangerDeposit();
        CashChangerService broken = serviceWith(failing, true);
        broken.fix();
        broken.cancel();
        verify(failing).fixCashChangerDeposit();
        verify(failing).cancelCashChangerDeposit();
    }

    /**
     * A till without a cash changer says nothing, and never asks the bridge.
     */
    @Test
    void levelAlertIsSilentWithoutACashChanger() {
        HardwareClient client = mock(HardwareClient.class);
        assertNull(serviceWith(client, false).levelAlert());
        verifyNoInteractions(client);
    }

    /**
     * A machine that does not answer is said unreachable.
     */
    @Test
    void levelAlertSaysTheMachineIsUnreachable() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getCashChangerLevels()).thenThrow(new RuntimeException("down"));
        assertEquals("MONNAYEUR INJOIGNABLE", serviceWith(client, true).levelAlert());
    }

    /**
     * Every denomination out of its thresholds is named, then the machine-wide
     * empty and full states; blank items are skipped.
     */
    @Test
    void levelAlertNamesEveryFlaggedDenominationAndTheMachineStates() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getCashChangerLevels()).thenReturn("low=100,,200\nhigh=50\nstatus=EMPTY\nfull=FULL");
        assertEquals("MONNAYEUR : NIVEAU BAS 1,00\u00a0€ 2,00\u00a0€ - NIVEAU HAUT 0,50\u00a0€ - VIDE - PLEIN",
                serviceWith(client, true).levelAlert());
    }

    /**
     * The near-empty and near-full states are named too.
     */
    @Test
    void levelAlertNamesTheNearStates() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getCashChangerLevels()).thenReturn("status=NEAREMPTY\nfull=NEARFULL");
        assertEquals("MONNAYEUR : PRESQUE VIDE - PRESQUE PLEIN", serviceWith(client, true).levelAlert());
    }

    /**
     * A machine within all its thresholds says nothing.
     */
    @Test
    void levelAlertIsSilentWithinThresholds() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getCashChangerLevels()).thenReturn("status=OK\nfull=OK\nlow=\nhigh= ");
        assertNull(serviceWith(client, true).levelAlert());
        when(client.getCashChangerLevels()).thenReturn(null);
        assertNull(serviceWith(client, true).levelAlert());
    }

    // --- BO-11-03-03: loans, withdrawals and the cash level ---

    /**
     * The movements go through the machine only with a cash changer at the
     * till AND the store asking for it.
     */
    @Test
    void theMovementsGoThroughTheMachineWhereAdministered() {
        CashChangerService service = serviceWith(mock(HardwareClient.class), true);
        when(service.posSettingsService.cashChangerMovements()).thenReturn(true);
        assertTrue(service.movementsViaMachine());
        when(service.posSettingsService.cashChangerMovements()).thenReturn(false);
        assertFalse(service.movementsViaMachine());
        CashChangerService none = serviceWith(mock(HardwareClient.class), false);
        when(none.posSettingsService.cashChangerMovements()).thenReturn(true);
        assertFalse(none.movementsViaMachine());
    }

    /**
     * A withdrawal is dispensed in cents on the wire and read back as what the
     * machine handed out; a machine that cannot be asked answers nothing.
     */
    @Test
    void aWithdrawalIsDispensed() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.dispenseFromCashChanger("5000")).thenReturn("dispensed=4500");
        CashChangerService service = serviceWith(client, true);
        assertEquals(new BigDecimal("45.00"), service.dispense(new BigDecimal("50")));
        when(client.dispenseFromCashChanger("100")).thenThrow(new RuntimeException("jam"));
        assertNull(service.dispense(BigDecimal.ONE));
    }

    /**
     * A loan is fed in cents on the wire and read back as what the machine
     * accepted; an answer without the figure reads as zero; a machine that
     * cannot be asked answers nothing.
     */
    @Test
    void aLoanIsFedIn() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.replenishCashChanger("2000")).thenReturn("accepted=2000");
        when(client.replenishCashChanger("300")).thenReturn("noise");
        CashChangerService service = serviceWith(client, true);
        assertEquals(new BigDecimal("20.00"), service.replenish(new BigDecimal("20")));
        assertEquals(new BigDecimal("0.00"), service.replenish(new BigDecimal("3")));
        when(client.replenishCashChanger("100")).thenThrow(new RuntimeException("down"));
        assertNull(service.replenish(BigDecimal.ONE));
    }

    /**
     * The cash level lists each denomination by decreasing value, its count and
     * its amount, then the total; an unreadable line is skipped; without a
     * machine, or with one that cannot be read, there is no level.
     */
    @Test
    void theCashLevelIsReadByDenomination() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getCashChangerInventory()).thenReturn("total=12050\n50=1\n2000=6\nX=1\n10=abc");
        CashChangerService.Inventory inventory = serviceWith(client, true).inventory();
        assertEquals(0, new BigDecimal("120.50").compareTo(inventory.total()));
        assertEquals(2, inventory.levels().size());
        assertEquals(new BigDecimal("20.00"), inventory.levels().get(0).unit());
        assertEquals(6, inventory.levels().get(0).count());
        assertEquals(new BigDecimal("120.00"), inventory.levels().get(0).amount());
        assertEquals(new BigDecimal("0.50"), inventory.levels().get(1).unit());
        assertEquals(new BigDecimal("0.50"), inventory.levels().get(1).amount());
        assertNull(serviceWith(client, false).inventory());
        HardwareClient down = mock(HardwareClient.class);
        when(down.getCashChangerInventory()).thenThrow(new RuntimeException("down"));
        assertNull(serviceWith(down, true).inventory());
    }
}
