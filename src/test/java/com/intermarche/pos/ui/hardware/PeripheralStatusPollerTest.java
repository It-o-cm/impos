package com.intermarche.pos.ui.hardware;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

/** LC-13-02-12: verifies the scanner monitor's lifecycle without booting the application. */
class PeripheralStatusPollerTest {
    /** Stopping an absent or started worker is safe and never starts an immediate hardware probe. */
    @Test
    void lifecycle() {
        var poller = new PeripheralStatusPoller();
        poller.hardware = mock(HardwareService.class);
        poller.stop();
        poller.start(new io.quarkus.runtime.StartupEvent());
        poller.stop();
        verifyNoInteractions(poller.hardware);
    }

    /** Missing transaction infrastructure is contained and cannot break the sale thread. */
    @Test
    void failedPulseIsContained() {
        var poller = new PeripheralStatusPoller();
        poller.hardware = mock(HardwareService.class);
        assertDoesNotThrow(poller::poll);
    }
    /** LC-13: the periodic callback executes inside its transaction, without real hardware or a container. */
    @Test
    void periodicWorkRunsInsideTheTransaction() {
        var worker = new PeripheralStatusPoller();
        worker.hardware = mock(HardwareService.class);
        var runner = mock(io.quarkus.narayana.jta.TransactionRunnerOptions.class);
        try (var transactions = mockStatic(io.quarkus.narayana.jta.QuarkusTransaction.class)) {
            transactions.when(io.quarkus.narayana.jta.QuarkusTransaction::requiringNew).thenReturn(runner);
            doAnswer(call -> { call.getArgument(0, Runnable.class).run(); return null; }).when(runner).run(any(Runnable.class));
            worker.poll();
            verify(worker.hardware).probeScanner();
        }
    }

}
