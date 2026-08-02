package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.scanner.ScanContext.ScanHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link ScanContext}.
 * <p>
 * The class is a plain data token walked through the scan chain: a two-argument
 * constructor that assigns the {@code code} and {@code state} public fields and
 * leaves {@code handled} at its {@code false} default, plus the nested
 * {@link ScanHandler} interface. It carries no conditional logic — there are no
 * branches to cover — so the tests pin the constructor's assignments (including
 * the pass-through of {@code null} arguments), the mutability of the public
 * fields, the {@code handled} default, and that a {@link ScanHandler} mock is
 * invoked with the very context handed to it. The {@link PosState} collaborator
 * is a Mockito mock.
 */
class ScanContextTest {

    /**
     * The constructor stores the code and state on the public fields and leaves
     * {@code handled} at its {@code false} default.
     */
    @Test
    void constructorAssignsFieldsAndDefaultsHandledToFalse() {
        PosState state = mock(PosState.class);
        ScanContext context = new ScanContext("3401579800005", state);
        assertEquals("3401579800005", context.code);
        assertSame(state, context.state);
        assertFalse(context.handled);
    }

    /**
     * The constructor passes {@code null} arguments straight through without
     * guarding them.
     */
    @Test
    void constructorAcceptsNullArguments() {
        ScanContext context = new ScanContext(null, null);
        assertNull(context.code);
        assertNull(context.state);
        assertFalse(context.handled);
    }

    /**
     * Every public field stays mutable: a handler may rewrite the code, swap the
     * state and flip {@code handled} to stop the chain.
     */
    @Test
    void publicFieldsRemainMutable() {
        ScanContext context = new ScanContext("initial", mock(PosState.class));
        PosState other = mock(PosState.class);
        context.code = "rewritten";
        context.state = other;
        context.handled = true;
        assertEquals("rewritten", context.code);
        assertSame(other, context.state);
        assertTrue(context.handled);
    }

    /**
     * A {@link ScanHandler} receives the exact context instance it is handed.
     */
    @Test
    void scanHandlerReceivesTheContext() {
        ScanContext context = new ScanContext("code", mock(PosState.class));
        ScanHandler handler = mock(ScanHandler.class);
        handler.handle(context);
        verify(handler).handle(context);
    }
}
