package com.intermarche.pos.ui.hardware.scanner;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** LC-11-01: malformed HID-POS lengths cannot emit an empty or truncated barcode. */
class HidPosFormatTest {
    /** LC-11-01: an empty payload and one exceeding the received frame are rejected independently. */
    @Test
    void emptyAndTruncatedPayloadsAreRejected() {
        var format = new HidPosFormat();
        byte[] frame = new byte[]{2, 0, 0, 0, 0};
        assertNull(format.decode(frame, frame.length));
        frame[1] = 1;
        assertNull(format.decode(frame, frame.length));
        assertEquals("HID-POS (0x8C)", format.name());
    }
}
