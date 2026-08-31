package com.intermarche.pos.ui.journal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link JournalEventRow}.
 * <p>
 * Branch enumeration (100%): the {@code detail == null} guard is exercised on
 * both arms — a null detail becomes the empty string, a present detail is kept.
 */
class JournalEventRowTest {

    /**
     * A present detail is carried through unchanged (non-null arm).
     */
    @Test
    void presentDetailIsKept() {
        JournalEventRow row = new JournalEventRow("C04", "SESSION_CLOSED",
                "31/08/2026", "18:00", "Z report");
        assertEquals("C04", row.terminal);
        assertEquals("SESSION_CLOSED", row.type);
        assertEquals("31/08/2026", row.date);
        assertEquals("18:00", row.time);
        assertEquals("Z report", row.detail);
    }

    /**
     * A null detail becomes the empty string (null arm).
     */
    @Test
    void nullDetailBecomesEmpty() {
        JournalEventRow row = new JournalEventRow("C04", "AUTH_LOCKED",
                "31/08/2026", "09:00", null);
        assertEquals("", row.detail);
    }
}
