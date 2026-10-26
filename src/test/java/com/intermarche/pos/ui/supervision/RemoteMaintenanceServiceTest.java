package com.intermarche.pos.ui.supervision;

import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.deploy.DeployPayloads;
import com.intermarche.pos.service.sync.RefPullService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.HardwareClient;
import com.intermarche.pos.ui.hardware.HardwareService;
import com.intermarche.pos.ui.ticket.TicketState;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.TransactionRunnerOptions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link RemoteMaintenanceService}. */
class RemoteMaintenanceServiceTest {

    /**
     * Builds the service over mocked collaborators.
     *
     * @param enabled whether the remote maintenance is active
     * @return the service
     */
    private RemoteMaintenanceService service(boolean enabled) {
        RemoteMaintenanceService service = new RemoteMaintenanceService();
        service.state = new PosState();
        service.posSettingsService = mock(PosSettingsService.class);
        service.hardwareClient = mock(HardwareClient.class);
        service.hardwareService = mock(HardwareService.class);
        service.refPullService = mock(RefPullService.class);
        when(service.posSettingsService.remoteMaintenanceEnabled()).thenReturn(enabled);
        return service;
    }

    /**
     * Builds an order.
     *
     * @param type its type
     * @return the order
     */
    private DeployPayloads.Action order(String type) {
        DeployPayloads.Action order = new DeployPayloads.Action();
        order.uid = "u-" + type;
        order.type = type;
        return order;
    }

    /** BO-08-04-17: a register with the remote maintenance off refuses, and says so. */
    @Test
    void aDisabledRegisterRefuses() {
        RemoteMaintenanceService service = service(false);
        DeployPayloads.ActionResult result = service.execute(order("TERMINAL_RESET"));
        assertFalse(result.success);
        assertEquals("Prise en main à distance désactivée sur cette caisse", result.result);
        verify(service.hardwareClient, never()).maintenance(any(), any(), any());
    }

    /** BO-08-04-17: the payment terminal, the printer and the scanner are reset through the bridge. */
    @Test
    void thePeripheralsAreReset() {
        RemoteMaintenanceService service = service(true);
        DeployPayloads.ActionResult terminal = service.execute(order("TERMINAL_RESET"));
        assertTrue(terminal.success);
        assertEquals("u-TERMINAL_RESET", terminal.uid);
        assertNotNull(terminal.doneAt);
        assertEquals("Réinitialisation terminal effectuée", terminal.result);
        service.execute(order("PRINTER_RESET"));
        service.execute(order("SCANNER_RESET"));
        verify(service.hardwareClient).maintenance("terminal", "reset", "");
        verify(service.hardwareClient).maintenance("printer", "reset", "");
        verify(service.hardwareClient).maintenance("scanner", "reset", "");
    }

    /** BO-08-04-17: a bridge failure is a failed outcome; a sale on the screen refuses the reset. */
    @Test
    void aResetCanFailOrBeRefused() {
        RemoteMaintenanceService service = service(true);
        when(service.hardwareClient.maintenance("terminal", "reset", "")).thenThrow(new IllegalStateException("503"));
        DeployPayloads.ActionResult failed = service.execute(order("TERMINAL_RESET"));
        assertFalse(failed.success);
        assertEquals("Réinitialisation terminal en échec : 503", failed.result);
        service.state.ticket.items.add(mock(TicketState.TicketItem.class));
        DeployPayloads.ActionResult refused = service.execute(order("PRINTER_RESET"));
        assertFalse(refused.success);
        assertEquals("Vente en cours : ordre refusé", refused.result);
    }

    /** BO-08-04-17: the peripherals are probed again and their states reported. */
    @Test
    @SuppressWarnings("unchecked")
    void thePeripheralsAreProbed() throws Exception {
        RemoteMaintenanceService service = service(true);
        when(service.hardwareService.probeDevices()).thenReturn(List.of(
                new HardwareService.DeviceStatus("BALANCE", HardwareService.Availability.ABSENT),
                new HardwareService.DeviceStatus("IMPRIMANTE TICKETS", HardwareService.Availability.AVAILABLE)));
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class)) {
            TransactionRunnerOptions runner = mock(TransactionRunnerOptions.class);
            tx.when(QuarkusTransaction::requiringNew).thenReturn(runner);
            when(runner.call(any(Callable.class))).thenAnswer(invocation -> ((Callable<Object>) invocation.getArgument(0)).call());
            DeployPayloads.ActionResult result = service.execute(order("PERIPHERAL_PROBE"));
            assertTrue(result.success);
            assertEquals("BALANCE=ABSENT, IMPRIMANTE TICKETS=AVAILABLE", result.result);
        }
    }

    /** BO-08-04-09/10 from afar: the pull is relaunched, its error reported; an edge order is refused. */
    @Test
    void thePullIsRelaunched() {
        RemoteMaintenanceService service = service(true);
        assertEquals("Tirage des référentiels relancé", service.execute(order("REFERENTIAL_PULL")).result);
        when(service.refPullService.triggerPull()).thenReturn("injoignable");
        assertEquals("injoignable", service.execute(order("REFERENTIAL_PULL")).result);
        DeployPayloads.ActionResult edge = service.execute(order("INTEGRATION_RETRY"));
        assertFalse(edge.success);
        assertEquals("Ordre non applicable à cette caisse", edge.result);
    }
}
