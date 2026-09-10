package com.intermarche.pos.ui;

import com.intermarche.pos.domain.CashSession;
import com.intermarche.pos.service.CashSessionService;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SessionCheckFilter}.
 * <p>
 * The filter is a plain JAX-RS request filter: it reads the path off a mocked
 * {@link ContainerRequestContext}, consults a real {@link PosState} for the
 * lock and training flags and a mocked {@link CashSessionService} for the open
 * session, and either lets the request through or aborts it toward
 * {@code /session}. No database and no Quarkus context is booted.
 * <p>
 * Branch enumeration (every arm exercised — 100%): the early-return compound
 * guard has one case per leg (locked true, training true, both false), the
 * allowlist has a matching and a non-matching path, the open-session lookup has
 * its non-null and null arms, and the path normalization is exercised with and
 * without the leading slash.
 */
class SessionCheckFilterTest {

    /**
     * Builds a filter over the given state, with the session service answering
     * the given open session.
     *
     * @param state the register state
     * @param open the session the service reports, or null for none
     * @return the wired filter
     */
    private SessionCheckFilter newFilter(PosState state, CashSession open) {
        SessionCheckFilter filter = new SessionCheckFilter();
        filter.state = state;
        filter.cashSessionService = mock(CashSessionService.class);
        when(filter.cashSessionService.getOpenSession()).thenReturn(open);
        return filter;
    }

    /**
     * Builds a request context whose path is fixed.
     *
     * @param path the value {@code getUriInfo().getPath()} returns
     * @return the mocked context
     */
    private ContainerRequestContext ctxFor(String path) {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn(path);
        return ctx;
    }

    /**
     * Builds an unlocked, non-training register state.
     *
     * @return the state
     */
    private PosState selling() {
        PosState state = new PosState();
        state.auth.isLocked = false;
        state.trainingMode = false;
        return state;
    }

    /**
     * Asserts the request was diverted to the session screen.
     *
     * @param ctx the context the filter ran on
     */
    private void assertDivertedToSession(ContainerRequestContext ctx) {
        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        verify(ctx).abortWith(captor.capture());
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), captor.getValue().getStatus());
        assertEquals("/session?error=no-session", captor.getValue().getLocation().toString());
    }

    /**
     * A logged-in cashier with no open session cannot reach the sale screen:
     * the request is diverted (both early-return legs false, non-allowlisted
     * arm, session-null arm).
     *
     * @throws Exception never in practice
     */
    @Test
    void noSessionDivertsTheSaleScreen() throws Exception {
        ContainerRequestContext ctx = ctxFor("/");
        newFilter(selling(), null).filter(ctx);
        assertDivertedToSession(ctx);
    }

    /**
     * The path is normalized: a container that hands the path without its
     * leading slash is matched the same way (normalization arm).
     *
     * @throws Exception never in practice
     */
    @Test
    void noSessionDivertsAPathWithoutItsLeadingSlash() throws Exception {
        ContainerRequestContext ctx = ctxFor("pay");
        newFilter(selling(), null).filter(ctx);
        assertDivertedToSession(ctx);
    }

    /**
     * An open session lets the request through (session non-null arm).
     *
     * @throws Exception never in practice
     */
    @Test
    void anOpenSessionLetsTheRequestThrough() throws Exception {
        ContainerRequestContext ctx = ctxFor("/");
        newFilter(selling(), new CashSession()).filter(ctx);
        verify(ctx, never()).abortWith(org.mockito.ArgumentMatchers.any());
    }

    /**
     * A locked register is not held here — the lock guard owns that request
     * (first leg of the early return true).
     *
     * @throws Exception never in practice
     */
    @Test
    void aLockedRegisterIsLeftToTheLockGuard() throws Exception {
        PosState state = selling();
        state.auth.isLocked = true;
        ContainerRequestContext ctx = ctxFor("/");
        newFilter(state, null).filter(ctx);
        verify(ctx, never()).abortWith(org.mockito.ArgumentMatchers.any());
    }

    /**
     * Training mode sells without a session by design (second leg of the early
     * return true).
     *
     * @throws Exception never in practice
     */
    @Test
    void trainingModeSellsWithoutASession() throws Exception {
        PosState state = selling();
        state.trainingMode = true;
        ContainerRequestContext ctx = ctxFor("/");
        newFilter(state, null).filter(ctx);
        verify(ctx, never()).abortWith(org.mockito.ArgumentMatchers.any());
    }

    /**
     * THE WHOLE SESSION FLOW answers with no session, the OPENING ACTION above
     * all: {@code /action/session/open} does not start with {@code /session},
     * so allowlisting the screen alone diverted the OUVRIR button straight back
     * to the screen it was posted from and the session could never be opened.
     * This case is the one that must never pass again.
     *
     * @throws Exception never in practice
     */
    @Test
    void theWholeSessionFlowAnswersWithoutASession() throws Exception {
        for (String path : new String[] {"/session", "/action/session/open",
                "/session/x-report", "/action/session/x-report",
                "/action/session/close-start", "/action/session/close", "/cash-count"}) {
            ContainerRequestContext ctx = ctxFor(path);
            newFilter(selling(), null).filter(ctx);
            verify(ctx, never()).abortWith(org.mockito.ArgumentMatchers.any());
        }
    }

    /**
     * The scale PUSHES its weight to the register: a machine surface, which a
     * redirect would break as surely as it would break the scan bus.
     *
     * @throws Exception never in practice
     */
    @Test
    void theScalePushAnswersWithoutASession() throws Exception {
        ContainerRequestContext ctx = ctxFor("/weight");
        newFilter(selling(), null).filter(ctx);
        verify(ctx, never()).abortWith(org.mockito.ArgumentMatchers.any());
    }

    /**
     * What an operator does at their post rather than at the till answers with
     * no session: none of it sells anything, and the supervisor call is wanted
     * precisely when someone is stuck.
     *
     * @throws Exception never in practice
     */
    @Test
    void thePostFunctionsAnswerWithoutASession() throws Exception {
        for (String path : new String[] {"/pin-change", "/action/pin-change",
                "/badge-print", "/action/badge-print", "/theme-select", "/action/theme",
                "/supervisor", "/action/supervisor/caisse"}) {
            ContainerRequestContext ctx = ctxFor(path);
            newFilter(selling(), null).filter(ctx);
            verify(ctx, never()).abortWith(org.mockito.ArgumentMatchers.any());
        }
    }

    /**
     * And the selling gestures ARE held — the point of the guard. One per
     * family, so a future allowlist entry cannot quietly open the till.
     *
     * @throws Exception never in practice
     */
    @Test
    void theSellingGesturesAreHeld() throws Exception {
        for (String path : new String[] {"/", "/pay", "/search", "/fruits", "/manual",
                "/parked", "/fidelity", "/return/search", "/reprint/next",
                "/action/add/123", "/action/print-last", "/action/menu/ticket"}) {
            ContainerRequestContext ctx = ctxFor(path);
            newFilter(selling(), null).filter(ctx);
            assertDivertedToSession(ctx);
        }
    }

    /**
     * The entry flow answers with no session: it is what ENDS on the session
     * screen, so holding it would divert the operator before they logged in.
     *
     * @throws Exception never in practice
     */
    @Test
    void theEntryFlowAnswersWithoutASession() throws Exception {
        for (String path : new String[] {"/lock", "/action/unlock", "/action/hardware-override"}) {
            ContainerRequestContext ctx = ctxFor(path);
            newFilter(selling(), null).filter(ctx);
            verify(ctx, never()).abortWith(org.mockito.ArgumentMatchers.any());
        }
    }

    /**
     * The drawer diversion answers with no session: the login pulse opens the
     * drawer, so the very next request is one of these.
     *
     * @throws Exception never in practice
     */
    @Test
    void theDrawerDiversionAnswersWithoutASession() throws Exception {
        for (String path : new String[] {"/drawer-error", "/api/drawer-status",
                "/action/resume-after-drawer"}) {
            ContainerRequestContext ctx = ctxFor(path);
            newFilter(selling(), null).filter(ctx);
            verify(ctx, never()).abortWith(org.mockito.ArgumentMatchers.any());
        }
    }

    /**
     * Entering training mode answers with no session: it is precisely how one
     * sells without opening one.
     *
     * @throws Exception never in practice
     */
    @Test
    void enteringTrainingAnswersWithoutASession() throws Exception {
        ContainerRequestContext ctx = ctxFor("/action/training");
        newFilter(selling(), null).filter(ctx);
        verify(ctx, never()).abortWith(org.mockito.ArgumentMatchers.any());
    }

    /**
     * The polls of an open screen answer with no session: a redirect would
     * reach a JSON parser that cannot read it.
     *
     * @throws Exception never in practice
     */
    @Test
    void thePollsAnswerWithoutASession() throws Exception {
        for (String path : new String[] {"/ticket-fragment", "/endorsement-data"}) {
            ContainerRequestContext ctx = ctxFor(path);
            newFilter(selling(), null).filter(ctx);
            verify(ctx, never()).abortWith(org.mockito.ArgumentMatchers.any());
        }
    }

    /**
     * The back office, the store node and the customer-facing pages have no
     * cash session at all and answer with none.
     *
     * @throws Exception never in practice
     */
    @Test
    void theNonRegisterSurfacesAnswerWithoutASession() throws Exception {
        for (String path : new String[] {"/admin/settings", "/dashboard", "/journal",
                "/t/1/abc", "/customer", "/api/sync/ticket"}) {
            ContainerRequestContext ctx = ctxFor(path);
            newFilter(selling(), null).filter(ctx);
            verify(ctx, never()).abortWith(org.mockito.ArgumentMatchers.any());
        }
    }
}
