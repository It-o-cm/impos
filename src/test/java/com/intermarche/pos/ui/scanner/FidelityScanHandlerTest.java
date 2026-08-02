package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.fidelity.FidelityService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FidelityScanHandler}.
 * <p>
 * The handler is a {@code @Priority(1)} link of the scan chain: it
 * recognizes fidelity cards via the {@code scan.pattern.fidelity} regex and,
 * only while the register is unlocked, hands the card to
 * {@link FidelityService#validateCard}, marking the context handled. Every
 * collaborator is a Mockito mock: the {@link PosState} whose
 * {@code isLocked()} verdict is driven, and the {@link FidelityService}
 * whose {@code validateCard} interaction is verified. The
 * {@code fidelityPattern} field is a package-private collaborator set
 * directly. Three decision points, six branches, are exercised by four
 * isolated cases.
 */
class FidelityScanHandlerTest {

    /** Regex recognizing a fidelity card, injected into the handler. */
    private static final String FIDELITY_PATTERN = "FID[0-9]{4}";

    /** A code matching {@link #FIDELITY_PATTERN}. */
    private static final String FIDELITY_CODE = "FID1234";

    /** A code NOT matching {@link #FIDELITY_PATTERN}. */
    private static final String NON_FIDELITY_CODE = "1234";

    /**
     * Builds a handler wired with the fidelity pattern and a mock service.
     *
     * @param fidelityService the fidelity service mock to inject
     * @return a ready-to-test handler
     */
    private FidelityScanHandler newHandler(FidelityService fidelityService) {
        FidelityScanHandler handler = new FidelityScanHandler();
        handler.fidelityPattern = FIDELITY_PATTERN;
        handler.fidelityService = fidelityService;
        return handler;
    }

    /**
     * An already-handled context short-circuits: the handler returns before
     * touching the state or the fidelity service.
     */
    @Test
    void alreadyHandledShortCircuits() {
        PosState state = mock(PosState.class);
        FidelityService fidelityService = mock(FidelityService.class);
        ScanContext ctx = new ScanContext(FIDELITY_CODE, state);
        ctx.handled = true;
        newHandler(fidelityService).handle(ctx);
        assertTrue(ctx.handled);
        verifyNoInteractions(state);
        verifyNoInteractions(fidelityService);
    }

    /**
     * A matching card on an unlocked register is validated by the fidelity
     * service and the context is marked handled.
     */
    @Test
    void matchingCardUnlockedIsValidated() {
        PosState state = mock(PosState.class);
        when(state.isLocked()).thenReturn(false);
        FidelityService fidelityService = mock(FidelityService.class);
        ScanContext ctx = new ScanContext(FIDELITY_CODE, state);
        newHandler(fidelityService).handle(ctx);
        verify(fidelityService).validateCard(state, FIDELITY_CODE);
        assertTrue(ctx.handled);
    }

    /**
     * A matching card on a locked register is ignored: the lock guard
     * short-circuits before the regex, no validation happens, the context
     * stays unhandled for the next link.
     */
    @Test
    void matchingCardLockedIsIgnored() {
        PosState state = mock(PosState.class);
        when(state.isLocked()).thenReturn(true);
        FidelityService fidelityService = mock(FidelityService.class);
        ScanContext ctx = new ScanContext(FIDELITY_CODE, state);
        newHandler(fidelityService).handle(ctx);
        verify(fidelityService, never()).validateCard(state, FIDELITY_CODE);
        assertFalse(ctx.handled);
    }

    /**
     * A non-fidelity code on an unlocked register is not recognized: the
     * regex fails, no validation happens, the context stays unhandled.
     */
    @Test
    void nonFidelityCodeIsNotRecognized() {
        PosState state = mock(PosState.class);
        when(state.isLocked()).thenReturn(false);
        FidelityService fidelityService = mock(FidelityService.class);
        ScanContext ctx = new ScanContext(NON_FIDELITY_CODE, state);
        newHandler(fidelityService).handle(ctx);
        verify(fidelityService, never()).validateCard(state, NON_FIDELITY_CODE);
        assertFalse(ctx.handled);
    }
}
