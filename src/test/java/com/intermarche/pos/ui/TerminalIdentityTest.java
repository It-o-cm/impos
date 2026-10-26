package com.intermarche.pos.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests of {@link TerminalIdentity}: the screens name the register by
 * its configured identifier.
 */
class TerminalIdentityTest {

    /** The identifier is the configured one. */
    @Test
    void theIdIsTheConfiguredTerminal() {
        TerminalIdentity identity = new TerminalIdentity();
        identity.terminalId = "C07";
        assertEquals("C07", identity.getId());
    }
}
