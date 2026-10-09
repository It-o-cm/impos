package com.intermarche.pos.ui.cash;

import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.session.CashMovement;
import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.service.CashMovementService;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TicketNumberService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.auth.AuthState;
import com.intermarche.pos.ui.endorsement.EndorsementService;
import com.intermarche.pos.ui.hardware.TicketPrinterService;
import com.intermarche.pos.ui.TestMessages;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FinancialMovementResource}.
 * <p>
 * The resource is a thin JAX-RS facade over {@link PosState}, a
 * {@link CashSessionService}, a {@link CashMovementService}, a
 * {@link PosSettingsService}, an {@link EndorsementService}, a
 * {@link TicketPrinterService}, a {@link TicketNumberService} and one Qute
 * {@link Template}. Every collaborator is a Mockito mock; {@code PosState} is a
 * mock carrying a real {@link AuthState} so the operator reads without a null,
 * and the sole static call ({@code Employee.findById}) is intercepted with
 * {@link org.mockito.Mockito#mockStatic} on {@link PanacheEntityBase}. No
 * database and no Quarkus context is booted.
 * <p>
 * Branch enumeration (both arms, 100%): the page's six {@code messageOf} codes
 * plus the unknown-code null arm and the {@code ok != null} saved flag;
 * {@code record}'s training gate, {@code session == null}, the source/destination
 * membership guard (unknown source, unknown destination), the same-tender guard,
 * the non-positive amount guard, the {@code requiresEndorsement && endorsedBy
 * == null} park arm and the perform arm (supervisor self-endorse and
 * below-threshold), the print-on and print-off arms, and the
 * {@code recorded == null} refusal arm through {@code performEndorsed}; plus
 * {@code perform}'s re-run of each guard.
 */
class FinancialMovementResourceTest {

    /**
     * Builds a resource with fresh mocks and a real {@link AuthState} carrying a
     * logged operator (id 7, badge {@code M1}, name {@code Jean}).
     *
     * @return the wired resource
     */
    private FinancialMovementResource newResource() {
        FinancialMovementResource resource = new FinancialMovementResource();
        // The operator messages come from the French bundle, so every
        // assertion below keeps the literal sentence the register shows
        // (LC-13-06).
        resource.i18n = TestMessages.french();
        resource.state = mock(PosState.class);
        AuthState auth = new AuthState();
        auth.operatorId = 7L;
        auth.operatorBadgeId = "M1";
        auth.operatorName = "Jean";
        resource.state.auth = auth;
        when(resource.state.getOperatorName()).thenReturn("Jean");
        resource.cashSessionService = mock(CashSessionService.class);
        resource.cashMovementService = mock(CashMovementService.class);
        resource.posSettingsService = mock(PosSettingsService.class);
        resource.endorsementService = mock(EndorsementService.class);
        resource.ticketPrinterService = mock(TicketPrinterService.class);
        resource.ticketNumberService = mock(TicketNumberService.class);
        resource.financialMovement = mock(Template.class);
        when(resource.posSettingsService.financialMovementSources()).thenReturn("CASH:Espèces");
        when(resource.posSettingsService.financialMovementDestinations()).thenReturn("CREDIT:Crédit client");
        when(resource.posSettingsService.cashMovementEndorsementThreshold())
                .thenReturn(new BigDecimal("100.00"));
        return resource;
    }

    /**
     * Stubs the six-link page template chain, returning the link that receives
     * the {@code error} data (so its argument can be verified) and the rendered
     * view.
     *
     * @param resource the resource whose template is stubbed
     * @return the two links: [errorLink, result]
     */
    private TemplateInstance[] stubPageChain(FinancialMovementResource resource) {
        TemplateInstance ti1 = mock(TemplateInstance.class);
        TemplateInstance ti2 = mock(TemplateInstance.class);
        TemplateInstance ti3 = mock(TemplateInstance.class);
        TemplateInstance ti4 = mock(TemplateInstance.class);
        TemplateInstance ti5 = mock(TemplateInstance.class);
        TemplateInstance ti6 = mock(TemplateInstance.class);
        when(resource.financialMovement.data("state", resource.state)).thenReturn(ti1);
        when(ti1.data(eq("sources"), any())).thenReturn(ti2);
        when(ti2.data(eq("destinations"), any())).thenReturn(ti3);
        when(ti3.data(eq("threshold"), any())).thenReturn(ti4);
        when(ti4.data(eq("saved"), any())).thenReturn(ti5);
        when(ti5.data(eq("error"), any())).thenReturn(ti6);
        return new TemplateInstance[]{ti5, ti6};
    }

    /**
     * Asserts the given response is a 303 redirect to the expected location.
     *
     * @param response the response under test
     * @param location the expected {@code Location} header value
     */
    private void assertRedirect(Response response, String location) {
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals(location, response.getLocation().toString());
    }

    /**
     * The page maps every error code to its French message (the six
     * {@code messageOf} true arms), a null for an unknown code (else arm), and
     * raises the saved flag when {@code ok} is present.
     */
    @Test
    void pageMapsEveryMessageAndSavedFlag() {
        assertPageError("no-session", "AUCUNE SESSION OUVERTE");
        assertPageError("bad-tender", "MOYEN DE PAIEMENT INVALIDE");
        assertPageError("same-tender", "MEME MOYEN EN SOURCE ET DESTINATION");
        assertPageError("amount", "MONTANT INVALIDE");
        assertPageError("endorsement", "AVAL MANAGER REFUSÉ OU MANQUANT");
        assertPageError("training", "INDISPONIBLE EN FORMATION");
        assertPageError("bogus", null);
        FinancialMovementResource resource = newResource();
        TemplateInstance[] chain = stubPageChain(resource);
        assertSame(chain[1], resource.financialMovementPage(null, "1"));
        verify(chain[0]).data("error", (String) null);
    }

    /**
     * Renders the page with the given error code and asserts the mapped message
     * reached the template.
     *
     * @param error the error code
     * @param expected the message expected on the page
     */
    private void assertPageError(String error, String expected) {
        FinancialMovementResource resource = newResource();
        TemplateInstance[] chain = stubPageChain(resource);
        assertSame(chain[1], resource.financialMovementPage(error, null));
        verify(chain[0]).data("error", expected);
    }

    /**
     * A record in training mode is blocked with the training redirect
     * ({@code trainingMode} true arm); nothing is looked up or written.
     */
    @Test
    void recordTrainingModeBlocked() {
        FinancialMovementResource resource = newResource();
        resource.state.trainingMode = true;
        assertRedirect(resource.record("CASH", "CREDIT", "10"),
                "/financial-movement?error=training");
        verifyNoInteractions(resource.cashSessionService);
        verifyNoInteractions(resource.cashMovementService);
    }

    /**
     * A record with no open session redirects with {@code no-session}
     * ({@code session == null} true arm).
     */
    @Test
    void recordNoSessionRedirects() {
        FinancialMovementResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.cashSessionService.getOpenSession()).thenReturn(null);
        assertRedirect(resource.record("CASH", "CREDIT", "10"),
                "/financial-movement?error=no-session");
        verifyNoInteractions(resource.cashMovementService);
    }

    /**
     * A record whose source is unknown (null source, membership false arm) and
     * one whose destination is unknown both redirect with {@code bad-tender}.
     */
    @Test
    void recordUnknownSourceOrDestinationRedirectsBadTender() {
        FinancialMovementResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        assertRedirect(resource.record(null, "CREDIT", "10"),
                "/financial-movement?error=bad-tender");
        assertRedirect(resource.record("CASH", "UNKNOWN", "10"),
                "/financial-movement?error=bad-tender");
        verifyNoInteractions(resource.cashMovementService);
    }

    /**
     * A record whose source equals its destination redirects with
     * {@code same-tender} ({@code source.equals(destination)} true arm). Both
     * ends must be administered for the guard to be reached, so the list carries
     * CASH as source and destination.
     */
    @Test
    void recordSameTenderRedirects() {
        FinancialMovementResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        when(resource.posSettingsService.financialMovementDestinations()).thenReturn("CASH:Espèces");
        assertRedirect(resource.record("CASH", "CASH", "10"),
                "/financial-movement?error=same-tender");
        verifyNoInteractions(resource.cashMovementService);
    }

    /**
     * A record with a non-positive amount redirects with {@code amount}
     * (signum &lt;= 0 true arm, via a blank amount that parses to ZERO).
     */
    @Test
    void recordNonPositiveAmountRedirects() {
        FinancialMovementResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        assertRedirect(resource.record("CASH", "CREDIT", "  "),
                "/financial-movement?error=amount");
        verifyNoInteractions(resource.cashMovementService);
    }

    /**
     * A below-threshold movement with a logged operator writes a TRANSFER naming
     * both ends and prints the ticket ({@code requiresEndorsement} false arm,
     * print-on arm); the valid comma amount exercises the parse success branch.
     */
    @Test
    void recordBelowThresholdWritesAndPrints() {
        FinancialMovementResource resource = newResource();
        resource.state.trainingMode = false;
        CashSession session = mock(CashSession.class);
        Employee cashier = mock(Employee.class);
        CashMovement recorded = mock(CashMovement.class);
        when(resource.cashSessionService.getOpenSession()).thenReturn(session);
        when(resource.cashMovementService.requiresEndorsement(any())).thenReturn(false);
        when(resource.posSettingsService.financialMovementPrint()).thenReturn(true);
        when(resource.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(resource.cashMovementService.record(eq(session), eq(cashier),
                eq(CashMovement.MovementType.TRANSFER), eq(new BigDecimal("30.00")),
                any(), eq(null), eq("CASH"), eq("CREDIT"), eq(null))).thenReturn(recorded);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Employee.findById(7L)).thenReturn(cashier);
            assertRedirect(resource.record("CASH", "CREDIT", "30,00"),
                    "/financial-movement?ok=1");
        }
        verify(resource.ticketPrinterService).printTransferTicket(eq("Espèces"),
                eq("Crédit client"), eq(new BigDecimal("30.00")), eq("Jean"), eq("C04"));
        verify(resource.state).touch();
    }

    /**
     * A below-threshold movement with printing disabled writes but does NOT
     * print (print-off arm).
     */
    @Test
    void recordBelowThresholdPrintDisabledDoesNotPrint() {
        FinancialMovementResource resource = newResource();
        resource.state.trainingMode = false;
        CashSession session = mock(CashSession.class);
        Employee cashier = mock(Employee.class);
        when(resource.cashSessionService.getOpenSession()).thenReturn(session);
        when(resource.cashMovementService.requiresEndorsement(any())).thenReturn(false);
        when(resource.posSettingsService.financialMovementPrint()).thenReturn(false);
        when(resource.cashMovementService.record(any(), any(), any(), any(), any(),
                any(), any(), any(), any())).thenReturn(mock(CashMovement.class));
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Employee.findById(7L)).thenReturn(cashier);
            assertRedirect(resource.record("CASH", "CREDIT", "30"),
                    "/financial-movement?ok=1");
        }
        verifyNoInteractions(resource.ticketPrinterService);
    }

    /**
     * An above-threshold movement whose operator is a supervisor self-endorses
     * with their own badge ({@code requiresEndorsement} true, supervisor arm),
     * so it writes without parking and never asks the modal.
     */
    @Test
    void recordAboveThresholdSelfEndorsedWrites() {
        FinancialMovementResource resource = newResource();
        resource.state.trainingMode = false;
        CashSession session = mock(CashSession.class);
        Employee cashier = mock(Employee.class);
        when(resource.cashSessionService.getOpenSession()).thenReturn(session);
        when(resource.cashMovementService.requiresEndorsement(any())).thenReturn(true);
        when(resource.endorsementService.operatorIsSupervisor(resource.state)).thenReturn(true);
        when(resource.posSettingsService.financialMovementPrint()).thenReturn(false);
        when(resource.cashMovementService.record(eq(session), eq(cashier),
                eq(CashMovement.MovementType.TRANSFER), eq(new BigDecimal("150")),
                any(), eq("M1"), eq("CASH"), eq("CREDIT"), eq(null)))
                .thenReturn(mock(CashMovement.class));
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Employee.findById(7L)).thenReturn(cashier);
            assertRedirect(resource.record("CASH", "CREDIT", "150"),
                    "/financial-movement?ok=1");
        }
        verify(resource.endorsementService, never()).requestAuthorization(any(), any(), any(), any());
    }

    /**
     * An above-threshold movement with no supervisor logged PARKS the form and
     * opens the shared modal ({@code endorsedBy == null} park arm): the screen
     * redirects to itself, the request carries the three typed fields and the
     * return path, and nothing reaches the movement service.
     */
    @Test
    void recordAboveThresholdParksAndAsksTheModal() {
        FinancialMovementResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        when(resource.cashMovementService.requiresEndorsement(any())).thenReturn(true);
        when(resource.endorsementService.operatorIsSupervisor(resource.state)).thenReturn(false);
        assertRedirect(resource.record("CASH", "CREDIT", "150"), "/financial-movement");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, String>> form = ArgumentCaptor.forClass(java.util.Map.class);
        verify(resource.endorsementService).requestAuthorization(eq(resource.state),
                eq("FINANCIAL_MOVEMENT"), form.capture(), eq("/financial-movement"));
        assertEquals("CASH", form.getValue().get("source"));
        assertEquals("CREDIT", form.getValue().get("destination"));
        assertEquals("150", form.getValue().get("amount"));
        verify(resource.cashMovementService, never()).record(any(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    /**
     * A movement replayed by the modal is written with the endorsing badge
     * ({@code performEndorsed}); a null operator id exercises the
     * {@code operatorId != null} false arm (no cashier resolved).
     */
    @Test
    void performEndorsedWritesWithEndorserBadgeAndNullOperator() {
        FinancialMovementResource resource = newResource();
        resource.state.trainingMode = false;
        resource.state.auth.operatorId = null;
        CashSession session = mock(CashSession.class);
        when(resource.cashSessionService.getOpenSession()).thenReturn(session);
        when(resource.cashMovementService.requiresEndorsement(any())).thenReturn(true);
        when(resource.posSettingsService.financialMovementPrint()).thenReturn(false);
        when(resource.cashMovementService.record(eq(session), eq(null),
                eq(CashMovement.MovementType.TRANSFER), eq(new BigDecimal("150")),
                any(), eq("22222222"), eq("CASH"), eq("CREDIT"), eq(null)))
                .thenReturn(mock(CashMovement.class));
        java.util.Map<String, String> form = new java.util.LinkedHashMap<>();
        form.put("source", "CASH");
        form.put("destination", "CREDIT");
        form.put("amount", "150");
        assertRedirect(resource.performEndorsed(form, "22222222"), "/financial-movement?ok=1");
    }

    /**
     * A movement the service refuses (record returns null) redirects with
     * {@code endorsement} ({@code recorded == null} true arm).
     */
    @Test
    void performRecordRefusalRedirectsEndorsement() {
        FinancialMovementResource resource = newResource();
        resource.state.trainingMode = false;
        CashSession session = mock(CashSession.class);
        Employee cashier = mock(Employee.class);
        when(resource.cashSessionService.getOpenSession()).thenReturn(session);
        when(resource.cashMovementService.requiresEndorsement(any())).thenReturn(false);
        when(resource.cashMovementService.record(any(), any(), any(), any(), any(),
                any(), any(), any(), any())).thenReturn(null);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Employee.findById(7L)).thenReturn(cashier);
            assertRedirect(resource.record("CASH", "CREDIT", "30"),
                    "/financial-movement?error=endorsement");
        }
        verify(resource.state).touch();
    }

    /**
     * A parked movement replayed while the register slipped into training mode
     * is blocked ({@code perform}'s training true arm, reachable only through
     * {@code performEndorsed}).
     */
    @Test
    void performEndorsedTrainingModeBlocked() {
        FinancialMovementResource resource = newResource();
        resource.state.trainingMode = true;
        java.util.Map<String, String> form = new java.util.LinkedHashMap<>();
        form.put("source", "CASH");
        form.put("destination", "CREDIT");
        form.put("amount", "150");
        assertRedirect(resource.performEndorsed(form, "22222222"),
                "/financial-movement?error=training");
        verifyNoInteractions(resource.cashMovementService);
    }

    /**
     * A parked movement replayed after the session closed is refused with the
     * no-session redirect ({@code perform}'s {@code session == null} true arm).
     */
    @Test
    void performEndorsedNoSessionRedirects() {
        FinancialMovementResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.cashSessionService.getOpenSession()).thenReturn(null);
        java.util.Map<String, String> form = new java.util.LinkedHashMap<>();
        form.put("source", "CASH");
        form.put("destination", "CREDIT");
        form.put("amount", "150");
        assertRedirect(resource.performEndorsed(form, "22222222"),
                "/financial-movement?error=no-session");
        verifyNoInteractions(resource.cashMovementService);
    }

    /**
     * A parked movement whose ends no longer validate is refused with
     * {@code bad-tender} ({@code perform}'s membership false arm), and one whose
     * ends collapsed to the same tender with {@code same-tender}
     * ({@code perform}'s same-tender arm).
     */
    @Test
    void performEndorsedGuardsBadAndSameTender() {
        FinancialMovementResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        java.util.Map<String, String> bad = new java.util.LinkedHashMap<>();
        bad.put("source", "CASH");
        bad.put("destination", "UNKNOWN");
        bad.put("amount", "150");
        assertRedirect(resource.performEndorsed(bad, "22222222"),
                "/financial-movement?error=bad-tender");
        when(resource.posSettingsService.financialMovementDestinations()).thenReturn("CASH:Espèces");
        java.util.Map<String, String> same = new java.util.LinkedHashMap<>();
        same.put("source", "CASH");
        same.put("destination", "CASH");
        same.put("amount", "150");
        assertRedirect(resource.performEndorsed(same, "22222222"),
                "/financial-movement?error=same-tender");
    }

    /**
     * A parked movement whose amount is non-positive is refused with
     * {@code amount} ({@code perform}'s amount guard), and one still requiring an
     * endorsement with no badge with {@code endorsement} ({@code perform}'s
     * endorsement guard).
     */
    @Test
    void performEndorsedAmountAndEndorsementGuards() {
        FinancialMovementResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        java.util.Map<String, String> zero = new java.util.LinkedHashMap<>();
        zero.put("source", "CASH");
        zero.put("destination", "CREDIT");
        zero.put("amount", "0");
        assertRedirect(resource.performEndorsed(zero, "22222222"),
                "/financial-movement?error=amount");
        when(resource.cashMovementService.requiresEndorsement(any())).thenReturn(true);
        java.util.Map<String, String> big = new java.util.LinkedHashMap<>();
        big.put("source", "CASH");
        big.put("destination", "CREDIT");
        big.put("amount", "150");
        assertRedirect(resource.performEndorsed(big, null),
                "/financial-movement?error=endorsement");
        verify(resource.cashMovementService, never()).record(any(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }
}
