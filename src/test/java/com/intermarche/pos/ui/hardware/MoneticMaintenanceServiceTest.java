package com.intermarche.pos.ui.hardware;

import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TicketNumberService;
import com.intermarche.pos.domain.session.CashSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** LC-13-04-03/-06: exercises deferred scheduling and support timestamps without a terminal. */
class MoneticMaintenanceServiceTest {
    /** Isolates support traces from the application's working directory. */
    @TempDir Path directory;

    /** Builds a deterministic sequential maintenance service.
     * @return service with a simulated bridge and a closed register
     */
    private MoneticMaintenanceService service() {
        var service = new MoneticMaintenanceService();
        service.settings = mock(PosSettingsService.class);
        service.sessions = mock(CashSessionService.class);
        service.numbers = mock(TicketNumberService.class);
        service.hardware = mock(HardwareClient.class);
        service.statusFile = directory.resolve("maintenance.txt");
        when(service.settings.value("maintenance.verifone.enabled")).thenReturn("true");
        when(service.settings.value("maintenance.verifone.start")).thenReturn("02:00");
        when(service.settings.value("maintenance.verifone.end")).thenReturn("03:00");
        when(service.settings.value("maintenance.verifone.random")).thenReturn("false");
        when(service.settings.value("maintenance.verifone.stagger-seconds")).thenReturn("30");
        when(service.numbers.getTerminalId()).thenReturn("C02");
        return service;
    }

    /** Disabled maintenance never consults the bridge or session. */
    @Test
    void disabled() {
        var service = service();
        when(service.settings.value("maintenance.verifone.enabled")).thenReturn("false");
        service.tick(LocalDateTime.of(2026, 10, 20, 2, 30));
        verifyNoInteractions(service.hardware, service.sessions);
        service.stop();
    }

    /** Sequential offsets are bounded, tolerate absent identities, and clamp negative staggering. */
    @Test
    void sequentialOffsets() {
        assertEquals(60, MoneticMaintenanceService.sequentialOffset("C02", "30", 3600));
        assertEquals(3599, MoneticMaintenanceService.sequentialOffset("999", "30", 3600));
        assertEquals(0, MoneticMaintenanceService.sequentialOffset(null, "30", 3600));
        assertEquals(0, MoneticMaintenanceService.sequentialOffset("C", "30", 3600));
        assertEquals(0, MoneticMaintenanceService.sequentialOffset("2", "-5", 3600));
    }

    /** Before the slot, during a session, during a payment and after the window no request starts. */
    @Test
    void defersSafely() {
        var service = service();
        service.tick(LocalDateTime.of(2026, 10, 20, 2, 0));
        verifyNoInteractions(service.hardware);
        when(service.sessions.getOpenSession()).thenReturn(new CashSession());
        service.tick(LocalDateTime.of(2026, 10, 20, 2, 10));
        verifyNoInteractions(service.hardware);
        when(service.sessions.getOpenSession()).thenReturn(null);
        when(service.hardware.maintenance("terminal", "status", "1")).thenReturn("state=RUNNING\n");
        service.tick(LocalDateTime.of(2026, 10, 20, 2, 11));
        verify(service.hardware, never()).maintenance("terminal", "test", "1");
        service.tick(LocalDateTime.of(2026, 10, 20, 4, 0));
        assertEquals(java.time.LocalDate.of(2026, 10, 20), service.completed);
    }

    /** Both servers run sequentially and only their own approved response updates the local text file. */
    @Test
    void sequentialPairAndLastSuccess() throws Exception {
        var service = service();
        LocalDateTime now = LocalDateTime.of(2026, 10, 20, 2, 10);
        service.tick(now);
        assertEquals(1, service.pendingServer);
        verify(service.hardware).maintenance("terminal", "test", "1");
        service.tick(now.plusMinutes(1));
        assertEquals(1, service.pendingServer);
        when(service.hardware.maintenance("terminal", "status", "1"))
                .thenReturn("state=RUNNING\n");
        service.tick(now.plusMinutes(2));
        assertEquals(1, service.pendingServer);
        when(service.hardware.maintenance("terminal", "status", "1"))
                .thenReturn("state=DONE\noperation=CONTROL:test:1\napproved=true\n");
        service.tick(now.plusMinutes(3));
        assertEquals(2, service.pendingServer);
        verify(service.hardware).maintenance("terminal", "test", "2");
        when(service.hardware.maintenance("terminal", "status", "1"))
                .thenReturn("state=DONE\noperation=CONTROL:test:2\napproved=true\n");
        service.tick(now.plusMinutes(4));
        String trace = Files.readString(service.statusFile);
        assertTrue(trace.contains("server.1="));
        assertTrue(trace.contains("server.2="));
        assertEquals(0, service.pendingServer);
        assertEquals(now.toLocalDate(), service.completed);
        service.tick(now.plusMinutes(5));
        verify(service.hardware, times(1)).maintenance("terminal", "test", "1");
    }

    /** A refused maintenance or another operation cannot masquerade as a successful server update. */
    @Test
    void ignoresRefusalAndInterleaving() {
        var service = service();
        var now = LocalDateTime.of(2026, 10, 20, 2, 10);
        service.tick(now);
        when(service.hardware.maintenance("terminal", "status", "1"))
                .thenReturn("state=DONE\noperation=CONTROL:test:1\napproved=false\n");
        service.tick(now.plusMinutes(1));
        when(service.hardware.maintenance("terminal", "status", "1"))
                .thenReturn("state=DONE\noperation=DEBIT\napproved=true\n");
        service.tick(now.plusMinutes(2));
        assertFalse(Files.exists(service.statusFile));
        assertEquals(now.toLocalDate(), service.completed);
    }

    /** A trace failure does not disable maintenance on the secondary server. */
    @Test
    void traceFailureStillAdvances() {
        var service = service();
        service.statusFile = directory;
        var now = LocalDateTime.of(2026, 10, 20, 2, 10);
        service.tick(now);
        when(service.hardware.maintenance("terminal", "status", "1"))
                .thenReturn("state=DONE\noperation=CONTROL:test:1\napproved=true\n");
        service.tick(now.plusMinutes(1));
        assertEquals(2, service.pendingServer);
    }

    /** Random slots stay inside the configured window and are renewed after period reset and date change. */
    @Test
    void randomAndPeriodRenewal() {
        var service = service();
        when(service.settings.value("maintenance.verifone.random")).thenReturn("true");
        var now = LocalDateTime.of(2026, 10, 20, 1, 0);
        service.tick(now);
        assertFalse(service.planned.isBefore(now.withHour(2)));
        assertTrue(service.planned.isBefore(now.withHour(3)));
        service.resetPlan();
        assertNull(service.planned);
        service.tick(now.plusDays(1));
        assertEquals(now.plusDays(1).toLocalDate(), service.planned.toLocalDate());
    }

    /** An empty or inverted window never schedules a terminal request. */
    @Test
    void invalidWindow() {
        var service = service();
        when(service.settings.value("maintenance.verifone.end")).thenReturn("02:00");
        service.tick(LocalDateTime.of(2026, 10, 20, 2, 0));
        assertNull(service.planned);
        verifyNoInteractions(service.hardware);
    }
    /** LC-13-04-05: a new day renews an existing non-null plan without requiring an application restart. */
    @Test
    void nextDayRenewsExistingPlan() {
        var service = service();
        var now = LocalDateTime.of(2026, 10, 20, 1, 0);
        service.tick(now);
        service.tick(now.plusDays(1));
        assertEquals(now.plusDays(1).toLocalDate(), service.planned.toLocalDate());
    }

    /** LC-13-04: lifecycle starts and stops only the daemon timer; it sends no immediate terminal request. */
    @Test
    void daemonLifecycle() {
        var service = service();
        service.start(new io.quarkus.runtime.StartupEvent());
        service.stop();
        verifyNoInteractions(service.hardware);
    }

    /** LC-13: the periodic callback executes inside its transaction, without real hardware or a container. */
    @Test
    void periodicWorkRunsInsideTheTransaction() {
        var worker = service();
        when(worker.settings.value("maintenance.verifone.enabled")).thenReturn("false");
        var runner = mock(io.quarkus.narayana.jta.TransactionRunnerOptions.class);
        try (var transactions = mockStatic(io.quarkus.narayana.jta.QuarkusTransaction.class)) {
            transactions.when(io.quarkus.narayana.jta.QuarkusTransaction::requiringNew).thenReturn(runner);
            doAnswer(call -> { call.getArgument(0, Runnable.class).run(); return null; }).when(runner).run(any(Runnable.class));
            worker.pulse();
            verify(worker.settings).value("maintenance.verifone.enabled");
        }
    }

    /** LC-13-04: an unavailable transaction defers maintenance without reaching the terminal. */
    @Test
    void transactionFailureDefersTheMaintenancePulse() {
        var worker = service();
        try (var transactions = mockStatic(io.quarkus.narayana.jta.QuarkusTransaction.class)) {
            transactions.when(io.quarkus.narayana.jta.QuarkusTransaction::requiringNew).thenThrow(new IllegalStateException("database unavailable"));
            assertDoesNotThrow(worker::pulse);
            verifyNoInteractions(worker.hardware);
        }
    }

}
