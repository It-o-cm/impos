package com.intermarche.pos.ui.hardware.scanner;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link KeyboardScancodeDecoder}: the assembly of barcode
 * strings from the key events of a keyboard-mode scanner. Key codes are the
 * Linux input codes (input-event-codes.h).
 */
class KeyboardScancodeDecoderTest {

    /** Collects the codes the decoder emits. */
    private final List<String> emitted = new ArrayList<>();

    /** The decoder under test, feeding {@link #emitted}. */
    private final KeyboardScancodeDecoder decoder = new KeyboardScancodeDecoder(emitted::add);

    /**
     * Presses the digit keys of an EAN, then ENTER: the code is emitted once,
     * exactly as scanned.
     */
    @Test
    void assemblesAnEanTerminatedByEnter() {
        for (int key : new int[]{3, 3, 11, 11, 11, 11, 11, 11, 11, 2}) {
            decoder.onKeyPress(key);
        }
        decoder.onKeyPress(28);
        assertEquals(List.of("2200000001"), emitted);
    }

    /**
     * Keypad digits and keypad ENTER assemble the same way as the main row
     * (a scanner may emit either).
     */
    @Test
    void assemblesFromKeypadAndKeypadEnter() {
        for (int key : new int[]{79, 80, 81}) {
            decoder.onKeyPress(key);
        }
        decoder.onKeyPress(96);
        assertEquals(List.of("123"), emitted);
    }

    /**
     * Letters are upper-cased and the dash is kept — a caseless alphanumeric
     * code assembles verbatim.
     */
    @Test
    void assemblesLettersUpperCasedWithDash() {
        for (int key : new int[]{30, 12, 48}) {
            decoder.onKeyPress(key);
        }
        decoder.onKeyPress(28);
        assertEquals(List.of("A-B"), emitted);
    }

    /**
     * An ENTER with nothing buffered emits nothing (empty codes dropped).
     */
    @Test
    void emptyEnterEmitsNothing() {
        decoder.onKeyPress(28);
        assertTrue(emitted.isEmpty());
    }

    /**
     * Two codes in a row are emitted separately, the buffer reset by the
     * first ENTER.
     */
    @Test
    void twoCodesEmitSeparately() {
        decoder.onKeyPress(2);
        decoder.onKeyPress(28);
        decoder.onKeyPress(3);
        decoder.onKeyPress(28);
        assertEquals(List.of("1", "2"), emitted);
    }

    /**
     * An unmapped key (here KEY_LEFTSHIFT = 42) is ignored, not appended.
     */
    @Test
    void unmappedKeyIsIgnored() {
        decoder.onKeyPress(2);
        decoder.onKeyPress(42);
        decoder.onKeyPress(3);
        decoder.onKeyPress(28);
        assertEquals(List.of("12"), emitted);
    }

    /**
     * A runaway buffer (more than {@link KeyboardScancodeDecoder#MAX_LENGTH}
     * characters with no ENTER) resets rather than growing without bound.
     */
    @Test
    void runawayBufferResets() {
        for (int i = 0; i < KeyboardScancodeDecoder.MAX_LENGTH + 5; i++) {
            decoder.onKeyPress(2);
        }
        decoder.onKeyPress(28);
        assertEquals(1, emitted.size());
        assertTrue(emitted.get(0).length() <= KeyboardScancodeDecoder.MAX_LENGTH);
    }

    /**
     * Presses every mapped key code in turn — each main-row digit (2..11),
     * the main-row dash (12), each main-row letter (16..25, 30..38, 44..50),
     * every keypad digit (71,72,73,75,76,77,79,80,81,82) and the keypad dash
     * (74) — then ENTER: this exercises EVERY case arm of the mapping switch
     * and asserts the exact character produced by each, in order. The 48
     * characters stay under {@link KeyboardScancodeDecoder#MAX_LENGTH} so no
     * runaway reset interferes.
     */
    @Test
    void everyMappedKeyProducesItsExactCharacter() {
        int[] keys = {2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 30, 31, 32, 33, 34, 35, 36, 37, 38, 44, 45, 46, 47, 48, 49, 50, 71, 72, 73, 75, 76, 77, 79, 80, 81, 82, 74};
        for (int key : keys) {
            decoder.onKeyPress(key);
        }
        decoder.onKeyPress(28);
        assertEquals(List.of("1234567890-QWERTYUIOPASDFGHJKLZXCVBNM7894561230-"), emitted);
    }
}
