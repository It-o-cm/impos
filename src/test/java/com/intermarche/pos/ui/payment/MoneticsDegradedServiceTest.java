package com.intermarche.pos.ui.payment;

import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.endorsement.EndorsementService;
import com.intermarche.pos.ui.ticket.TicketState;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MoneticsDegradedService} and the expiring flag it drives on
 * {@link PosState}.
 * <p>
 * The gesture is the shop deciding, under its own responsibility, to stop asking the
 * monetics for authorization — so every leg of the permission is covered separately:
 * a shop that requires no endorsement, a logged operator who is already a supervisor
 * (which must not even reach the credential check), a supervisor credential that
 * passes, and one that does not. The flag itself is checked on its three states —
 * never set, set in the future, set in the past — because the expiry is what makes
 * {@code LC-07-08-04}'s automatic release work without a scheduler, and a flag that
 * did not expire on read would leave a till bypassing its monetics overnight.
 */
class MoneticsDegradedServiceTest {

    /**
     * Builds a service whose collaborators are all mocks, wired for the strictest
     * case: an endorsement required, and a logged operator who is not a supervisor.
     *
     * @return the wired service
     */
    private MoneticsDegradedService newService() {
        MoneticsDegradedService service = new MoneticsDegradedService();
        service.posSettingsService = mock(PosSettingsService.class);
        service.endorsementService = mock(EndorsementService.class);
        service.technicalEventService = mock(TechnicalEventService.class);
        when(service.posSettingsService.moneticsDegradedForcedEndorsement()).thenReturn(true);
        when(service.posSettingsService.moneticsDegradedForcedMinutes()).thenReturn(60);
        when(service.endorsementService.operatorIsSupervisor(any())).thenReturn(false);
        return service;
    }

    /**
     * Builds a real POS state with a mocked ticket mailbox, so the messages the
     * service posts can be asserted.
     *
     * @return the state under test
     */
    private PosState newState() {
        PosState state = new PosState();
        state.ticket = mock(TicketState.class);
        state.auth.operatorName = "ALICE";
        return state;
    }

    // --------------------------------------------------
    // The expiring flag
    // --------------------------------------------------

    /**
     * A register that never forced anything is not degraded.
     */
    @Test
    void anUnsetFlagIsNotForced() {
        assertFalse(newState().isMoneticsDegradedForced());
    }

    /**
     * A forcing that has not run out yet is on.
     */
    @Test
    void aFutureExpiryIsForced() {
        PosState state = newState();
        state.moneticsDegradedUntil = LocalDateTime.now().plusMinutes(5);
        assertTrue(state.isMoneticsDegradedForced());
    }

    /**
     * A forcing whose delay has run out is off AND forgotten: reading the flag is
     * what releases it, so every consumer sees the same instant of truth.
     */
    @Test
    void anExpiredForcingReleasesItself() {
        PosState state = newState();
        state.moneticsDegradedUntil = LocalDateTime.now().minusMinutes(1);
        assertFalse(state.isMoneticsDegradedForced());
        assertNull(state.moneticsDegradedUntil);
    }

    // --------------------------------------------------
    // Activating
    // --------------------------------------------------

    /**
     * A shop that requires no endorsement lets the operator force it alone, and no
     * credential is ever checked.
     */
    @Test
    void withoutEndorsementTheOperatorForcesItAlone() {
        MoneticsDegradedService service = newService();
        when(service.posSettingsService.moneticsDegradedForcedEndorsement()).thenReturn(false);
        PosState state = newState();
        assertTrue(service.activate(state, null, null));
        assertTrue(state.isMoneticsDegradedForced());
        verify(service.endorsementService, never()).authorize(any(), any(), any());
    }

    /**
     * A logged operator who already holds a supervising role forces it without a
     * second credential — the middle leg of the permission, which short-circuits the
     * credential check.
     */
    @Test
    void aConnectedSupervisorForcesItWithoutCredential() {
        MoneticsDegradedService service = newService();
        when(service.endorsementService.operatorIsSupervisor(any())).thenReturn(true);
        PosState state = newState();
        assertTrue(service.activate(state, null, null));
        assertTrue(state.isMoneticsDegradedForced());
        verify(service.endorsementService, never()).authorize(any(), any(), any());
    }

    /**
     * A supervisor credential that passes forces it, and the decision is journalled.
     */
    @Test
    void aSupervisorCredentialForcesIt() {
        MoneticsDegradedService service = newService();
        when(service.endorsementService.authorize(eq("chef"), eq("mdp"),
                eq(MoneticsDegradedService.ACTIVATE_ACTION))).thenReturn(true);
        PosState state = newState();
        assertTrue(service.activate(state, "chef", "mdp"));
        assertTrue(state.isMoneticsDegradedForced());
        verify(service.technicalEventService).log(any(), anyString());
    }

    /**
     * A credential that does not pass forces nothing.
     */
    @Test
    void aRefusedCredentialForcesNothing() {
        MoneticsDegradedService service = newService();
        when(service.endorsementService.authorize(anyString(), anyString(), anyString()))
                .thenReturn(false);
        PosState state = newState();
        assertFalse(service.activate(state, "stagiaire", "mdp"));
        assertFalse(state.isMoneticsDegradedForced());
        verify(state.ticket).setError("AUTORISATION REFUSEE");
    }

    /**
     * An administered duration of zero — or a corrupt one — still gives a forcing
     * that lasts at least a minute, rather than one that is over before the screen
     * has repainted.
     */
    @Test
    void aZeroDurationStillLastsAMinute() {
        MoneticsDegradedService service = newService();
        when(service.posSettingsService.moneticsDegradedForcedEndorsement()).thenReturn(false);
        when(service.posSettingsService.moneticsDegradedForcedMinutes()).thenReturn(0);
        PosState state = newState();
        assertTrue(service.activate(state, null, null));
        assertTrue(state.isMoneticsDegradedForced());
    }

    // --------------------------------------------------
    // Releasing
    // --------------------------------------------------

    /**
     * Releasing what is not forced says so, without asking anyone for a credential.
     */
    @Test
    void releasingWhatIsNotForcedAsksNobody() {
        MoneticsDegradedService service = newService();
        PosState state = newState();
        assertFalse(service.deactivate(state, "chef", "mdp"));
        verify(service.endorsementService, never()).authorize(any(), any(), any());
        verify(state.ticket).setNotice("MODE DÉGRADÉ MONÉTIQUE DÉJÀ INACTIF");
    }

    /**
     * A forcing that expired on its own is already released, and takes the same leg.
     */
    @Test
    void releasingAnExpiredForcingAsksNobody() {
        MoneticsDegradedService service = newService();
        PosState state = newState();
        state.moneticsDegradedUntil = LocalDateTime.now().minusMinutes(1);
        assertFalse(service.deactivate(state, "chef", "mdp"));
        verify(service.endorsementService, never()).authorize(any(), any(), any());
    }

    /**
     * A supervisor credential releases the forcing before its delay runs out.
     */
    @Test
    void aSupervisorCredentialReleasesIt() {
        MoneticsDegradedService service = newService();
        when(service.endorsementService.authorize(eq("chef"), eq("mdp"),
                eq(MoneticsDegradedService.DEACTIVATE_ACTION))).thenReturn(true);
        PosState state = newState();
        state.moneticsDegradedUntil = LocalDateTime.now().plusMinutes(30);
        assertTrue(service.deactivate(state, "chef", "mdp"));
        assertFalse(state.isMoneticsDegradedForced());
        assertNull(state.moneticsDegradedUntil);
    }

    /**
     * A credential that does not pass leaves the forcing on: releasing it is as
     * guarded a gesture as setting it.
     */
    @Test
    void aRefusedCredentialLeavesItForced() {
        MoneticsDegradedService service = newService();
        when(service.endorsementService.authorize(anyString(), anyString(), anyString()))
                .thenReturn(false);
        PosState state = newState();
        state.moneticsDegradedUntil = LocalDateTime.now().plusMinutes(30);
        assertFalse(service.deactivate(state, "stagiaire", "mdp"));
        assertTrue(state.isMoneticsDegradedForced());
        verify(state.ticket).setError("AUTORISATION REFUSEE");
    }

    /**
     * A shop that requires no endorsement lets the operator release it alone.
     */
    @Test
    void withoutEndorsementTheOperatorReleasesItAlone() {
        MoneticsDegradedService service = newService();
        when(service.posSettingsService.moneticsDegradedForcedEndorsement()).thenReturn(false);
        PosState state = newState();
        state.moneticsDegradedUntil = LocalDateTime.now().plusMinutes(30);
        assertTrue(service.deactivate(state, null, null));
        assertFalse(state.isMoneticsDegradedForced());
    }

    /**
     * A connected supervisor releases it without a second credential.
     */
    @Test
    void aConnectedSupervisorReleasesItWithoutCredential() {
        MoneticsDegradedService service = newService();
        when(service.endorsementService.operatorIsSupervisor(any())).thenReturn(true);
        PosState state = newState();
        state.moneticsDegradedUntil = LocalDateTime.now().plusMinutes(30);
        assertTrue(service.deactivate(state, null, null));
        verify(service.endorsementService, never()).authorize(any(), any(), any());
    }
}
