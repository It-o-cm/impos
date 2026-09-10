package com.intermarche.pos.ui.hardware.scanner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link InputEvent}: the little-endian extraction of type,
 * code and value from a 24-byte {@code struct input_event}.
 */
class InputEventTest {

    /**
     * Builds a 24-byte record with the given fields, timeval left zero.
     *
     * @param type the event type
     * @param code the key code
     * @param value the event value
     * @return the encoded record
     */
    private static byte[] record(int type, int code, int value) {
        byte[] b = new byte[LinuxInput.INPUT_EVENT_SIZE];
        b[16] = (byte) (type & 0xFF);
        b[17] = (byte) ((type >> 8) & 0xFF);
        b[18] = (byte) (code & 0xFF);
        b[19] = (byte) ((code >> 8) & 0xFF);
        b[20] = (byte) (value & 0xFF);
        b[21] = (byte) ((value >> 8) & 0xFF);
        b[22] = (byte) ((value >> 16) & 0xFF);
        b[23] = (byte) ((value >> 24) & 0xFF);
        return b;
    }

    /**
     * A key-press record parses to its type, code and value, and reads as a
     * key press.
     */
    @Test
    void parsesKeyPress() {
        InputEvent event = InputEvent.parse(record(LinuxInput.EV_KEY, 30, 1), 0);
        assertEquals(LinuxInput.EV_KEY, event.type());
        assertEquals(30, event.code());
        assertEquals(1, event.value());
        assertTrue(event.isKeyPress());
    }

    /**
     * A key-release record (value 0) is not a key press.
     */
    @Test
    void releaseIsNotAPress() {
        assertFalse(InputEvent.parse(record(LinuxInput.EV_KEY, 30, 0), 0).isKeyPress());
    }

    /**
     * A non-key event (EV_SYN, type 0) is not a key press whatever its value.
     */
    @Test
    void synIsNotAPress() {
        assertFalse(InputEvent.parse(record(0, 0, 1), 0).isKeyPress());
    }

    /**
     * A record parsed at a non-zero base reads the fields at that offset —
     * the reader splits a multi-record buffer this way.
     */
    @Test
    void parsesAtOffset() {
        byte[] two = new byte[LinuxInput.INPUT_EVENT_SIZE * 2];
        byte[] second = record(LinuxInput.EV_KEY, 46, 1);
        System.arraycopy(second, 0, two, LinuxInput.INPUT_EVENT_SIZE, second.length);
        InputEvent event = InputEvent.parse(two, LinuxInput.INPUT_EVENT_SIZE);
        assertEquals(46, event.code());
        assertTrue(event.isKeyPress());
    }
}
