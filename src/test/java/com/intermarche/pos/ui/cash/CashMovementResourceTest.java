package com.intermarche.pos.ui.cash;

import com.intermarche.pos.domain.session.CashMovement;
import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.service.CashMovementService;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.auth.AuthState;
import com.intermarche.pos.ui.endorsement.EndorsementService;
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
 * Unit tests for {@link CashMovementResource}.
 * <p>
 * The resource is a thin JAX-RS facade over {@link PosState}, a
 * {@link CashSessionService}, a {@link CashMovementService}, a
 * {@link PosSettingsService}, an {@link EndorsementService} and one Qute
 * {@link Template}. Every collaborator is a Mockito mock; {@code PosState} is a
 * mock carrying a real {@link AuthState} so {@code operatorId} /
 * {@code operatorBadgeId} read without a null, and the sole static call
 * ({@code Employee.findById}) is intercepted with
 * {@link org.mockito.Mockito#mockStatic} on {@link PanacheEntityBase}. No
 * database and no Quarkus context are booted.
 * <p>
 * Branch enumeration (both arms of every decision, 100%): the page's eight error
 * codes plus the {@code ok != null} saved flag; {@code rejectionOf}'s three
 * refusals (a withdrawal and a coin supply, which belong to their own screens; an
 * expense with no reason; a down-payment with no order reference) and the
 * declaration that passes them all; {@code record}'s training gate,
 * {@code session == null}, {@code type == null} (via a null and an unknown
 * name), {@code requiresEndorsement} with the endorsement-resolved and
 * endorsement-refused arms, the {@code operatorId != null} cashier ternary and
 * the {@code recorded == null} arm; {@code resolveEndorsement}'s supervisor,
 * authorized and refused roads; and every {@code parseAmount} case (null,
 * blank, valid, invalid).
 */
class CashMovementResourceTest {

    /**
     * Builds a resource with fresh mocks and a real {@link AuthState} carrying a
     * logged operator (id 7, badge {@code M1}).
     *
     * @return the wired resource
     */
    private CashMovementResource newResource() {
        CashMovementResource resource = new CashMovementResource();
        // The operator messages come from the French bundle, so every
        // assertion below keeps the literal sentence the register shows
        // (LC-13-06).
        resource.i18n = TestMessages.french();
        resource.state = mock(PosState.class);
        AuthState auth = new AuthState();
        auth.operatorId = 7L;
        auth.operatorBadgeId = "M1";
        resource.state.auth = auth;
        resource.cashSessionService = mock(CashSessionService.class);
        resource.cashMovementService = mock(CashMovementService.class);
        resource.posSettingsService = mock(PosSettingsService.class);
        // The administered lists (BO-04-03-12), consulted for the justifications.
        resource.posListService = mock(com.intermarche.pos.service.PosListService.class);
        resource.endorsementService = mock(EndorsementService.class);
        // The tender referential (BO-03-02-20/-26): no tender is administered.
        resource.tenderRulesService = mock(com.intermarche.pos.ui.payment.TenderRulesService.class);
        resource.cashMovement = mock(Template.class);
        resource.ticketPrinterService =
                mock(com.intermarche.pos.ui.hardware.TicketPrinterService.class);
        resource.ticketNumberService =
                mock(com.intermarche.pos.service.TicketNumberService.class);
        return resource;
    }

    /**
     * Stubs the five-link {@code cashMovement} template chain with permissive
     * matchers on the reasons, threshold, saved and error data.
     *
     * @param resource the resource whose template is stubbed
     * @return the chain, the last element being the rendered view
     */
    private TemplateInstance[] stubPageChain(CashMovementResource resource) {
        when(resource.posListService.justifications(
                com.intermarche.pos.service.PosListService.FUNCTION_EXPENSE)).thenReturn(List.of("Coffre"));
        when(resource.posSettingsService.cashMovementTenders()).thenReturn(List.of());
        when(resource.posSettingsService.cashMovementEndorsementThreshold())
                .thenReturn(new BigDecimal("100.00"));
        TemplateInstance ti1 = mock(TemplateInstance.class);
        TemplateInstance ti2 = mock(TemplateInstance.class);
        TemplateInstance ti3 = mock(TemplateInstance.class);
        TemplateInstance ti4 = mock(TemplateInstance.class);
        TemplateInstance ti5 = mock(TemplateInstance.class);
        TemplateInstance tiTenders = mock(TemplateInstance.class);
        when(resource.cashMovement.data("state", resource.state)).thenReturn(ti1);
        when(ti1.data(eq("reasons"), any())).thenReturn(ti2);
        when(ti2.data(eq("tenders"), any())).thenReturn(tiTenders);
        TemplateInstance tiExpenses = mock(TemplateInstance.class);
        when(tiTenders.data(eq("expenses"), any())).thenReturn(tiExpenses);
        when(tiExpenses.data(eq("threshold"), any())).thenReturn(ti3);
        when(ti3.data(eq("saved"), any())).thenReturn(ti4);
        when(ti4.data(eq("error"), any())).thenReturn(ti5);
        return new TemplateInstance[]{ti1, ti2, ti3, ti4, ti5};
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
     * Builds the template chain with the given administered lists, asserting
     * nothing, so a caller can check WHICH values reach the page.
     *
     * @param resource the resource whose template is stubbed
     * @param reasons the administered movement reasons
     * @param tenders the administered movement tenders
     * @return the two links carrying the reasons and the tenders
     */
    private TemplateInstance[] stubChainWith(CashMovementResource resource,
                                             List<String> reasons, List<String> tenders) {
        when(resource.posListService.justifications(
                com.intermarche.pos.service.PosListService.FUNCTION_EXPENSE)).thenReturn(reasons);
        when(resource.posSettingsService.cashMovementTenders()).thenReturn(tenders);
        when(resource.posSettingsService.cashMovementEndorsementThreshold())
                .thenReturn(new BigDecimal("100.00"));
        TemplateInstance ti1 = mock(TemplateInstance.class);
        TemplateInstance tiReasons = mock(TemplateInstance.class);
        TemplateInstance tiTenders = mock(TemplateInstance.class);
        TemplateInstance ti3 = mock(TemplateInstance.class);
        TemplateInstance ti4 = mock(TemplateInstance.class);
        TemplateInstance ti5 = mock(TemplateInstance.class);
        when(resource.cashMovement.data("state", resource.state)).thenReturn(ti1);
        when(ti1.data(eq("reasons"), any())).thenReturn(tiReasons);
        when(tiReasons.data(eq("tenders"), any())).thenReturn(tiTenders);
        TemplateInstance tiExpenses = mock(TemplateInstance.class);
        when(tiTenders.data(eq("expenses"), any())).thenReturn(tiExpenses);
        when(tiExpenses.data(eq("threshold"), any())).thenReturn(ti3);
        when(ti3.data(eq("saved"), any())).thenReturn(ti4);
        when(ti4.data(eq("error"), any())).thenReturn(ti5);
        return new TemplateInstance[]{ti1, tiReasons};
    }

    /**
     * The page hands the template the ADMINISTERED movement reasons, whatever
     * they are (BO-04-03-10): two distinct administered lists reach the page
     * as themselves, which no hard-coded list could satisfy.
     */
    @Test
    void pageHandsTheTemplateTheAdministeredReasons() {
        CashMovementResource first = newResource();
        TemplateInstance[] a = stubChainWith(first, List.of("Coffre", "Erreur"), List.of());
        first.cashMovementPage(null, null);
        verify(a[0]).data("reasons", List.of("Coffre", "Erreur"));
        CashMovementResource second = newResource();
        TemplateInstance[] b = stubChainWith(second, List.of("Banque"), List.of());
        second.cashMovementPage(null, null);
        verify(b[0]).data("reasons", List.of("Banque"));
    }

    /**
     * The page hands the template the ADMINISTERED movement tenders, whatever
     * they are (BO-03-02-20), on two distinct administered lists.
     */
    @Test
    void pageHandsTheTemplateTheAdministeredTenders() {
        CashMovementResource first = newResource();
        TemplateInstance[] a = stubChainWith(first, List.of(), List.of("ESPECES", "CHEQUE"));
        first.cashMovementPage(null, null);
        verify(a[1]).data("tenders", List.of("ESPECES", "CHEQUE"));
        CashMovementResource second = newResource();
        TemplateInstance[] b = stubChainWith(second, List.of(), List.of("CB"));
        second.cashMovementPage(null, null);
        verify(b[1]).data("tenders", List.of("CB"));
    }

    // --- cashMovementPage ---

    /**
     * The page renders the no-session message (first {@code equals} true arm)
     * and the not-saved flag (ok null arm).
     */
    @Test
    void pageNoSessionMessage() {
        CashMovementResource resource = newResource();
        TemplateInstance[] chain = stubPageChain(resource);
        assertSame(chain[4], resource.cashMovementPage("no-session", null));
        verify(chain[2]).data("saved", false);
        verify(chain[3]).data("error", "AUCUNE SESSION OUVERTE");
    }

    /**
     * The page renders the bad-type message (second {@code equals} true arm).
     */
    @Test
    void pageBadTypeMessage() {
        CashMovementResource resource = newResource();
        TemplateInstance[] chain = stubPageChain(resource);
        assertSame(chain[4], resource.cashMovementPage("bad-type", null));
        verify(chain[3]).data("error", "TYPE DE MOUVEMENT INVALIDE");
    }

    /**
     * The page renders the own-screen message: a withdrawal or a coin supply posted
     * here is sent back to its own screen, where it is counted.
     */
    @Test
    void pageOwnScreenMessage() {
        CashMovementResource resource = newResource();
        TemplateInstance[] chain = stubPageChain(resource);
        assertSame(chain[4], resource.cashMovementPage("own-screen", null));
        verify(chain[3]).data("error", "PRÉLÈVEMENT ET APPORT : ÉCRANS DÉDIÉS");
    }

    /**
     * The page renders the missing-reason message of a refused expense.
     */
    @Test
    void pageMissingReasonMessage() {
        CashMovementResource resource = newResource();
        TemplateInstance[] chain = stubPageChain(resource);
        assertSame(chain[4], resource.cashMovementPage("reason", null));
        verify(chain[3]).data("error", "MOTIF OBLIGATOIRE");
    }

    /**
     * The page renders the missing-reference message of a refused down-payment.
     */
    @Test
    void pageMissingReferenceMessage() {
        CashMovementResource resource = newResource();
        TemplateInstance[] chain = stubPageChain(resource);
        assertSame(chain[4], resource.cashMovementPage("reference", null));
        verify(chain[3]).data("error", "RÉFÉRENCE DE COMMANDE OBLIGATOIRE");
    }

    /**
     * The page renders the endorsement message (third {@code equals} true arm).
     */
    @Test
    void pageEndorsementMessage() {
        CashMovementResource resource = newResource();
        TemplateInstance[] chain = stubPageChain(resource);
        assertSame(chain[4], resource.cashMovementPage("endorsement", null));
        verify(chain[3]).data("error", "AVAL MANAGER REFUSÉ OU MANQUANT");
    }

    /**
     * The page renders the training message (fourth {@code equals} true arm).
     */
    @Test
    void pageTrainingMessage() {
        CashMovementResource resource = newResource();
        TemplateInstance[] chain = stubPageChain(resource);
        assertSame(chain[4], resource.cashMovementPage("training", null));
        verify(chain[3]).data("error", "INDISPONIBLE EN FORMATION");
    }

    /**
     * The page renders a null message for an unknown code (all {@code equals}
     * false arm) and the saved flag when {@code ok} is present (ok non-null arm).
     */
    @Test
    void pageUnknownErrorAndSavedFlag() {
        CashMovementResource resource = newResource();
        TemplateInstance[] chain = stubPageChain(resource);
        assertSame(chain[4], resource.cashMovementPage("bogus", "1"));
        verify(chain[2]).data("saved", true);
        verify(chain[3]).data("error", (String) null);
    }

    // --- record ---

    /**
     * A record in training mode is blocked with the training redirect
     * ({@code trainingMode} true arm); nothing is looked up or written.
     */
    @Test
    void recordTrainingModeBlocked() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = true;
        assertRedirect(resource.record("EXPENSE", "10", "Timbres", null, null, null),
                "/cash-movement?error=training");
        verifyNoInteractions(resource.cashSessionService);
        verifyNoInteractions(resource.cashMovementService);
    }

    /**
     * A record with no open session redirects with {@code no-session}
     * ({@code session == null} true arm).
     */
    @Test
    void recordNoSessionRedirects() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.cashSessionService.getOpenSession()).thenReturn(null);
        assertRedirect(resource.record("EXPENSE", "10", "Timbres", null, null, null),
                "/cash-movement?error=no-session");
        verifyNoInteractions(resource.cashMovementService);
    }

    /**
     * A record with a null type name redirects with {@code bad-type}
     * ({@code parseType} value-null arm, so {@code type == null} true arm).
     */
    @Test
    void recordNullTypeRedirectsBadType() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        assertRedirect(resource.record(null, "10", "Timbres", null, null, null),
                "/cash-movement?error=bad-type");
        verifyNoInteractions(resource.cashMovementService);
    }

    /**
     * A record with an unknown type name redirects with {@code bad-type}
     * ({@code parseType} {@code IllegalArgumentException} arm).
     */
    @Test
    void recordUnknownTypeRedirectsBadType() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        assertRedirect(resource.record("BOGUS", "10", "Timbres", null, null, null),
                "/cash-movement?error=bad-type");
        verifyNoInteractions(resource.cashMovementService);
    }

    /**
     * A below-threshold record with a logged operator writes the movement and
     * redirects with {@code ok=1} ({@code requiresEndorsement} false arm,
     * {@code operatorId != null} true arm, {@code recorded == null} false arm);
     * the valid comma amount exercises the {@code parseAmount} success branch and
     * the resolved cashier is carried to the service.
     */
    @Test
    void recordBelowThresholdWritesAndConfirms() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        CashSession session = mock(CashSession.class);
        Employee cashier = mock(Employee.class);
        CashMovement recorded = mock(CashMovement.class);
        when(resource.cashSessionService.getOpenSession()).thenReturn(session);
        when(resource.cashMovementService.requiresEndorsement(any())).thenReturn(false);
        when(resource.cashMovementService.record(eq(session), eq(cashier),
                eq(CashMovement.MovementType.EXPENSE), eq(new BigDecimal("30.00")),
                eq("Timbres"), eq(null), eq(null), eq(null), eq(null))).thenReturn(recorded);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Employee.findById(7L)).thenReturn(cashier);
            assertRedirect(resource.record("EXPENSE", "30,00", "Timbres", null, null, null),
                    "/cash-movement?ok=1");
        }
        verify(resource.state).touch();
    }

    /**
     * A movement carrying a tender (BO-03-02-20) passes it through to the
     * service as the {@code paymentMethod}, so a cheque withdrawal does not move
     * the cash theoretical; a blank tender would land as null (cash) instead.
     */
    @Test
    void recordPassesTenderToService() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        CashSession session = mock(CashSession.class);
        Employee cashier = mock(Employee.class);
        CashMovement recorded = mock(CashMovement.class);
        when(resource.cashSessionService.getOpenSession()).thenReturn(session);
        when(resource.cashMovementService.requiresEndorsement(any())).thenReturn(false);
        when(resource.cashMovementService.record(eq(session), eq(cashier),
                eq(CashMovement.MovementType.EXPENSE), any(),
                eq("Timbres"), eq(null), eq("CHEQUE"), eq(null), eq(null))).thenReturn(recorded);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Employee.findById(7L)).thenReturn(cashier);
            assertRedirect(resource.record("EXPENSE", "10", "Timbres", null, "CHEQUE", "true"),
                    "/cash-movement?ok=1");
        }
        verify(resource.cashMovementService).record(eq(session), eq(cashier),
                eq(CashMovement.MovementType.EXPENSE), any(), eq("Timbres"), eq(null),
                eq("CHEQUE"), eq(null), eq(null));
    }

    /**
     * A blank tender lands as a null payment method (BO-03-02-20, the
     * non-null-but-blank leg of the guard) — the movement then concerns the cash.
     */
    @Test
    void recordBlankTenderBecomesNullPaymentMethod() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        CashSession session = mock(CashSession.class);
        Employee cashier = mock(Employee.class);
        CashMovement recorded = mock(CashMovement.class);
        when(resource.cashSessionService.getOpenSession()).thenReturn(session);
        when(resource.cashMovementService.requiresEndorsement(any())).thenReturn(false);
        when(resource.cashMovementService.record(eq(session), eq(cashier),
                eq(CashMovement.MovementType.EXPENSE), any(),
                eq("Timbres"), eq(null), eq(null), eq(null), eq(null))).thenReturn(recorded);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Employee.findById(7L)).thenReturn(cashier);
            assertRedirect(resource.record("EXPENSE", "10", "Timbres", null, "   ", null),
                    "/cash-movement?ok=1");
        }
        verify(resource.cashMovementService).record(eq(session), eq(cashier),
                eq(CashMovement.MovementType.EXPENSE), any(), eq("Timbres"), eq(null),
                eq(null), eq(null), eq(null));
    }

    /**
     * An above-threshold record self-endorsed by a connected supervisor writes
     * with the operator's own badge ({@code requiresEndorsement} true arm,
     * {@code resolveEndorsement} supervisor arm, {@code endorsedBy != null});
     * the null amount exercises the {@code parseAmount} null branch.
     */
    @Test
    void recordAboveThresholdSelfEndorsedWrites() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        CashSession session = mock(CashSession.class);
        Employee cashier = mock(Employee.class);
        CashMovement recorded = mock(CashMovement.class);
        when(resource.cashSessionService.getOpenSession()).thenReturn(session);
        when(resource.cashMovementService.requiresEndorsement(any())).thenReturn(true);
        when(resource.endorsementService.operatorIsSupervisor(resource.state)).thenReturn(true);
        when(resource.cashMovementService.record(eq(session), eq(cashier),
                eq(CashMovement.MovementType.EXPENSE), eq(BigDecimal.ZERO),
                eq("Timbres"), eq("M1"), eq(null), eq(null), eq(null))).thenReturn(recorded);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Employee.findById(7L)).thenReturn(cashier);
            assertRedirect(resource.record("EXPENSE", null, "Timbres", null, null, null),
                    "/cash-movement?ok=1");
        }
        verify(resource.endorsementService, never()).authorize(any(), any(), any());
    }

    /**
     * A movement the shared modal has authorized is written with the endorsing
     * badge ({@code performEndorsed} replaying the parked form); the blank
     * amount exercises the {@code parseAmount} blank branch.
     */
    @Test
    void recordAboveThresholdManagerAuthorizedWrites() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        CashSession session = mock(CashSession.class);
        Employee cashier = mock(Employee.class);
        CashMovement recorded = mock(CashMovement.class);
        when(resource.cashSessionService.getOpenSession()).thenReturn(session);
        when(resource.cashMovementService.requiresEndorsement(any())).thenReturn(true);
        when(resource.endorsementService.operatorIsSupervisor(resource.state)).thenReturn(false);
        when(resource.cashMovementService.record(eq(session), eq(cashier),
                eq(CashMovement.MovementType.EXPENSE), eq(BigDecimal.ZERO),
                eq("Pharmacie"), eq("22222222"), eq(null), eq(null), eq(null))).thenReturn(recorded);
        java.util.Map<String, String> parked = new java.util.LinkedHashMap<>();
        parked.put("type", "EXPENSE");
        parked.put("amount", "   ");
        parked.put("reason", "Pharmacie");
        parked.put("paymentMethod", null);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Employee.findById(7L)).thenReturn(cashier);
            assertRedirect(resource.performEndorsed(parked, "22222222"),
                    "/cash-movement?ok=1");
        }
    }

    /**
     * An above-threshold record with no supervisor logged PARKS the form and
     * opens the shared modal instead of writing: the screen redirects to itself,
     * the endorsement request carries the four typed fields and the return path,
     * and nothing reaches the movement service.
     */
    @Test
    void recordAboveThresholdParksTheFormAndAsksTheModal() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        when(resource.cashMovementService.requiresEndorsement(any())).thenReturn(true);
        when(resource.endorsementService.operatorIsSupervisor(resource.state)).thenReturn(false);
        assertRedirect(resource.record("EXPENSE", "150", "Timbres", null, null, null),
                "/cash-movement");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, String>> form = ArgumentCaptor.forClass(java.util.Map.class);
        verify(resource.endorsementService).requestAuthorization(eq(resource.state),
                eq("CASH_MOVEMENT"), form.capture(), eq("/cash-movement"));
        assertEquals("EXPENSE", form.getValue().get("type"));
        assertEquals("150", form.getValue().get("amount"));
        assertEquals("Timbres", form.getValue().get("reason"));
        assertEquals("", form.getValue().get("reference"));
        assertEquals("", form.getValue().get("paymentMethod"));
        assertEquals("", form.getValue().get("tenderPicked"));
        verify(resource.cashMovementService, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * A movement replayed WITHOUT an endorsing badge — the modal was dismissed
     * and the form reached {@code perform} anyway — is refused rather than
     * written ({@code endorsedBy == null} arm of the guard).
     */
    @Test
    void replayWithoutBadgeIsRefused() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        when(resource.cashMovementService.requiresEndorsement(any())).thenReturn(true);
        java.util.Map<String, String> parked = new java.util.LinkedHashMap<>();
        parked.put("type", "EXPENSE");
        parked.put("amount", "150");
        parked.put("reason", "Timbres");
        parked.put("paymentMethod", null);
        assertRedirect(resource.performEndorsed(parked, null),
                "/cash-movement?error=endorsement");
        verify(resource.cashMovementService, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * A record the service refuses (returns null) redirects with
     * {@code endorsement} ({@code recorded == null} true arm); a null operator id
     * exercises the {@code operatorId != null} false arm (no cashier resolved)
     * and the unparsable amount exercises the {@code parseAmount} catch branch.
     */
    @Test
    void recordServiceRefusalRedirects() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        resource.state.auth.operatorId = null;
        CashSession session = mock(CashSession.class);
        when(resource.cashSessionService.getOpenSession()).thenReturn(session);
        when(resource.cashMovementService.requiresEndorsement(any())).thenReturn(false);
        when(resource.cashMovementService.record(eq(session), eq(null),
                eq(CashMovement.MovementType.DECLARATION), eq(BigDecimal.ZERO),
                eq("Comptage"), eq(null), eq(null), eq(null), eq(null))).thenReturn(null);
        assertRedirect(resource.record("DECLARATION", "abc", "Comptage", null, null, null),
                "/cash-movement?error=endorsement");
        verify(resource.state).touch();
    }

    /**
     * An above-threshold record parked with a NULL amount, a NULL reason and a
     * PRESENT tender exercises the opposite arm of the parking ternaries:
     * {@code amountStr == null} true, {@code reason == null} true,
     * {@code reference == null} true and {@code paymentMethod == null} false — the
     * null fields land as empty strings and the present tender lands as itself in
     * the parked form. The type is a DECLARATION, the one movement that asks for
     * neither a reason nor a reference.
     */
    @Test
    void recordParksNullAmountNullReasonAndPresentTender() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        when(resource.cashMovementService.requiresEndorsement(any())).thenReturn(true);
        when(resource.endorsementService.operatorIsSupervisor(resource.state)).thenReturn(false);
        assertRedirect(resource.record("DECLARATION", null, null, null, "CB", "true"),
                "/cash-movement");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, String>> form = ArgumentCaptor.forClass(java.util.Map.class);
        verify(resource.endorsementService).requestAuthorization(eq(resource.state),
                eq("CASH_MOVEMENT"), form.capture(), eq("/cash-movement"));
        assertEquals("DECLARATION", form.getValue().get("type"));
        assertEquals("", form.getValue().get("amount"));
        assertEquals("", form.getValue().get("reason"));
        assertEquals("", form.getValue().get("reference"));
        assertEquals("CB", form.getValue().get("paymentMethod"));
        assertEquals("true", form.getValue().get("tenderPicked"));
        verify(resource.cashMovementService, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * A WITHDRAWAL posted to this screen is refused: the prélèvement has its own
     * screen, where it is counted denomination by denomination and prints its
     * bordereau. The flat form here would record it without that count.
     */
    @Test
    void recordRefusesAWithdrawalWhichHasItsOwnScreen() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        assertRedirect(resource.record("WITHDRAWAL", "30", "Coffre", null, null, null),
                "/cash-movement?error=own-screen");
        verify(resource.cashMovementService, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * A DEPOSIT posted to this screen is refused for the same reason — the second
     * leg of the own-screen disjunction: the apport de monnaie is counted on its
     * own page.
     */
    @Test
    void recordRefusesADepositWhichHasItsOwnScreen() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        assertRedirect(resource.record("DEPOSIT", "30", "Apport", null, null, null),
                "/cash-movement?error=own-screen");
        verify(resource.cashMovementService, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * An EXPENSE with NO reason is refused (null leg of the reason guard): cash
     * that left the drawer must say what it paid for.
     */
    @Test
    void recordRefusesAnExpenseWithoutAReason() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        assertRedirect(resource.record("EXPENSE", "30", null, null, null, null),
                "/cash-movement?error=reason");
        verify(resource.cashMovementService, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * An EXPENSE whose reason is blank is refused too (isBlank leg of the reason
     * guard) — the empty entry of the administered list is not a reason.
     */
    @Test
    void recordRefusesAnExpenseWithABlankReason() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        assertRedirect(resource.record("EXPENSE", "30", "   ", null, null, null),
                "/cash-movement?error=reason");
        verify(resource.cashMovementService, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * A CUSTOMER_DEPOSIT with NO order reference is refused (null leg of the
     * reference guard): an advance the store cashes must name what it advances on.
     */
    @Test
    void recordRefusesADownPaymentWithoutAnOrderReference() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        assertRedirect(resource.record("CUSTOMER_DEPOSIT", "30", null, null, null, null),
                "/cash-movement?error=reference");
        verify(resource.cashMovementService, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * A CUSTOMER_DEPOSIT whose reference is blank is refused too (isBlank leg of
     * the reference guard).
     */
    @Test
    void recordRefusesADownPaymentWithABlankOrderReference() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        assertRedirect(resource.record("CUSTOMER_DEPOSIT", "30", null, "  ", null, null),
                "/cash-movement?error=reference");
        verify(resource.cashMovementService, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * A CUSTOMER_DEPOSIT is written through {@code recordAcompte} (EXP-85), which
     * carries the order reference in its own column: that column is what a later
     * recall of the order reads to deduct what was already collected. The generic
     * writer is not used, which would have left the reference as prose.
     */
    @Test
    void recordADownPaymentThroughTheAcompteWriter() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        CashSession session = mock(CashSession.class);
        Employee cashier = mock(Employee.class);
        CashMovement recorded = mock(CashMovement.class);
        when(resource.cashSessionService.getOpenSession()).thenReturn(session);
        when(resource.cashMovementService.requiresEndorsement(any())).thenReturn(false);
        when(resource.cashMovementService.recordAcompte(session, cashier,
                new BigDecimal("30"), " CMD42 ")).thenReturn(recorded);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Employee.findById(7L)).thenReturn(cashier);
            assertRedirect(resource.record("CUSTOMER_DEPOSIT", "30", null, " CMD42 ", null, null),
                    "/cash-movement?ok=1");
        }
        verify(resource.cashMovementService).recordAcompte(session, cashier,
                new BigDecimal("30"), " CMD42 ");
        verify(resource.cashMovementService, never()).record(any(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    /**
     * A down-payment the acompte writer refuses (it returns null on an amount or a
     * reference it will not write) redirects rather than confirming, and prints
     * nothing.
     */
    @Test
    void recordADownPaymentTheWriterRefuses() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        CashSession session = mock(CashSession.class);
        when(resource.cashSessionService.getOpenSession()).thenReturn(session);
        when(resource.cashMovementService.requiresEndorsement(any())).thenReturn(false);
        when(resource.cashMovementService.recordAcompte(any(), any(), any(), any()))
                .thenReturn(null);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Employee.findById(7L)).thenReturn(mock(Employee.class));
            assertRedirect(resource.record("CUSTOMER_DEPOSIT", "0", null, "CMD42", null, null),
                    "/cash-movement?error=endorsement");
        }
        verifyNoInteractions(resource.ticketPrinterService);
    }

    /**
     * Every movement recorded here PRINTS its justificatif, carrying the type's
     * wording, the amount and the reason: these three movements used to leave the
     * drawer with no paper to file beside the receipts.
     */
    @Test
    void recordPrintsTheJustificatif() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        CashSession session = mock(CashSession.class);
        Employee cashier = mock(Employee.class);
        CashMovement recorded = mock(CashMovement.class);
        // The slip states the reason the movement WAS RECORDED with, read back off
        // the written movement rather than off the form.
        recorded.reason = "Timbres";
        when(resource.cashSessionService.getOpenSession()).thenReturn(session);
        when(resource.cashMovementService.requiresEndorsement(any())).thenReturn(false);
        when(resource.cashMovementService.record(any(), any(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(recorded);
        when(resource.ticketNumberService.getTerminalId()).thenReturn("C01");
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Employee.findById(7L)).thenReturn(cashier);
            assertRedirect(resource.record("EXPENSE", "12,50", "Timbres", null, null, null),
                    "/cash-movement?ok=1");
        }
        verify(resource.ticketPrinterService).printMovementTicket("Dépense",
                new BigDecimal("12.50"), "Timbres", null, null, "C01");
    }

    /**
     * A DECLARATION asks for neither a reason nor a reference (both guards' type
     * arms false) and is recorded as it stands, with its own wording on the
     * justificatif.
     */
    @Test
    void recordADeclarationNeedsNeitherReasonNorReference() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        CashSession session = mock(CashSession.class);
        Employee cashier = mock(Employee.class);
        CashMovement recorded = mock(CashMovement.class);
        when(resource.cashSessionService.getOpenSession()).thenReturn(session);
        when(resource.cashMovementService.requiresEndorsement(any())).thenReturn(false);
        when(resource.cashMovementService.record(any(), any(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(recorded);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Employee.findById(7L)).thenReturn(cashier);
            assertRedirect(resource.record("DECLARATION", "200", null, null, null, null),
                    "/cash-movement?ok=1");
        }
        verify(resource.cashMovementService).record(eq(session), eq(cashier),
                eq(CashMovement.MovementType.DECLARATION), eq(new BigDecimal("200")),
                eq(null), eq(null), eq(null), eq(null), eq(null));
        verify(resource.ticketPrinterService).printMovementTicket(eq("Déclaration (comptage)"),
                any(), eq(null), eq(null), any(), any());
    }

    /**
     * A parked movement replayed while the register slipped into training mode
     * is blocked with the training redirect ({@code perform}'s
     * {@code trainingMode} true arm, reachable only through
     * {@code performEndorsed}); nothing is looked up or written.
     */
    @Test
    void performEndorsedTrainingModeBlocked() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = true;
        java.util.Map<String, String> parked = new java.util.LinkedHashMap<>();
        parked.put("type", "WITHDRAWAL");
        parked.put("amount", "150");
        parked.put("reason", "Coffre");
        parked.put("paymentMethod", null);
        assertRedirect(resource.performEndorsed(parked, "22222222"),
                "/cash-movement?error=training");
        verifyNoInteractions(resource.cashSessionService);
        verifyNoInteractions(resource.cashMovementService);
    }

    /**
     * A parked movement replayed after the session closed is refused with the
     * no-session redirect ({@code perform}'s {@code session == null} true arm);
     * nothing reaches the movement service.
     */
    @Test
    void performEndorsedNoSessionRedirects() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.cashSessionService.getOpenSession()).thenReturn(null);
        java.util.Map<String, String> parked = new java.util.LinkedHashMap<>();
        parked.put("type", "WITHDRAWAL");
        parked.put("amount", "150");
        parked.put("reason", "Coffre");
        parked.put("paymentMethod", null);
        assertRedirect(resource.performEndorsed(parked, "22222222"),
                "/cash-movement?error=no-session");
        verifyNoInteractions(resource.cashMovementService);
    }

    /**
     * A parked movement whose type name no longer parses is refused with the
     * bad-type redirect ({@code perform}'s {@code type == null} true arm, via a
     * null parked type); nothing reaches the movement service.
     */
    @Test
    void performEndorsedBadTypeRedirects() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        java.util.Map<String, String> parked = new java.util.LinkedHashMap<>();
        parked.put("type", null);
        parked.put("amount", "150");
        parked.put("reason", "Coffre");
        parked.put("paymentMethod", null);
        assertRedirect(resource.performEndorsed(parked, "22222222"),
                "/cash-movement?error=bad-type");
        verifyNoInteractions(resource.cashMovementService);
    }
    /** LC-12: each movement kind carries the exact label used by its printed document. */
    @org.junit.jupiter.api.Test
    void coverageEveryMovementTypeLabel() throws Exception {
        var resource = new CashMovementResource();
        resource.i18n = TestMessages.french();
        var method = CashMovementResource.class.getDeclaredMethod("typeLabelOf", com.intermarche.pos.domain.session.CashMovement.MovementType.class);
        method.setAccessible(true);
        var expected = java.util.Map.of("EXPENSE", "Dépense", "CUSTOMER_DEPOSIT", "Acompte client", "DECLARATION", "Déclaration (comptage)",
                "WITHDRAWAL", "Prélèvement", "DEPOSIT", "Apport", "TRANSFER", "Transfert");
        for (var type : com.intermarche.pos.domain.session.CashMovement.MovementType.values())
            org.junit.jupiter.api.Assertions.assertEquals(expected.get(type.name()), method.invoke(resource, type));
    }

    /** LC-12: a missing optional reference remains empty in an endorsement, and endorsed expenses still require their reason. */
    @Test
    void endorsementPayloadAndRevalidationKeepTheirGuards() {
        var resource = newResource();
        when(resource.cashSessionService.getOpenSession()).thenReturn(new CashSession());
        when(resource.cashMovementService.requiresEndorsement(any())).thenReturn(true);
        resource.record("DECLARATION", "150", null, null, "CASH", "true");
        var form = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(resource.endorsementService).requestAuthorization(org.mockito.ArgumentMatchers.eq(resource.state),
                org.mockito.ArgumentMatchers.anyString(), form.capture(), org.mockito.ArgumentMatchers.eq("/cash-movement"));
        assertEquals("", form.getValue().get("reference"));
        var posted = new java.util.HashMap<String, String>();
        posted.put("type", "EXPENSE");
        posted.put("amount", "10");
        assertEquals("/cash-movement?error=reason", resource.performEndorsed(posted, "MANAGER").getLocation().toString());
        org.mockito.Mockito.verifyNoInteractions(resource.ticketPrinterService);
    }


    /** LC-12: an optional supplied reference is retained when a declaration is parked for endorsement. */
    @Test
    void suppliedReferenceIsRetainedInTheEndorsementPayload() {
        var resource = newResource();
        when(resource.cashSessionService.getOpenSession()).thenReturn(new CashSession());
        when(resource.cashMovementService.requiresEndorsement(any())).thenReturn(true);
        resource.record("DECLARATION", "150", null, "REFERENCE", "CASH", "true");
        var form = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(resource.endorsementService).requestAuthorization(org.mockito.ArgumentMatchers.eq(resource.state),
                org.mockito.ArgumentMatchers.anyString(), form.capture(), org.mockito.ArgumentMatchers.eq("/cash-movement"));
        assertEquals("REFERENCE", form.getValue().get("reference"));
    }


    /**
     * Builds an administered expense.
     *
     * @param tender the tender it is paid in, or null for cash
     * @param max its maximum, or null
     * @param level its control above the maximum
     * @return the expense
     */
    private com.intermarche.pos.service.PosListService.Expense expense(String tender, String max,
            com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel level) {
        return new com.intermarche.pos.service.PosListService.Expense("TIMBRES", "Timbres", tender,
                max == null ? null : new BigDecimal(max), level);
    }

    /**
     * Wires an open session, a logged cashier and a movement the service records.
     *
     * @param resource the resource under test
     * @return the open session
     */
    private CashSession openSessionFor(CashMovementResource resource) {
        resource.state.trainingMode = false;
        CashSession session = mock(CashSession.class);
        when(resource.cashSessionService.getOpenSession()).thenReturn(session);
        when(resource.cashMovementService.record(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mock(CashMovement.class));
        when(resource.cashMovementService.recordAcompte(any(), any(), any(), any()))
                .thenReturn(mock(CashMovement.class));
        return session;
    }

    /**
     * BO-03-02-20/-47: the page offers the parameter's tenders then the
     * referential's, each once, and names the two expense controls.
     */
    @Test
    void pageOffersTheReferentialTendersAndNamesTheExpenseControls() {
        CashMovementResource resource = newResource();
        when(resource.tenderRulesService.movementTenders()).thenReturn(List.of("CHEQUE", "TR"));
        TemplateInstance[] chain = stubChainWith(resource, List.of(), List.of("CHEQUE"));
        resource.cashMovementPage(null, null);
        verify(chain[1]).data("tenders", List.of("CHEQUE", "TR"));
        CashMovementResource blocked = newResource();
        TemplateInstance[] links = stubPageChain(blocked);
        blocked.cashMovementPage("expense-max", null);
        verify(links[3]).data("error", "MONTANT SUPERIEUR AU MAXIMUM DE LA DEPENSE");
        CashMovementResource warned = newResource();
        TemplateInstance[] more = stubPageChain(warned);
        warned.cashMovementPage("expense-warn", "1");
        verify(more[3]).data("error", "MOUVEMENT ENREGISTRE AU-DELA DU MAXIMUM DE LA DEPENSE");
    }

    /**
     * BO-03-02-46: an expense the back office no longer offers is refused when
     * expenses are administered; with none administered, any reason passes.
     */
    @Test
    void anExpenseNoLongerOfferedIsRefused() {
        CashMovementResource resource = newResource();
        openSessionFor(resource);
        when(resource.posListService.expenses()).thenReturn(List.of(expense(null, null,
                com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.NONE)));
        assertRedirect(resource.record("EXPENSE", "10", "Essence", null, null, null),
                "/cash-movement?error=reason");
        verify(resource.cashMovementService, never()).record(any(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    /**
     * BO-03-02-47: above a BLOCKING maximum the expense is refused, whether
     * posted directly or replayed after an endorsement.
     */
    @Test
    void anExpenseOverABlockingMaximumIsRefused() {
        CashMovementResource resource = newResource();
        openSessionFor(resource);
        when(resource.posListService.expense("Timbres")).thenReturn(expense(null, "50",
                com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.BLOCKING));
        assertRedirect(resource.record("EXPENSE", "60", "Timbres", null, null, null),
                "/cash-movement?error=expense-max");
        java.util.Map<String, String> parked = new java.util.HashMap<>();
        parked.put("type", "EXPENSE");
        parked.put("amount", "60");
        parked.put("reason", "Timbres");
        assertRedirect(resource.performEndorsed(parked, "M2"), "/cash-movement?error=expense-max");
        verify(resource.cashMovementService, never()).record(any(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    /**
     * BO-03-02-47: above a SUPERVISOR maximum the expense is parked for a
     * manager; replayed without a badge it is refused, with one it is recorded.
     */
    @Test
    void anExpenseOverASupervisorMaximumAsksAManager() {
        CashMovementResource resource = newResource();
        openSessionFor(resource);
        when(resource.posListService.expense("Timbres")).thenReturn(expense(null, "50",
                com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.SUPERVISOR));
        assertRedirect(resource.record("EXPENSE", "60", "Timbres", null, null, null), "/cash-movement");
        verify(resource.endorsementService).requestAuthorization(eq(resource.state),
                eq(CashMovementResource.ENDORSEMENT_ACTION), any(), eq("/cash-movement"));
        java.util.Map<String, String> parked = new java.util.HashMap<>();
        parked.put("type", "EXPENSE");
        parked.put("amount", "60");
        parked.put("reason", "Timbres");
        assertRedirect(resource.performEndorsed(parked, null), "/cash-movement?error=endorsement");
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            assertRedirect(resource.performEndorsed(parked, "M2"), "/cash-movement?ok=1&error=expense-warn");
        }
    }

    /**
     * BO-03-02-45/-47: an expense administered in another tender is recorded
     * in it when none was picked, keeps the picked one otherwise, and an
     * informative maximum records the expense and tells the cashier.
     */
    @Test
    void anExpenseIsRecordedInItsTenderAndUnderItsInformativeMaximum() {
        CashMovementResource resource = newResource();
        CashSession session = openSessionFor(resource);
        when(resource.posListService.expense("Timbres")).thenReturn(expense("CHEQUE", "50",
                com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.INFO));
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            assertRedirect(resource.record("EXPENSE", "60", "Timbres", null, "", null),
                    "/cash-movement?ok=1&error=expense-warn");
            assertRedirect(resource.record("EXPENSE", "20", "Timbres", null, "TR", "true"),
                    "/cash-movement?ok=1");
        }
        verify(resource.cashMovementService).record(eq(session), any(), eq(CashMovement.MovementType.EXPENSE),
                eq(new BigDecimal("60")), eq("Timbres"), any(), eq("CHEQUE"), any(), any());
        verify(resource.cashMovementService).record(eq(session), any(), eq(CashMovement.MovementType.EXPENSE),
                eq(new BigDecimal("20")), eq("Timbres"), any(), eq("TR"), any(), any());
    }

    /**
     * BO-03-02-26: a down-payment prints its duplicate where the down-payment
     * tender asks for one, and only there.
     */
    @Test
    void aDownPaymentPrintsItsDuplicateWhereTheTenderAsks() {
        CashMovementResource resource = newResource();
        openSessionFor(resource);
        com.intermarche.pos.domain.payment.TenderDefinition deposit =
                new com.intermarche.pos.domain.payment.TenderDefinition();
        deposit.code = "ACOMPTE";
        when(resource.tenderRulesService.tender("ACOMPTE")).thenReturn(null, deposit, deposit);
        CashMovement third = new CashMovement();
        third.reason = "Acompte commande CMD3";
        when(resource.cashMovementService.recordAcompte(any(), any(), any(), eq("CMD3"))).thenReturn(third);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            resource.record("CUSTOMER_DEPOSIT", "30", null, "CMD1", null, null);
            resource.record("CUSTOMER_DEPOSIT", "30", null, "CMD2", null, null);
            deposit.printDuplicate = true;
            resource.record("CUSTOMER_DEPOSIT", "30", null, "CMD3", null, null);
        }
        verify(resource.ticketPrinterService, org.mockito.Mockito.times(1)).printMovementTicket(
                eq("Acompte client - DUPLICATA"), eq(new BigDecimal("30")), eq("Acompte commande CMD3"),
                any(), any(), any());
        verify(resource.ticketPrinterService, org.mockito.Mockito.times(3)).printMovementTicket(
                eq("Acompte client"), any(), any(), any(), any(), any());
    }

    /**
     * BO-03-02-45: an expense administered in NO tender is booked in cash when the
     * cashier did not pick one — even when the screen still posts the cheque of the
     * expense chosen before it — and an expense administered with a blank tender
     * likewise; a tender the cashier picked by hand is kept.
     */
    @Test
    void aCashExpenseIsNotBookedInATenderLeftBehind() {
        CashMovementResource resource = newResource();
        CashSession session = openSessionFor(resource);
        when(resource.posListService.expense("Timbres")).thenReturn(expense(null, null,
                com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.NONE));
        when(resource.posListService.expense("Fournitures")).thenReturn(
                new com.intermarche.pos.service.PosListService.Expense("FOURN", "Fournitures", "  ", null,
                        com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.NONE));
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            assertRedirect(resource.record("EXPENSE", "11", "Timbres", null, "CHEQUE", null), "/cash-movement?ok=1");
            assertRedirect(resource.record("EXPENSE", "12", "Fournitures", null, "CHEQUE", ""), "/cash-movement?ok=1");
            assertRedirect(resource.record("EXPENSE", "13", "Timbres", null, "CHEQUE", "true"), "/cash-movement?ok=1");
        }
        verify(resource.cashMovementService).record(eq(session), any(), eq(CashMovement.MovementType.EXPENSE),
                eq(new BigDecimal("11")), eq("Timbres"), any(), org.mockito.ArgumentMatchers.isNull(), any(), any());
        verify(resource.cashMovementService).record(eq(session), any(), eq(CashMovement.MovementType.EXPENSE),
                eq(new BigDecimal("12")), eq("Fournitures"), any(), org.mockito.ArgumentMatchers.isNull(), any(), any());
        verify(resource.cashMovementService).record(eq(session), any(), eq(CashMovement.MovementType.EXPENSE),
                eq(new BigDecimal("13")), eq("Timbres"), any(), eq("CHEQUE"), any(), any());
    }

    /**
     * The justificatif names the movement in the register's language: « Uitgave »
     * at a Dutch till.
     *
     * @throws Exception if the private method cannot be reached
     */
    @Test
    void theMovementTypeIsNamedInTheRegisterLanguage() throws Exception {
        var resource = new CashMovementResource();
        PosState dutch = new PosState();
        dutch.language = "nl";
        resource.i18n = TestMessages.french(dutch);
        var method = CashMovementResource.class.getDeclaredMethod("typeLabelOf", CashMovement.MovementType.class);
        method.setAccessible(true);
        assertEquals(resource.i18n.get("cashmovement.depense"), method.invoke(resource, CashMovement.MovementType.EXPENSE));
        org.junit.jupiter.api.Assertions.assertNotEquals("Dépense", method.invoke(resource, CashMovement.MovementType.EXPENSE));
    }
}
