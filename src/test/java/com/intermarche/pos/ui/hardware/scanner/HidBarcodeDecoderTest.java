package com.intermarche.pos.ui.hardware.scanner;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link HidBarcodeDecoder}: the format chain, exercised
 * end to end — the IBM hand-held frames captured on the reference till, the
 * HID-POS standard frames anticipated for other brands, and the visibility
 * of anything neither format recognizes.
 */
class HidBarcodeDecoderTest {

    /** Collects the emitted codes. */
    private final List<String> emitted = new ArrayList<>();

    /** The decoder under test. */
    private final HidBarcodeDecoder decoder = new HidBarcodeDecoder(emitted::add);

    /**
     * Builds a 64-byte report carrying one data frame: identifier, announced
     * length, symbology, flags, the character bytes, the terminator, then zero
     * padding.
     *
     * @param symbology the symbology byte
     * @param chars the character bytes of the code
     * @return the 64-byte report
     */
    private static byte[] frame(int symbology, int... chars) {
        byte[] r = new byte[64];
        r[0] = (byte) IbmHandHeldFormat.FRAME_ID;
        r[1] = (byte) (chars.length + 3);
        r[2] = (byte) symbology;
        r[3] = 0x00;
        for (int i = 0; i < chars.length; i++) {
            r[4 + i] = (byte) chars[i];
        }
        r[4 + chars.length] = (byte) IbmHandHeldFormat.TERMINATOR;
        return r;
    }

    /**
     * The raw digit bytes of a numeric code — one digit per byte, binary.
     *
     * @param digits the digit string
     * @return the raw bytes
     */
    private static int[] digits(String digits) {
        int[] out = new int[digits.length()];
        for (int i = 0; i < digits.length(); i++) {
            out[i] = digits.charAt(i) - '0';
        }
        return out;
    }

    /**
     * The first EAN-13 captured on the reference till decodes to its digits,
     * the zero padding after the terminator ignored.
     */
    @Test
    void decodesCapturedEan13() {
        decoder.feed(frame(0x03, digits("3178530403022")), 64);
        assertEquals(List.of("3178530403022"), emitted);
    }

    /**
     * The second captured EAN-13 decodes the same way.
     */
    @Test
    void decodesSecondCapturedEan13() {
        decoder.feed(frame(0x03, digits("3250393026256")), 64);
        assertEquals(List.of("3250393026256"), emitted);
    }

    /**
     * Two scans in a row emit separately — a frame is self-contained, no
     * state carries between reports.
     */
    @Test
    void twoFramesEmitSeparately() {
        decoder.feed(frame(0x03, digits("3178530403022")), 64);
        decoder.feed(frame(0x03, digits("3250393026256")), 64);
        assertEquals(List.of("3178530403022", "3250393026256"), emitted);
    }

    /**
     * A payload of printable ASCII is taken verbatim — the working hypothesis
     * for the non-numeric symbologies (printable-range arm).
     */
    @Test
    void decodesPrintableAsciiPayload() {
        decoder.feed(frame(0x0F, 'A', 'B', '-', '1'), 64);
        assertEquals(List.of("AB-1"), emitted);
    }

    /**
     * A report of an unknown frame format emits no code but IS counted and
     * traced — the format can be read from the log and supported, instead of
     * vanishing (unknown-identifier arm).
     */
    @Test
    void unknownFrameFormatIsCountedNotSwallowed() {
        byte[] r = new byte[64];
        r[0] = 0x02;
        r[1] = 0x05;
        decoder.feed(r, 64);
        assertTrue(emitted.isEmpty());
        assertEquals(1, decoder.unknownFrameCount());
    }

    /**
     * An idle all-zero report is the one thing dropped without a trace: it is
     * neither emitted nor counted as an unknown format (idle arm).
     */
    @Test
    void idleReportIsDroppedSilently() {
        decoder.feed(new byte[64], 64);
        assertTrue(emitted.isEmpty());
        assertEquals(0, decoder.unknownFrameCount());
    }

    /**
     * A steady talker cannot flood the log: past
     * {@link HidBarcodeDecoder#MAX_UNKNOWN_TRACES} reports the tracing stops
     * while the counter keeps running (both arms of the trace cap).
     */
    @Test
    void unknownFrameTracingIsCapped() {
        byte[] r = new byte[64];
        r[0] = 0x02;
        r[1] = 0x05;
        int total = HidBarcodeDecoder.MAX_UNKNOWN_TRACES + 3;
        for (int i = 0; i < total; i++) {
            decoder.feed(r, 64);
        }
        assertTrue(emitted.isEmpty());
        assertEquals(total, decoder.unknownFrameCount());
    }

    /**
     * Builds a 64-byte HID-POS report: report identifier, data length, three
     * symbology bytes, then the data in ASCII.
     *
     * @param reportId the report identifier
     * @param data the ASCII code
     * @return the 64-byte report
     */
    private static byte[] hidPosFrame(int reportId, String data) {
        byte[] r = new byte[64];
        r[0] = (byte) reportId;
        r[1] = (byte) data.length();
        r[2] = 'E';
        r[3] = '0';
        r[4] = '4';
        for (int i = 0; i < data.length(); i++) {
            r[5 + i] = (byte) data.charAt(i);
        }
        return r;
    }

    /**
     * A HID-POS frame (report id 0x02) decodes its ASCII payload — the format
     * anticipated for another brand on a mixed fleet.
     */
    @Test
    void decodesHidPosFrame() {
        decoder.feed(hidPosFrame(0x02, "3178530403022"), 64);
        assertEquals(List.of("3178530403022"), emitted);
        assertEquals(0, decoder.unknownFrameCount());
    }

    /**
     * The other HID-POS report identifier (0x03) is recognized too.
     */
    @Test
    void decodesHidPosFrameWithAlternateReportId() {
        decoder.feed(hidPosFrame(0x03, "ABC-123"), 64);
        assertEquals(List.of("ABC-123"), emitted);
    }

    /**
     * A HID-POS-looking frame whose announced length overruns the report is
     * not claimed by the format, and lands in the unknown trace rather than
     * being half-read.
     */
    @Test
    void hidPosFrameWithOverrunLengthIsNotClaimed() {
        byte[] r = hidPosFrame(0x02, "123");
        r[1] = (byte) 0x7F;
        decoder.feed(r, 64);
        assertTrue(emitted.isEmpty());
        assertEquals(1, decoder.unknownFrameCount());
    }

    /**
     * A HID-POS-looking frame carrying a non-printable data byte is refused
     * by the format (strict recognition) and traced as unknown.
     */
    @Test
    void hidPosFrameWithNonPrintableDataIsRefused() {
        byte[] r = hidPosFrame(0x02, "12345");
        r[7] = (byte) 0xAB;
        decoder.feed(r, 64);
        assertTrue(emitted.isEmpty());
        assertEquals(1, decoder.unknownFrameCount());
    }

    /**
     * A well-formed frame is never counted as an unknown format.
     */
    @Test
    void wellFormedFrameIsNotCountedUnknown() {
        decoder.feed(frame(0x03, digits("3178530403022")), 64);
        assertEquals(0, decoder.unknownFrameCount());
    }

    /**
     * A report shorter than the header is dropped (length guard).
     */
    @Test
    void tooShortReportIsIgnored() {
        decoder.feed(new byte[]{(byte) IbmHandHeldFormat.FRAME_ID}, 1);
        assertTrue(emitted.isEmpty());
    }

    /**
     * An announced length that overruns the report is refused rather than
     * read out of bounds (incoherent-length arm).
     */
    @Test
    void announcedLengthBeyondReportIsRefused() {
        byte[] r = frame(0x03, digits("3178530403022"));
        r[1] = (byte) 0x7F;
        decoder.feed(r, 64);
        assertTrue(emitted.isEmpty());
    }

    /**
     * An announced length below the payload overhead is refused (the other
     * leg of the length guard).
     */
    @Test
    void announcedLengthBelowOverheadIsRefused() {
        byte[] r = frame(0x03, digits("3178530403022"));
        r[1] = 0x02;
        decoder.feed(r, 64);
        assertTrue(emitted.isEmpty());
    }

    /**
     * A frame whose announced payload does not end on the terminator is
     * refused — the frame is not the one this decoder understands.
     */
    @Test
    void missingTerminatorIsRefused() {
        byte[] r = frame(0x03, digits("3178530403022"));
        r[4 + 13] = 0x00;
        decoder.feed(r, 64);
        assertTrue(emitted.isEmpty());
    }

    /**
     * A character byte in neither the raw-digit nor the printable range makes
     * the whole frame undecodable: nothing is emitted, so the register never
     * receives a half-read code.
     */
    @Test
    void undecodableByteEmitsNothing() {
        decoder.feed(frame(0x0F, 0x41, 0xAB, 0x42), 64);
        assertTrue(emitted.isEmpty());
    }

    /**
     * A frame announcing only its overhead carries an empty code, which is
     * dropped rather than pushed as an empty scan.
     */
    @Test
    void emptyPayloadEmitsNothing() {
        decoder.feed(frame(0x03), 64);
        assertTrue(emitted.isEmpty());
    }
}
