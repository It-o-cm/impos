package com.intermarche.pos.ui.hardware.scanner;

import java.nio.charset.StandardCharsets;

/**
 * The IBM HAND-HELD USB frame format, on the IBM vendor usage page (0xff45) —
 * what the Symbol/Zebra units of the target tills run: no keyboard emulation
 * (nothing leaks to whatever holds the focus) and no HID-POS usage page.
 * <p>
 * Frame layout, as read off a till from BOTH a table-top scanner and a
 * hand-held one chained on its auxiliary port:
 * <pre>
 * 12 | 10 | 03 00 | 03 01 07 08 05 03 00 04 00 03 00 02 02 | 16          (table-top)
 * 14 | 10 | 03 00 | 32 33 30 34 37 39 36 30 31 30 30 30 36 | 00 ff 0b    (hand-held)
 * ln   typ  sym fl   the label                                trailer
 * </pre>
 * <p>
 * THE FIRST BYTE IS THE TOTAL LENGTH of the report, padding excluded — not a
 * frame identifier. It reads like one because a 13-digit EAN-13 report from
 * the table-top scanner is exactly 18 bytes long and 18 is {@code 0x12}, and
 * this class used to test it as an identifier: every label of another length
 * was then undecodable, the table-top's own EAN-8 and Code 128 included, and
 * every single label of the hand-held, whose reports are 40 bytes. The
 * counter-example is what settles it.
 * <p>
 * The second byte is the message type, {@code 0x10} for a label. The label
 * itself arrives either as one binary digit per byte (table-top, numeric
 * symbologies) or as ASCII (hand-held), and ends either on a {@code 16}
 * terminator or on a {@code 00} followed by an {@code ff 0b} trailer. Both
 * endings are accepted, and a report that fits neither is rejected rather
 * than guessed at — {@link HidBarcodeDecoder} then traces it in hex, which is
 * what is needed to support a form nobody has met yet.
 */
public class IbmHandHeldFormat implements HidFrameFormat {

    /** Message type of a label report, carried by the second byte. */
    static final int MESSAGE_TYPE_LABEL = 0x10;

    /** Terminator closing the label data of a table-top report. */
    static final int TERMINATOR = 0x16;

    /** First byte of the trailer closing a hand-held report. */
    private static final int TRAILER_FIRST = 0xFF;

    /** Second byte of the trailer closing a hand-held report. */
    private static final int TRAILER_SECOND = 0x0B;

    /** Separator a GS1 label carries between two application identifiers. */
    private static final int GROUP_SEPARATOR = 0x1D;

    /** Index of the total length of the report. */
    private static final int LENGTH_INDEX = 0;

    /** Index of the message type. */
    private static final int TYPE_INDEX = 1;

    /** Where the label itself starts: after length, type, symbology and flags. */
    private static final int PAYLOAD_START = 4;

    /**
     * {@inheritDoc}
     *
     * @return the format name
     */
    @Override
    public String name() {
        return "IBM hand-held";
    }

    /**
     * Decodes an IBM hand-held frame, or returns null when the report is not
     * one: too short to carry a label, a message type that is not a label, a
     * label emptied by its own trailer, or bytes that are neither binary
     * digits nor printable ASCII.
     *
     * @param report the report bytes
     * @param length the number of valid bytes
     * @return the decoded code, or null when this is not an IBM label frame
     */
    @Override
    public String decode(byte[] report, int length) {
        if (length <= PAYLOAD_START || (report[TYPE_INDEX] & 0xFF) != MESSAGE_TYPE_LABEL) {
            return null;
        }
        int end = Math.min(report[LENGTH_INDEX] & 0xFF, length);
        end = trimTrailer(report, end);
        if (end <= PAYLOAD_START) {
            return null;
        }
        byte[] data = new byte[end - PAYLOAD_START];
        System.arraycopy(report, PAYLOAD_START, data, 0, data.length);
        String code = allBinaryDigits(data) ? digitsToText(data) : asciiOrNull(data);
        if (code == null) {
            return null;
        }
        return code;
    }

    /**
     * Drops whatever closes the label, leaving the label alone.
     * <p>
     * Two endings exist and they cannot be told apart by looking for a NUL: in
     * a binary-digit label a {@code 00} byte is the digit zero, and an EAN-13
     * carries several. So the hand-held's {@code ff 0b} trailer is what
     * identifies its ending, and only once it is found is the {@code 00}
     * before it read as a terminator.
     *
     * @param report the report bytes
     * @param end one past the last byte the length announces
     * @return one past the last byte of the label itself
     */
    private int trimTrailer(byte[] report, int end) {
        if (end >= PAYLOAD_START + 2
                && (report[end - 2] & 0xFF) == TRAILER_FIRST
                && (report[end - 1] & 0xFF) == TRAILER_SECOND) {
            int trimmed = end - 2;
            return trimmed > PAYLOAD_START && report[trimmed - 1] == 0 ? trimmed - 1 : trimmed;
        }
        if (end > PAYLOAD_START && (report[end - 1] & 0xFF) == TERMINATOR) {
            return end - 1;
        }
        return end;
    }

    /**
     * Tells whether every byte is a binary digit 0-9 — the table-top's
     * encoding of a numeric symbology, one digit per byte.
     *
     * @param data the label bytes
     * @return true for a numeric label encoded one digit per byte
     */
    private boolean allBinaryDigits(byte[] data) {
        for (byte b : data) {
            if (b < 0 || b > 9) {
                return false;
            }
        }
        return true;
    }

    /**
     * Converts binary digits to their text.
     *
     * @param data one digit per byte
     * @return the digit string
     */
    private String digitsToText(byte[] data) {
        StringBuilder text = new StringBuilder(data.length);
        for (byte b : data) {
            text.append((char) ('0' + b));
        }
        return text.toString();
    }

    /**
     * Accepts the bytes as text when they are all printable ASCII.
     * <p>
     * The group separator is not printable but IS part of the label: a GS1
     * code carries it between two application identifiers, and dropping the
     * whole label because of it would lose every batch-and-date barcode.
     *
     * @param data the label bytes
     * @return the text, or null when a byte is neither printable ASCII nor the
     *         group separator
     */
    private String asciiOrNull(byte[] data) {
        for (byte b : data) {
            if ((b < 0x20 || b > 0x7E) && (b & 0xFF) != GROUP_SEPARATOR) {
                return null;
            }
        }
        return new String(data, StandardCharsets.US_ASCII);
    }
}
