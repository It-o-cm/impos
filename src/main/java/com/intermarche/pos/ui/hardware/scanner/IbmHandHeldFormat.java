package com.intermarche.pos.ui.hardware.scanner;

/**
 * The IBM HAND-HELD USB frame format, on the IBM vendor usage page (0xff45) —
 * what the Symbol/Zebra units of the target tills run: no keyboard emulation
 * (nothing leaks to whatever holds the focus), no HID-POS usage page, and the
 * scanner emits on its own trigger with no host activation command.
 * <p>
 * Frame layout, observed on the reference unit (64-byte reports):
 * <pre>
 *   [0] 0x12   frame identifier
 *   [1] len    number of bytes that follow (0x10 = 16 for an EAN-13)
 *   [2] sym    symbology (0x03 = EAN-13)
 *   [3] flags  (0x00 observed)
 *   [4..]      the code, ONE CHARACTER PER BYTE
 *   [.]  0x16  terminator
 *   ...        zero padding to the report size
 * </pre>
 * The character bytes are RAW VALUES, not ASCII: a digit arrives as 0x00–0x09,
 * so {@code 3178530403022} is {@code 03 01 07 08 …}. Bytes already in the
 * printable ASCII range are taken verbatim, which is the working hypothesis
 * for the non-numeric symbologies (alphanumeric badge, Code 128, QR) this
 * unit has not yet been observed emitting.
 */
public class IbmHandHeldFormat implements HidFrameFormat {

    /** First byte of a data frame. */
    static final int FRAME_ID = 0x12;

    /** Last byte of the announced payload. */
    static final int TERMINATOR = 0x16;

    /** Bytes before the announced payload: the identifier and the length. */
    private static final int HEADER_SIZE = 2;

    /** Payload bytes that are not code characters: symbology, flags, terminator. */
    private static final int PAYLOAD_OVERHEAD = 3;

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
     * one: wrong identifier, announced length that does not fit the report,
     * missing terminator, or a character byte outside the raw-digit and
     * printable-ASCII ranges.
     *
     * @param report the report bytes
     * @param length the number of valid bytes
     * @return the decoded code, or null when this is not an IBM frame
     */
    @Override
    public String decode(byte[] report, int length) {
        if (length < HEADER_SIZE || (report[0] & 0xFF) != FRAME_ID) {
            return null;
        }
        int payloadLength = report[1] & 0xFF;
        if (payloadLength < PAYLOAD_OVERHEAD || HEADER_SIZE + payloadLength > length) {
            return null;
        }
        int terminatorIndex = HEADER_SIZE + payloadLength - 1;
        if ((report[terminatorIndex] & 0xFF) != TERMINATOR) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (int i = HEADER_SIZE + 2; i < terminatorIndex; i++) {
            int b = report[i] & 0xFF;
            if (b <= 9) {
                text.append((char) ('0' + b));
            } else if (b >= 0x20 && b <= 0x7E) {
                text.append((char) b);
            } else {
                return null;
            }
        }
        return text.toString();
    }
}
