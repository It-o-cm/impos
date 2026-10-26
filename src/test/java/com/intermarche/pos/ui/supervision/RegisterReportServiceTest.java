package com.intermarche.pos.ui.supervision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.pos.domain.deploy.NodeVersion;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.store.CheckoutPoint;
import com.intermarche.pos.domain.sync.RefState;
import com.intermarche.pos.service.CheckoutPointService;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.deploy.DeployPayloads;
import com.intermarche.pos.service.deploy.FunctionUsageService;
import com.intermarche.pos.service.deploy.NodeUpdateService;
import com.intermarche.pos.service.sync.RefPullService;
import com.intermarche.pos.service.sync.SyncEndpoints;
import com.intermarche.pos.service.sync.register.SyncOutboxService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.ticket.TicketState;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.TransactionRunnerOptions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link RegisterReportService}. */
class RegisterReportServiceTest {

    /**
     * Builds the service over mocked collaborators.
     *
     * @param pdv the commissioned point of sale, possibly null
     * @param token the shared token, possibly null
     * @return the service
     */
    private RegisterReportService service(String pdv, String token) {
        RegisterReportService service = new RegisterReportService();
        service.syncEndpoints = mock(SyncEndpoints.class);
        when(service.syncEndpoints.storeUrl()).thenReturn("http://store");
        when(service.syncEndpoints.hasStoreUrl()).thenReturn(true);
        service.state = new PosState();
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.supervisionReportSeconds()).thenReturn(60);
        service.nodeUpdateService = mock(NodeUpdateService.class);
        service.functionUsageService = mock(FunctionUsageService.class);
        service.refPullService = mock(RefPullService.class);
        service.syncOutboxService = mock(SyncOutboxService.class);
        service.checkoutPointService = mock(CheckoutPointService.class);
        service.remoteMaintenanceService = mock(RemoteMaintenanceService.class);
        service.objectMapper = new ObjectMapper();
        service.terminalId = "C04";
        service.pdvNumber = Optional.ofNullable(pdv);
        service.token = Optional.ofNullable(token);
        service.httpClient = mock(HttpClient.class);
        NodeVersion version = new NodeVersion();
        version.installedVersion = "1.0";
        when(service.nodeUpdateService.snapshot()).thenReturn(version);
        return service;
    }

    /**
     * Runs every transaction in place.
     *
     * @param tx the static mock of the transaction API
     * @throws Exception never
     */
    @SuppressWarnings("unchecked")
    private void inPlace(MockedStatic<QuarkusTransaction> tx) throws Exception {
        TransactionRunnerOptions runner = mock(TransactionRunnerOptions.class);
        tx.when(QuarkusTransaction::requiringNew).thenReturn(runner);
        when(runner.call(any(Callable.class))).thenAnswer(invocation -> ((Callable<Object>) invocation.getArgument(0)).call());
    }

    /**
     * Makes the HTTP client answer.
     *
     * @param service the service
     * @param status the status
     * @param body the body
     * @return the request captor
     * @throws Exception never
     */
    @SuppressWarnings("unchecked")
    private ArgumentCaptor<HttpRequest> answer(RegisterReportService service, int status, String body) throws Exception {
        HttpResponse<Object> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        when(service.httpClient.send(requests.capture(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        return requests;
    }

    /** The loop starts only with a store node, and stops cleanly. */
    @Test
    void theLoopNeedsAStoreNode() {
        RegisterReportService alone = service(null, null);
        when(alone.syncEndpoints.hasStoreUrl()).thenReturn(false);
        alone.onStart(null);
        alone.stop();
        verify(alone.posSettingsService, never()).supervisionReportSeconds();
        RegisterReportService linked = service(null, null);
        linked.onStart(null);
        linked.stop();
        verify(linked.posSettingsService).supervisionReportSeconds();
    }

    /**
     * BO-08-04-15/16/20: the report carries the version, the peripherals and the inventory, the last ticket,
     * the push backlog and its first error, the integrations, the counters and the outcomes.
     */
    @Test
    void buildReportsTheRegister() {
        RegisterReportService service = service(" 07039 ", null);
        NodeVersion version = new NodeVersion();
        version.installedVersion = "1.0";
        version.state = NodeVersion.State.FAILED;
        version.lastError = "boom";
        version.targetVersion = "2.0";
        when(service.nodeUpdateService.snapshot()).thenReturn(version);
        service.state.peripheralAvailability.put("hardware.printer", "FAILED");
        when(service.syncOutboxService.backlog()).thenReturn(List.of(
                new SyncOutboxService.BacklogRow("TICKET", 2, 3, null),
                new SyncOutboxService.BacklogRow("EVENT", 1, 1, "HTTP 503"),
                new SyncOutboxService.BacklogRow("SESSION", 1, 1, "later")));
        CheckoutPoint point = new CheckoutPoint();
        point.scaleAddress = "COM1";
        when(service.checkoutPointService.own()).thenReturn(point);
        Ticket last = new Ticket();
        last.closingDate = LocalDateTime.of(2026, 10, 24, 9, 0);
        service.results.add(new DeployPayloads.ActionResult());
        try (MockedStatic<Ticket> tickets = mockStatic(Ticket.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            tickets.when(() -> Ticket.findLastClosedByTerminal("C04")).thenReturn(last);
            panache.when(PanacheEntityBase::listAll).thenReturn(List.of());
            DeployPayloads.NodeReport report = service.build(true);
            assertEquals("REGISTER", report.kind);
            assertEquals("07039", report.pdvNumber);
            assertEquals("C04", report.nodeId);
            assertFalse(report.appOk);
            assertEquals("boom", report.appDetail);
            assertEquals("2.0", report.updateTarget);
            assertTrue(report.startup);
            assertEquals("2026-10-24T09:00", report.lastTicketAt);
            assertEquals(4, report.outboxPending);
            assertEquals("HTTP 503", report.outboxError);
            assertEquals("Imprimante", report.peripherals.get(0).role);
            assertEquals("PERIPHERAL", report.inventory.get(0).category);
            assertEquals(11, report.inventory.size());
            assertEquals("NONE COM1", report.inventory.get(9).detail);
            assertEquals("NONE ", report.inventory.get(10).detail);
            assertEquals(1, report.actionResults.size());
        }
    }

    /** A register without point of sale, without ticket or with an undated one, reports healthy. */
    @Test
    void buildReportsAFreshRegister() {
        RegisterReportService blank = service(" ", null);
        Ticket undated = new Ticket();
        try (MockedStatic<Ticket> tickets = mockStatic(Ticket.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(PanacheEntityBase::listAll).thenReturn(List.of());
            DeployPayloads.NodeReport report = blank.build(false);
            assertNull(report.pdvNumber);
            assertTrue(report.appOk);
            assertNull(report.appDetail);
            assertNull(report.lastTicketAt);
            assertEquals(5, report.inventory.size());
            tickets.when(() -> Ticket.findLastClosedByTerminal("C04")).thenReturn(undated);
            assertNull(service(null, null).build(false).pdvNumber);
            assertNull(blank.build(false).lastTicketAt);
        }
    }

    /** BO-08-01-17: each pulled domain is reported, its pull error failing it; an error alone is listed too. */
    @Test
    void integrationsReportThePulls() {
        RegisterReportService service = service(null, null);
        RefState products = new RefState();
        products.domain = "PRODUCTS";
        products.appliedAt = LocalDateTime.of(2026, 10, 24, 8, 0);
        RefState vat = new RefState();
        vat.domain = "VAT_RATES";
        Map<String, String> errors = new LinkedHashMap<>();
        errors.put("VAT_RATES", "boom");
        errors.put("SETTINGS", "down");
        when(service.refPullService.getLastErrors()).thenReturn(errors);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(PanacheEntityBase::listAll).thenReturn(List.of(products, vat));
            List<DeployPayloads.Integration> integrations = service.integrations();
            assertEquals(3, integrations.size());
            assertEquals("APPLIED", integrations.get(0).outcome);
            assertEquals("2026-10-24T08:00", integrations.get(0).at);
            assertEquals("FAILED", integrations.get(1).outcome);
            assertNull(integrations.get(1).at);
            assertEquals("SETTINGS", integrations.get(2).domain);
        }
    }

    /** The peripheral roles are named for the back office, an unknown one kept as is. */
    @Test
    void peripheralNamesAreFrench() {
        assertEquals("Imprimante", RegisterReportService.peripheralName("hardware.printer"));
        assertEquals("Balance", RegisterReportService.peripheralName("hardware.scale"));
        assertEquals("Scanner", RegisterReportService.peripheralName("hardware.scanner"));
        assertEquals("hardware.other", RegisterReportService.peripheralName("hardware.other"));
        assertEquals("", RegisterReportService.text(null));
    }

    /**
     * BO-08-03-07/10, BO-08-04-17: a report is sent, the answer's own target considered at start-up and the
     * orders carried out, the version check answered with the update's decision; another node's target is
     * ignored.
     */
    @Test
    @SuppressWarnings("unchecked")
    void cycleSendsAndCarriesOut() throws Exception {
        RegisterReportService service = service(null, "s3cret");
        DeployPayloads.ReportAnswer answer = new DeployPayloads.ReportAnswer();
        DeployPayloads.Target store = new DeployPayloads.Target();
        store.kind = "STORE";
        store.nodeId = "C04";
        DeployPayloads.Target other = new DeployPayloads.Target();
        other.kind = "REGISTER";
        other.nodeId = "C05";
        DeployPayloads.Target own = new DeployPayloads.Target();
        own.kind = "REGISTER";
        own.nodeId = "C04";
        own.version = "2.0";
        DeployPayloads.Action reset = new DeployPayloads.Action();
        reset.uid = "r1";
        reset.type = "TERMINAL_RESET";
        DeployPayloads.Action check = new DeployPayloads.Action();
        check.uid = "c1";
        check.type = "UPDATE_CHECK";
        answer.targets = List.of(store, other, own);
        answer.actions = List.of(reset, check);
        ArgumentCaptor<HttpRequest> requests = answer(service, 200, new ObjectMapper().writeValueAsString(answer));
        DeployPayloads.ActionResult resetDone = new DeployPayloads.ActionResult();
        when(service.remoteMaintenanceService.execute(any())).thenReturn(resetDone);
        when(service.nodeUpdateService.consider(any(), anyBoolean(), anyBoolean(), any(Function.class)))
                .thenReturn(NodeUpdateService.Decision.APPLIED);
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class);
             MockedStatic<Ticket> tickets = mockStatic(Ticket.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            inPlace(tx);
            panache.when(PanacheEntityBase::listAll).thenReturn(List.of());
            service.cycleSafely();
        }
        assertTrue(service.started);
        assertEquals("http://store/api/supervision/report", requests.getValue().uri().toString());
        assertEquals(Optional.of("s3cret"), requests.getValue().headers().firstValue("X-Sync-Token"));
        ArgumentCaptor<DeployPayloads.Target> considered = ArgumentCaptor.forClass(DeployPayloads.Target.class);
        verify(service.nodeUpdateService).consider(considered.capture(), eq(true), eq(true), any(Function.class));
        assertEquals("2.0", considered.getValue().version);
        assertEquals(2, service.results.size());
        assertEquals("Contrôle de mise à jour : APPLIED", service.results.get(1).result);
    }

    /** A busy register with no target and no order defers; a refused report is swallowed and retried. */
    @Test
    @SuppressWarnings("unchecked")
    void aBusyOrRefusedReport() throws Exception {
        RegisterReportService service = service(null, null);
        service.started = true;
        service.state.ticket.items.add(mock(TicketState.TicketItem.class));
        answer(service, 200, "{\"targets\":[],\"actions\":[]}");
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class);
             MockedStatic<Ticket> tickets = mockStatic(Ticket.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            inPlace(tx);
            panache.when(PanacheEntityBase::listAll).thenReturn(List.of());
            service.cycle();
            verify(service.nodeUpdateService).consider(eq(null), eq(false), eq(false), any(Function.class));
            RegisterReportService refused = service(null, null);
            refused.results.add(new DeployPayloads.ActionResult());
            answer(refused, 503, "");
            refused.cycleSafely();
            assertFalse(refused.started);
            assertEquals(1, refused.results.size());
        }
    }

    /** BO-08-03-07: the package is downloaded from the store node; a refusal or a transport failure throws. */
    @Test
    @SuppressWarnings("unchecked")
    void downloadFetchesTheStorePackage() throws Exception {
        DeployPayloads.Target target = new DeployPayloads.Target();
        target.product = "IMPOS";
        target.version = "2.0";
        RegisterReportService service = service(null, null);
        ArgumentCaptor<HttpRequest> requests = answer(service, 200, "pkg");
        assertEquals("pkg", service.download(target));
        assertEquals("http://store/api/deployment/package/IMPOS/2.0", requests.getValue().uri().toString());
        assertFalse(requests.getValue().headers().firstValue("X-Sync-Token").isPresent());
        RegisterReportService missing = service(null, "");
        answer(missing, 404, "");
        assertEquals("HTTP 404", assertThrows(IllegalStateException.class, () -> missing.download(target)).getMessage());
        RegisterReportService broken = service(null, null);
        when(broken.httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("refused"));
        assertEquals("refused", assertThrows(IllegalStateException.class, () -> broken.download(target)).getMessage());
    }

    /** BO-08-04-17: a version check after the start-up still bypasses the window. */
    @Test
    @SuppressWarnings("unchecked")
    void aLaterVersionCheckBypassesTheWindow() throws Exception {
        RegisterReportService service = service(null, null);
        service.started = true;
        answer(service, 200, "{\"targets\":[],\"actions\":[{\"uid\":\"c1\",\"type\":\"UPDATE_CHECK\"}]}");
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class);
             MockedStatic<Ticket> tickets = mockStatic(Ticket.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            inPlace(tx);
            panache.when(PanacheEntityBase::listAll).thenReturn(List.of());
            service.cycle();
        }
        verify(service.nodeUpdateService).consider(eq(null), eq(true), eq(true), any(Function.class));
    }
}
