package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.recall.RecalledDocument;
import com.intermarche.pos.ui.recall.SelfScanControl;
import com.intermarche.pos.ui.recall.SelfScanService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link SelfScanControlScanHandler}, targeting 100% branch
 * coverage.
 * <p>
 * The handler claims a scan only while a selfscanning basket is under control.
 * Branch enumeration: already handled, no state, no basket, locked register,
 * null code, blank code, and the claimed re-scan.
 */
class SelfScanControlScanHandlerTest {

    /**
     * A handler over a mocked control service.
     *
     * @return the handler
     */
    private SelfScanControlScanHandler handler() {
        SelfScanControlScanHandler handler = new SelfScanControlScanHandler();
        handler.selfScanService = mock(SelfScanService.class);
        return handler;
    }

    /**
     * An unlocked state with a basket under control.
     *
     * @return the state
     */
    private PosState controlling() {
        PosState state = new PosState();
        state.auth.isLocked = false;
        state.selfScan = new SelfScanControl(new RecalledDocument(RecalledDocument.Kind.SELF_SCAN, "SS1",
                List.of(), null), RecalledDocument.CONTROL_TOTAL, 1);
        return state;
    }

    /**
     * Every scan that is not a re-scan of a basket under control is left to the
     * rest of the chain.
     */
    @Test
    void scansOutsideAControlAreLeftAlone() {
        SelfScanControlScanHandler handler = handler();
        ScanContext handled = new ScanContext("301", controlling());
        handled.handled = true;
        handler.handle(handled);
        ScanContext noState = new ScanContext("301", null);
        handler.handle(noState);
        PosState idle = controlling();
        idle.selfScan = null;
        ScanContext noBasket = new ScanContext("301", idle);
        handler.handle(noBasket);
        PosState locked = controlling();
        locked.auth.isLocked = true;
        ScanContext lockedScan = new ScanContext("301", locked);
        handler.handle(lockedScan);
        ScanContext nullCode = new ScanContext(null, controlling());
        handler.handle(nullCode);
        ScanContext blankCode = new ScanContext(" ", controlling());
        handler.handle(blankCode);
        verify(handler.selfScanService, never()).rescan(any(), any());
        assertFalse(noState.handled);
        assertFalse(noBasket.handled);
        assertFalse(lockedScan.handled);
        assertFalse(nullCode.handled);
        assertFalse(blankCode.handled);
    }

    /**
     * A scan while a basket is under control is its re-scan, and goes nowhere
     * else (LC-06-05-04, -05).
     */
    @Test
    void aScanDuringTheControlIsARescan() {
        SelfScanControlScanHandler handler = handler();
        PosState state = controlling();
        ScanContext ctx = new ScanContext("301", state);
        handler.handle(ctx);
        verify(handler.selfScanService).rescan(state, "301");
        assertTrue(ctx.handled);
    }
}
